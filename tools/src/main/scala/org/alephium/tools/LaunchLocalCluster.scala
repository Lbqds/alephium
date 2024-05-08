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

import java.util.concurrent.TimeUnit

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging

import org.alephium.app.Server
import org.alephium.flow.network.InterCliqueManager
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.{Duration, TimeStamp}

// scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.ThreadSleep"))
object LaunchLocalCluster extends App with StrictLogging {
  import LocalCluster._

  val localClusterConfig: LocalClusterConfig = loadLocalClusterConfig()

  val percentageOfNodesForMining: Double = localClusterConfig.percentageOfNodesForMining
  if (percentageOfNodesForMining > 1 || percentageOfNodesForMining < 0) {
    logger.error("The value for percentage-of-nodes-for-mining should be in [0, 1]")
    System.exit(1)
  }

  val localCluster: LocalCluster = new LocalCluster(
    localClusterConfig.numberOfNodes,
    localClusterConfig.singleNodeDiff,
    TimeStamp.now() + Duration.from(localClusterConfig.rhoneHardForkActivationWindow).get
  )

  @SuppressWarnings(Array("org.wartremover.warts.GlobalExecutionContext"))
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  val bootstrapServer: Server =
    localCluster.bootServer(index = 0, 19973, 22973, 21973, 20973, Seq.empty)
  implicit val groupConfig: GroupConfig = bootstrapServer.config.broker

  val restOfServers: Seq[Server] = (1 until localClusterConfig.numberOfNodes).map { index =>
    val bootstrapNetworkConfig = bootstrapServer.config.network
    val publicPort             = bootstrapNetworkConfig.bindAddress.getPort + index
    val restPort               = bootstrapNetworkConfig.restPort + index
    val wsPort                 = bootstrapNetworkConfig.wsPort + index
    val minerApiPort           = bootstrapNetworkConfig.minerApiPort + index
    localCluster.bootServer(index, publicPort, restPort, wsPort, minerApiPort, Seq(bootstrapServer))
  }

  val servers: Seq[Server] = bootstrapServer +: restOfServers

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def checkSyncedAndStart(): Unit = {
    implicit val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)
    val futures = servers.map { server =>
      server.node.allHandlers.viewHandler.ref
        .ask(InterCliqueManager.IsSynced)
        .mapTo[InterCliqueManager.SyncedResult]
    }
    Future.sequence(futures).onComplete {
      case Success(results) if results.forall(_.isSynced) =>
        logger.info("All nodes synced now")
        val numberOfMiningNodes: Int = (servers.length * percentageOfNodesForMining).toInt
        if (numberOfMiningNodes == 0) {
          logger.warn("No mining nodes")
        } else {
          logger.info(s"Start miner with ${numberOfMiningNodes} mining nodes")
          localCluster.startMiner(servers.take(numberOfMiningNodes))
        }

        Wallet.restoreWallets(servers)
        new TransferSimutation(servers).simulate()
      case Failure(error) => throw error
      case _ =>
        Thread.sleep(1000)
        checkSyncedAndStart()
    }
  }

  checkSyncedAndStart()
}
// scalastyle:on magic.number
