package org.red.hermes

import com.twitter.util.Await
import com.typesafe.scalalogging.LazyLogging
import org.red.hermes.Implicits._
import org.red.hermes.controllers._
import org.red.iris.finagle.clients.UserClient
import org.red.hermes.finagle.TeamspeakServer
import org.red.iris.finagle.servers.{TeamspeakServer => TTeamspeakServer}

import scala.concurrent.ExecutionContext.Implicits.global


object ApplicationMain extends App with LazyLogging {

  lazy val userClient = new UserClient(config)
  lazy val teamspeakController = new TeamspeakController(hermesConfig)
  lazy val scheduleController = new ScheduleController(hermesConfig, teamspeakController, userClient)
  val discordController = new DiscordController(hermesConfig)
  val teamspeakServer = new TTeamspeakServer(config).build(new TeamspeakServer(teamspeakController))

  scheduleController.teamspeakDaemon
  Await.result(teamspeakServer)
}
