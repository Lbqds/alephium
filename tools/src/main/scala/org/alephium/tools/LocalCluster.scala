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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Random, Success}

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.api.model.{Amount, Destination}
import org.alephium.app.{ApiConfig, BootUp, CpuSoloMiner, Server}
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.flow.setting.{AlephiumConfig, Configs}
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, Env, Files => AFiles, TimeStamp}

class LocalCluster(
    numberOfNodes: Int,
    singleNodeDiff: Int,
    rhoneHardforkTimestamp: TimeStamp
) extends StrictLogging {

  import LocalCluster._

  def bootServer(
      index: Int,
      publicPort: Int,
      restPort: Int,
      wsPort: Int,
      minerApiPort: Int,
      bootStrapNodes: Seq[Server]
  ): Server = {
    try {
      val rootPath: Path = AFiles.tmpDir.resolve(s".alephium-rhone-${Hash.random.shortHex}")
      if (!Files.exists(rootPath)) {
        rootPath.toFile.mkdir()
      }

      val baseConfig: Config = Configs.parseConfig(
        Env.Prod,
        rootPath,
        overwrite = true,
        predefined = getConfig(publicPort, restPort, wsPort, minerApiPort, rootPath, bootStrapNodes)
      )

      val flowSystem: ActorSystem = ActorSystem(s"flow-$index", baseConfig)

      implicit val config: AlephiumConfig             = AlephiumConfig.load(baseConfig)
      implicit val apiConfig: ApiConfig               = ApiConfig.load(baseConfig)
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

  def startMiner(servers: Seq[Server]): CpuSoloMiner = {
    assume(servers.nonEmpty)
    val apiAddresses = servers
      .map { server =>
        val hostAddr     = server.apiConfig.networkInterface.getHostAddress()
        val minerApiPort = server.config.network.minerApiPort
        s"$hostAddr:$minerApiPort"
      }
      .mkString(",")
    new CpuSoloMiner(servers(0).config, servers(0).flowSystem, Some(apiAddresses))
  }

  // scalastyle:off method.length
  private def getConfig(
      publicPort: Int,
      restPort: Int,
      wsPort: Int,
      minerApiPort: Int,
      rootPath: Path,
      bootStrapNodes: Seq[Server]
  ): Config = {
    val bootStrapConfig = bootStrapNodes
      .map { server =>
        val bindAddress = server.config.network.bindAddress
        s""""${bindAddress.getHostString}:${bindAddress.getPort}""""
      }
      .mkString("[", ",", "]")

    val numZerosAtLeastInHash =
      (Math.log(Math.pow(2, singleNodeDiff.toDouble) / numberOfNodes) / Math.log(2)).toInt

    logger.info(s"================= zeros: ${numZerosAtLeastInHash}")

    ConfigFactory.parseString(
      s"""
         |alephium.genesis.allocations = [
         |  {
         |    address = "${Genesis.address}",
         |    amount = 1000000000000000000000000,
         |    lock-duration = 0 seconds
         |  }
         |]
         |
         |alephium.consensus.num-zeros-at-least-in-hash = $numZerosAtLeastInHash
         |alephium.consensus.mainnet.uncle-dependency-gap-time = 16 seconds
         |alephium.consensus.mainnet.block-target-time = 64 seconds
         |alephium.consensus.rhone.uncle-dependency-gap-time = 8 seconds
         |alephium.consensus.rhone.block-target-time = 16 seconds
         |
         |alephium.discovery.bootstrap = $bootStrapConfig
         |alephium.discovery.max-clique-from-same-ip = ${numberOfNodes}
         |
         |alephium.node.event-log.enabled=true
         |alephium.node.event-log.index-by-tx-id = true
         |alephium.node.event-log.index-by-block-hash = true
         |
         |alephium.api.network-interface = "127.0.0.1"
         |alephium.api.api-key-enabled = false
         |
         |alephium.network.network-id = 3
         |alephium.network.rhone-hard-fork-timestamp = ${rhoneHardforkTimestamp.millis}
         |alephium.network.rest-port = $restPort
         |alephium.network.ws-port = $wsPort
         |alephium.network.miner-api-port = $minerApiPort
         |alephium.network.bind-address  = "127.0.0.1:$publicPort"
         |alephium.network.internal-address  = "127.0.0.1:$publicPort"
         |alephium.network.coordinator-address  = "127.0.0.1:$publicPort"
         |alephium.network.external-address  = "127.0.0.1:$publicPort"
         |alephium.network.max-clique-from-same-ip = ${numberOfNodes}
         |
         |alephium.mining.miner-addresses = [
         |"1FsroWmeJPBhcPiUr37pWXdojRBe6jdey9uukEXk1TheA",
         |"1CQvSXsmM5BMFKguKDPpNUfw1idiut8UifLtT8748JdHc",
         |"193maApeJWrz9GFwWCfa982ccLARVE9Y1WgKSJaUs7UAx",
         |"16fZKYPCZJv2TP3FArA9FLUQceTS9U8xVnSjxFG9MBKyY"
         |]
         |alephium.mining.api-interface = "0.0.0.0"
         |
         |alephium.wallet.secret-dir = "${rootPath}"
        """.stripMargin
    )
  }
  // scalastyle:on method.length
}

object LocalCluster extends StrictLogging {
  object Genesis {
    val address: String    = "14PqtYSSbwpUi2RJKUvv9yUwGafd6yHbEcke7ionuiE7w"
    val publicKey: String  = "03e75902fa24caff042b2b4c350e8f2ffeb3cb95f4263f0e109a2c2d7aa3dcae5c"
    val privateKey: String = "d24967efb7f1b558ad40a4d71593ceb5b3cecf46d17f0e68ef53def6b391c33d"
    val mnemonic: String =
      "toward outdoor daughter deny mansion bench water alien crumble " +
        "mother exchange screen salute antenna abuse key hair crisp debate " +
        "goose great market core screen"
  }

  object Wallet {
    val walletName = "local-cluster-test-wallet"
    val password   = "local-cluster-test-wallet-password"

    def restoreWallets(servers: Seq[Server]): Unit = {
      servers.foreach(restoreWallet)
    }

    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    def restoreWallet(server: Server): Unit = {
      val walletApp = server.walletApp.get
      val result = walletApp.walletService.restoreWallet(
        password,
        Mnemonic.from(Genesis.mnemonic).get,
        false,
        walletName,
        None
      )

      result match {
        case Right(name) =>
          logger.info(s"Restore wallet ${name} successfully")
        case Left(reason) =>
          logger.error(s"Restore wallet failed: $reason")
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    def transfer(server: Server, address: Address.Asset)(implicit
        executionContext: ExecutionContext
    ): Unit = {
      val walletApp = server.walletApp.get
      val transferResult = walletApp.walletService.transfer(
        walletName,
        AVector(
          Destination(
            address,
            attoAlphAmount = Amount(ALPH.oneAlph)
          )
        ),
        gas = None,
        gasPrice = None,
        utxosLimit = None
      )

      transferResult.onComplete {
        case Success(Right((txId, fromGroup, toGroup))) =>
          logger.info(s"Transfer ${fromGroup} -> ${toGroup} submitted, tx: $txId")
        case Success(Left(reason)) =>
          logger.error(s"Transfer failed: $reason")
        case Failure(exception) =>
          logger.error(s"Transfer error: $exception")
      }
    }
  }

  // scalastyle:off magic.number
  @SuppressWarnings(Array("org.wartremover.warts.ThreadSleep"))
  class TransferSimutation(servers: Seq[Server])(implicit
      groupConfig: GroupConfig,
      executionContext: ExecutionContext
  ) {
    @tailrec
    final def simulate(): Unit = {
      val index          = Random.nextInt(servers.length)
      val groupIndex     = GroupIndex.unsafe(Random.nextInt(groupConfig.groups))
      val (_, publicKey) = groupIndex.generateKey
      val address        = Address.Asset(LockupScript.p2pkh(publicKey))
      Wallet.transfer(servers(index), address)

      Thread.sleep(2000)
      simulate()
    }
  }
  // scalastyle:on magic.number

  final case class LocalClusterConfig(
      numberOfNodes: Int,
      singleNodeDiff: Int,
      rhoneHardForkActivationWindow: java.time.Duration,
      percentageOfNodesForMining: Double
  )

  object LocalClusterConfig {
    def from(config: Config): LocalClusterConfig = {
      LocalClusterConfig(
        config.getInt("number-of-nodes"),
        config.getInt("single-node-diff"),
        config.getDuration("rhone-hard-fork-activation-window"),
        config.getDouble("percentage-of-nodes-for-mining")
      )
    }
  }

  def loadLocalClusterConfig(): LocalClusterConfig = {
    val homeDir = sys.env.get("ALEPHIUM_LOCAL_CLUSTER_HOME") match {
      case Some(rawPath) =>
        Paths.get(rawPath)
      case None =>
        Paths.get(System.getProperty("user.home")).resolve(".alephium-local-cluster")
    }

    val configFile = homeDir.resolve("local-cluster.conf").toFile

    val defaultConfigStr =
      s"""number-of-nodes = 3
         |single-node-diff = 17
         |rhone-hard-fork-activation-window = 1h
         |percentage-of-nodes-for-mining = 1
      """.stripMargin
    val defaultConfig = ConfigFactory.parseString(defaultConfigStr)

    if (configFile.exists) {
      LocalClusterConfig.from(
        ConfigFactory
          .parseFile(configFile)
          .withFallback(defaultConfig)
      )
    } else {
      try {
        Files.createDirectories(homeDir)
        Files.write(
          configFile.toPath,
          defaultConfigStr.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE
        )
        LocalClusterConfig.from(defaultConfig)
      } catch {
        case e: Throwable =>
          throw new RuntimeException(s"Failed to write config file", e)
      }
    }
  }
}
