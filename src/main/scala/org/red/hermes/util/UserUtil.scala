package org.red.hermes.util

import org.red.iris.User


object UserUtil {
  def generateNickName(user: User): String = {
    (user.eveUserData.allianceTicker match {
      case Some(n) => s"$n | "
      case None => ""
    }) + s"${user.eveUserData.corporationTicker} | ${user.eveUserData.characterName}"
  }
}
