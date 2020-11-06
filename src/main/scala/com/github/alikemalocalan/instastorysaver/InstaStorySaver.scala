package com.github.alikemalocalan.instastorysaver

import akka.actor.ActorSystem
import com.github.alikemalocalan.instastorysaver.service.InstaService
import com.github.instagram4j.instagram4j.IGClient
import org.apache.commons.logging.{Log, LogFactory}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object InstaStorySaver extends App with Config {
  implicit val system: ActorSystem = ActorSystem("InstaStorySaver-Actor-System")
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  implicit val client: IGClient = InstaService
    .login(userName, passWord)

  val logger: Log = LogFactory.getLog(this.getClass)

  val saverScheduler: Runnable = () => {
    Try {
      logger.info("Starting Saving Stories...")
      InstaService.saveStoriesToS3
    } match {
      case Success(_) => logger.info("Finish Success, yuu are the best!!!")
      case Failure(exception) => logger.error("Feed error on: ", exception)
    }
  }

  system.scheduler.scheduleWithFixedDelay(5.seconds, 24.hour)(
    saverScheduler
  )
}
