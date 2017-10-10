package org.red.hermes.controllers

import java.nio.channels.NotYetConnectedException

import com.gilt.gfc.concurrent.ScalaFutures.retryWithExponentialDelay
import com.github.theholywaffle.teamspeak3.api.CommandFuture
import com.github.theholywaffle.teamspeak3.api.event._
import com.github.theholywaffle.teamspeak3.api.wrapper.{Client, DatabaseClient, DatabaseClientInfo}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import org.red.hermes.daemons.teamspeak.TeamspeakDaemon
import org.red.hermes.jobs.teamspeak.RegistrationJoinListener
import org.red.hermes.util.FutureConverters._
import org.red.hermes.util.{TeamspeakGroupMapEntry, UserUtil}
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
import scala.util.{Failure, Success, Try}


class TeamspeakController(config: Config)
                         (implicit dbAgent: JdbcBackend.Database, ec: ExecutionContext)
  extends LazyLogging {
  private val daemon = new TeamspeakDaemon(config)
  private val client = daemon.client

  private case class TeamspeakGroupMap(teamspeak_group_map: Seq[TeamspeakGroupMapEntry])

  val teamspeakPermissionMap: Seq[TeamspeakGroupMapEntry] =
    YamlParser.parseResource[TeamspeakGroupMap](Source.fromResource("teamspeak_group_map.yml")).teamspeak_group_map

  def registerUserOnTeamspeak(user: User, characterId: Long, userIp: String): Future[String] = {

    // Creates event that returns uniqueId when user joins teamspeak
    def getUniqueIdOnJoin(expectedNickname: String): Future[String] = {
      val p = Promise[String]()
      safeAddListener(new RegistrationJoinListener(client, this, config, expectedNickname, userIp, p))(10.minutes)
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
      p.future
    }

    // Adds user to db
    def addUserToDB(uniqueId: String): Future[Unit] = {
      val f = dbAgent.run(Coalition.TeamspeakUsers.filter(_.uniqueId === uniqueId).take(1).result).flatMap { r =>
        r.headOption match {
          case Some(res) => Future.failed(ConflictingEntityException("User with such teamspeak uniqueId already exists"))
          case None =>
            val q = Coalition.TeamspeakUsers
              .map(r => (r.userId, r.uniqueId, r.mainCharacterId)) += (user.userId, uniqueId, characterId)
            dbAgent.run(q)
        }
      }.recoverWith(ExceptionHandlers.dbExceptionHandler)
      f.onComplete {
        case Success(_) =>
          logger.info(s"Successfully added user to db userId=${user.userId} event=teamspeak.register.success")
        case Failure(ex) =>
          logger.error(s"Failed to add user to db userId=${user.userId} event=teamspeak.register.failure", ex)
      }
      f.map(_ => ())
    }

    val expectedName = UserUtil.generateNickName(user, characterId)

    val r = for {
      uniqueId <- this.getConnectedClientByName(expectedName).map(_.getUniqueIdentifier)
        .fallbackTo(getUniqueIdOnJoin(expectedName))
      _ <- addUserToDB(uniqueId)
      res <- syncTeamspeakUser(user)
    } yield res

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

    // Log and discard output of `r` (since event handler may live up to 10 minutes)
    Future(expectedName)
  }

  def getUniqueIdByUserId(userId: Int): Future[String] = {
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

  def getAllClients: Future[List[DatabaseClient]] = {
    this.safeTeamspeakQuery(() => client.getDatabaseClients)().map(_.asScala.toList.filterNot(_.getNickname.contains("ServerQuery")))
  }

  def getConnectedClientByName(name: String): Future[Client] = {
    val f = this.safeTeamspeakQuery(() => client.getClientByNameExact(name, true))().map { c =>
      Option(c) match {
        case Some(cl) => cl
        case None => throw ResourceNotFoundException("No such client connected")
      }
    }
    f.onComplete {
      case Success(res) =>
        logger.info(s"Got connected client by name " +
          s"name=$name " +
          s"uniqueId8=${res.getUniqueIdentifier.substring(8)} " +
          s"event=teamspeak.getConnectedByName.success")
      case Failure(ex: ResourceNotFoundException) =>
        logger.warn(s"Did not find a connected client by name " +
          s"name=$name " +
          s"event=teamspeak.getConnectedByName.notFound")
      case Failure(ex) =>
        logger.error(s"Exception while trying to find a connected user by name " +
          s"name=$name " +
          s"event=teamspeak.getConnectedByName.failure", ex)
    }
    f
  }

  def syncTeamspeakUser(uniqueId: String, permissions: Seq[PermissionBit]): Future[Unit] = {
    // First, generate set of groups user is expected to have
    val expectedGroups = permissions.flatMap { p =>
      teamspeakPermissionMap.find(_.bit_position == p.bitPosition).map(_.teamspeak_group_id)
    }.toSet

    def applyGroups(databaseId: Int, groupsToAdd: Set[Int], groupsToRemove: Set[Int]) = {
      def groupUpdateCallback(action: String, groupId: Int)(t: Try[Boolean]): Unit = {
        t match {
          case Success(res) if res =>
            logger.debug(s"Successfully changed or removed group " +
              s"action=$action " +
              s"groupId=$groupId " +
              s"databaseId=$databaseId " +
              s"uniqueId8=${uniqueId.substring(8)} " +
              s"event=teamspeak.group.$action.success")
          case Success(res) if !res =>
            logger.error(s"Failed to change or remove group " +
              s"action=$action " +
              s"groupId=$groupId " +
              s"databaseId=$databaseId " +
              s"uniqueId8=${uniqueId.substring(8)} " +
              s"event=teamspeak.group.$action.failure")
          case Failure(ex) =>
            logger.error(s"Exception while trying to change or remove group " +
              s"action=$action " +
              s"groupId=$groupId " +
              s"databaseId=$databaseId " +
              s"uniqueId8=${uniqueId.substring(8)} " +
              s"event=teamspeak.group.$action.failure", ex)
        }
      }
      // Add every group from add list
      val addFutureList = groupsToAdd.map { groupId =>
        val f = this.safeTeamspeakQuery(() => client.addClientToServerGroup(groupId, databaseId))()
          .map(_.booleanValue())
        f.onComplete(groupUpdateCallback("add", groupId))
        f
      }.toList
      // Remove every group from remove list
      val removeFutureList = groupsToRemove.map { groupId =>
        val f = this.safeTeamspeakQuery(() => client.removeClientFromServerGroup(groupId, databaseId))()
          .map(_.booleanValue())
        f.onComplete(groupUpdateCallback("remove", groupId))
        f
      }.toList
      // Sequence the future and apply identity function in case of success
      Future.sequence(addFutureList ++ removeFutureList).map(_.forall(identity))
    }

    val f = for {
      c <- getClientByUniqueId(uniqueId)
      currentGroups <- getServerGroupsByTeamspeakDatabaseId(c.getDatabaseId)
      res <- applyGroups(c.getDatabaseId, expectedGroups -- currentGroups, currentGroups -- expectedGroups)
    } yield res

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


  def getServerGroupsByUniqueId(uniqueId: String): Future[Set[Int]] = {
    this.getClientByUniqueId(uniqueId)
      .flatMap(c => getServerGroupsByTeamspeakDatabaseId(c.getDatabaseId))
  }

  def getServerGroupsByTeamspeakDatabaseId(databaseId: Int): Future[Set[Int]] = {
    // Filtering group 8, default group that cannot be added or removed
    val f = this.safeTeamspeakQuery(() => client.getServerGroupsByClientId(databaseId))()
      .map(_.asScala.map(_.getId).filterNot(_ != 8).toSet)
    f.onComplete {
      case Success(res) =>
        logger.info(s"Got groups " +
          s"teamspeakDatabaseId=$databaseId " +
          s"teamspeakGroupList=${res.mkString(",")} " +
          s"event=teamspeak.getGroups.success")
      case Failure(ex) =>
        logger.error(s"Failed to get groups for teamspeakDatabaseId=$databaseId event=teamspeak.getGroups.failure", ex)
    }
    f
  }

  // Gets database user (ie offline user) by unique id
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

  // Gets connected user (ie user with IP and additional information) by unique id
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
      uniqueId <- this.getUniqueIdByUserId(user.userId)
      res <- syncTeamspeakUser(uniqueId, user.permissions)
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
