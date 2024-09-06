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

import scala.collection.mutable

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, Configs}
import org.alephium.io.RocksDBSource.ProdSettings
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{Env, Files}

// scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.IterableOps", "org.wartremover.warts.OptionPartial"))
object HeightGapStatistic extends App {
  final class BlockState(var all: Int, var uncles: Int) {
    def increase(isUncleBlock: Boolean): Unit = {
      all += 1
      if (isUncleBlock) uncles += 1
    }
    def uncleRate: Double = uncles.toDouble / all.toDouble
  }

  // private val rootPath       = Files.homeDir.resolve(".alephium-testnet")
  private val rootPath       = Files.homeDir.resolve(".alephium")
  private val typesafeConfig = Configs.parseConfigAndValidate(Env.Prod, rootPath, overwrite = true)
  private val config         = AlephiumConfig.load(typesafeConfig, "alephium")
  private val dbPath         = rootPath.resolve(config.network.networkId.nodeFolder)
  private val storages =
    Storages.createUnsafe(dbPath, "db", ProdSettings.writeOptions)(config.broker, config.node)
  private val blockFlow = BlockFlow.fromStorageUnsafe(config, storages)

  private val heightGap   = 40000
  private var allBlocks   = 0
  private var uncleBlocks = 0

  private val miners = mutable.Map.empty[LockupScript, BlockState]

  config.broker.chainIndexes.foreach { chainIndex =>
    blockFlow.getMaxHeightByWeight(chainIndex) match {
      case Right(maxHeight) =>
        val fromHeight = maxHeight - heightGap
        print(s"$chainIndex, max height: $maxHeight, from: $fromHeight\n")
        val chain = blockFlow.getBlockChain(chainIndex)
        (fromHeight to maxHeight).foreach { height =>
          val hashes = chain.getHashesUnsafe(height)
          allBlocks += hashes.length
          uncleBlocks += hashes.length - 1
          hashes.foreachWithIndex { case (blockHash, index) =>
            val isUncleBlock = index != 0
            val block        = chain.getBlockUnsafe(blockHash)
            miners.get(block.minerLockupScript) match {
              case Some(state) => state.increase(isUncleBlock)
              case None =>
                val state = new BlockState(0, 0)
                state.increase(isUncleBlock)
                miners(block.minerLockupScript) = state
            }
          }
        }
        print(s"$chainIndex, all blocks: $allBlocks, uncle blocks: $uncleBlocks\n")
      case Left(error) =>
        print(s"failed to get max height for $chainIndex, error: $error\n")
    }
  }

  print(
    s"========== all blocks: $allBlocks, uncle blocks: $uncleBlocks, uncle rate: ${uncleBlocks.toDouble / allBlocks.toDouble}\n"
  )

  miners.foreach { case (lockupScript, state) =>
    val address = Address.from(lockupScript)
    print(
      s"${address.toBase58}, all blocks: ${state.all}, uncle blocks: ${state.uncles}, uncle rate: ${state.uncleRate}\n"
    )
  }

  storages.close() match {
    case Left(error) => throw error
    case Right(_)    =>
  }
}
