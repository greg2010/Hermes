package org.red.hermes.daemons.teamspeak

import com.github.theholywaffle.teamspeak3.TS3Query.FloodRate
import com.github.theholywaffle.teamspeak3.api.reconnect.ReconnectStrategy
import com.github.theholywaffle.teamspeak3.{TS3ApiAsync, TS3Config, TS3Query}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Cancelable
import monix.execution.Scheduler.{global => scheduler}

import scala.concurrent.ExecutionContext


class TeamspeakDaemon(config: Config)
                     (implicit ec: ExecutionContext) extends LazyLogging {

  private val ts3Conf = new TS3Config
  ts3Conf.setHost(config.getString("ts3.host"))
  ts3Conf.setFloodRate(FloodRate.DEFAULT)
  private val connectionHandler = new CustomConnectionHandler(config)
  ts3Conf.setConnectionHandler(connectionHandler)

  private val ts3Query = new TS3Query(ts3Conf)
  val client: TS3ApiAsync = ts3Query.getAsyncApi
  val daemon: Cancelable = connectionHandler.connect(ts3Query)

  def isConnected: Boolean = connectionHandler.isConnected
}
