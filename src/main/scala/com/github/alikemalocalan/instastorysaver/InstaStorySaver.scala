package com.github.alikemalocalan.instastorysaver

import java.util.{Timer, TimerTask}

import com.github.alikemalocalan.instastorysaver.service.InstaService
import com.github.instagram4j.instagram4j.IGClient
import org.apache.commons.logging.{Log, LogFactory}

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object InstaStorySaver extends App with Config {
  val logger: Log = LogFactory.getLog(this.getClass)
  val timer = new Timer()

  implicit val client: IGClient = InstaService
    .login(userName, passWord)

  val storyScheduler: TimerTask = new TimerTask {
    def run(): Unit =
      Try {
        logger.info("Starting Saving Stories...")
        InstaService.saveStoriesToS3
      } match {
        case Success(_) => logger.info("Finish Success Story, yuu are the best!!!")
        case Failure(exception) => logger.error("Story error on: ", exception)
      }
  }
  val feedScheduler: TimerTask = new TimerTask {
    def run(): Unit =
      Try {
        logger.info("Starting Saving Feeds...")
        InstaService.saveFeedsToS3
      } match {
        case Success(_) => logger.info("Finish Success Feed, yuu are the best!!!")
        case Failure(exception) => logger.error("Feed error on: ", exception)
      }
  }
  timer.scheduleAtFixedRate(storyScheduler, 3.seconds.toMillis, 24.hours.toMillis)
  timer.scheduleAtFixedRate(feedScheduler, 15.minutes.toMillis, 168.hours.toMillis)
}
