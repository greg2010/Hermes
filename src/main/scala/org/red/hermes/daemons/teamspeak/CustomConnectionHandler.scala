package org.red.hermes.daemons.teamspeak

import java.util.concurrent.atomic.AtomicBoolean

import com.gilt.gfc.concurrent.ScalaFutures.FutureOps
import com.github.theholywaffle.teamspeak3.TS3Query
import com.github.theholywaffle.teamspeak3.api.reconnect.{ConnectionHandler, DisconnectingConnectionHandler}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Cancelable
import monix.execution.Scheduler.{global => scheduler}
import org.red.hermes.util.FutureConverters

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}

private[this] class CustomConnectionHandler(config: Config)(implicit ec: ExecutionContext)
  extends ConnectionHandler with LazyLogging {
  private val rsg: Stream[Char] = Random.alphanumeric

  def generatePostfix: String = rsg.take(4).mkString

  private val connected: AtomicBoolean = new AtomicBoolean(false)

  def isConnected: Boolean = connected.get()

  def connect(ts3Query: TS3Query): Cancelable = {
    scheduler.scheduleWithFixedDelay(0.seconds, 10.seconds) {
      if (!isConnected) {
        Future(ts3Query.connect()).withTimeout(8.seconds)
          .onComplete {
            case Success(r) =>
              logger.info(s"Connected to teamspeak teamspeakHost=${config.getString("ts3.host")} " +
                s"event=teamspeak.connect.success")
            case Failure(ex) =>
              logger.error("Failed to connect to teamspeak, retrying event=teamspeak.connect.failure")
          }
      } else {
        logger.debug("Have connection to the teamspeak server event=teamspeak.connect.success")
      }
    }
  }

  override def onDisconnect(ts3Query: TS3Query): Unit = {
    connected.set(false)
    logger.warn("Disconnected from teamspeak event=teamspeak.disconnect")
  }

  override def onConnect(ts3Query: TS3Query): Unit = {
    val client = ts3Query.getAsyncApi
    logger.info("Connected to teamspeak event=teamspeak.connect")
    connected.set(true)
    (for {
      _ <- FutureConverters.commandToScalaFuture(
        client.login(config.getString("ts3.serverQueryLogin"), config.getString("ts3.serverQueryPassword"))
      )
      _ <- FutureConverters.commandToScalaFuture(
        client.selectVirtualServerById(config.getInt("ts3.virtualServerId"))
      )
      _ <- FutureConverters.commandToScalaFuture(
        client.setNickname(config.getString("ts3.botName") + "_" + generatePostfix)
      )
      _ <- FutureConverters.commandToScalaFuture(
        client.registerAllEvents()
      )
      res <- FutureConverters.commandToScalaFuture(client.getServerInfo)
    } yield res).onComplete {
      case Success(r) =>
        logger.info(s"Successfully connected to TS3 server " +
          s"teamspeakServerName=${r.getName} " +
          s"teamspeakVersion=${r.getVersion} " +
          s"ip=${r.getIp} " +
          s"ping=${r.getPing} " +
          s"event=teamspeak.connect.success")
      case Failure(ex) =>
        logger.error("Failed to get TS3 server info event=teamspeak.connect.failure", ex)
    }
  }
}
