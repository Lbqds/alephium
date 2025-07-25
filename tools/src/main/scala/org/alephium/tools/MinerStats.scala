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
import java.time.{Instant, ZonedDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, Configs}
import org.alephium.io.RocksDBSource.ProdSettings
import org.alephium.protocol.model.{Address, Block}
import org.alephium.util.{Duration, Env, TimeStamp}

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

  private val miners = Array(
    Address
      .fromBase58("1PZonix2UoaguUfbbBnWevk4vod1m9MeJXBnkr7aqN76")
      .getOrElse(throw new RuntimeException("invalid address")),
    Address
      .fromBase58("15AkQjovigzbQXGoHekLMb1prs1MTyRXFKSLw8VRy4quJ")
      .getOrElse(throw new RuntimeException("invalid address")),
    Address
      .fromBase58("1LzhJdLG1SMMCPqRpnUJRs5wgLqRYcWZcRdG8baLeFbT")
      .getOrElse(throw new RuntimeException("invalid address")),
    Address
      .fromBase58("1EbUwQWRfuXnEkvPTYxubp5cYsGo3foAMA9g9zvNQNSwW")
      .getOrElse(throw new RuntimeException("invalid address"))
  )

  stats(miners)

  storages.close() match {
    case Left(error) => throw error
    case Right(_)    =>
  }

  private def stats(miners: Array[Address]): Unit = {
    config.broker.chainIndexes.foreach { chainIndex =>
      val miner       = miners(chainIndex.to.value)
      val latestBlock = blockFlow.getBlockChain(chainIndex).getBestTipUnsafe()
      var fromBlock   = blockFlow.getBlockUnsafe(latestBlock)
      (0 until 20).foreach { _ =>
        fromBlock = stats(fromBlock, miner)
      }
    }
  }

  private def stats(fromBlock: Block, miner: Address): Block = {
    var currentBlock = fromBlock
    while (currentBlock.minerLockupScript != miner.lockupScript) {
      currentBlock = blockFlow.getBlockUnsafe(currentBlock.parentHash)
    }

    val toTimestamp              = currentBlock.timestamp
    var fromTimestamp: TimeStamp = toTimestamp
    currentBlock = blockFlow.getBlockUnsafe(currentBlock.parentHash)
    while (currentBlock.minerLockupScript == miner.lockupScript) {
      fromTimestamp = currentBlock.timestamp
      currentBlock = blockFlow.getBlockUnsafe(currentBlock.parentHash)
    }
    val parentBlock = blockFlow.getBlockUnsafe(currentBlock.parentHash)
    val chainIndex  = fromBlock.chainIndex
    val from = if (toTimestamp == fromTimestamp) {
      fromTimestamp
    } else {
      toTimestamp.minusUnsafe(Duration.ofSecondsUnsafe(8))
    }
    if (toTimestamp > fromTimestamp) {
      print(
        s"miner ${miner.toBase58} mining from ${toUtc(from)} to ${toUtc(toTimestamp)} on ${chainIndex.from.value -> chainIndex.to.value}\n"
      )
    }
    parentBlock
  }

  private def toUtc(ts: TimeStamp): String = {
    val utcTime: ZonedDateTime = Instant.ofEpochMilli(ts.millis).atZone(ZoneOffset.UTC)

    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    formatter.format(utcTime)
  }
}
