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

import org.alephium.flow.FlowFixture
import org.alephium.protocol.{ALPH, Generators}
import org.alephium.protocol.model.{ChainIndex, GroupIndex}
import org.alephium.util.{AlephiumSpec, TimeStamp}

class BlockFlowGroupViewSpec extends AlephiumSpec {
  it should "fetch preOutputs" in new FlowFixture {
    val blockFlow1 = isolatedBlockFlow()
    val block0     = transfer(blockFlow1, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow1, block0)
    val block1 = transfer(blockFlow1, ChainIndex.unsafe(0, 2))
    addAndCheck(blockFlow1, block1)
    val block2 = transfer(blockFlow1, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow1, block2)
    val block3 = transfer(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block3)

    addAndCheck(blockFlow, block0)

    val grandPool = blockFlow.getGrandPool()
    val mempool   = blockFlow.getMemPool(ChainIndex.unsafe(0, 0))

    val mainGroup    = GroupIndex.unsafe(0)
    val lockupScript = getGenesisLockupScript(ChainIndex(mainGroup, mainGroup))

    val tx1        = block1.nonCoinbase.head.toTemplate
    val groupView1 = blockFlow.getImmutableGroupView(mainGroup).rightValue
    groupView1.getPreOutputs(tx1.unsigned.inputs).rightValue.get is
      block0.nonCoinbase.head.unsigned.fixedOutputs.tail
    groupView1.getRelevantUtxos(lockupScript, Int.MaxValue).rightValue.map(_.output) is
      block0.nonCoinbase.head.unsigned.fixedOutputs.tail
    grandPool.add(block1.chainIndex, tx1, TimeStamp.now())
    mempool.contains(tx1) is true

    val tx2        = block2.nonCoinbase.head.toTemplate
    val groupView2 = blockFlow.getImmutableGroupViewIncludePool(mainGroup).rightValue
    groupView2.getPreOutputs(tx1.unsigned.inputs).rightValue.isEmpty is true
    groupView2.getPreOutputs(tx2.unsigned.inputs).rightValue.get is
      block1.nonCoinbase.head.unsigned.fixedOutputs.tail
    groupView2.getRelevantUtxos(lockupScript, Int.MaxValue).rightValue.map(_.output) is
      block1.nonCoinbase.head.unsigned.fixedOutputs.tail
    grandPool.add(block2.chainIndex, tx2, TimeStamp.now())
    mempool.contains(tx2) is true

    val tx3        = block3.nonCoinbase.head.toTemplate
    val groupView3 = blockFlow.getImmutableGroupViewIncludePool(mainGroup).rightValue
    groupView3.getPreOutputs(tx1.unsigned.inputs).rightValue.isEmpty is true
    groupView3.getPreOutputs(tx2.unsigned.inputs).rightValue.isEmpty is true
    groupView3.getPreOutputs(tx3.unsigned.inputs).rightValue.get is
      block2.nonCoinbase.head.unsigned.fixedOutputs.tail
    groupView3.getRelevantUtxos(lockupScript, Int.MaxValue).rightValue.map(_.output) is
      block2.nonCoinbase.head.unsigned.fixedOutputs.tail
  }

  it should "test OutputCacheOnlyForValidation" in new FlowFixture with Generators {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val chainIndex = chainIndexGen.sample.get
    val genesisKey = genesisKeys(chainIndex.from.value)._1
    val privateKey = {
      val (privateKey, publicKey) = chainIndex.from.generateKey
      (0 until 4).foreach { _ =>
        addAndCheck(blockFlow, transfer(blockFlow, genesisKey, publicKey, ALPH.alph(10)))
      }
      privateKey
    }

    val txs = (0 until 19).map { _ =>
      val (_, toPublicKey) = chainIndex.to.generateKey
      val tx =
        transfer(blockFlow, privateKey, toPublicKey, ALPH.alph(2)).nonCoinbase.head.toTemplate
      blockFlow.grandPool.add(chainIndex, tx, TimeStamp.now())
      tx
    }

    val bestDeps = blockFlow.getBestDeps(chainIndex.from)
    val groupView =
      blockFlow.getMutableGroupViewOnlyForValidation(chainIndex.from, bestDeps).rightValue

    val block = mineFromMemPool(blockFlow, chainIndex)
    block.nonCoinbase.map(_.toTemplate).toSet is txs.toSet
    block.nonCoinbase.foreach { tx =>
      tx.unsigned.inputs.foreach(i => groupView.containAsset(i.outputRef) is true)
      tx.assetOutputRefs.foreach(ref => groupView.containAsset(ref) is false)
      groupView.onTxValidated(tx)
      tx.unsigned.inputs.foreach(i => groupView.containAsset(i.outputRef) is false)
      tx.assetOutputRefs.foreach { ref =>
        groupView.containAsset(ref) is (ref.fromGroup == chainIndex.from)
      }
    }
    addAndCheck(blockFlow, block)
  }
}
