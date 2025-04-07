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

import java.util.concurrent.atomic.AtomicInteger

import org.alephium.flow.client.Node
import org.alephium.flow.setting.Platform
import org.alephium.flow.validation._
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{BlockEnv, LogConfig}

object ReplayTxScript extends App {
  private val rootPath                              = Platform.getRootPath()
  private val (blockFlow, storages)                 = Node.buildBlockFlowUnsafe(rootPath)
  implicit private val brokerConfig: BrokerConfig   = blockFlow.brokerConfig
  implicit private val networkConfig: NetworkConfig = blockFlow.networkConfig
  implicit private val logConfig: LogConfig         = blockFlow.logConfig
  private val txValidation: TxValidation            = TxValidation.build

  Runtime.getRuntime.addShutdownHook(new Thread(() => storages.closeUnsafe()))

  IOUtils.tryExecute(replayUnsafe()) match {
    case Right(threads) =>
      threads.foreach(_.start())
      threads.foreach(_.join())
      brokerConfig.cliqueGroups.foreach(persistStatistic)
      print(s"Replay completed\n")
    case Left(error) =>
      print(s"IO error occurred when replaying: $error\n")
      sys.exit(1)
  }

  private def tryPersistStatistic(groupIndex: GroupIndex): Unit = {
    import org.alephium.protocol.vm.Statistic
    val transferInfos = Statistic.transferInfos(groupIndex.value)
    if (transferInfos.length >= 100) {
      persistStatistic(groupIndex)
    }
  }

  private def persistStatistic(groupIndex: GroupIndex): Unit = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Paths, StandardOpenOption}
    import org.alephium.protocol.vm.Statistic
    val transferInfos = Statistic.transferInfos(groupIndex.value)
    val content0      = transferInfos.map(_.toString0).mkString("", "\n", "\n")
    val path0         = Paths.get(s"/alephium-home/.alephium/statistic0-${groupIndex.value}")
    Files.write(
      path0,
      content0.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.APPEND,
      StandardOpenOption.CREATE
    )

    val content1 = transferInfos.map(_.toString1).mkString("", "\n", "\n")
    val path1    = Paths.get(s"/alephium-home/.alephium/statistic1-${groupIndex.value}")
    Files.write(
      path1,
      content1.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.APPEND,
      StandardOpenOption.CREATE
    )
    transferInfos.clear()
  }

  private def replayUnsafe() = {
    val fromHeight    = ALPH.GenesisHeight + 1
    var totalCount    = 0
    val executedCount = new AtomicInteger(0)
    (0 until brokerConfig.groups).map { index =>
      val groupIndex = GroupIndex.unsafe(index)
      val chainIndex = ChainIndex(groupIndex, groupIndex)
      val maxHeight  = blockFlow.getBlockChain(chainIndex).maxHeightByWeightUnsafe
      totalCount += maxHeight

      new Thread(() =>
        (fromHeight to maxHeight).reverse.foreach { height =>
          replayBlock(chainIndex, height)
          tryPersistStatistic(groupIndex)
          val count = executedCount.addAndGet(1)
          if (count % 10000 == 0) {
            val progress = (count.toDouble / totalCount.toDouble) * 100
            print(s"Executed #$count blocks, progress: ${f"$progress%.0f%%"}\n")
          }
        }
      )
    }
  }

  private def replayBlock(chainIndex: ChainIndex, height: Int): Unit = {
    val blockchain = blockFlow.getBlockChain(chainIndex)
    val result = for {
      blockHash <- blockchain.getHashes(height).map(_.head)
      block     <- blockchain.getBlock(blockHash)
      _ <-
        if (block.nonCoinbase.exists(_.unsigned.scriptOpt.isDefined)) {
          replayAndCheck(block)
        } else {
          Right(())
        }
    } yield ()
    result.left.foreach { error =>
      print(
        s"IO error occurred when replaying block: $error, chain index: $chainIndex, height: $height\n"
      )
    }
  }

  private def replayAndCheck(block: Block): IOResult[Unit] = {
    for {
      expected  <- storages.worldStateStorage.get(block.hash).map(_.contractStateHash)
      stateHash <- replayBlock(block)
    } yield {
      if (stateHash != expected) {
        print(
          s"State hash mismatch: expected ${expected.toHexString}, got ${stateHash.toHexString}, block hash: ${block.hash.toHexString}\n"
        )
        sys.exit(1)
      }
    }
  }

  private def replayBlock(block: Block): IOResult[Hash] = {
    val hardFork = networkConfig.getHardFork(block.timestamp)
    val executionOrder =
      Block.getNonCoinbaseExecutionOrder(block.parentHash, block.nonCoinbase, hardFork)
    val blockEnv              = BlockEnv.from(block.chainIndex, block.header)
    val sequentialTxSupported = ALPH.isSequentialTxSupported(block.chainIndex, hardFork)
    blockFlow
      .getMutableGroupView(block.chainIndex, block.blockDeps, hardFork, Some(block.hash))
      .flatMap { groupView =>
        executionOrder.foreach { index =>
          val tx = block.transactions(index)
          val result = tx.unsigned.scriptOpt match {
            case Some(_) =>
              txValidation.checkBlockTx(block.chainIndex, tx, groupView, blockEnv, None, index)
            case None => Right(())
          }
          result match {
            case Right(_)                                                       => ()
            case Left(Right(TxScriptExeFailed(_))) if tx.contractInputs.isEmpty => ()
            case Left(error) =>
              print(s"Failed to validate tx ${tx.id.toHexString} due to $error\n")
              sys.exit(1)
          }
          if (sequentialTxSupported) blockEnv.addOutputRefFromTx(tx.unsigned)
        }
        groupView.worldState.contractState.getRootHash()
      }
  }
}
