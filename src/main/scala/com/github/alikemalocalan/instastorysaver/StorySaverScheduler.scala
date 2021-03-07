package com.github.alikemalocalan.instastorysaver

import com.github.alikemalocalan.instastorysaver.service.InstaService
import com.github.instagram4j.instagram4j.IGClient
import org.apache.commons.logging.{Log, LogFactory}

import java.util.{Timer, TimerTask}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object StorySaverScheduler extends App with Config {
  val logger: Log = LogFactory.getLog(this.getClass)
  val timer = new Timer()

  implicit val client: IGClient = InstaService
    .login(userName, passWord)

  val storyScheduler: TimerTask = new TimerTask {
    def run(): Unit =
      Try {
        logger.info("Starting Saving Stories...")
        InstaService.saveStories(enableS3Backup)
      } match {
        case Success(_) => logger.info("Finish Success Story, yuu are the best!!!")
        case Failure(exception) => logger.error("Story error on: ", exception)
      }
  }
  val feedScheduler: TimerTask = new TimerTask {
    def run(): Unit =
      Try {
        logger.info("Starting Saving Feeds...")
        InstaService.saveFeeds(enableS3Backup)
      } match {
        case Success(_) => logger.info("Finish Success Feed, yuu are the best!!!")
        case Failure(exception) => logger.error("Feed error on: ", exception)
      }
  }
  val highLightStoryScheduler: TimerTask = new TimerTask {
    def run(): Unit =
      Try {
        logger.info("Starting Saving HighLighted Story...")
        InstaService.saveUserHighLightStories(enableS3Backup)
      } match {
        case Success(_) => logger.info("Finish Success HighLighted Story yuu are the best!!!")
        case Failure(exception) => logger.error("HighLighted Story error on: ", exception)
      }
  }
  timer.scheduleAtFixedRate(storyScheduler, 3.seconds.toMillis, 24.hours.toMillis)
  //timer.scheduleAtFixedRate(feedScheduler, 30.minutes.toMillis, 168.hours.toMillis)
  timer.scheduleAtFixedRate(highLightStoryScheduler, 15.minutes.toMillis, 168.hours.toMillis)
}
