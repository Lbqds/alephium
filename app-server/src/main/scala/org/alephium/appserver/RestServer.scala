package org.alephium.appserver

import scala.collection.immutable.ArraySeq
import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.scalalogging.StrictLogging
import sttp.tapir.docs.openapi.RichOpenAPIEndpoints
import sttp.tapir.openapi.OpenAPI
import sttp.tapir.openapi.circe.yaml.RichOpenAPI
import sttp.tapir.server.akkahttp.RichAkkaHttpEndpoint

import org.alephium.appserver.ApiModel._
import org.alephium.flow.client.Miner
import org.alephium.flow.core.{BlockFlow, TxHandler}
import org.alephium.flow.platform.{Mode, PlatformConfig}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, Duration, Service}

// scalastyle:off method.length
class RestServer(mode: Mode, port: Int, miner: ActorRefT[Miner.Command])(
    implicit config: PlatformConfig,
    actorSystem: ActorSystem,
    protected val executionContext: ExecutionContext)
    extends Endpoints
    with Service
    with StrictLogging {

  private val blockFlow: BlockFlow                    = mode.node.blockFlow
  private val txHandler: ActorRefT[TxHandler.Command] = mode.node.allHandlers.txHandler
  private val terminationHardDeadline                 = Duration.ofSecondsUnsafe(10).asScala

  implicit val rpcConfig: RPCConfig     = RPCConfig.load(config.aleph)
  implicit val groupConfig: GroupConfig = config
  implicit val askTimeout: Timeout      = Timeout(rpcConfig.askTimeout.asScala)

  private val docs: OpenAPI = List(
    getBlockflow,
    getBlock,
    getBalance,
    getGroup,
    getHashesAtHeight,
    getChainInfo,
    createTransaction,
    sendTransaction,
    minerAction
  ).toOpenAPI("Alephium BlockFlow API", "1.0")

  private val getBlockflowRoute = getBlockflow.toRoute { timeInterval =>
    Future.successful(
      ServerUtils.getBlockflow(blockFlow, FetchRequest(timeInterval.from, timeInterval.to)))
  }

  private val getBlockRoute = getBlock.toRoute { hash =>
    Future.successful(ServerUtils.getBlock(blockFlow, GetBlock(hash)))
  }

  private val getBalanceRoute = getBalance.toRoute { address =>
    Future.successful(ServerUtils.getBalance(blockFlow, GetBalance(address)))
  }

  private val getGroupRoute = getGroup.toRoute { address =>
    Future.successful(ServerUtils.getGroup(blockFlow, GetGroup(address)))
  }

  private val getHashesAtHeightRoute = getHashesAtHeight.toRoute {
    case (from, to, height) =>
      Future.successful(
        ServerUtils.getHashesAtHeight(blockFlow,
                                      ChainIndex(from, to),
                                      GetHashesAtHeight(from.value, to.value, height)))
  }

  private val getChainInfoRoute = getChainInfo.toRoute {
    case (from, to) =>
      Future.successful(ServerUtils.getChainInfo(blockFlow, ChainIndex(from, to)))
  }

  private val createTransactionRoute = createTransaction.toRoute {
    case (fromKey, toAddress, value) =>
      Future.successful(
        ServerUtils.createTransaction(blockFlow, CreateTransaction(fromKey, toAddress, value)))
  }

  private val sendTransactionRoute = sendTransaction.toRoute { transaction =>
    ServerUtils.sendTransaction(txHandler, transaction)
  }

  private val minerActionRoute = minerAction.toRoute {
    case MinerAction.StartMining => ServerUtils.execute(miner ! Miner.Start)
    case MinerAction.StopMining  => ServerUtils.execute(miner ! Miner.Stop)
  }

  private val getOpenapiRoute = getOpenapi.toRoute(_ => Future.successful(Right(docs.toYaml)))

  val route: Route =
    cors()(
      getBlockflowRoute ~
        getBlockRoute ~
        getBalanceRoute ~
        getGroupRoute ~
        getHashesAtHeightRoute ~
        getChainInfoRoute ~
        createTransactionRoute ~
        sendTransactionRoute ~
        minerActionRoute ~
        getOpenapiRoute
    )

  private val httpBindingPromise: Promise[Http.ServerBinding] = Promise()

  override def subServices: ArraySeq[Service] = ArraySeq(mode)

  protected def startSelfOnce(): Future[Unit] = {
    for {
      httpBinding <- Http()
        .bindAndHandle(route, rpcConfig.networkInterface.getHostAddress, port)
    } yield {
      logger.info(s"Listening http request on $httpBinding")
      httpBindingPromise.success(httpBinding)
    }
  }

  protected def stopSelfOnce(): Future[Unit] =
    for {
      httpBinding <- httpBindingPromise.future
      httpStop    <- httpBinding.terminate(hardDeadline = terminationHardDeadline)
    } yield {
      logger.info(s"http unbound with message $httpStop.")
      ()
    }
}

object RestServer {
  def apply(mode: Mode, miner: ActorRefT[Miner.Command])(
      implicit system: ActorSystem,
      config: PlatformConfig,
      executionContext: ExecutionContext): RestServer = {
    (for {
      restPort <- mode.config.restPort
    } yield {
      new RestServer(mode, restPort, miner)
    }) match {
      case Some(server) => server
      case None         => throw new RuntimeException("rpc and ws ports are required")
    }
  }
}