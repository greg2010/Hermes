package org.red


import com.typesafe.config.{Config, ConfigFactory}
import slick.jdbc.JdbcBackend
import slick.jdbc.JdbcBackend.Database

import scala.language.postfixOps


package object hermes {
  val config: Config = ConfigFactory.load()
  val hermesConfig: Config = config.getConfig("hermes")

  object Implicits {
    implicit val dbAgent: JdbcBackend.Database = Database.forConfig("postgres", config)
  }

}
