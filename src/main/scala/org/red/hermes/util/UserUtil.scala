package org.red.hermes.util

import com.typesafe.scalalogging.LazyLogging
import org.red.iris.User


object UserUtil extends LazyLogging {
  def generateNickName(user: User, characterId: Long): String = {
    val userData = (user.eveUserDataList.head +: user.eveUserDataList.tail).find(_.characterId == characterId)
    userData match {
      case Some(u) =>
        val name = (u.allianceTicker match {
          case Some(n) => s"$n | "
          case None => ""
        }) + s"${u.corporationTicker} | ${u.characterName}"
        logger.info(s"User name generated " +
          s"userId=${user.userId} " +
          s"characterId=$characterId " +
          s"expectedNickname=$name " +
          s"event=userUtil.genName.success")
        name
      case None =>
        logger.error(s"Account doesn't own characterId " +
          s"userId=${user.userId} " +
          s"characterId=$characterId " +
          s"event=userUtil.genName.failure")
        throw new RuntimeException("Account doesn't own characterId")
    }
  }
}
