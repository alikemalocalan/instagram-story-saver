package com.github.alikemalocalan.instastorysaver

import com.github.alikemalocalan.instastorysaver.service.InstaService
import com.github.instagram4j.instagram4j.IGClient
import org.apache.commons.io.FileUtils
import org.apache.commons.logging.{Log, LogFactory}
import org.rogach.scallop._

import scala.util.{Failure, Success, Try}

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val username: ScallopOption[String] = opt[String](required = true)
  val password: ScallopOption[String] = opt[String](required = true)
  val destinationFolder: ScallopOption[String] =
    opt[String](default = Some(s"${FileUtils.getUserDirectory.getAbsoluteFile}/instagram-stories"), required = false)
  verify()
}

object StorySaverCLI {
  val logger: Log = LogFactory.getLog(this.getClass)

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)
    Try {
      implicit val client: IGClient = InstaService
        .login(conf.username(), conf.password())

      logger.info("Starting Saving Stories...")
      InstaService.saveStories(enableS3 = false, Some(conf.destinationFolder()))
    } match {
      case Success(_) => logger.info("Finish Success Story, you are the best!!!")
      case Failure(exception) => logger.error("Story error on: ", exception)
    }

  }

}
