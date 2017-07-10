package org.red.hermes.controllers

import java.util.Date

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Cancelable
import org.quartz.JobBuilder.newJob
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.{CronScheduleBuilder, TriggerKey}
import org.red.hermes.daemons.ScheduleDaemon
import org.red.hermes.jobs.quartz.TeamspeakJob
import org.red.iris.finagle.clients.UserClient
import slick.jdbc.JdbcBackend

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random


class ScheduleController(config: Config, teamspeakController: => TeamspeakController, userClient: => UserClient)
                        (implicit dbAgent: JdbcBackend.Database, ec: ExecutionContext)
  extends LazyLogging {

  private val daemon = new ScheduleDaemon(this, config, teamspeakController, userClient)
  val teamspeakDaemon: Cancelable = daemon.teamspeakDaemon
  private val quartzScheduler = daemon.quartzScheduler


  def scheduleTeamspeakJob(uniqueId: String): Future[Option[Date]] = {
    val maybeTriggerKey =  new TriggerKey(uniqueId, config.getString("quartzTeamspeakGroupName"))
    for {
      ifExists <- Future(quartzScheduler.checkExists(maybeTriggerKey))
      res <- {
        if (ifExists) {
          logger.info(s"Teamspeak job for uniqueId8=${uniqueId.substring(8)} already exists event=teamspeak.schedule")
          Future(None)
        } else {
          val j = newJob()
            .withIdentity(uniqueId, config.getString("quartzTeamspeakGroupName"))

          val builtJob = j.ofType((new TeamspeakJob).getClass).build()
          builtJob.getJobDataMap.put("uniqueId", uniqueId)
          val minutes = Random.nextInt(config.getInt("quartzTeamspeakUpdateMinutesRefreshRate"))
          val seconds = Random.nextInt(59)
          val cronString = s"$seconds $minutes/${config.getString("quartzTeamspeakUpdateMinutesRefreshRate")} 0 * * ?"
          val t = newTrigger()
            .forJob(builtJob)
            .withIdentity(maybeTriggerKey)
            .withSchedule(
              CronScheduleBuilder
                .cronSchedule(cronString)
            ).build()
          val r = Some(quartzScheduler.scheduleJob(builtJob, t))
          logger.info(s"Scheduled update job for " +
            s"uniqueId8=${uniqueId.substring(8)} " +
            s"cronString=$cronString " +
            s"event=teamspeak.schedule.failure")
          Future(r)
        }
      }
    } yield res
  }
}
