package org.red.hermes.exceptions

import com.typesafe.scalalogging.LazyLogging
import org.postgresql.util.PSQLException
import org.red.iris.ConflictingEntityException

import scala.concurrent.Future


object ExceptionHandlers extends LazyLogging {
  def dbExceptionHandler[T]: PartialFunction[Throwable, Future[T]] = {
    // Conflicting entities (eg duplicate unique key)
    case ex: PSQLException if ex.getSQLState == "23505" =>
      throw ConflictingEntityException(ex.getServerErrorMessage.getMessage)
  }

  def jobExceptionHandler: PartialFunction[Throwable, Unit] = {
    case ex: ClassCastException =>
      logger.error("Failed to instantiate one or more classes event=user.schedule.failure", ex)
    case ex: NullPointerException =>
      logger.error("One or more required fields were null event=user.schedule.failure", ex)
  }
}
