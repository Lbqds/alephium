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

package org.alephium.flow.mempool

import scala.util.Random

import org.scalacheck.Gen

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasPrice
import org.alephium.util.{AVector, LockFixture, TimeStamp, U256, UnsecureRandom}

class MemPoolSpec
    extends AlephiumFlowSpec
    with TxIndexesSpec.Fixture
    with LockFixture
    with NoIndexModelGeneratorsLike {
  def now = TimeStamp.now()

  val mainGroup      = GroupIndex.unsafe(0)
  val emptyTxIndexes = TxIndexes.emptyMemPool(mainGroup)

  it should "initialize an empty pool" in {
    val pool = MemPool.empty(mainGroup)
    pool.size is 0
  }

  it should "contain/add/remove for transactions" in {
    forAll(blockGen) { block =>
      val txTemplates = block.transactions.map(_.toTemplate)
      val group       = GroupIndex.unsafe(UnsecureRandom.sample(brokerConfig.groupRange))
      val pool        = MemPool.empty(group)
      val index       = block.chainIndex
      if (index.from.equals(group)) {
        txTemplates.foreach(pool.contains(_) is false)
        pool.add(index, txTemplates, now) is block.transactions.length
        pool.size is block.transactions.length
        block.transactions.foreach(tx => checkTx(pool.sharedTxIndexes, tx.toTemplate))
        txTemplates.foreach(pool.contains(_) is true)
        pool.removeUsedTxs(txTemplates) is block.transactions.length
        pool.size is 0
        pool.sharedTxIndexes is emptyTxIndexes
      }
    }
  }

  it should "calculate the size of mempool" in {
    val pool = MemPool.empty(mainGroup)
    val tx0  = transactionGen().sample.get.toTemplate
    pool.add(ChainIndex.unsafe(0, 0), tx0, TimeStamp.now())
    pool.size is 1
    val tx1 = transactionGen().sample.get.toTemplate
    pool.add(ChainIndex.unsafe(0, 1), tx1, now)
    pool.size is 2
  }

  it should "check capacity" in {
    val pool       = MemPool.ofCapacity(mainGroup, 1)
    val tx0        = transactionGen().sample.get.toTemplate
    val chainIndex = ChainIndex.unsafe(0, 0)
    pool.add(chainIndex, tx0, TimeStamp.now()) is MemPool.AddedToMemPool
    pool.isFull() is true
    pool.contains(tx0.id) is true

    val higherGasPrice = GasPrice(tx0.unsigned.gasPrice.value.addUnsafe(1))
    val tx1            = tx0.copy(unsigned = tx0.unsigned.copy(gasPrice = higherGasPrice))
    pool.add(chainIndex, tx1, TimeStamp.now()) is MemPool.AddedToMemPool
    pool.isFull() is true
    pool.contains(tx0.id) is false
    pool.contains(tx1.id) is true

    pool.add(chainIndex, tx0, TimeStamp.now()) is MemPool.MemPoolIsFull
    pool.isFull() is true
    pool.contains(tx0.id) is false
    pool.contains(tx1.id) is true
  }

  trait Fixture {
    val pool   = MemPool.empty(GroupIndex.unsafe(0))
    val index0 = ChainIndex.unsafe(0, 0)
    val index1 = ChainIndex.unsafe(0, 1)
    val tx0    = transactionGen().retryUntil(_.chainIndex equals index0).sample.get.toTemplate
    val tx1    = transactionGen().retryUntil(_.chainIndex equals index1).sample.get.toTemplate
    pool.add(index0, tx0, TimeStamp.now())
    pool.add(index1, tx1, now)
  }

  it should "list transactions for a specific group" in new Fixture {
    pool.getAll().map(_.id).toSet is AVector(tx0, tx1).map(_.id).toSet
  }

  it should "work for utxos" in new Fixture {
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    tx1.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    pool.isDoubleSpending(index0, tx0) is true
    pool.isDoubleSpending(index0, tx1) is true
    tx0.assetOutputRefs.foreach(output =>
      pool.sharedTxIndexes.outputIndex.contains(output) is
        (output.fromGroup equals mainGroup)
    )
    tx1.assetOutputRefs.foreach(output =>
      pool.sharedTxIndexes.outputIndex.contains(output) is
        (output.fromGroup equals mainGroup)
    )
    tx0.assetOutputRefs.foreachWithIndex((output, index) =>
      if (output.fromGroup equals mainGroup) {
        pool.getOutput(output) is Some(tx0.getOutput(index))
      }
    )
    tx1.assetOutputRefs.foreachWithIndex((output, index) =>
      if (output.fromGroup equals mainGroup) {
        pool.getOutput(output) is Some(tx1.getOutput(index))
      }
    )
  }

  it should "work for sequential txs for intra-group chain" in new Fixture {
    val blockFlow  = isolatedBlockFlow()
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block0     = transfer(blockFlow, chainIndex)
    val tx2        = block0.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, chainIndex)
    val tx3    = block1.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block1)

    pool.add(chainIndex, tx2, TimeStamp.now())
    pool.add(chainIndex, tx3, now)
    val tx2Outputs = tx2.assetOutputRefs
    tx2Outputs.length is 2
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.head) is true
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.last) is true
    pool.isSpent(tx2Outputs.last) is true
    tx3.assetOutputRefs.foreach(output => pool.isSpent(output) is false)
  }

  it should "work for sequential txs for inter-group chain" in new Fixture {
    val blockFlow  = isolatedBlockFlow()
    val chainIndex = ChainIndex.unsafe(0, 2)
    val block0     = transfer(blockFlow, chainIndex)
    val tx2        = block0.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, chainIndex)
    val tx3    = block1.nonCoinbase.head.toTemplate
    addAndCheck(blockFlow, block1)

    pool.add(chainIndex, tx2, TimeStamp.now())
    pool.add(chainIndex, tx3, now)
    val tx2Outputs = tx2.assetOutputRefs
    tx2Outputs.length is 2
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.head) is false
    pool.sharedTxIndexes.outputIndex.contains(tx2Outputs.last) is true
    pool.isSpent(tx2Outputs.last) is true
    tx3.assetOutputRefs.foreach { output =>
      if (output.fromGroup.value == 0) {
        pool.isSpent(output) is false
      } else {
        pool.sharedTxIndexes.outputIndex.contains(output) is false
      }
    }
  }

  it should "clean mempool" in {
    val blockFlow = isolatedBlockFlow()

    val pool   = MemPool.empty(mainGroup)
    val index0 = ChainIndex.unsafe(0, 0)
    val index1 = ChainIndex.unsafe(0, 1)
    val index2 = ChainIndex.unsafe(0, 2)
    val tx0    = transactionGen().retryUntil(_.chainIndex equals index0).sample.get.toTemplate
    val tx1    = transactionGen().retryUntil(_.chainIndex equals index1).sample.get.toTemplate
    val block2 = transfer(blockFlow, index2)
    val tx2    = block2.nonCoinbase.head.toTemplate
    val tx3 =
      tx2.copy(unsigned = tx2.unsigned.copy(inputs = tx2.unsigned.inputs ++ tx1.unsigned.inputs))

    blockFlow.recheckInputs(index2.from, AVector(tx2, tx3)) isE AVector(tx3)

    val currentTs = TimeStamp.now()
    pool.add(index0, tx0, currentTs) is MemPool.AddedToMemPool
    pool.size is 1
    pool.add(index1, tx1, currentTs) is MemPool.AddedToMemPool
    pool.size is 2
    pool.add(index2, tx2, currentTs) is MemPool.AddedToMemPool
    pool.size is 3
    pool.add(index2, tx3, currentTs) is MemPool.DoubleSpending
    pool.size is 3
    pool.cleanInvalidTxs(blockFlow, TimeStamp.now().plusMinutesUnsafe(1)) is 2
    pool.size is 1
    pool.contains(tx2) is true
  }

  it should "remove unconfirmed txs from mempool" in {
    val pool       = MemPool.empty(mainGroup)
    val chainIndex = ChainIndex.unsafe(0, 0)
    val txs0       = AVector.fill(3)(transactionGen().sample.get.toTemplate)
    val ts0        = TimeStamp.now()
    val txs1       = AVector.fill(3)(transactionGen().sample.get.toTemplate)
    val ts1        = TimeStamp.now().plusSecondsUnsafe(1)

    pool.add(chainIndex, txs0, ts0)
    pool.add(chainIndex, txs1, ts1)

    txs0.foreach(tx => pool.contains(tx.id) is true)
    txs1.foreach(tx => pool.contains(tx.id) is true)

    pool.cleanUnconfirmedTxs(ts0)
    txs0.foreach(tx => pool.contains(tx.id) is false)
    txs1.foreach(tx => pool.contains(tx.id) is true)
  }

  it should "clear mempool" in new Fixture {
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is true)
    pool.clear()
    tx0.unsigned.inputs.foreach(input => pool.isSpent(input.outputRef) is false)
  }

  it should "collect transactions based on gas price" in {
    val pool = MemPool.empty(mainGroup)
    pool.size is 0

    val index = ChainIndex.unsafe(0)
    val txs = Seq.tabulate(10) { k =>
      val tx = transactionGen().sample.get
      tx.copy(unsigned = tx.unsigned.copy(gasPrice = GasPrice(nonCoinbaseMinGasPrice.value + k)))
        .toTemplate
    }
    val timeStamp = TimeStamp.now()
    Random.shuffle(txs).foreach(tx => pool.add(index, tx, timeStamp))

    pool.collectForBlockPreGhost(index, Int.MaxValue) is AVector.from(
      txs.sortBy(_.unsigned.gasPrice.value).reverse
    )
  }

  it should "handle cross-group transactions" in {
    val mainGroup = GroupIndex.unsafe(0)
    val pool      = MemPool.empty(mainGroup)
    val index     = ChainIndex.unsafe(1, 0)
    val tx        = transactionGen().retryUntil(_.chainIndex == index).sample.get.toTemplate
    pool.addXGroupTx(index, tx, TimeStamp.now())
    pool.size is 1
    pool.collectForBlockPreGhost(ChainIndex(mainGroup, mainGroup), Int.MaxValue).isEmpty is true

    pool.cleanInvalidTxs(blockFlow, TimeStamp.now().plusHoursUnsafe(1)) is 1
    pool.size is 0
  }

  trait SequentialTxsFixture {
    val chainIndex = chainIndexGen.sample.get

    implicit class RichTransactionTemplate(template: TransactionTemplate) {
      def outputRefs = template.assetOutputRefs.filter(_.fromGroup == chainIndex.from)

      def withGasPrice(gasPrice: GasPrice): TransactionTemplate = {
        template.copy(unsigned = template.unsigned.copy(gasPrice = gasPrice))
      }
    }

    implicit class RichGasPrice(gasPrice: GasPrice) {
      def add(num: Int): GasPrice = GasPrice(gasPrice.value.addUnsafe(U256.unsafe(num)))
    }

    @scala.annotation.tailrec
    final def genTx(
        chainIndex: ChainIndex = chainIndex,
        changeOutputSize: Int = 1
    ): TransactionTemplate = {
      val tx = transactionGen(chainIndexGen = Gen.const(chainIndex)).sample.get.toTemplate
      if (tx.outputRefs.length < changeOutputSize) genTx() else tx
    }

    def childTxOf(parentTx: TransactionTemplate): TransactionTemplate = {
      childTxOf(AVector(parentTx), chainIndex)
    }

    def childTxOfRefs(
        outputRefs: AVector[AssetOutputRef],
        chainIndex: ChainIndex = chainIndex
    ): TransactionTemplate = {
      val tx     = genTx(chainIndex)
      val inputs = outputRefs.map(ref => TxInput(ref, p2pkhUnlockGen(chainIndex.from).sample.get))
      tx.copy(unsigned = tx.unsigned.copy(inputs = inputs))
    }

    def childTxOf(
        parentTxs: AVector[TransactionTemplate],
        chainIndex: ChainIndex = chainIndex
    ): TransactionTemplate = {
      childTxOfRefs(parentTxs.map(_.outputRefs.head), chainIndex)
    }

    def createIsOutputRefExist(pool: MemPool): AssetOutputRef => Boolean = { ref =>
      {
        val allSourceNodes = pool.flow.sourceTxs.flatMap(m => AVector.from(m.values()))
        allSourceNodes.exists(_.tx.unsigned.inputs.exists(_.outputRef == ref))
      }
    }
  }

  it should "collect sequential txs" in new SequentialTxsFixture {
    {
      info("tx has one source parent")
      val pool     = MemPool.empty(chainIndex.from)
      val parentTx = genTx()
      pool.add(chainIndex, parentTx, TimeStamp.now())

      val childTx = childTxOf(parentTx)
      pool.add(chainIndex, childTx, TimeStamp.now())

      val isOutputRefExist = createIsOutputRefExist(pool)
      pool.collectForBlockGhost(chainIndex, 0, _ => true) is AVector.empty[TransactionTemplate]
      pool.collectForBlockGhost(chainIndex, 1, _ => false) is AVector.empty[TransactionTemplate]
      pool.collectForBlockGhost(chainIndex, 1, isOutputRefExist) is AVector(parentTx)
      pool.collectForBlockGhost(chainIndex, Int.MaxValue, isOutputRefExist) is AVector(
        parentTx,
        childTx
      )
    }

    {
      info("tx has two source parents")
      val pool                   = MemPool.empty(chainIndex.from)
      val (parentTx0, parentTx1) = (genTx(), genTx())
      pool.add(chainIndex, AVector(parentTx0, parentTx1), now)

      val childTx = childTxOf(AVector(parentTx0, parentTx1))
      pool.add(chainIndex, childTx, TimeStamp.now())

      val isOutputRefExist = createIsOutputRefExist(pool)
      pool.collectForBlockGhost(chainIndex, 2, isOutputRefExist).toSet is Set(parentTx0, parentTx1)
      pool.collectForBlockGhost(chainIndex, Int.MaxValue, isOutputRefExist).toSet is Set(
        parentTx0,
        parentTx1,
        childTx
      )
    }

    {
      info("the input of parent tx is not exist")
      val pool     = MemPool.empty(chainIndex.from)
      val parentTx = genTx()
      pool.add(chainIndex, parentTx, now)

      val childTx = childTxOf(parentTx)
      pool.add(chainIndex, childTx, TimeStamp.now())

      val isOutputRefExist = createIsOutputRefExist(pool)
      pool.collectForBlockGhost(
        chainIndex,
        Int.MaxValue,
        isOutputRefExist
      ) is AVector(parentTx, childTx)
      pool.collectForBlockGhost(
        chainIndex,
        Int.MaxValue,
        ref => isOutputRefExist(ref) && ref != parentTx.unsigned.inputs.head.outputRef
      ) is AVector.empty[TransactionTemplate]
    }

    {
      info("tx has one source parent and one non-source parent")
      val pool                   = MemPool.empty(chainIndex.from)
      val (parentTx0, parentTx1) = (genTx(), genTx())
      pool.add(chainIndex, AVector(parentTx0, parentTx1), now)

      val tx0 = childTxOf(parentTx0)
      val tx1 = childTxOf(AVector(parentTx1, tx0))
      pool.add(chainIndex, AVector(tx1, tx0), TimeStamp.now())

      val isOutputRefExist = createIsOutputRefExist(pool)
      pool.collectForBlockGhost(chainIndex, 3, isOutputRefExist).toSet is Set(
        parentTx0,
        parentTx1,
        tx0
      )
      pool.collectForBlockGhost(chainIndex, Int.MaxValue, isOutputRefExist).toSet is Set(
        parentTx0,
        parentTx1,
        tx0,
        tx1
      )
    }

    {
      info("chain index of tx is different from parent")
      val pool = MemPool.empty(chainIndex.from)
      val chainIndex1 =
        chainIndexGen.retryUntil(c => c.from == chainIndex.from && c.to != chainIndex.to).sample.get
      chainIndex1.from is chainIndex.from

      val parentTx = genTx()
      pool.add(chainIndex, parentTx, TimeStamp.now())

      val childTx = childTxOf(AVector(parentTx), chainIndex1)
      pool.add(chainIndex1, childTx, TimeStamp.now())

      val isOutputRefExist = createIsOutputRefExist(pool)
      pool.collectForBlockGhost(chainIndex, Int.MaxValue, isOutputRefExist) is AVector(parentTx)
      pool.collectForBlockGhost(chainIndex1, Int.MaxValue, isOutputRefExist) is AVector
        .empty[TransactionTemplate]
    }

    {
      info("parent tx is not from the same chain")
      val pool = MemPool.empty(chainIndex.from)
      val chainIndex1 =
        chainIndexGen.retryUntil(c => c.from == chainIndex.from && c.to != chainIndex.to).sample.get
      chainIndex1.from is chainIndex.from

      val (parentTx0, parentTx1) = (genTx(), genTx(chainIndex1))
      val now                    = TimeStamp.now()
      pool.add(chainIndex, parentTx0, now)
      pool.add(chainIndex1, parentTx1, now)

      val childTx = childTxOf(AVector(parentTx0, parentTx1))
      pool.add(chainIndex, childTx, TimeStamp.now())

      val isOutputRefExist = createIsOutputRefExist(pool)
      pool.collectForBlockGhost(chainIndex, Int.MaxValue, isOutputRefExist) is AVector(parentTx0)
      pool.collectForBlockGhost(chainIndex1, Int.MaxValue, isOutputRefExist) is AVector(parentTx1)
    }

    {
      info("sort txs by dependency")
      val pool     = MemPool.empty(chainIndex.from)
      val parentTx = genTx(chainIndex, 2)
      pool.add(chainIndex, parentTx, TimeStamp.now())

      val tx0      = childTxOfRefs(AVector(parentTx.outputRefs(0)))
      val gasPrice = tx0.unsigned.gasPrice.add(1)
      val tx1 =
        childTxOfRefs(AVector(parentTx.outputRefs(1), tx0.outputRefs.head)).withGasPrice(gasPrice)
      pool.add(chainIndex, AVector(tx0, tx1), TimeStamp.now())

      val isOutputRefExist = createIsOutputRefExist(pool)
      pool.collectForBlockGhost(chainIndex, Int.MaxValue, isOutputRefExist) is AVector(
        parentTx,
        tx0,
        tx1
      )
    }

    {
      info("sort txs by gas price")
      val pool      = MemPool.empty(chainIndex.from)
      val parentTx0 = genTx()
      val parentTx1 = genTx().withGasPrice(parentTx0.unsigned.gasPrice.add(1))
      pool.add(chainIndex, AVector(parentTx0, parentTx1), TimeStamp.now())

      val childTx0 = childTxOf(parentTx0)
      val childTx1 = childTxOf(parentTx1).withGasPrice(parentTx1.unsigned.gasPrice.add(1))
      pool.add(chainIndex, AVector(childTx0, childTx1), TimeStamp.now())

      val isOutputRefExist = createIsOutputRefExist(pool)
      pool.collectForBlockGhost(chainIndex, Int.MaxValue, isOutputRefExist) is AVector(
        parentTx1,
        childTx1,
        parentTx0,
        childTx0
      )
    }

    {
      info("output ref is not exist")
      val pool     = MemPool.empty(chainIndex.from)
      val parentTx = genTx(chainIndex)
      pool.add(chainIndex, parentTx, TimeStamp.now())

      val invalidOutputRef = assetOutputRefGen(chainIndex.from).sample.get
      val outputRefs       = AVector(parentTx.outputRefs.head, invalidOutputRef)
      val childTx          = childTxOfRefs(outputRefs)
      pool.add(chainIndex, childTx, TimeStamp.now())

      val isOutputRefExist = createIsOutputRefExist(pool)
      pool.collectForBlockGhost(chainIndex, Int.MaxValue, isOutputRefExist) is AVector(parentTx)
      pool.collectForBlockGhost(
        chainIndex,
        Int.MaxValue,
        ref => isOutputRefExist(ref) || ref == invalidOutputRef
      ) is AVector(parentTx, childTx)
    }
  }
}
