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

package org.alephium.tools

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.Node
import org.alephium.flow.setting.Platform
import org.alephium.io.RocksDBKeyValueStorage
import org.alephium.protocol.model.{Block, BlockHash, Transaction, TxOutputRef}
import org.alephium.protocol.vm.nodeindexes._
import org.alephium.util.AVector

object AddIndexForConflictedTxs extends App with StrictLogging {
  private val rootPath              = Platform.getRootPath()
  private val (blockFlow, storages) = Node.buildBlockFlowUnsafe(rootPath)

  private val outputRefIndexStorage = blockFlow.txOutputRefIndexStorage match {
    case Right(storage) => storage
    case Left(error) =>
      logger.error(s"TxOutputRefIndexStorage does not exist: $error")
      storages.closeUnsafe()
      sys.exit()
  }

  private def updateIndexForConflictedTx(tx: Transaction, txIndex: Int, block: Block) = {
    assert(!block.chainIndex.isIntraGroup)
    tx.unsigned.fixedOutputs.foreachWithIndexE { case (output, index) =>
      val outputRef = TxOutputRef.from(tx.id, index, output)
      TxOutputRefIndexStorage.store(
        outputRefIndexStorage,
        outputRef.key,
        tx.id,
        Some(TxOutputLocator(block.hash, txIndex, index))
      )
    }
  }

  private val conflictedTxsStorage = blockFlow.conflictedTxsStorage
  private val conflictedTxsPerIntraBlock = conflictedTxsStorage.conflictedTxsPerIntraBlock
    .asInstanceOf[RocksDBKeyValueStorage[BlockHash, AVector[ConflictedTxsPerBlock]]]

  conflictedTxsPerIntraBlock.iterateE { case (_, conflicts) =>
    conflicts.foreachE { conflictedTxs =>
      for {
        block <- blockFlow.getBlock(conflictedTxs.interBlock)
        _ <- block.nonCoinbase.foreachWithIndexE { case (tx, txIndex) =>
          if (conflictedTxs.txs.contains(tx.id)) {
            updateIndexForConflictedTx(tx, txIndex, block)
          } else {
            Right(())
          }
        }
      } yield ()
    }
  } match {
    case Right(_)    => ()
    case Left(error) => logger.error(s"Failed to add index for conflicted txs due to $error")
  }

  storages.closeUnsafe()
}
