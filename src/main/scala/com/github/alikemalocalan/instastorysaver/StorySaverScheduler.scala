package com.github.alikemalocalan.instastorysaver

import com.github.alikemalocalan.instastorysaver.service.{FileService, InstaService}
import com.instagram4j.web.Instagram4j
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

object StorySaverScheduler extends Config {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val threadCounter = new AtomicInteger(1)
  private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
    2,
    (r: Runnable) => {
      val t = new Thread(r, s"story-scheduler-${threadCounter.getAndIncrement()}")
      t.setDaemon(true)
      t
    }
  )

  private def getClient: Instagram4j = (sessionId, csrfToken) match {
    case (Some(session), Some(csrf)) =>
      InstaService.fromSession(session, csrf, username)
    case _ =>
      InstaService.login(username, password)
  }

  def main(args: Array[String]): Unit = {
    registerShutdownHook()

    logger.info("Initializing 24/7 Instagram Story Saver Daemon...")

    // Check stories every 1 hour (Instagram stories expire in 24h)
    scheduleTask("Stories", initialDelay = 5.seconds, period = 1.hour) {
      given client: Instagram4j = getClient
      InstaService.saveStories(downloadFolder, maxConcurrentDownloads, requestDelayMs)
    }

    // Check highlights weekly
    scheduleTask("Highlights", initialDelay = 10.minutes, period = 168.hours) {
      given client: Instagram4j = getClient
      InstaService.saveUserHighLightStories(downloadFolder, maxConcurrentDownloads, requestDelayMs)
    }

    // Check feeds every 24 hours
    scheduleTask("Feeds", initialDelay = 5.minutes, period = 24.hours) {
      given client: Instagram4j = getClient
      InstaService.saveFeeds(downloadFolder, maxConcurrentDownloads, requestDelayMs)
    }

    logger.info("StorySaverScheduler running 24/7. Press CTRL+C to terminate.")
    try {
      Thread.currentThread().join()
    } catch {
      case _: InterruptedException =>
        logger.info("Scheduler main thread interrupted. Exiting...")
    }
  }

  private def scheduleTask(name: String, initialDelay: FiniteDuration, period: FiniteDuration)(
      action: => Unit
  ): Unit = {
    val runnable: Runnable = () => {
      Try {
        logger.info(s"Starting scheduled task: $name...")
        action
      } match {
        case Success(_)  =>
          logger.info(s"Finished scheduled task: $name.")
        case Failure(ex) =>
          logger.error(s"Error in scheduled task $name: ${ex.getMessage}")
      }
      FileService.evictIdleConnections()
    }
    scheduler.scheduleWithFixedDelay(runnable, initialDelay.toMillis, period.toMillis, TimeUnit.MILLISECONDS)
  }

  private def registerShutdownHook(): Unit = {
    sys.addShutdownHook {
      logger.info("Shutting down StorySaverScheduler...")
      try {
        scheduler.shutdown()
        scheduler.awaitTermination(10, TimeUnit.SECONDS)
        FileService.shutdown()
      } catch {
        case _: Throwable => ()
      }
      logger.info("Shutdown completed successfully.")
    }
  }
}


