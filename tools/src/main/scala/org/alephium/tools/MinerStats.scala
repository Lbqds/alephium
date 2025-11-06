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

import java.nio.file.Paths

import scala.collection.mutable

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, Configs}
import org.alephium.io.RocksDBSource.ProdSettings
import org.alephium.protocol.model.{Address, Block}
import org.alephium.util.{Env, TimeStamp}

// scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.IterableOps", "org.wartremover.warts.OptionPartial"))
object MinerStats extends App {
  // private val rootPath       = Files.homeDir.resolve(".alephium-testnet")
  private val rootPath       = Paths.get("/mnt/shared-linux/.alephium")
  private val typesafeConfig = Configs.parseConfigAndValidate(Env.Prod, rootPath, overwrite = true)
  private val config         = AlephiumConfig.load(typesafeConfig, "alephium")
  private val dbPath         = rootPath.resolve(config.network.networkId.nodeFolder)
  private val storages =
    Storages.createUnsafe(dbPath, "db", ProdSettings.writeOptions)(config.broker, config.node)
  private val blockFlow = BlockFlow.fromStorageUnsafe(config, storages)

  private val fromTs = TimeStamp.unsafe(1762351200000L)
  private val toTs   = TimeStamp.unsafe(1762372800000L)

  final class MinerStat(val toGroup: Int, val blockCounts: Array[Int]) {
    def add(block: Block): Unit = {
      assert(block.chainIndex.to.value == toGroup)
      val index = block.chainIndex.from.value
      blockCounts(index) += 1
    }

    def stat(): String = {
      blockCounts.zipWithIndex
        .map { case (count, fromGroup) =>
          s"$fromGroup->$toGroup:$count"
        }
        .mkString("; ")
    }
  }
  object MinerStat {
    def apply(toGroup: Int): MinerStat = {
      new MinerStat(toGroup, Array.fill(config.broker.groups)(0))
    }
  }

  private val allBlocks = blockFlow.getHeightedBlocks(fromTs, toTs).toOption.get
  private val allMiners = mutable.HashMap.empty[Address, MinerStat]

  allBlocks.foreach { case (chainIndex, blocksPerChain) =>
    blocksPerChain.foreach { case (block, _) =>
      val address = Address.from(block.minerLockupScript)
      allMiners.get(address) match {
        case Some(stat) => stat.add(block)
        case None =>
          assert(address.groupIndex(config.broker) == chainIndex.to)
          val stat = MinerStat(chainIndex.to.value)
          stat.add(block)
          allMiners.put(address, stat)
      }
    }
  }

  allMiners.foreachEntry { case (address, stat) =>
    print(s"address: $address\n")
    print(s"${stat.stat()}\n")
  }

  storages.closeUnsafe()
}
