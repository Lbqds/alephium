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

package org.alephium.flow.validation

import scala.collection.mutable

import org.alephium.flow.core.{BlockFlow, BlockFlowGroupView}
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfigs, NetworkConfig}
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{BlockEnv, GasPrice, LogConfig, WorldState}
import org.alephium.serde._
import org.alephium.util.{EitherF, U256}

// scalastyle:off number.of.methods

trait BlockValidation extends Validation[Block, InvalidBlockStatus, Option[WorldState.Cached]] {
  import ValidationStatus._

  implicit def networkConfig: NetworkConfig

  def headerValidation: HeaderValidation
  def nonCoinbaseValidation: TxValidation

  override def validate(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    checkBlock(block, flow)
  }

  def validateTemplate(
      chainIndex: ChainIndex,
      template: BlockFlowTemplate,
      blockFlow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    val dummyHeader = BlockHeader.unsafe(
      BlockDeps.unsafe(template.deps),
      template.depStateHash,
      Hash.zero,
      template.templateTs,
      template.target,
      Nonce.zero
    )
    val dummyBlock = Block(dummyHeader, template.uncles, template.transactions)
    checkTemplate(chainIndex, dummyBlock, blockFlow)
  }

  // keep the commented lines so we could compare it easily with `checkBlockAfterHeader`
  def checkTemplate(
      chainIndex: ChainIndex,
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    for {
//      _ <- checkGroup(block)
//      _ <- checkNonEmptyTransactions(block)
      _ <- checkTxNumber(block)
      _ <- checkTxOrder(chainIndex, block, flow)
      _ <- checkTotalGas(block)
//      _ <- checkMerkleRoot(block)
//      _ <- checkFlow(block, flow)
      sideResult <- checkTxs(chainIndex, block, flow)
    } yield sideResult
  }

  override def validateUntilDependencies(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    checkBlockUntilDependencies(block, flow)
  }

  def validateAfterDependencies(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    checkBlockAfterDependencies(block, flow)
  }

  private[validation] def checkBlockUntilDependencies(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    headerValidation.checkHeaderUntilDependencies(block.header, flow)
  }

  private[validation] def checkBlockAfterDependencies(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    for {
      _          <- headerValidation.checkHeaderAfterDependencies(block.header, flow)
      sideResult <- checkBlockAfterHeader(block, flow)
    } yield sideResult
  }

  private[validation] def checkBlock(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    for {
      _          <- headerValidation.checkHeader(block.header, flow)
      sideResult <- checkBlockAfterHeader(block, flow)
    } yield sideResult
  }

  private[flow] def checkBlockAfterHeader(
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    for {
      _          <- checkGroup(block)
      _          <- checkNonEmptyTransactions(block)
      _          <- checkTxNumber(block)
      _          <- checkTxOrder(block.chainIndex, block, flow)
      _          <- checkTotalGas(block)
      _          <- checkMerkleRoot(block)
      _          <- checkFlow(block, flow)
      _          <- checkUncles(block, flow)
      sideResult <- checkTxs(block.chainIndex, block, flow)
    } yield sideResult
  }

  private def checkUncles(block: Block, flow: BlockFlow): BlockValidationResult[Unit] = {
    if (brokerConfig.contains(block.chainIndex.from)) {
      val hardFork = networkConfig.getHardFork(block.timestamp)
      if (hardFork.isGhostEnabled() && block.uncles.nonEmpty) {
        for {
          _       <- checkUncleSize(block)
          _       <- checkDuplicateUncles(block)
          isValid <- from(flow.getBlockChain(block.chainIndex).validateUncles(block))
          _       <- if (isValid) validBlock(()) else invalidBlock(InvalidUncles)
          _ <- if (isUncleDepsValid(block, flow)) validBlock(()) else invalidBlock(InvalidUncles)
          _ <- block.uncles.foreachE(header => headerValidation.validate(header, flow))
        } yield ()
      } else if (block.uncles.nonEmpty) {
        invalidBlock(InvalidUnclesBeforeGhostHardFork)
      } else {
        validBlock(())
      }
    } else {
      validBlock(())
    }
  }

  @inline private def checkUncleSize(block: Block): BlockValidationResult[Unit] = {
    if (block.uncles.length > ALPH.MaxUncleSize) {
      invalidBlock(InvalidUncleSize)
    } else {
      validBlock(())
    }
  }

  @inline private def checkDuplicateUncles(block: Block): BlockValidationResult[Unit] = {
    if (block.uncles.toSeq.distinctBy(_.hash).length != block.uncles.length) {
      invalidBlock(DuplicatedUncles)
    } else {
      validBlock(())
    }
  }

  @inline private def isUncleDepsValid(block: Block, flow: BlockFlow): Boolean = {
    block.uncles.forall(uncle => flow.isExtendingUnsafe(block.blockDeps, uncle.blockDeps))
  }

  private[validation] def checkTxs(
      chainIndex: ChainIndex,
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Option[WorldState.Cached]] = {
    if (brokerConfig.contains(chainIndex.from)) {
      val hardFork = networkConfig.getHardFork(block.timestamp)
      for {
        groupView <- from(if (hardFork.isGhostEnabled()) {
          flow.getMutableGroupViewOnlyForValidation(chainIndex.from, block.blockDeps)
        } else {
          flow.getMutableGroupView(chainIndex.from, block.blockDeps)
        })
        _ <- checkNonCoinbases(chainIndex, block, groupView, hardFork)
        _ <- checkCoinbase(
          chainIndex,
          block,
          groupView,
          hardFork
        ) // validate non-coinbase first for gas fee
      } yield {
        if (chainIndex.isIntraGroup) Some(groupView.worldState) else None
      }
    } else {
      validBlock(None)
    }
  }

  private[validation] def checkGroup(block: Block): BlockValidationResult[Unit] = {
    if (block.chainIndex.relateTo(brokerConfig)) {
      validBlock(())
    } else {
      invalidBlock(InvalidGroup)
    }
  }

  private[validation] def checkNonEmptyTransactions(block: Block): BlockValidationResult[Unit] = {
    if (block.transactions.nonEmpty) validBlock(()) else invalidBlock(EmptyTransactionList)
  }

  private[validation] def checkTxNumber(block: Block): BlockValidationResult[Unit] = {
    if (block.transactions.length <= maximalTxsInOneBlock) {
      validBlock(())
    } else {
      invalidBlock(TooManyTransactions)
    }
  }

  def checkTxOrderGhost(
      chainIndex: ChainIndex,
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    @scala.annotation.tailrec
    def iter(
        outputRefSet: mutable.Set[AssetOutputRef],
        groupView: BlockFlowGroupView[WorldState.Cached],
        txs: mutable.ArrayBuffer[Transaction],
        txIndex: Int
    ): BlockValidationResult[Unit] = {
      if (txs.isEmpty) {
        validBlock(())
      } else {
        txs.find(
          _.unsigned.inputs.forall { input =>
            outputRefSet.contains(input.outputRef) || groupView.containAsset(input.outputRef)
          }
        ) match {
          case Some(tx) =>
            if (block.transactions(txIndex) == tx) {
              iter(
                outputRefSet.addAll(tx.assetOutputRefs),
                groupView,
                txs.subtractOne(tx),
                txIndex + 1
              )
            } else {
              invalidBlock(InvalidTxOrderGhostHardFork)
            }
          case None => invalidBlock(InvalidTxOrderGhostHardFork)
        }
      }
    }

    from(flow.getMutableGroupView(chainIndex.from, block.blockDeps)).flatMap { groupView =>
      val sortedTxs = block.transactions.sortBy(_.toTemplate)(TransactionTemplate.txOrdering)
      iter(mutable.Set.empty, groupView, mutable.ArrayBuffer.from(sortedTxs), 0)
    }
  }

  private[validation] def checkTxOrder(
      chainIndex: ChainIndex,
      block: Block,
      flow: BlockFlow
  ): BlockValidationResult[Unit] = {
    val hardFork = networkConfig.getHardFork(block.timestamp)
    if (hardFork.isGhostEnabled()) {
      if (brokerConfig.contains(chainIndex.from)) {
        checkTxOrderGhost(chainIndex, block, flow)
      } else {
        Right(())
      }
    } else {
      val result = block.transactions.foldE[Unit, GasPrice](GasPrice(ALPH.MaxALPHValue)) {
        case (lastGasPrice, tx) =>
          val txGasPrice = tx.unsigned.gasPrice
          if (txGasPrice > lastGasPrice) Left(()) else Right(txGasPrice)
      }
      if (result.isRight) validBlock(()) else invalidBlock(TxGasPriceNonDecreasing)
    }
  }

  // Let's check the gas is decreasing as well
  private[validation] def checkTotalGas(block: Block): BlockValidationResult[Unit] = {
    val totalGas = block.transactions.fold(0)(_ + _.unsigned.gasAmount.value)
    if (totalGas <= maximalGasPerBlock.value) validBlock(()) else invalidBlock(TooMuchGasUsed)
  }

  private[validation] def checkCoinbase(
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached],
      hardFork: HardFork
  ): BlockValidationResult[Unit] = {
    val consensusConfig = consensusConfigs.getConsensusConfig(hardFork)
    val result = consensusConfig.emission.reward(block.header) match {
      case Emission.PoW(miningReward) =>
        val netReward = Transaction.totalReward(block.gasFee, miningReward, hardFork)
        checkCoinbase(chainIndex, block, groupView, 1 + block.uncles.length, netReward, netReward)
      case Emission.PoLW(miningReward, burntAmount) =>
        val lockedReward = Transaction.totalReward(block.gasFee, miningReward, hardFork)
        val netReward    = lockedReward.subUnsafe(burntAmount)
        checkCoinbase(chainIndex, block, groupView, 2, netReward, lockedReward)
    }

    result match {
      case Left(Right(ExistInvalidTx(_, InvalidAlphBalance))) => Left(Right(InvalidCoinbaseReward))
      case result                                             => result
    }
  }

  private[validation] def checkCoinbase(
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached],
      outputNum: Int,
      netReward: U256,
      lockedReward: U256
  ): BlockValidationResult[Unit] = {
    // FIXME
    val rewards = netReward.mulUnsafe(U256.unsafe(outputNum))
    for {
      _ <- checkCoinbaseEasy(block, outputNum)
      _ <- checkCoinbaseData(chainIndex, block)
      _ <- checkCoinbaseAsTx(chainIndex, block, groupView, rewards.addUnsafe(coinbaseGasFee))
      _ <- checkLockedReward(block, lockedReward)
    } yield ()
  }

  private[validation] def checkCoinbaseAsTx(
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached],
      netReward: U256
  ): BlockValidationResult[Unit] = {
    if (brokerConfig.contains(chainIndex.from)) {
      val blockEnv = BlockEnv.from(chainIndex, block.header)
      convert(
        block.coinbase,
        nonCoinbaseValidation.checkBlockTx(
          chainIndex,
          block.coinbase,
          groupView,
          blockEnv,
          Some(netReward)
        )
      )
    } else {
      validBlock(())
    }
  }

  private[validation] def checkCoinbaseEasy(
      block: Block,
      outputsNum: Int
  ): BlockValidationResult[Unit] = {
    val coinbase = block.coinbase // Note: validateNonEmptyTransactions first pls!
    val unsigned = coinbase.unsigned
    if (
      unsigned.scriptOpt.isEmpty &&
      unsigned.gasAmount == minimalGas &&
      unsigned.gasPrice == coinbaseGasPrice &&
      unsigned.fixedOutputs.length == outputsNum &&
      unsigned.fixedOutputs(0).tokens.isEmpty &&
      coinbase.contractInputs.isEmpty &&
      coinbase.generatedOutputs.isEmpty &&
      coinbase.inputSignatures.isEmpty &&
      coinbase.scriptSignatures.isEmpty
    ) {
      validBlock(())
    } else {
      invalidBlock(InvalidCoinbaseFormat)
    }
  }

  private[validation] def checkCoinbaseData(
      chainIndex: ChainIndex,
      block: Block
  ): BlockValidationResult[Unit] = {
    val coinbase = block.coinbase
    val data     = coinbase.unsigned.fixedOutputs.head.additionalData
    deserialize[CoinbaseData](data) match {
      case Right(CoinbaseDataV1(prefix, _)) =>
        if (prefix == CoinbaseDataPrefix.from(chainIndex, block.timestamp)) {
          validBlock(())
        } else {
          invalidBlock(InvalidCoinbaseData)
        }
      case Right(CoinbaseDataV2(prefix, version, uncleHashes, _)) =>
        if (
          prefix == CoinbaseDataPrefix.from(chainIndex, block.timestamp) &&
          version == CoinbaseData.GhostVersion &&
          uncleHashes == block.uncles.map(_.hash)
        ) {
          validBlock(())
        } else {
          invalidBlock(InvalidCoinbaseData)
        }
      case Left(_) => invalidBlock(InvalidCoinbaseData)
    }
  }

  private[validation] def checkLockedReward(
      block: Block,
      lockedAmount: U256
  ): BlockValidationResult[Unit] = {
    val output = block.coinbase.unsigned.fixedOutputs.head
    if (output.amount != lockedAmount) {
      invalidBlock(InvalidCoinbaseLockedAmount)
    } else if (output.lockTime != block.timestamp.plusUnsafe(networkConfig.coinbaseLockupPeriod)) {
      invalidBlock(InvalidCoinbaseLockupPeriod)
    } else {
      validBlock(())
    }
  }

  private[validation] def checkMerkleRoot(block: Block): BlockValidationResult[Unit] = {
    if (block.header.txsHash == Block.calTxsHash(block.transactions)) {
      validBlock(())
    } else {
      invalidBlock(InvalidTxsMerkleRoot)
    }
  }

  private[validation] def checkNonCoinbases(
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached],
      hardFork: HardFork
  ): BlockValidationResult[Unit] = {
    assume(chainIndex.relateTo(brokerConfig))

    if (brokerConfig.contains(chainIndex.from)) {
      for {
        _ <- checkBlockDoubleSpending(block)
        _ <- checkNonCoinbasesEach(chainIndex, block, groupView, hardFork)
      } yield ()
    } else {
      validBlock(())
    }
  }

  private[validation] def checkNonCoinbasesEach(
      chainIndex: ChainIndex,
      block: Block,
      groupView: BlockFlowGroupView[WorldState.Cached],
      hardFork: HardFork
  ): BlockValidationResult[Unit] = {
    val blockEnv       = BlockEnv.from(chainIndex, block.header)
    val parentHash     = block.blockDeps.uncleHash(chainIndex.to)
    val executionOrder = Block.getNonCoinbaseExecutionOrder(parentHash, block.nonCoinbase, hardFork)
    EitherF.foreachTry(executionOrder) { index =>
      val tx = block.transactions(index)
      val txValidationResult = nonCoinbaseValidation.checkBlockTx(
        chainIndex,
        tx,
        groupView,
        blockEnv,
        None
      )
      txValidationResult match {
        case Right(_) =>
          groupView.onTxValidated(tx)
          Right(())
        case Left(Right(TxScriptExeFailed(_))) =>
          if (tx.contractInputs.isEmpty) {
            groupView.onTxValidated(tx)
            Right(())
          } else {
            convert(tx, invalidTx(ContractInputsShouldBeEmptyForFailedTxScripts))
          }
        case Left(Right(e)) => convert(tx, invalidTx(e))
        case Left(Left(e))  => Left(Left(e))
      }
    }
  }

  private[validation] def checkBlockDoubleSpending(block: Block): BlockValidationResult[Unit] = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
    block.nonCoinbase.foreachE { tx =>
      tx.unsigned.inputs.foreachE { input =>
        if (utxoUsed.contains(input.outputRef)) {
          invalidBlock(BlockDoubleSpending)
        } else {
          utxoUsed += input.outputRef
          validBlock(())
        }
      }
    }
  }

  private[validation] def checkFlow(block: Block, blockFlow: BlockFlow)(implicit
      brokerConfig: BrokerConfig
  ): BlockValidationResult[Unit] = {
    if (brokerConfig.contains(block.chainIndex.from)) {
      ValidationStatus.from(blockFlow.checkFlowTxs(block)).flatMap { ok =>
        if (ok) validBlock(()) else invalidBlock(InvalidFlowTxs)
      }
    } else {
      validBlock(())
    }
  }
}

object BlockValidation {
  def build(blockFlow: BlockFlow): BlockValidation =
    build(
      blockFlow.brokerConfig,
      blockFlow.networkConfig,
      blockFlow.consensusConfigs,
      blockFlow.logConfig
    )

  def build(implicit
      brokerConfig: BrokerConfig,
      networkConfig: NetworkConfig,
      consensusConfigs: ConsensusConfigs,
      logConfig: LogConfig
  ): BlockValidation = new Impl()

  class Impl(implicit
      val brokerConfig: BrokerConfig,
      val networkConfig: NetworkConfig,
      val consensusConfigs: ConsensusConfigs,
      val logConfig: LogConfig
  ) extends BlockValidation {
    override def headerValidation: HeaderValidation  = HeaderValidation.build
    override def nonCoinbaseValidation: TxValidation = TxValidation.build
  }
}
