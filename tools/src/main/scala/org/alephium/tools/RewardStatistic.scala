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
import org.alephium.protocol.ALPH
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{Duration, Env, Files, TimeStamp, U256}

// scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.IterableOps", "org.wartremover.warts.OptionPartial"))
object RewardStatistic extends App {
  final class BlockState(var all: Int, var uncles: Int) {
    def increase(isUncleBlock: Boolean): Unit = {
      all += 1
      if (isUncleBlock) uncles += 1
    }
    def uncleRate: Double                  = uncles.toDouble / all.toDouble
    def blockRate(totalBlock: Int): Double = all.toDouble / totalBlock.toDouble
  }

  // private val rootPath       = Files.homeDir.resolve(".alephium-testnet")
  private val rootPath       = Files.homeDir.resolve(".alephium")
  private val typesafeConfig = Configs.parseConfigAndValidate(Env.Prod, rootPath, overwrite = true)
  private val config         = AlephiumConfig.load(typesafeConfig, "alephium")
  private val dbPath         = rootPath.resolve(config.network.networkId.nodeFolder)
  private val storages =
    Storages.createUnsafe(dbPath, "db", ProdSettings.writeOptions)(config.broker, config.node)
  private val blockFlow = BlockFlow.fromStorageUnsafe(config, storages)

  private var allRewards   = U256.Zero
  private var uncleRewards = U256.Zero

  private val now    = TimeStamp.now()
  private val fromTs = now.minusUnsafe(Duration.ofHoursUnsafe(24L))

  private val fromHeights = mutable.Map.empty[ChainIndex, Int]

  blockFlow.getHeightedBlocks(fromTs, fromTs.plusMinutesUnsafe(5L)) match {
    case Right(blocks) =>
      blocks.foreach { case (chainIndex, bs) =>
        fromHeights(chainIndex) = bs.head._2
      }
    case Left(error) => print(s"failed to get heighted blocks, error: ${error}")
  }

  config.broker.chainIndexes.foreach { chainIndex =>
    blockFlow.getMaxHeightByWeight(chainIndex) match {
      case Right(maxHeight) =>
        val fromHeight = fromHeights(chainIndex)
        print(s"$chainIndex, max height: $maxHeight, from: $fromHeight\n")
        val chain = blockFlow.getBlockChain(chainIndex)
        (fromHeight to maxHeight).foreach { height =>
          val hash = chain.getHashesUnsafe(height).head
          val block = chain.getBlockUnsafe(hash)
          block.coinbase.unsigned.fixedOutputs.zipWithIndex.foreach { case (output, index) =>
            allRewards = allRewards.addUnsafe(output.amount)
            if (index > 0) uncleRewards = uncleRewards.addUnsafe(output.amount)
          }
        }
      case Left(error) =>
        print(s"failed to get max height for $chainIndex, error: $error\n")
    }
  }

  print(
    s"========== all rewards: ${ALPH.prettifyAmount(allRewards)}, uncle rewards: ${ALPH.prettifyAmount(uncleRewards)}\n"
  )

  storages.close() match {
    case Left(error) => throw error
    case Right(_)    =>
  }
}
