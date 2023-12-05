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

package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.crypto.MerkleHashable
import org.alephium.protocol._
import org.alephium.protocol.config.{ConsensusConfigs, GroupConfig, NetworkConfig}
import org.alephium.protocol.model.Transaction.MerkelTx
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde._
import org.alephium.util.{AVector, Duration, Math, TimeStamp, U256}

sealed trait TransactionAbstract {
  def unsigned: UnsignedTransaction
  def inputSignatures: AVector[Signature]
  def scriptSignatures: AVector[Signature]

  def id: TransactionId = unsigned.id

  // this might only works for validated tx
  def fromGroup(implicit config: GroupConfig): GroupIndex = unsigned.fromGroup

  // this might only works for validated tx
  def toGroup(implicit config: GroupConfig): GroupIndex = unsigned.toGroup

  // this might only works for validated tx
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)

  def gasFeeUnsafe: U256 = unsigned.gasPrice * unsigned.gasAmount

  def outputsLength: Int

  def getOutput(index: Int): TxOutput

  def assetOutputRefs: AVector[AssetOutputRef] = {
    unsigned.fixedOutputs.mapWithIndex { case (output, index) =>
      AssetOutputRef.from(output, TxOutputRef.key(id, index))
    }
  }

  def isEntryMethodPayable: Boolean = unsigned.scriptOpt.exists(_.entryMethod.usePreapprovedAssets)
}

final case class Transaction(
    unsigned: UnsignedTransaction,
    scriptExecutionOk: Boolean,
    contractInputs: AVector[ContractOutputRef],
    generatedOutputs: AVector[TxOutput],
    inputSignatures: AVector[Signature],
    scriptSignatures: AVector[Signature]
) extends TransactionAbstract
    with MerkleHashable[Hash] {
  def toMerkleTx: MerkelTx =
    MerkelTx(
      id,
      scriptExecutionOk,
      contractInputs,
      generatedOutputs,
      inputSignatures,
      scriptSignatures
    )

  def merkleHash: Hash = Hash.hash(serialize(toMerkleTx))

  def allOutputs: AVector[TxOutput] = unsigned.fixedOutputs.as[TxOutput] ++ generatedOutputs

  def allInputRefs: AVector[TxOutputRef] =
    unsigned.inputs.map[TxOutputRef](_.outputRef) ++ contractInputs

  def inputsLength: Int  = unsigned.inputs.length + contractInputs.length
  def outputsLength: Int = unsigned.fixedOutputs.length + generatedOutputs.length

  def getOutput(index: Int): TxOutput = {
    assume(index >= 0 && index < outputsLength)
    if (index < unsigned.fixedOutputs.length) {
      unsigned.fixedOutputs(index)
    } else {
      generatedOutputs(index - unsigned.fixedOutputs.length)
    }
  }

  lazy val attoAlphAmountInOutputs: Option[U256] = {
    val sum1Opt =
      unsigned.fixedOutputs
        .foldE(U256.Zero)((sum, output) => sum.add(output.amount).toRight(()))
        .toOption
    val sum2Opt =
      generatedOutputs
        .foldE(U256.Zero)((sum, output) => sum.add(output.amount).toRight(()))
        .toOption
    for {
      sum1 <- sum1Opt
      sum2 <- sum2Opt
      sum  <- sum1.add(sum2)
    } yield sum
  }

  def toTemplate: TransactionTemplate =
    TransactionTemplate(unsigned, inputSignatures, scriptSignatures)
}

object Transaction {
  implicit val serde: Serde[Transaction] =
    Serde.forProduct6(
      Transaction.apply,
      t =>
        (
          t.unsigned,
          t.scriptExecutionOk,
          t.contractInputs,
          t.generatedOutputs,
          t.inputSignatures,
          t.scriptSignatures
        )
    )

  def from(
      inputs: AVector[TxInput],
      outputs: AVector[AssetOutput],
      generatedOutputs: AVector[TxOutput],
      privateKey: PrivateKey
  )(implicit networkConfig: NetworkConfig): Transaction = {
    from(UnsignedTransaction(inputs, outputs), generatedOutputs, privateKey)
  }

  def from(
      inputs: AVector[TxInput],
      outputs: AVector[AssetOutput],
      privateKey: PrivateKey
  )(implicit networkConfig: NetworkConfig): Transaction = {
    from(inputs, outputs, AVector.empty, privateKey)
  }

  def from(
      inputs: AVector[TxInput],
      outputs: AVector[AssetOutput],
      inputSignatures: AVector[Signature]
  )(implicit networkConfig: NetworkConfig): Transaction = {
    Transaction(
      UnsignedTransaction(inputs, outputs),
      scriptExecutionOk = true,
      contractInputs = AVector.empty,
      generatedOutputs = AVector.empty,
      inputSignatures,
      scriptSignatures = AVector.empty
    )
  }

  def from(
      inputs: AVector[TxInput],
      outputs: AVector[AssetOutput],
      generatedOutputs: AVector[TxOutput],
      inputSignatures: AVector[Signature]
  )(implicit networkConfig: NetworkConfig): Transaction = {
    Transaction(
      UnsignedTransaction(inputs, outputs),
      scriptExecutionOk = true,
      contractInputs = AVector.empty,
      generatedOutputs,
      inputSignatures,
      scriptSignatures = AVector.empty
    )
  }

  def from(unsigned: UnsignedTransaction, privateKey: PrivateKey): Transaction = {
    from(unsigned, AVector.empty[TxOutput], privateKey)
  }

  def from(
      unsigned: UnsignedTransaction,
      generatedOutputs: AVector[TxOutput],
      privateKey: PrivateKey
  ): Transaction = {
    val signature = SignatureSchema.sign(unsigned.id, privateKey)
    Transaction(
      unsigned,
      scriptExecutionOk = true,
      contractInputs = AVector.empty,
      generatedOutputs,
      AVector(signature),
      scriptSignatures = AVector.empty
    )
  }

  def from(
      unsigned: UnsignedTransaction,
      contractInputs: AVector[ContractOutputRef],
      generatedOutputs: AVector[TxOutput],
      privateKey: PrivateKey
  ): Transaction = {
    val signature = SignatureSchema.sign(unsigned.id, privateKey)
    Transaction(
      unsigned,
      scriptExecutionOk = true,
      contractInputs,
      generatedOutputs,
      AVector(signature),
      scriptSignatures = AVector.empty
    )
  }

  def from(unsigned: UnsignedTransaction, inputSignatures: AVector[Signature]): Transaction = {
    Transaction(
      unsigned,
      scriptExecutionOk = true,
      contractInputs = AVector.empty,
      generatedOutputs = AVector.empty,
      inputSignatures,
      scriptSignatures = AVector.empty
    )
  }

  def totalReward(gasFee: U256, miningReward: U256, hardFork: HardFork): U256 = {
    if (hardFork.isLemanEnabled()) {
      miningReward
    } else {
      totalRewardPreLeman(gasFee, miningReward)
    }
  }

  // PoLW burning is not considered
  @inline def totalRewardPreLeman(gasFee: U256, miningReward: U256): U256 = {
    val threshold = Math.max(miningReward, ALPH.oneAlph)
    val gasReward = gasFee.divUnsafe(U256.Two)
    if (gasReward >= threshold) {
      miningReward.addUnsafe(threshold)
    } else {
      miningReward.addUnsafe(gasReward)
    }
  }

  def coinbase(
      chainIndex: ChainIndex,
      txs: AVector[Transaction],
      lockupScript: LockupScript.Asset,
      target: Target,
      blockTs: TimeStamp,
      uncles: AVector[(BlockHeader, LockupScript.Asset)]
  )(implicit consensusConfigs: ConsensusConfigs, networkConfig: NetworkConfig): Transaction = {
    coinbase(chainIndex, txs, lockupScript, ByteString.empty, target, blockTs, uncles)
  }

  // scalastyle:off parameter.number
  def coinbase(
      chainIndex: ChainIndex,
      txs: AVector[Transaction],
      lockupScript: LockupScript.Asset,
      minerData: ByteString,
      target: Target,
      blockTs: TimeStamp,
      uncles: AVector[(BlockHeader, LockupScript.Asset)]
  )(implicit consensusConfigs: ConsensusConfigs, networkConfig: NetworkConfig): Transaction = {
    val gasFee = txs.fold(U256.Zero)(_ addUnsafe _.gasFeeUnsafe)
    coinbase(chainIndex, gasFee, lockupScript, minerData, target, blockTs, uncles)
  }

  def coinbase(
      chainIndex: ChainIndex,
      gasFee: U256,
      lockupScript: LockupScript.Asset,
      target: Target,
      blockTs: TimeStamp,
      uncles: AVector[(BlockHeader, LockupScript.Asset)]
  )(implicit consensusConfigs: ConsensusConfigs, networkConfig: NetworkConfig): Transaction = {
    coinbase(chainIndex, gasFee, lockupScript, ByteString.empty, target, blockTs, uncles)
  }

  def coinbase(
      chainIndex: ChainIndex,
      gasFee: U256,
      lockupScript: LockupScript.Asset,
      minerData: ByteString,
      target: Target,
      blockTs: TimeStamp,
      uncles: AVector[(BlockHeader, LockupScript.Asset)]
  )(implicit consensusConfigs: ConsensusConfigs, networkConfig: NetworkConfig): Transaction = {
    val emissionConfig = consensusConfigs.getConsensusConfig(blockTs)
    Coinbase.build(chainIndex, gasFee, lockupScript, minerData, target, blockTs, uncles)(
      emissionConfig,
      networkConfig
    )
  }

  def genesis(
      balances: AVector[(LockupScript.Asset, U256, Duration)],
      noPreMineProof: ByteString
  )(implicit networkConfig: NetworkConfig): Transaction = {
    val outputs = balances.mapWithIndex[AssetOutput] {
      case ((lockupScript, value, lockupDuration), index) =>
        val txData = if (index == 0) noPreMineProof else ByteString.empty
        TxOutput.genesis(value, lockupScript, lockupDuration, txData)
    }
    val unsigned = UnsignedTransaction(inputs = AVector.empty, fixedOutputs = outputs)
    Transaction(
      unsigned,
      scriptExecutionOk = true,
      contractInputs = AVector.empty,
      generatedOutputs = AVector.empty,
      inputSignatures = AVector.empty,
      scriptSignatures = AVector.empty
    )
  }

  final private[model] case class MerkelTx(
      id: TransactionId,
      scriptExecutionOk: Boolean,
      contractInputs: AVector[ContractOutputRef],
      generatedOutputs: AVector[TxOutput],
      inputSignatures: AVector[Signature],
      scriptSignatures: AVector[Signature]
  )
  object MerkelTx {
    implicit val serde: Serde[MerkelTx] = Serde.forProduct6(
      MerkelTx.apply,
      t =>
        (
          t.id,
          t.scriptExecutionOk,
          t.contractInputs,
          t.generatedOutputs,
          t.inputSignatures,
          t.scriptSignatures
        )
    )
  }
}

final case class TransactionTemplate(
    unsigned: UnsignedTransaction,
    inputSignatures: AVector[Signature],
    scriptSignatures: AVector[Signature]
) extends TransactionAbstract {
  override def outputsLength: Int = unsigned.fixedOutputs.length

  override def getOutput(index: Int): TxOutput = unsigned.fixedOutputs(index)

}

object TransactionTemplate {
  val txOrdering: Ordering[TransactionTemplate] =
    Ordering
      .by[TransactionTemplate, (U256, Hash)](tx => (tx.unsigned.gasPrice.value, tx.id.value))
      .reverse // reverse the order so that higher gas tx can be at the front of an ordered collection

  implicit val serde: Serde[TransactionTemplate] = Serde.forProduct3(
    TransactionTemplate.apply,
    t => (t.unsigned, t.inputSignatures, t.scriptSignatures)
  )

  def from(unsigned: UnsignedTransaction, privateKey: PrivateKey): TransactionTemplate = {
    val signature = SignatureSchema.sign(unsigned.id, privateKey)
    TransactionTemplate(
      unsigned,
      AVector(signature),
      scriptSignatures = AVector.empty
    )
  }
}
