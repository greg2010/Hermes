package org.red.hermes.daemons

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Cancelable
import monix.execution.Scheduler.{global => scheduler}
import org.quartz.JobBuilder.newJob
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.impl.StdSchedulerFactory
import org.quartz.{Scheduler, SimpleScheduleBuilder, TriggerKey}
import org.red.hermes.controllers.{ScheduleController, TeamspeakController}
import org.red.hermes.jobs.quartz.TeamspeakDaemonJob
import org.red.iris.finagle.clients.UserClient
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


class ScheduleDaemon(scheduleController: => ScheduleController,
                     config: Config,
                     teamspeakController: => TeamspeakController,
                     userClient: => UserClient)
                    (implicit dbAgent: JdbcBackend.Database, ec: ExecutionContext) extends LazyLogging {
  val quartzScheduler: Scheduler = new StdSchedulerFactory().getScheduler
  private val userDaemonTriggerName = "userDaemon"
  private val teamspeakDaemonTriggerName = "teamspeakDaemon"

  quartzScheduler.getContext.put("dbAgent", dbAgent)
  quartzScheduler.getContext.put("ec", ec)
  quartzScheduler.getContext.put("scheduleController", scheduleController)
  quartzScheduler.getContext.put("teamspeakController", teamspeakController)
  quartzScheduler.getContext.put("userClient", userClient)
  quartzScheduler.start()
  val teamspeakDaemon: Cancelable =
    scheduler.scheduleWithFixedDelay(0.seconds, 1.minute) {
      val maybeTriggerKey = new TriggerKey(teamspeakDaemonTriggerName, config.getString("quartzTeamspeakGroupName"))
      if (quartzScheduler.checkExists(maybeTriggerKey)) {
        logger.info("Teamspeak daemon has already started, doing nothing event=teamspeak.schedule")
        quartzScheduler.getTrigger(maybeTriggerKey).getNextFireTime
      } else {
        val j = newJob((new TeamspeakDaemonJob).getClass)
          .withIdentity(teamspeakDaemonTriggerName, config.getString("quartzTeamspeakGroupName"))
          .build()
        val t = newTrigger()
          .withIdentity(teamspeakDaemonTriggerName, config.getString("quartzTeamspeakGroupName"))
          .forJob(j)
          .withSchedule(SimpleScheduleBuilder
            .repeatMinutelyForever(config.getInt("quartzTeamspeakUpdateDaemonRefreshRate"))
          )
          .startNow()
          .build()
        quartzScheduler.scheduleJob(j, t)
      }
    }
}
