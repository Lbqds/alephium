// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.core

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag

import com.typesafe.scalalogging.LazyLogging

import org.alephium.flow.Utils
import org.alephium.flow.mempool._
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.setting.{ConsensusSettings, MemPoolSetting}
import org.alephium.flow.validation._
import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Hex, TimeStamp}

// scalastyle:off number.of.methods
trait FlowUtils
    extends MultiChain
    with BlockFlowState
    with SyncUtils
    with TxUtils
    with LogUtils
    with ContractUtils
    with ConflictedBlocks
    with LazyLogging { Self: BlockFlow =>
  implicit def mempoolSetting: MemPoolSetting
  implicit def consensusConfigs: ConsensusSettings
  implicit def networkConfig: NetworkConfig
  implicit def logConfig: LogConfig

  val blockFlow = Self

  val grandPool = GrandPool.empty

  def getGrandPool(): GrandPool = grandPool

  def getMemPool(mainGroup: GroupIndex): MemPool = {
    grandPool.getMemPool(mainGroup)
  }

  def getMemPool(chainIndex: ChainIndex): MemPool = {
    grandPool.getMemPool(chainIndex.from)
  }

  def calMemPoolChangesUnsafe(
      mainGroup: GroupIndex,
      oldDeps: BlockDeps,
      newDeps: BlockDeps
  ): MemPoolChanges = {
    val oldOutDeps = oldDeps.outDeps
    val newOutDeps = newDeps.outDeps
    val outDiffs = AVector.tabulate(brokerConfig.groups) { toGroup =>
      val toGroupIndex = GroupIndex.unsafe(toGroup)
      val oldDep       = oldOutDeps(toGroup)
      val newDep       = newOutDeps(toGroup)
      val chainIndex   = ChainIndex(mainGroup, toGroupIndex)
      getBlockChain(chainIndex).calBlockDiffUnsafe(chainIndex, newDep, oldDep)
    }
    val inDiffs = newDeps.inDeps.mapWithIndex { case (newInIntraDep, k) =>
      val intraIndex  = ChainIndex.from(newInIntraDep)
      val fromGroup   = intraIndex.from
      val targetIndex = ChainIndex(fromGroup, mainGroup)
      assume(intraIndex.isIntraGroup)
      val oldInIntraDep = oldDeps.inDeps(k)
      assume(ChainIndex.from(oldInIntraDep) == intraIndex)
      val newInDep = getOutTipsUnsafe(newInIntraDep)(mainGroup.value)
      val oldInDep = getOutTipsUnsafe(oldInIntraDep)(mainGroup.value)
      assume(ChainIndex.from(newInDep) == targetIndex)
      assume(ChainIndex.from(oldInDep) == targetIndex)
      getBlockChain(targetIndex).calBlockDiffUnsafe(targetIndex, newInDep, oldInDep)
    }
    val diffs    = inDiffs ++ outDiffs
    val toRemove = diffs.map(diff => diff.chainIndex -> diff.toAdd.flatMap(_.nonCoinbase))
    val toAdd    = diffs.map(diff => diff.chainIndex -> diff.toRemove.flatMap(_.nonCoinbase))
    if (toAdd.sumBy(_._2.length) == 0) Normal(toRemove) else Reorg(toRemove, toAdd)
  }

  def updateGrandPoolUnsafe(
      mainGroup: GroupIndex,
      newDeps: BlockDeps,
      oldDeps: BlockDeps,
      maxHeightGap: Int
  ): Unit = {
    val newHeight = getHeightUnsafe(newDeps.uncleHash(mainGroup))
    val oldHeight = getHeightUnsafe(oldDeps.uncleHash(mainGroup))
    if (newHeight <= oldHeight + maxHeightGap) {
      updateMemPoolUnsafe(mainGroup, newDeps, oldDeps)
    }
  }

  def updateGrandPoolUnsafe(
      mainGroup: GroupIndex,
      newDeps: BlockDeps,
      oldDeps: BlockDeps
  ): Unit = {
    updateGrandPoolUnsafe(mainGroup, newDeps, oldDeps, maxSyncBlocksPerChain)
  }

  def updateMemPoolUnsafe(mainGroup: GroupIndex, newDeps: BlockDeps, oldDeps: BlockDeps): Unit = {
    calMemPoolChangesUnsafe(mainGroup, oldDeps, newDeps) match {
      case Normal(toRemove) =>
        val removed = toRemove.fold(0) { case (sum, (_, txs)) =>
          sum + getMemPool(mainGroup).removeUsedTxs(txs.map(_.toTemplate))
        }
        if (removed > 0) {
          logger.debug(s"Normal update for #$mainGroup mempool: #$removed removed")
        }
      case Reorg(toRemove, toAdd) =>
        val (removed, added) = getMemPool(mainGroup).reorg(toRemove, toAdd)
        logger.info(s"Reorg for #$mainGroup mempool: #$removed removed, #$added added")
    }
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps

  def updateBestDeps(): IOResult[Unit]

  def updateBestDepsUnsafe(): Unit

  def calBestDepsUnsafe(group: GroupIndex): BlockDeps

  def collectPooledTxsGhost(
      chainIndex: ChainIndex,
      groupView: BlockFlowGroupView[WorldState.Cached]
  ): AVector[TransactionTemplate] = {
    getMemPool(chainIndex).collectForBlockGhost(
      chainIndex,
      mempoolSetting.txMaxNumberPerBlock,
      groupView.containAsset
    )
  }

  private def filterValidTxs(
      txs: AVector[TransactionTemplate],
      groupView: BlockFlowGroupView[WorldState.Cached]
  ): AVector[TransactionTemplate] = {
    var result       = AVector.ofCapacity[TransactionTemplate](txs.length)
    val outputRefSet = mutable.Set.empty[AssetOutputRef]
    txs.foreach { tx =>
      if (
        tx.unsigned.inputs
          .forall(i => outputRefSet.contains(i.outputRef) || groupView.containAsset(i.outputRef))
      ) {
        outputRefSet.addAll(tx.assetOutputRefs)
        result = result :+ tx
      }
    }
    result
  }

  // TODO: truncate txs in advance for efficiency
  def collectTransactionsGhost(
      chainIndex: ChainIndex,
      groupView: BlockFlowGroupView[WorldState.Cached],
      bestDeps: BlockDeps
  ): IOResult[AVector[TransactionTemplate]] = {
    IOUtils.tryExecute {
      // some tx inputs might from bestDeps, but not loosenDeps, check inputs using groupView
      val candidates0 = collectPooledTxsGhost(chainIndex, groupView)
      val candidates1 = FlowUtils.filterDoubleSpending(candidates0)
      // we don't want any tx that conflicts with bestDeps
      val candidates2 = filterConflicts(chainIndex.from, bestDeps, candidates1, getBlockUnsafe)
      val candidates3 = filterValidTxs(candidates2, groupView)
      FlowUtils.truncateTxs(candidates3, maximalTxsInOneBlock, maximalGasPerBlock)
    }
  }

  def collectPooledTxsPreGhost(chainIndex: ChainIndex): AVector[TransactionTemplate] = {
    getMemPool(chainIndex).collectForBlockPreGhost(chainIndex, mempoolSetting.txMaxNumberPerBlock)
  }

  def filterValidInputsUnsafe(
      txs: AVector[TransactionTemplate],
      groupView: BlockFlowGroupView[WorldState.Cached]
  ): AVector[TransactionTemplate] = {
    txs.filter { tx =>
      Utils.unsafe(groupView.getPreOutputs(tx.unsigned.inputs)).nonEmpty
    }
  }

  // TODO: truncate txs in advance for efficiency
  def collectTransactionsPreGhost(
      chainIndex: ChainIndex,
      groupView: BlockFlowGroupView[WorldState.Cached],
      bestDeps: BlockDeps
  ): IOResult[AVector[TransactionTemplate]] = {
    IOUtils.tryExecute {
      val candidates0 = collectPooledTxsPreGhost(chainIndex)
      val candidates1 = FlowUtils.filterDoubleSpending(candidates0)
      // some tx inputs might from bestDeps, but not loosenDeps
      val candidates2 = filterValidInputsUnsafe(candidates1, groupView)
      // we don't want any tx that conflicts with bestDeps
      val candidates3 = filterConflicts(chainIndex.from, bestDeps, candidates2, getBlockUnsafe)
      FlowUtils.truncateTxs(candidates3, maximalTxsInOneBlock, maximalGasPerBlock)
    }
  }

  def collectTransactions(
      chainIndex: ChainIndex,
      groupView: BlockFlowGroupView[WorldState.Cached],
      bestDeps: BlockDeps,
      hardFork: HardFork
  ): IOResult[AVector[TransactionTemplate]] = {
    if (hardFork.isGhostEnabled()) {
      collectTransactionsGhost(chainIndex, groupView, bestDeps)
    } else {
      collectTransactionsPreGhost(chainIndex, groupView, bestDeps)
    }
  }

  // all the inputs and double spending should have been checked
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def executeTxTemplates(
      chainIndex: ChainIndex,
      blockEnv: BlockEnv,
      deps: BlockDeps,
      groupView: BlockFlowGroupView[WorldState.Cached],
      txTemplates: AVector[TransactionTemplate]
  ): IOResult[AVector[Transaction]] = {
    if (chainIndex.isIntraGroup) {
      val parentHash = deps.getOutDep(chainIndex.to)
      val order = Block.getScriptExecutionOrder(parentHash, txTemplates, blockEnv.getHardFork())
      val fullTxs =
        Array.ofDim[Transaction](txTemplates.length + 1) // reserve 1 slot for coinbase tx
      txTemplates.foreachWithIndex { case (tx, index) =>
        if (tx.unsigned.scriptOpt.isEmpty) {
          fullTxs(index) = FlowUtils.convertNonScriptTx(tx)
        }
      }

      order
        .foreachE[IOError] { scriptTxIndex =>
          val tx = txTemplates(scriptTxIndex)
          generateFullTx(chainIndex, groupView, blockEnv, tx, tx.unsigned.scriptOpt.get)
            .map(fullTx => fullTxs(scriptTxIndex) = fullTx)
        }
        .map { _ =>
          AVector.unsafe(fullTxs, 0, txTemplates.length)
        }
    } else {
      Right(txTemplates.map(FlowUtils.convertNonScriptTx))
    }
  }

  @inline private def getUncles(
      hardFork: HardFork,
      deps: BlockDeps,
      parentHeader: BlockHeader
  ): IOResult[AVector[(BlockHeader, LockupScript.Asset)]] = {
    if (hardFork.isGhostEnabled()) {
      getUncles(parentHeader, uncle => isExtendingUnsafe(deps, uncle.blockDeps))
    } else {
      Right(AVector.empty)
    }
  }

  def prepareBlockFlow(
      chainIndex: ChainIndex,
      miner: LockupScript.Asset
  ): IOResult[BlockFlowTemplate] = {
    assume(brokerConfig.contains(chainIndex.from))
    val bestDeps = getBestDeps(chainIndex.from)
    for {
      parentHeader <- getBlockHeader(bestDeps.parentHash(chainIndex))
      templateTs = FlowUtils.nextTimeStamp(parentHeader.timestamp)
      hardFork   = networkConfig.getHardFork(templateTs)
      loosenDeps   <- looseUncleDependencies(bestDeps, chainIndex, templateTs, hardFork)
      target       <- getNextHashTarget(chainIndex, loosenDeps, templateTs)
      groupView    <- getMutableGroupView(chainIndex.from, loosenDeps)
      uncles       <- getUncles(hardFork, loosenDeps, parentHeader)
      txCandidates <- collectTransactions(chainIndex, groupView, bestDeps, hardFork)
      template <- prepareBlockFlow(
        chainIndex,
        loosenDeps,
        groupView,
        uncles,
        txCandidates,
        target,
        templateTs,
        miner
      )
      validated <- validateTemplate(chainIndex, template)
    } yield {
      if (validated) {
        template
      } else {
        logger.warn("Assemble empty block due to invalid txs")
        val coinbaseTx =
          Transaction.coinbase(
            chainIndex,
            AVector.empty[Transaction],
            miner,
            target,
            templateTs,
            uncles
          )
        template.copy(transactions = AVector(coinbaseTx)) // fall back to empty block
      }
    }
  }

  private def prepareBlockFlow(
      chainIndex: ChainIndex,
      loosenDeps: BlockDeps,
      groupView: BlockFlowGroupView[WorldState.Cached],
      uncles: AVector[(BlockHeader, LockupScript.Asset)],
      candidates: AVector[TransactionTemplate],
      target: Target,
      templateTs: TimeStamp,
      miner: LockupScript.Asset
  ): IOResult[BlockFlowTemplate] = {
    val blockEnv = BlockEnv(chainIndex, networkConfig.networkId, templateTs, target, None)
    for {
      fullTxs      <- executeTxTemplates(chainIndex, blockEnv, loosenDeps, groupView, candidates)
      depStateHash <- getDepStateHash(loosenDeps, chainIndex.from)
    } yield {
      val coinbaseTx = Transaction.coinbase(chainIndex, fullTxs, miner, target, templateTs, uncles)
      BlockFlowTemplate(
        chainIndex,
        loosenDeps.deps,
        depStateHash,
        target,
        templateTs,
        uncles.map(_._1),
        fullTxs :+ coinbaseTx
      )
    }
  }

  lazy val templateValidator =
    BlockValidation.build(brokerConfig, networkConfig, consensusConfigs, logConfig)
  def validateTemplate(
      chainIndex: ChainIndex,
      template: BlockFlowTemplate
  ): IOResult[Boolean] = {
    templateValidator.validateTemplate(chainIndex, template, this) match {
      case Left(Left(error)) => Left(error)
      case Left(Right(error)) =>
        error match {
          case ExistInvalidTx(t, _) =>
            logger.warn(
              s"Remove invalid mempool tx: ${t.id.toHexString} - ${Hex.toHexString(serialize(t))}"
            )
            this.getMemPool(chainIndex).removeUnusedTxs(AVector(t.toTemplate))
          case _ => ()
        }
        Right(false)
      case Right(_) => Right(true)
    }
  }

  def looseUncleDependencies(
      bestDeps: BlockDeps,
      chainIndex: ChainIndex,
      currentTs: TimeStamp,
      hardFork: HardFork
  ): IOResult[BlockDeps] = {
    val consensusConfig = consensusConfigs.getConsensusConfig(hardFork)
    val thresholdTs     = currentTs.minusUnsafe(consensusConfig.uncleDependencyGapTime)
    bestDeps.deps
      .mapWithIndexE {
        case (hash, k) if k != (groups - 1 + chainIndex.to.value) =>
          val hashIndex = ChainIndex.from(hash)
          val chain     = getHeaderChain(hashIndex)
          looseDependency(hash, chain, thresholdTs)
        case (hash, _) => Right(hash)
      }
      .map(BlockDeps.unsafe)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def looseDependency(
      hash: BlockHash,
      headerChain: BlockHeaderChain,
      thresholdTs: TimeStamp
  ): IOResult[BlockHash] = {
    headerChain.getTimestamp(hash).flatMap {
      case timeStamp if timeStamp <= thresholdTs =>
        Right(hash)
      case _ =>
        headerChain.getParentHash(hash).flatMap(looseDependency(_, headerChain, thresholdTs))
    }
  }

  def prepareBlockFlowUnsafe(
      chainIndex: ChainIndex,
      miner: LockupScript.Asset
  ): BlockFlowTemplate = {
    Utils.unsafe(prepareBlockFlow(chainIndex, miner))
  }

  def generateFullTx(
      chainIndex: ChainIndex,
      groupView: BlockFlowGroupView[WorldState.Cached],
      blockEnv: BlockEnv,
      tx: TransactionTemplate,
      script: StatefulScript
  ): IOResult[Transaction] = {
    val validator = TxValidation.build
    for {
      preOutputs <- groupView
        .getPreOutputs(tx.unsigned.inputs)
        .flatMap {
          case None =>
            // Runtime exception as we have validated the inputs in collectTransactions
            Left(IOError.Other(new RuntimeException(s"Inputs should exist, but not actually")))
          case Some(outputs) => Right(outputs)
        }
      tx <- {
        val result = validator.validateSuccessfulScriptTxTemplate(
          tx,
          script,
          chainIndex,
          groupView,
          blockEnv,
          preOutputs
        )
        result match {
          case Right(successfulTx) => Right(successfulTx)
          case Left(Right(TxScriptExeFailed(_))) =>
            FlowUtils.convertFailedScriptTx(preOutputs, tx, script) match {
              case Some(failedTx) => Right(failedTx)
              case None =>
                Left(IOError.Other(new RuntimeException("Invalid remaining balances in tx")))
            }
          case Left(Left(error)) => Left(error)
          case Left(Right(error)) =>
            Left(IOError.Other(new RuntimeException(s"Invalid tx: $error")))
        }
      }
    } yield tx
  }

  @volatile private var checkingHashIndexing: Boolean = false
  def checkHashIndexingUnsafe(): Unit = {
    if (!checkingHashIndexing) {
      checkingHashIndexing = true
      brokerConfig.chainIndexes.foreach { chainIndex =>
        getHeaderChain(chainIndex).checkHashIndexingUnsafe()
      }
      logger.info("Hash indexing checking is done!")
      checkingHashIndexing = false
    } else {
      logger.warn("Hash indexing checking is on-going")
    }
  }

  def getDifficultyMetric(): IOResult[Difficulty] = {
    brokerConfig.cliqueChainIndexes
      .foldE(Difficulty.zero) { case (acc, chainIndex) =>
        val headerChain = blockFlow.getHeaderChain(chainIndex)
        for {
          bestHeaderHash <- headerChain.getBestTip()
          bestHeader     <- headerChain.getBlockHeader(bestHeaderHash)
        } yield acc.add(bestHeader.target.getDifficulty())
      }
      .map(_.divide(brokerConfig.chainNum))
  }
}

object FlowUtils {
  sealed trait OutputInfo {
    def ref: TxOutputRef
    def output: TxOutput
  }
  final case class AssetOutputInfo(ref: AssetOutputRef, output: AssetOutput, outputType: OutputType)
      extends OutputInfo
  final case class ContractOutputInfo(ref: ContractOutputRef, output: ContractOutput)
      extends OutputInfo

  sealed trait OutputType {
    def cachedLevel: Int
  }
  case object PersistedOutput extends OutputType {
    val cachedLevel = 0
  }
  case object UnpersistedBlockOutput extends OutputType {
    val cachedLevel = 1
  }
  case object MemPoolOutput extends OutputType {
    val cachedLevel = 2
  }

  def filterDoubleSpending[T <: TransactionAbstract: ClassTag](txs: AVector[T]): AVector[T] = {
    var output   = AVector.ofCapacity[T](txs.length)
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
    txs.foreach { tx =>
      if (tx.unsigned.inputs.forall(input => !utxoUsed.contains(input.outputRef))) {
        utxoUsed.addAll(tx.unsigned.inputs.toIterable.view.map(_.outputRef))
        output = output :+ tx
      }
    }
    output
  }

  def convertNonScriptTx(txTemplate: TransactionTemplate): Transaction = {
    Transaction(
      txTemplate.unsigned,
      scriptExecutionOk = true,
      AVector.empty,
      AVector.empty,
      txTemplate.inputSignatures,
      txTemplate.scriptSignatures
    )
  }

  def convertSuccessfulTx(
      txTemplate: TransactionTemplate,
      result: TxScriptExecution
  ): Transaction = {
    Transaction(
      txTemplate.unsigned,
      scriptExecutionOk = true,
      result.contractInputs,
      result.generatedOutputs,
      txTemplate.inputSignatures,
      txTemplate.scriptSignatures
    )
  }

  def convertFailedScriptTx(
      preOutputs: AVector[AssetOutput],
      txTemplate: TransactionTemplate,
      script: StatefulScript
  ): Option[Transaction] = {
    if (script.entryMethod.usePreapprovedAssets) {
      for {
        balances0 <- MutBalances.from(preOutputs, txTemplate.unsigned.fixedOutputs)
        _         <- balances0.subAlph(preOutputs.head.lockupScript, txTemplate.gasFeeUnsafe)
        outputs   <- balances0.toOutputs()
      } yield {
        Transaction(
          txTemplate.unsigned,
          scriptExecutionOk = false,
          AVector.empty,
          generatedOutputs = outputs,
          txTemplate.inputSignatures,
          txTemplate.scriptSignatures
        )
      }
    } else {
      Some(
        Transaction(
          txTemplate.unsigned,
          scriptExecutionOk = false,
          contractInputs = AVector.empty,
          generatedOutputs = AVector.empty,
          txTemplate.inputSignatures,
          txTemplate.scriptSignatures
        )
      )
    }
  }

  def nextTimeStamp(parentTs: TimeStamp): TimeStamp = {
    val resultTs = TimeStamp.now()
    if (resultTs <= parentTs) {
      parentTs.plusMillisUnsafe(1)
    } else {
      resultTs
    }
  }

  def isSame(utxos0: AVector[AssetOutputInfo], utxos1: AVector[AssetOutputInfo]): Boolean = {
    (utxos0.length == utxos1.length) && {
      val set = utxos0.toSet
      utxos1.forall(set.contains)
    }
  }

  def truncateTxs(
      txs: AVector[TransactionTemplate],
      maximalTxs: Int,
      maximalGas: GasBox
  ): AVector[TransactionTemplate] = {
    @tailrec
    def iter(gasSum: Int, index: Int): Int = {
      if (index < txs.length) {
        val newSum = gasSum + txs(index).unsigned.gasAmount.value
        if (newSum > 0 && newSum <= maximalGas.value) iter(newSum, index + 1) else index
      } else {
        index
      }
    }

    val maximalGasIndex = iter(0, 0)
    txs.take(math.min(maximalTxs, maximalGasIndex))
  }
}

trait SyncUtils {
  def getIntraSyncInventories(): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getIntraSyncInventoriesUnsafe())

  def getSyncLocators(): IOResult[AVector[(ChainIndex, AVector[BlockHash])]] =
    IOUtils.tryExecute(getSyncLocatorsUnsafe())

  def getSyncInventories(
      locators: AVector[AVector[BlockHash]],
      peerBrokerInfo: BrokerGroupInfo
  ): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getSyncInventoriesUnsafe(locators, peerBrokerInfo))

  protected def getIntraSyncInventoriesUnsafe(): AVector[AVector[BlockHash]]

  protected def getSyncLocatorsUnsafe(): AVector[(ChainIndex, AVector[BlockHash])]

  protected def getSyncInventoriesUnsafe(
      locators: AVector[AVector[BlockHash]],
      peerBrokerInfo: BrokerGroupInfo
  ): AVector[AVector[BlockHash]]
}
