package org.red.hermes.controllers

import java.nio.channels.NotYetConnectedException

import com.gilt.gfc.concurrent.ScalaFutures.{retryWithExponentialDelay}
import com.github.theholywaffle.teamspeak3.api.CommandFuture
import com.github.theholywaffle.teamspeak3.api.event._
import com.github.theholywaffle.teamspeak3.api.wrapper.{Client, DatabaseClient, DatabaseClientInfo}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import org.red.hermes.daemons.teamspeak.TeamspeakDaemon
import org.red.hermes.jobs.teamspeak.RegistrationJoinListener
import org.red.hermes.util.FutureConverters._
import org.red.hermes.util.TeamspeakGroupMapEntry
import org.red.db.models.Coalition
import org.red.hermes.exceptions.ExceptionHandlers
import org.red.iris.{ConflictingEntityException, PermissionBit, ResourceNotFoundException, User}
import org.red.iris.util.YamlParser
import slick.jdbc.JdbcBackend
import slick.jdbc.PostgresProfile.api._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.io.Source
import scala.util.{Failure, Success}


class TeamspeakController(config: Config)
                         (implicit dbAgent: JdbcBackend.Database, ec: ExecutionContext)
  extends LazyLogging {
  private val daemon = new TeamspeakDaemon(config)
  private val client = daemon.client

  private case class TeamspeakGroupMap(teamspeak_group_map: Seq[TeamspeakGroupMapEntry])

  val teamspeakPermissionMap: Seq[TeamspeakGroupMapEntry] =
    YamlParser.parseResource[TeamspeakGroupMap](Source.fromResource("teamspeak_group_map.yml")).teamspeak_group_map

  def obtainExpectedTeamspeakName(user: User): String = {
    (user.eveUserData.allianceTicker match {
      case Some(n) => s"$n | "
      case None => ""
    }) + s"${user.eveUserData.corporationTicker} | ${user.eveUserData.characterName}"
  }

  def registerUserOnTeamspeak(user: User, userIp: String): Future[Unit] = {

    def registerJoinedUser(expectedNickname: String): Future[Unit] = {
      val f = safeTeamspeakQuery(() => client.getClients)()
        .map { r =>
          r.asScala.find(_.getNickname == expectedNickname) match {
            case Some(u) if u.getIp == userIp => u.getUniqueIdentifier
            case _ => throw ResourceNotFoundException(s"No user with name $expectedNickname is on teamspeak")
          }
        }
      f.onComplete {
        case Success(res) =>
          logger.info(s"Successfully obtained joined user uniqueId " +
            s"userId=${user.userId} " +
            s"expectedNickname=$expectedNickname " +
            s"event=teamspeak.joined.get.success")
        case Failure(ex: ResourceNotFoundException) =>
          logger.warn("Failed to obtain joined user uniqueId, user isn't joined " +
            s"userId=${user.userId} " +
            s"expectedNickname=$expectedNickname " +
            s"event=teamspeak.joined.get.failure")
        case Failure(ex) =>
          logger.error("Failed to obtain joined user uniqueId " +
            s"userId=${user.userId} " +
            s"expectedNickname=$expectedNickname " +
            s"event=teamspeak.joined.get.failure")
      }
      f.flatMap(uniqueId => registerTeamspeakUser(user, uniqueId))
    }

    def registerUsingEventListener(expectedNickname: String): Future[Unit] = {
      val p = Promise[String]()
      safeAddListener(new RegistrationJoinListener(client, this, config, userIp, expectedNickname, p))(10.minutes)
        .onComplete {
          case Success(_) =>
            logger.info(s"Successfully registered listener for " +
              s"userId=${user.userId} " +
              s"expectedNickname=$expectedNickname " +
              s"event=teamspeak.listener.create.success")
          case Failure(ex) =>
            logger.error("Failed to register listener for " +
              s"userId=${user.userId} " +
              s"expectedNickname=$expectedNickname " +
              s"event=teamspeak.listener.create.failure")
        }
      p.future.flatMap(uniqueId => registerTeamspeakUser(user, uniqueId))
    }

    val expectedName = this.obtainExpectedTeamspeakName(user)

    val r = registerJoinedUser(expectedName).fallbackTo(registerUsingEventListener(expectedName))

    r.onComplete {
      case Success(_) =>
        logger.info(s"Registered user in teamspeak " +
          s"userId=${user.userId} " +
          s"event=teamspeak.register.success")
      case Failure(ex) =>
        logger.error("Failed to create registration request for " +
          s"userId=${user.userId} " +
          s"event=teamspeak.register.failure", ex)
    }
    r
  }

  def getTeamspeakUniqueId(userId: Int): Future[String] = {
    val f = dbAgent.run(Coalition.TeamspeakUsers.filter(_.userId === userId).result)
      .map { res =>
        res.headOption match {
          case Some(r) => r.uniqueId
          case None => throw ResourceNotFoundException(s"No teamspeak uniqueId found for $userId")
        }
      }
    f.onComplete {
      case Success(res) =>
        logger.info(s"Got teamspeak uniqueId for userId=$userId event=user.getTeamspeakUniqueId.success")
      case Failure(ex) =>
        logger.error(s"Failed to get teamspeak uniqueId for userId=$userId event=user.getTeamspeakUniqueId.failure")
    }
    f
  }

  def getUserIdByUniqueId(uniqueId: String): Future[Int] = {
    val f = dbAgent.run(Coalition.TeamspeakUsers.filter(_.uniqueId === uniqueId).result)
    .map { res =>
      res.headOption match {
        case Some(r) => r.userId
        case None => throw ResourceNotFoundException(s"No teamspeak uniqueId found for uniqueId8=${uniqueId.substring(8)}")
      }
    }
    f.onComplete {
      case Success(res) =>
        logger.info(s"Got teamspeak uniqueId for uniqueId8=${uniqueId.substring(8)} event=user.getTeamspeakUserId.success")
      case Failure(ex) =>
        logger.error(s"Failed to get teamspeak uniqueId for uniqueId8=${uniqueId.substring(8)} event=user.getTeamspeakUserId.failure")
    }
    f
  }

  def registerTeamspeakUser(user: User, uniqueId: String): Future[Unit] = {
    val f = dbAgent.run(Coalition.TeamspeakUsers.filter(_.uniqueId === uniqueId).take(1).result).flatMap { r =>
      r.headOption match {
        case Some(res) => Future.failed(ConflictingEntityException("User with such teamspeak uniqueId already exists"))
        case None =>
          val q = Coalition.TeamspeakUsers.map(r => (r.userId, r.uniqueId)) += (user.userId, uniqueId)
          dbAgent.run(q).flatMap(r => syncTeamspeakUser(user))
      }
    }.recoverWith(ExceptionHandlers.dbExceptionHandler)
    f.onComplete {
      case Success(_) =>
        logger.info(s"Successfully registered user in teamspeak userId=${user.userId} event=teamspeak.register.success")
      case Failure(ex) =>
        logger.error(s"Failed to register user in teamspeak userId=${user.userId} event=teamspeak.register.failure", ex)
    }
    f.map(_ => ())
  }

  def getAllClients: Future[List[DatabaseClient]] = {
    this.safeTeamspeakQuery(() => client.getDatabaseClients)().map(_.asScala.toList.filterNot(_.getNickname.contains("ServerQuery")))
  }

  def syncTeamspeakUser(uniqueId: String, permissions: Seq[PermissionBit]): Future[Unit] = {
    val shouldBeGroups = permissions.flatMap { p =>
      teamspeakPermissionMap.find(_.bit_name == p.name).map(_.teamspeak_group_id)
    }.toSet

    val f = (for {
      tsDbId <- this.safeTeamspeakQuery(() => client.getDatabaseClientByUId(uniqueId))().map(_.getDatabaseId)
      curGroups <- this.safeTeamspeakQuery(() => client.getServerGroupsByClientId(tsDbId))()
        .map(_.asScala.map(_.getId).filterNot(_ == 8).toSet)
      addToGroups <- Future.sequence {
        (shouldBeGroups -- curGroups).toList.map { groupId =>
          this.safeTeamspeakQuery(() => client.addClientToServerGroup(groupId, tsDbId))().map(_.booleanValue())
        }
      }
      removeFromGroups <- Future.sequence {
        (curGroups -- shouldBeGroups).toList.map { groupId =>
          this.safeTeamspeakQuery(() => client.addClientToServerGroup(groupId, tsDbId))().map(_.booleanValue())
        }
      }
    } yield addToGroups ++ removeFromGroups).map(_.forall(identity))

    f.onComplete {
      case Success(true) =>
        logger.info(s"Successfully updated teamspeak permissions for uniqueId8=${uniqueId.substring(8)} event=teamspeak.updateUser.success")
      case Success(false) =>
        logger.error(s"Updated user, but one or more requests failed uniqueId8=${uniqueId.substring(8)} event=teamspeak.updateUser.partial")
      case Failure(ex) =>
        logger.error(s"Failed to update user uniqueId8=${uniqueId.substring(8)} event=teamspeak.updateUser.failure", ex)
    }
    f.map { r =>
      if (r) ()
      else throw new RuntimeException(s"Not all requests returned true for userId ${uniqueId.substring(8)}")
    }
  }

  def getClientByUniqueId(uniqueId: String): Future[DatabaseClientInfo] = {
    val f: Future[DatabaseClientInfo] = this.safeTeamspeakQuery(() => client.getDatabaseClientByUId(uniqueId))()
      .map {
      case null => throw ResourceNotFoundException(s"No teamspeak user with name uniqueId found")
      case resp => resp
    }
    f.onComplete {
      case Success(c) =>
        logger.info(s"Got client " +
          s"uniqueId8=${uniqueId.substring(8)} " +
          s"teamspeakName=${c.getNickname} " +
          s"teamspeakDbId=${c.getDatabaseId} " +
          s"event=teamspeak.getByName.success")
      case Failure(ex) =>
        logger.error(s"Failed to get client by uniqueId8=${uniqueId.substring(8)} event=teamspeak.getByName.failure", ex)
    }
    f
  }

  def getConnectedClientByUniqueId(uniqueId: String): Future[Client] = {
    val f: Future[Client] = this.safeTeamspeakQuery(() => client.getClientByUId(uniqueId))()
      .map {
        case null => throw ResourceNotFoundException(s"No teamspeak user with name uniqueId found")
        case resp => resp
      }
    f.onComplete {
      case Success(c) =>
        logger.info(s"Got client " +
          s"uniqueId8=${uniqueId.substring(8)} " +
          s"teamspeakName=${c.getNickname} " +
          s"teamspeakDbId=${c.getDatabaseId} " +
          s"event=teamspeak.getByName.success")
      case Failure(ex) =>
        logger.error(s"Failed to get client by uniqueId8=${uniqueId.substring(8)} event=teamspeak.getByName.failure", ex)
    }
    f
  }

  def syncTeamspeakUser(user: User): Future[Unit] = {
    for {
      uniqueId <- this.getTeamspeakUniqueId(user.userId)
      res <- syncTeamspeakUser(uniqueId, user.userPermissions)
    } yield res
  }

  private def safeQuery[T](f: () => T, timeout: FiniteDuration): Future[T] = {
    val p = Promise[T]()
    val r = retryWithExponentialDelay(maxRetryTimeout = 1.hour.fromNow, initialDelay = 1.second, maxDelay = 5.seconds) {
      if (daemon.isConnected) {
        val op = Future(f.apply())
        op.onComplete {
          case Success(res) => ()
          case Failure(ex) =>
            logger.warn("Exception during execution of teamspeak command event=teamspeak.execute.retry", ex)
        }
        op
      } else {
        Future.failed(new NotYetConnectedException())
      }
    }
    r.onComplete {
      case Success(res) =>
        logger.info(s"Executed teamspeak command commandResultType=${res.getClass.getName} event=teamspeak.execute.success")
      case Failure(ex) =>
        logger.error(s"Failed to execute teamspeak command command ${timeout.toString()} event=teamspeak.execute.failure", ex)
    }
    r
  }

  private def safeAddListener(listeners: TS3Listener*)(timeout: FiniteDuration = 1.minute): Future[Unit] = {
    safeQuery(() => client.addTS3Listeners(listeners: _*), timeout)
  }

  private def safeTeamspeakQuery[T](f: () => CommandFuture[T])(timeout: FiniteDuration = 1.minute): Future[T] = {
    safeQuery(f, timeout).flatMap(x => commandToScalaFuture(x))
  }
}
