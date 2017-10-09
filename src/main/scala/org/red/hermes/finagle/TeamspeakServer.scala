package org.red.hermes.finagle

import org.red.hermes.controllers.TeamspeakController
import com.twitter.util.{Future => TFuture}
import org.red.iris.User
import com.twitter.bijection.twitter_util.UtilBijections.twitter2ScalaFuture
import com.twitter.bijection.Conversion.asMethod

import scala.concurrent.ExecutionContext

class TeamspeakServer(teamspeakController: => TeamspeakController)
                     (implicit ex: ExecutionContext) extends TeamspeakService[TFuture] {
  override def registerUserOnTeamspeak(user: User, characterId: Long, userIp: String): TFuture[String] = {
    teamspeakController.registerUserOnTeamspeak(user, characterId, userIp).as[TFuture[String]]
  }

  override def getTeamspeakUniqueId(userId: Int): TFuture[String] = {
    teamspeakController.getUniqueIdByUserId(userId).as[TFuture[String]]
  }

  override def getUserIdByUniqueId(uniqueId: String): TFuture[Int] = {
    teamspeakController.getUserIdByUniqueId(uniqueId).as[TFuture[Int]]
  }

  override def syncTeamspeakUser(user: User): TFuture[Unit] = {
    teamspeakController.syncTeamspeakUser(user).as[TFuture[Unit]]
  }
}
