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
import org.alephium.util.{Duration, Env, Files, TimeStamp}

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

  private val toTimeStamp   = TimeStamp.now()
  private val fromTimeStamp = toTimeStamp.minusUnsafe(Duration.ofDaysUnsafe(5))

  blockFlow.getHeightedBlocks(fromTimeStamp, toTimeStamp) match {
    case Right(allBlocks) =>
      allBlocks.foreach { case (chainIndex, blocksWithHeight) =>
        var gapCountLargeThan8 = 0
        val heightGaps         = mutable.HashMap.empty[Int, Int]
        blocksWithHeight.foreach { case (block, _) =>
          val commonIntraGroupDeps =
            blockFlow.calCommonIntraGroupDepsUnsafe(block.blockDeps, chainIndex.from)
          val (_, _, oldestTs) =
            blockFlow.getDiffAndTimeSpanUnsafe(commonIntraGroupDeps)(config.consensus.rhone)
          val chainDep  = block.blockDeps.getOutDep(chainIndex.to)
          val heightGap = blockFlow.calHeightDiffUnsafe(chainDep, oldestTs)
          heightGaps.get(heightGap) match {
            case Some(count) => heightGaps.update(heightGap, count + 1)
            case None        => heightGaps.addOne(heightGap -> 1)
          }
          if (heightGap > 8) gapCountLargeThan8 += 1
        }
        val ratio = s"$gapCountLargeThan8/${blocksWithHeight.length}".padTo(10, ' ')
        val gaps =
          heightGaps.view.toSeq
            .sortBy(_._1)
            .map(v => s"${v._1}:${v._2}".padTo(6, ' '))
            .mkString(" ")
        print(s"$chainIndex -> $ratio -> $gaps\n")
      }
    case Left(error) => throw error
  }

  storages.close() match {
    case Left(error) => throw error
    case Right(_)    =>
  }
}
