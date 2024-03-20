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

package org.alephium.app

import java.nio.file.Path

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.app.{ApiConfig, BootUp, CpuSoloMiner, Server}
import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}
import org.alephium.util.{Env, TimeStamp}

class LocalCluster(
  numberOfNodes: Int,
  ghostHardforkTimestamp: TimeStamp
) extends StrictLogging {

  def bootServer(
    index: Int,
    publicPort: Int,
    restPort: Int,
    wsPort: Int,
    minerApiPort: Int,
    bootStrapNodes: Seq[Server]
  ): Server = {
    try {
      val rootPath: Path = Platform.getRootPath(Env.Test)
      val baseConfig: Config = Configs.parseConfig(
        Env.Test,
        rootPath,
        overwrite = true,
        predefined = getConfig(publicPort, restPort, wsPort, minerApiPort, bootStrapNodes)
      )

      val flowSystem: ActorSystem = ActorSystem(s"flow-$index", baseConfig)

      implicit val config: AlephiumConfig = AlephiumConfig.load(baseConfig)
      implicit val apiConfig: ApiConfig = ApiConfig.load(baseConfig)
      implicit val executionContext: ExecutionContext = flowSystem.dispatcher

      val bootUp = new BootUp(rootPath, flowSystem)
      bootUp.init()
      bootUp.server
    } catch {
      case error: Throwable =>
        logger.error(s"Cannot initialize system: $error")
        sys.exit(1)
    }
  }

  def startMiner(server: Server): CpuSoloMiner = {
    val hostAddr = server.apiConfig.networkInterface.getHostAddress()
    val minerApiPort = server.config.network.minerApiPort
    val apiAddresses = s"$hostAddr:$minerApiPort"
    new CpuSoloMiner(server.config, server.flowSystem, Some(apiAddresses))
  }

  private def getConfig(
    publicPort: Int,
    restPort: Int,
    wsPort: Int,
    minerApiPort: Int,
    bootStrapNodes: Seq[Server]
  ): Config = {
    val bootStrapConfig = bootStrapNodes.map { server =>
      val ipAddr = server.config.network.bindAddress.getHostString
      val port = server.config.network.bindAddress.getPort
      s""""$ipAddr:$port""""
    }.mkString("[", ",", "]")
    ConfigFactory.parseString(
      s"""
        |alephium.consensus.num-zeros-at-least-in-hash = 12
        |alephium.consensus.uncle-dependency-gap-time = 0 seconds
        |alephium.consensus.mainnet.block-target-time = 64 seconds
        |alephium.consensus.ghost.block-target-time = 16 seconds
        |
        |alephium.discovery.bootstrap = $bootStrapConfig
        |alephium.discovery.max-clique-from-same-ip = ${this.numberOfNodes}
        |
        |alephium.node.event-log.enabled=true
        |alephium.node.event-log.index-by-tx-id = true
        |alephium.node.event-log.index-by-block-hash = true
        |
        |alephium.api.network-interface = "127.0.0.1"
        |alephium.api.api-key-enabled = false
        |
        |alephium.network.network-id = 4
        |alephium.ghost-hard-fork-timestamp = ${this.ghostHardforkTimestamp.millis}
        |alephium.network.rest-port = $restPort
        |alephium.network.ws-port = $wsPort
        |alephium.network.miner-api-port = $minerApiPort
        |alephium.network.bind-address  = "127.0.0.1:$publicPort"
        |alephium.network.internal-address  = "127.0.0.1:$publicPort"
        |alephium.network.coordinator-address  = "127.0.0.1:$publicPort"
        |alephium.network.external-address  = "127.0.0.1:$publicPort"
        |alephium.network.max-clique-from-same-ip = ${this.numberOfNodes}
        |
        |alephium.mining.miner-addresses = [
        |"1FsroWmeJPBhcPiUr37pWXdojRBe6jdey9uukEXk1TheA",
        |"1CQvSXsmM5BMFKguKDPpNUfw1idiut8UifLtT8748JdHc",
        |"193maApeJWrz9GFwWCfa982ccLARVE9Y1WgKSJaUs7UAx",
        |"16fZKYPCZJv2TP3FArA9FLUQceTS9U8xVnSjxFG9MBKyY"
        |]
        |
        |alephium.mining.api-interface = "127.0.0.1"
        """.stripMargin
    )
  }
}
