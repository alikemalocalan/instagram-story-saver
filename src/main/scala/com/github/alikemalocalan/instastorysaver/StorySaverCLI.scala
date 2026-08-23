package com.github.alikemalocalan.instastorysaver

import com.github.alikemalocalan.instastorysaver.service.{FileService, InstaService}
import com.instagram4j.web.Instagram4j
import mainargs.{ParserForMethods, arg, main}
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import scala.util.{Failure, Success, Try}

object StorySaverCLI {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

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
      destinationFolder: String = s"${sys.props.getOrElse("user.home", ".")}${File.separator}instagram-stories",
      @arg(name = "concurrency", doc = "Maximum concurrent downloads (default: 3)")
      concurrency: Int = 3,
      @arg(name = "delay-ms", doc = "Request delay in milliseconds between users (default: 350)")
      delayMs: Long = 350L
  ): Unit = {
    try {
      Try {
        val clientInstance: Instagram4j =
          if (sessionId.trim.nonEmpty && csrfToken.trim.nonEmpty) {
            InstaService.fromSession(sessionId.trim, csrfToken.trim, username)
          } else if (password.nonEmpty) {
            InstaService.login(username, password)
          } else {
            throw new IllegalArgumentException("Either password or both session-id and csrf-token must be provided.")
          }

        given client: Instagram4j = clientInstance

        logger.info(s"Starting story save pipeline for @${username}...")
        InstaService.saveStories(destinationFolder, concurrency, delayMs)
      } match {
        case Success(_)  => logger.info("Successfully finished saving stories.")
        case Failure(ex) => logger.error(s"Error saving stories: ${ex.getMessage}", ex)
      }
    } finally {
      com.github.alikemalocalan.instastorysaver.service.FileService.shutdown()
    }
  }

  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args.toIndexedSeq)
  }
}

