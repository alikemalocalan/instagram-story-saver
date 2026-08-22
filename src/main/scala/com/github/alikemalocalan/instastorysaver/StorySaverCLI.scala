package com.github.alikemalocalan.instastorysaver

import com.github.alikemalocalan.instastorysaver.service.InstaService
import com.instagram4j.web.Instagram4j
import mainargs.{ParserForMethods, arg, main}
import org.apache.commons.io.FileUtils
import org.apache.commons.logging.{Log, LogFactory}

import java.io.File
import scala.util.{Failure, Success, Try}

object StorySaverCLI {
  private val logger: Log = LogFactory.getLog(getClass)

  @main
  def run(
      @arg(name = "username", doc = "Instagram username")
      username: String,
      @arg(name = "password", doc = "Instagram password (optional if session-id and csrf-token provided)")
      password: String = "",
      @arg(name = "session-id", doc = "Instagram sessionid cookie value (recommended)")
      sessionId: String = "",
      @arg(name = "csrf-token", doc = "Instagram csrftoken cookie value (recommended)")
      csrfToken: String = "",
      @arg(name = "destination-folder", doc = "Destination folder path for downloaded stories")
      destinationFolder: String = s"${FileUtils.getUserDirectory.getAbsoluteFile}${File.separator}instagram-stories"
  ): Unit = {
    Try {
      given client: Instagram4j =
        if (sessionId.trim.nonEmpty && csrfToken.trim.nonEmpty) {
          InstaService.fromSession(sessionId.trim, csrfToken.trim, username)
        } else if (password.nonEmpty) {
          InstaService.login(username, password)
        } else {
          throw new IllegalArgumentException("Either password or both session-id and csrf-token must be provided.")
        }

      logger.info("Starting saving stories...")
      InstaService.saveStories(destinationFolder)
    } match {
      case Success(_)  => logger.info("Successfully finished saving stories.")
      case Failure(ex) => logger.error(s"Error saving stories: ${ex.getMessage}", ex)
    }
  }

  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args.toIndexedSeq)
  }
}

