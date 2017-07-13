package org.red.hermes.jobs.teamspeak

import com.github.theholywaffle.teamspeak3.TS3ApiAsync
import com.github.theholywaffle.teamspeak3.api.event.{ClientJoinEvent, TS3EventAdapter}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import org.red.hermes.controllers.TeamspeakController

import scala.concurrent.{ExecutionContext, Promise}


class RegistrationJoinListener(client: TS3ApiAsync,
                               teamspeakController: TeamspeakController,
                               config: Config,
                               expectedNickname: String,
                               userIp: String,
                               p: Promise[String])
                              (implicit ex: ExecutionContext)
  extends TS3EventAdapter with LazyLogging {
  logger.debug(s"Instantiated new teamspeak event listener " +
    s"teamspeakEvent=join " +
    s"expectedNickname=$expectedNickname " +
    s"event=teamspeak.listener.create")

  override def onClientJoin(e: ClientJoinEvent): Unit = {
    logger.debug(s"Teamspeak join event fired " +
      s"teamspeakClientId=${e.getClientId} " +
      s"teamspeakNickname=${e.getClientNickname} " +
      s"event=teamspeak.listener.fire")
    if (e.getClientNickname == expectedNickname) {
      teamspeakController.getConnectedClientByUniqueId(e.getUniqueClientIdentifier)
        .foreach {
          case c if c.getIp == userIp =>
            logger.info(s"Successfully obtained teamspeak uniqueId for user " +
              s"expectedNickname=$expectedNickname " +
              s"event=teamspeak.listener.success")
            p.success(e.getUniqueClientIdentifier)
            client.removeTS3Listeners(this)
          case c =>
            logger.warn(s"User IP ${c.getIp} doesn't match $userIp event=teamspeak.register.warn")
        }
    }
  }
}
