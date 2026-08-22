package com.github.alikemalocalan.instastorysaver

import com.github.alikemalocalan.instastorysaver.service.InstaService
import com.instagram4j.web.Instagram4j
import org.apache.commons.logging.{Log, LogFactory}

import java.util.{Timer, TimerTask}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

object StorySaverScheduler extends Config {
  private val logger: Log = LogFactory.getLog(getClass)
  private val timer       = new Timer()

  given client: Instagram4j = (sessionId, csrfToken) match {
    case (Some(session), Some(csrf)) =>
      InstaService.fromSession(session, csrf, username)
    case _ =>
      InstaService.login(username, password)
  }


  def main(args: Array[String]): Unit = {
    scheduleTask("Stories", initialDelay = 3.seconds, period = 24.hours) {
      InstaService.saveStories(downloadFolder)
    }

    scheduleTask("Highlights", initialDelay = 10.minutes, period = 168.hours) {
      InstaService.saveUserHighLightStories(downloadFolder)
    }

    scheduleTask("Feeds", initialDelay = 5.minutes, period = 168.hours) {
      InstaService.saveFeeds(downloadFolder)
    }
  }

  private def scheduleTask(name: String, initialDelay: FiniteDuration, period: FiniteDuration)(
      action: => Unit
  ): Unit = {
    val task = new TimerTask {
      override def run(): Unit =
        Try {
          logger.info(s"Starting saving $name...")
          action
        } match {
          case Success(_)  => logger.info(s"Successfully finished saving $name.")
          case Failure(ex) => logger.error(s"Error while saving $name: ${ex.getMessage}", ex)
        }
    }
    timer.scheduleAtFixedRate(task, initialDelay.toMillis, period.toMillis)
  }
}

