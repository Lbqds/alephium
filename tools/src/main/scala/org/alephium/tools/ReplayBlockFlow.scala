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

import java.nio.file.Path

import scala.collection.mutable.PriorityQueue

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.Node
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.setting.Platform
import org.alephium.flow.validation.{BlockValidation, BlockValidationResult}
import org.alephium.io.IOResult
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, TimeStamp}

class ReplayBlockFlow(
    sourceBlockFlow: BlockFlow,
    targetBlockFlow: BlockFlow
) extends StrictLogging {
  private val startLoadingHeight = 1
  private val brokerConfig       = sourceBlockFlow.brokerConfig
  private val chainIndexes       = brokerConfig.chainIndexes
  private val validator          = BlockValidation.build(targetBlockFlow)
  private val loadedHeights      = chainIndexes.map(_ => startLoadingHeight).toArray
  private val pendingBlocks =
    PriorityQueue.empty(Ordering.by[(Block, Int), TimeStamp](t => t._1.timestamp).reverse)

  def start(): BlockValidationResult[Unit] = {
    for {
      maxHeights <- from(chainIndexes.mapE(chainIndex => sourceBlockFlow.getMaxHeight(chainIndex)))
      _          <- from(chainIndexes.foreachE(loadBlocksAt(_, startLoadingHeight)))
      _          <- replay(maxHeights)
    } yield ()
  }

  private def replay(maxHeights: AVector[Int]): BlockValidationResult[Unit] = {
    var count: Int                          = 0
    var result: BlockValidationResult[Unit] = Right(())

    while (pendingBlocks.nonEmpty && result.isRight) {
      val (block, blockHeight) = pendingBlocks.dequeue()
      val chainIndex           = block.chainIndex

      result = for {
        sideEffect <- validator.validate(block, targetBlockFlow)
        _          <- from(targetBlockFlow.add(block, sideEffect))
        _          <- loadMoreBlocks(chainIndex, maxHeights, blockHeight)
      } yield ()

      count += 1
      if (count % 1000 == 0) {
        logger.info(s"Replayed #$count blocks")
      }
    }

    result
  }

  private def loadMoreBlocks(
      chainIndex: ChainIndex,
      maxHeights: AVector[Int],
      blockHeight: Int
  ): BlockValidationResult[Unit] = {
    val chainIndexOneDim = chainIndex.flattenIndex(brokerConfig)
    val shouldLoadMore =
      loadedHeights(chainIndexOneDim) == blockHeight && blockHeight < maxHeights(
        chainIndexOneDim
      )

    if (shouldLoadMore) {
      from(
        loadBlocksAt(chainIndex, blockHeight + 1).map(_ =>
          loadedHeights(chainIndexOneDim) = blockHeight + 1
        )
      )
    } else {
      Right(())
    }
  }

  private def loadBlocksAt(chainIndex: ChainIndex, height: Int): IOResult[Unit] = {
    sourceBlockFlow.getHashes(chainIndex, height).map { hashes =>
      require(
        hashes.toArray.distinct.length == hashes.length,
        s"Hashes from ${chainIndex} at height ${height} are not unique: ${hashes}"
      )
      hashes.foreach(blockHash =>
        pendingBlocks.enqueue(sourceBlockFlow.getBlockUnsafe(blockHash) -> height)
      )
    }
  }

  private def from[T](result: IOResult[T]): BlockValidationResult[T] = {
    result.left.map(Left(_))
  }
}

object ReplayBlockFlow extends App with StrictLogging {
  if (args.length != 1) {
    logger.error("Usage: ReplayBlocks <replayDbPath>")
    sys.exit(1)
  }

  private val replayDbPath = Path.of(args(0))
  private val targetPath   = Platform.getRootPath()

  private val (targetBlockFlow, targetStorages, config) = Node.buildBlockFlowUnsafe(targetPath)
  private val (sourceBlockFlow, sourceStorages) = Node.buildBlockFlowUnsafe(replayDbPath, config)

  Runtime.getRuntime.addShutdownHook(new Thread(() => {
    sourceStorages.closeUnsafe()
    targetStorages.closeUnsafe()
  }))

  new ReplayBlockFlow(sourceBlockFlow, targetBlockFlow).start() match {
    case Right(_)    => logger.info("Replay finished")
    case Left(error) => logger.error(s"Replay failed: $error")
  }
}
