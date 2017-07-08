package org.red.hermes.jobs.quartz

import com.typesafe.scalalogging.LazyLogging
import org.quartz.{Job, JobExecutionContext}
import org.red.hermes.controllers.{ScheduleController, TeamspeakController}
import org.red.hermes.exceptions.ExceptionHandlers
import slick.jdbc.JdbcBackend

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class TeamspeakDaemonJob extends Job with LazyLogging {
  override def execute(context: JobExecutionContext): Unit = {
    try {
      val dbAgent = context.getScheduler.getContext.get("dbAgent").asInstanceOf[JdbcBackend.Database]
      val teamspeakController = context.getScheduler.getContext.get("teamspeakController").asInstanceOf[TeamspeakController]
      val scheduleController = context.getScheduler.getContext.get("scheduleController").asInstanceOf[ScheduleController]
      implicit val ec = context.getScheduler.getContext.get("ec").asInstanceOf[ExecutionContext]

      teamspeakController.getAllClients.flatMap { clients =>
        Future.sequence {
          clients.map { c =>
            logger.debug(s"Parsing teamspeakNickname=${c.getNickname}")
            scheduleController.scheduleTeamspeakJob(c.getUniqueIdentifier)
          }
        }.map(_.flatten)
      }.onComplete {
        case Success(r) if r.isEmpty =>
          logger.info(s"No new teamspeak users to schedule for sync event=teamspeak.sync.success")
        case Success(r) =>
          logger.info(s"Scheduled teamspeak users for sync affected=${r.length} event=teamspeak.sync.success")
        case Failure(ex) =>
          logger.error("Failed to sync permissions event=teamspeak.sync.failure", ex)
      }
    } catch {
      ExceptionHandlers.jobExceptionHandler
    }
  }
}
