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

import scala.concurrent.ExecutionContext

import com.typesafe.scalalogging.StrictLogging

import org.alephium.app.{LocalCluster, Server}
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.TimeStamp

// scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.ThreadSleep"))
object LaunchLocalCluster extends App with StrictLogging {
  val numberOfNodes: Int  = 3
  val singleNodeDiff: Int = 12
  val numZerosAtLeastInHash: Int =
    (Math.log(Math.pow(2, singleNodeDiff.toDouble) / numberOfNodes) / Math.log(2)).toInt

  val ghostHardforkTimestamp: TimeStamp = TimeStamp.now().plusMinutesUnsafe(10)

  val localCluster: LocalCluster =
    new LocalCluster(numberOfNodes, numZerosAtLeastInHash, ghostHardforkTimestamp)

  @SuppressWarnings(Array("org.wartremover.warts.GlobalExecutionContext"))
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  val bootstrapServer: Server =
    localCluster.bootServer(index = 0, 19973, 22973, 21973, 20973, Seq.empty)
  implicit val groupConfig: GroupConfig = bootstrapServer.config.broker

  localCluster.startMiner(bootstrapServer)

  val restOfServers: Seq[Server] = (1 until numberOfNodes).map { index =>
    val bootstrapNetworkConfig = bootstrapServer.config.network
    val publicPort             = bootstrapNetworkConfig.bindAddress.getPort + index
    val restPort               = bootstrapNetworkConfig.restPort + index
    val wsPort                 = bootstrapNetworkConfig.wsPort + index
    val minerApiPort           = bootstrapNetworkConfig.minerApiPort + index
    localCluster.bootServer(index, publicPort, restPort, wsPort, minerApiPort, Seq(bootstrapServer))
  }

  val servers: Seq[Server] = bootstrapServer +: restOfServers

  LocalCluster.Wallet.restoreWallets(servers)

  new LocalCluster.TransferSimutation(servers).simulate()
}
// scalastyle:on magic.number
