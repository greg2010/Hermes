package org.red.hermes.util.discord

import dispatch.Defaults._
import dispatch.{Http, url}
import net.dv8tion.jda.core.entities.{Role => JDARole}
import io.circe.generic.auto._
import io.circe.syntax._
import org.asynchttpclient.Response

import scala.concurrent.{ExecutionContext, Future}


case class DiscordAddGuildMemberRequest(access_token: String,
                                        nick: Option[String],
                                        roles: Seq[DiscordRole],
                                        mute: Boolean,
                                        deaf: Boolean)

case class DiscordRole(id: Long,
                       name: String,
                       color: Long,
                       hoist: Boolean,
                       position: Int,
                       permissions: Long,
                       managed: Boolean,
                       mentionable: Boolean)

object DiscordRole {
  def apply(JDARole: JDARole): DiscordRole = {
    DiscordRole(
      id = JDARole.getIdLong,
      name = JDARole.getName,
      color = JDARole.getColor.getRGB,
      hoist = JDARole.isHoisted,
      position = JDARole.getPosition,
      permissions = JDARole.getPermissionsRaw,
      managed = JDARole.isManaged,
      mentionable = JDARole.isMentionable
    )
  }
}

object DiscordHelpers {
  private val baseurl = "https://discordapp.com/api"

  private val httpClient = Http.withConfiguration(_.setMaxRequestRetry(10))

  def inviteUserToGuild(guildId: Long,
                        userId: Long,
                        accessToken: String,
                        botToken: String,
                        nick: String,
                        discordRoles: Seq[DiscordRole])
                       (implicit ec: ExecutionContext): Future[Unit] = {
    val body = DiscordAddGuildMemberRequest(accessToken, Some(nick), discordRoles, mute = false, deaf = false)

    val req = url(baseurl + s"/guilds/$guildId/members/$userId")
      .addHeader("Authorization", s"Bot $botToken")
      .PUT
      .setBody(body.asJson.noSpaces)
    httpClient(req)(ec).map(_ => ())(ec)
  }
}
