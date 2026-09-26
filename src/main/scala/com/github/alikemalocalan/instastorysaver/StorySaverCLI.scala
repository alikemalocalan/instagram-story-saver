package com.github.alikemalocalan.instastorysaver

import com.github.alikemalocalan.instastorysaver.service.{FileService, InstaService}
import com.instagram4j.web.Instagram4j
import mainargs.{ParserForMethods, arg, main}
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import scala.util.{Failure, Success, Try}

object StorySaverCLI {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private def createInstagramClient(
      username: String,
      sessionId: String,
      csrfToken: String
  ): Instagram4j =
    if (sessionId.trim.nonEmpty && csrfToken.trim.nonEmpty) {
      InstaService.fromSession(sessionId.trim, csrfToken.trim, username)
    } else {
      throw new IllegalArgumentException("Both --session-id and --csrf-token must be provided.")
    }

  @main
  def run(
      @arg(name = "username", doc = "Instagram username")
      username: String,
      @arg(name = "session-id", doc = "Instagram sessionid cookie value (required)")
      sessionId: String,
      @arg(name = "csrf-token", doc = "Instagram csrftoken cookie value (required)")
      csrfToken: String,
      @arg(name = "destination-folder", doc = "Destination folder path for downloaded media")
      destinationFolder: String = s"${sys.props.getOrElse("user.home", ".")}${File.separator}instagram-stories",
      @arg(name = "include-highlights", doc = "Also download profile story highlights")
      includeHighlights: Boolean = false,
      @arg(name = "include-feeds", doc = "Also download recent user feed posts")
      includeFeeds: Boolean = false,
      @arg(name = "concurrency", doc = "Maximum concurrent downloads (default: 3)")
      concurrency: Int = 3,
      @arg(name = "check-interval-days", doc = "Days before re-checking feeds/highlights via following.csv (default: 7)")
      checkIntervalDays: Int = 7
  ): Unit = {
    try {
      Try {
        val clientInstance = createInstagramClient(username, sessionId, csrfToken)
        given Instagram4j = clientInstance

        // Always ensure following.csv exists so followed users list is available for stories, highlights, and feeds
        InstaService.ensureFollowingCsvExists(destinationFolder)

        logger.info(s"Starting daily story save pipeline for @${username}...")
        InstaService.saveStories(destinationFolder, concurrency)

        if (includeHighlights) {
          logger.info("Processing followed users' story highlights via following.csv...")
          InstaService.saveUserHighLightStories(
            destinationFolder = destinationFolder,
            maxConcurrency = concurrency,
            intervalDays = checkIntervalDays
          )
        }

        if (includeFeeds) {
          logger.info("Processing followed users' feed posts via following.csv...")
          InstaService.saveFeeds(
            destinationFolder = destinationFolder,
            maxConcurrency = concurrency,
            intervalDays = checkIntervalDays
          )
        }
      } match {
        case Success(_) =>
          logger.info("Successfully finished saving Instagram media.")
        case Failure(ex: IllegalStateException) =>
          logger.error(s"Execution aborted: ${ex.getMessage}")
        case Failure(ex) =>
          logger.error(s"Error saving media: ${ex.getMessage}", ex)
      }
    } finally {
      FileService.shutdown()
    }
  }

  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args.toIndexedSeq)
  }
}
