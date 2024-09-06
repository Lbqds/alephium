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

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, Configs}
import org.alephium.io.RocksDBSource.ProdSettings
import org.alephium.util.{Env, Files}

// scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.IterableOps", "org.wartremover.warts.OptionPartial"))
object HeightGapStatistic extends App {
  // private val rootPath       = Files.homeDir.resolve(".alephium-testnet")
  private val rootPath       = Files.homeDir.resolve(".alephium")
  private val typesafeConfig = Configs.parseConfigAndValidate(Env.Prod, rootPath, overwrite = true)
  private val config         = AlephiumConfig.load(typesafeConfig, "alephium")
  private val dbPath         = rootPath.resolve(config.network.networkId.nodeFolder)
  private val storages =
    Storages.createUnsafe(dbPath, "db", ProdSettings.writeOptions)(config.broker, config.node)
  private val blockFlow = BlockFlow.fromStorageUnsafe(config, storages)

  private val heightGap   = 37800
  private var allBlocks   = 0
  private var uncleBlocks = 0

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
        }
        print(s"$chainIndex, all blocks: $allBlocks, uncle blocks: $uncleBlocks\n")
      case Left(error) =>
        print(s"failed to get max height for $chainIndex, error: $error\n")
    }
  }

  print(
    s"========== all blocks: $allBlocks, uncle blocks: $uncleBlocks, uncle rate: ${uncleBlocks.toDouble / allBlocks.toDouble}\n"
  )

  storages.close() match {
    case Left(error) => throw error
    case Right(_)    =>
  }
}
