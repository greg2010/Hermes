package org.red.hermes.controllers

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import net.dv8tion.jda.core.entities.SelfUser
import net.dv8tion.jda.core.{AccountType, JDABuilder}
import org.red.db.models.Coalition
import org.red.hermes.util.discord.{DiscordHelpers, DiscordRole}
import org.red.hermes.util.{DiscordGroupMapEntry, UserUtil}
import org.red.hermes.util.FutureConverters._
import org.red.iris.{PermissionBit, ResourceNotFoundException, User}
import org.red.iris.util.YamlParser
import slick.jdbc.JdbcBackend
import slick.jdbc.PostgresProfile.api._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success}


class DiscordController(config: Config)(implicit ec: ExecutionContext, dbAgent: JdbcBackend.Database) extends LazyLogging {
  private val guildId = config.getLong("discord.guildId")
  private val token = config.getString("discord.token")

  private val discordClient = new JDABuilder(AccountType.BOT)
    .setToken(token)
    .setAutoReconnect(true)
    .setEnableShutdownHook(true).buildBlocking()

  private val guild = discordClient.getGuildById(guildId)

  private case class DiscordGroupMap(discordGroupMap: Seq[DiscordGroupMapEntry])

  val discordPermissionMap: Set[DiscordGroupMapEntry] =
    YamlParser.parseResource[DiscordGroupMap](Source.fromResource("discord_group_map.yml")).discordGroupMap.toSet


  private def getDiscordPermissions(permissions: Set[PermissionBit]): Set[DiscordGroupMapEntry] = {
    permissions.flatMap(p => discordPermissionMap.find(_.bit_position == p.bitPosition))
  }

  def getDiscordUser(accessToken: String): Future[SelfUser] = {
    val f = Future {
      new JDABuilder(AccountType.CLIENT)
        .setToken(token).buildBlocking().getSelfUser
    }
    f.onComplete {
      case Success(r) => logger.info(s"Got discord user by accessToken " +
        s"discordUserId=${r.getIdLong} " +
        s"event=discord.user.get.success")
      case Failure(ex) => logger.error(s"Failed to get discord user by accessToken " +
        s"event=discord.user.get.failure", ex)
    }
    f
  }

  def registerUserOnDiscord(user: User, accessToken: String, refreshToken: String): Future[Unit] = {
    val jdaRoles = this.getDiscordPermissions(user.userPermissions.toSet)
      .map(p => DiscordRole(guild.getRoleById(p.discord_group_id)))
    val f = this.getDiscordUser(accessToken).flatMap { dUser =>
      DiscordHelpers.inviteUserToGuild(
        guildId,
        dUser.getIdLong,
        accessToken,
        token,
        UserUtil.generateNickName(user),
        jdaRoles.toSeq
      ).map(_ => dUser)
    }.flatMap { dUser =>
      val q = Coalition.DiscordUsers.map(r => (r.userId, r.discordId, r.refreshToken)) +=
        (user.userId, dUser.getIdLong, refreshToken)
      dbAgent.run(q)
    }
    f.onComplete {
      case Success(_) =>
        logger.info(s"Registered user on discord " +
          s"userId=${user.userId} " +
          s"event=discord.register.success")
      case Failure(ex) =>
        logger.error("Failed to register user on discord " +
          s"userId=${user.userId} " +
          s"event=discord.register.failure", ex)
    }
    f
  }

  def syncDiscordUser(user: User): Future[Unit] = {
    val shouldBeRoles = this.getDiscordPermissions(user.userPermissions.toSet).map(_.discord_group_id)
    val discordId = dbAgent.run(
      Coalition.DiscordUsers.filter(_.userId === user.userId)
        .map(_.discordId)
        .take(1)
        .result
    ).map {
      _.headOption match {
        // TODO: investigate if the library is actually as dumb as I think it and the data is not refreshed
        // if there's an external change
        case Some(dId) => dId
        case None => throw ResourceNotFoundException("No discord user found")
      }
    }

    val currentRoles = discordId.map { dId =>
      discordClient
        .getGuildById(guildId)
        .getMemberById(dId)
        .getRoles
        .asScala
        .toSet
        .map(_.getIdLong)
      }

    val toRemove = currentRoles.map { curRoles =>
      curRoles -- shouldBeRoles
    }
    val toAdd = currentRoles.map { curRoles =>
      shouldBeRoles -- curRoles
    }
    val f = for {
      discordId <- discordId
      toAdd <- toAdd
      toRemove <- toRemove
      doAdd <- guild.getController.addRolesToMember(guild.getMemberById(discordId)).submit().asScala
      doRemove <- guild.getController.removeRolesFromMember(guild.getMemberById(discordId)).submit().asScala
    } yield ()
    f.onComplete {
      case Success(_) =>
        logger.info(s"Synced user on discord userId=${user.userId} event=discord.sync.success")
      case Failure(ex) =>
        logger.error(s"Failed to sync user on discord userId=${user.userId} event=discord.sync.failure", ex)
    }
    f
  }
}
