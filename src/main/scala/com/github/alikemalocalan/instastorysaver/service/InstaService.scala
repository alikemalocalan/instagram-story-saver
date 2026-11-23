package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.*
import com.github.alikemalocalan.instastorysaver.service.web.InstagramWebClient
import com.instagram4j.web.Instagram4j
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * Orchestrates story/feed/highlight saving. All Instagram web protocol details (cookies, headers,
 * GraphQL doc ids, response parsing) live in [[InstagramWebClient]].
 */
object InstaService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private def webClient(using client: Instagram4j): InstagramWebClient =
    new InstagramWebClient(client)

  def fromSession(sessionId: String, csrfToken: String, username: String = ""): Instagram4j =
    LoginService.fromSession(sessionId, csrfToken, username)

  def fetchFollowingUsersFromApi()(using client: Instagram4j): List[User] =
    Try {
      val selfPk = client.session.split("[:%]").headOption.getOrElse("")
      logger.info(s"Fetching following users from Instagram for account ID $selfPk...")

      val paginator =
        new com.instagram4j.web.paginators.ProfilePaginator(client.session, client.crsf, selfPk, "following", null)
      val seenPks = scala.collection.mutable.Set[String]()
      val allUsers = scala.collection.mutable.ListBuffer[User]()

      var hasMore = true
      var pageCount = 0
      val maxPages = 200

      while (hasMore && paginator.hasNext && pageCount < maxPages) {
        pageCount += 1
        if (pageCount > 1) {
          Thread.sleep(400)
        }
        val pageList = paginator.next()
        if (pageList == null || pageList.isEmpty) {
          hasMore = false
        } else {
          val newProfiles = pageList.asScala.filter(p => p != null && p.pk != null && seenPks.add(p.pk)).toList
          if (newProfiles.isEmpty) {
            hasMore = false
          } else {
            logger.info(s"Loaded ${newProfiles.size} following users (page $pageCount, total: ${seenPks.size})...")
            newProfiles.foreach(p => allUsers += User(p.username, p.pk))
          }
        }
      }

      allUsers.toList
    } match {
      case Success(users) =>
        logger.info(s"Successfully fetched ${users.size} following users from Instagram.")
        users
      case Failure(ex) =>
        logger.error(s"Failed to fetch following users: ${ex.getMessage}", ex)
        List.empty[User]
    }

  def ensureFollowingCsvExists(destinationFolder: String)(using client: Instagram4j): Unit =
    CsvService.loadOrCreateFollowing(destinationFolder, () => fetchFollowingUsersFromApi())

  private def getFeedStories(destinationFolder: String = "")(using client: Instagram4j): List[UserStories] = {
    logger.info("Fetching active stories from reels tray...")

    val trayReels = webClient.fetchReelsTray()
    if (trayReels.isEmpty) {
      logger.info("No active reels returned in tray.")
      return List.empty[UserStories]
    }

    logger.info(s"Loaded ${trayReels.size} active reels from feed tray.")

    val targetReels = if (
      destinationFolder.nonEmpty && java.nio.file.Files.exists(
        java.nio.file.Paths.get(destinationFolder, "following.csv")
      )
    ) {
      val followedUsers =
        CsvService.loadOrCreateFollowing(destinationFolder, () => fetchFollowingUsersFromApi()).map(_.toUser)
      val followedUsernames = followedUsers.map(_.folderName.toLowerCase).toSet
      val followedIds = followedUsers.map(_.userId).toSet
      trayReels.filter { case (user, _) =>
        followedUsernames.contains(user.folderName.toLowerCase) || followedIds.contains(user.userId)
      }
    } else {
      trayReels
    }

    logger.info(s"Matching ${targetReels.size} followed users with active stories to download...")

    val userStoriesList = targetReels.flatMap { case (user, mediaIds) =>
      logger.info(s"Fetching active stories for @${user.folderName} (${mediaIds.size} in tray)...")
      Thread.sleep(250)
      val userMedias = webClient.fetchStoriesFromPage(user)
      val finalMedias = if (userMedias.nonEmpty) {
        userMedias
      } else {
        mediaIds.flatMap { mediaId =>
          Thread.sleep(100)
          webClient.fetchMediaInfo(mediaId)
        }
      }
      if (finalMedias.nonEmpty) {
        logger.info(s"Loaded ${finalMedias.size} stories for @${user.folderName}.")
        Some(UserStories(user, finalMedias))
      } else None
    }

    val totalMediaCount = userStoriesList.map(_.medias.size).sum
    logger.info(s"Loaded $totalMediaCount active stories across ${userStoriesList.size} users.")
    userStoriesList
  }

  def saveStories(
      destinationFolder: String,
      maxConcurrency: Int = 3
  )(using client: Instagram4j): Unit = {
    val activeUserStories = getFeedStories(destinationFolder)

    if (activeUserStories.nonEmpty) {
      val operations = activeUserStories.flatMap { userStories =>
        userStories.medias.map { media =>
          UrlOperation(media.url, userStories.user.folderName, MediaType.Stories)
        }
      }
      logger.info(
        s"Saving ${operations.size} active stories across ${activeUserStories.size} users in highest quality..."
      )
      FileService.saveLocally(operations, destinationFolder, maxConcurrency)
      logger.info(s"Successfully finished saving ${operations.size} stories.")
    } else {
      logger.info("No active stories found in feed tray.")
    }
  }

  def saveFeeds(
      destinationFolder: String,
      maxConcurrency: Int = 3,
      intervalDays: Int = 7
  )(using client: Instagram4j): Unit = {
    logger.info(s"Checking feed posts for followed users (${intervalDays}-day interval check via CSV)...")
    val records = CsvService.loadOrCreateFollowing(destinationFolder, () => fetchFollowingUsersFromApi())

    val dueRecords = records.filter(r => CsvService.isDueForCheck(r.lastFeedChecked, intervalDays))
    val skippedCount = records.size - dueRecords.size

    if (skippedCount > 0) {
      logger.info(s"Skipped $skippedCount users whose feeds were checked within the last $intervalDays days.")
    }

    if (dueRecords.isEmpty) {
      logger.info(s"All ${records.size} users were checked recently (< $intervalDays days). No feed requests needed.")
      return
    }

    logger.info(s"Processing ${dueRecords.size} users due for feed check...")
    var processedCount = 0
    var savedFeedsCount = 0

    dueRecords.foreach { record =>
      processedCount += 1
      if (processedCount > 1) {
        Thread.sleep(500)
      }
      val user = record.toUser
      logger.info(s"[$processedCount/${dueRecords.size}] Fetching feeds for @${user.folderName}...")
      val userFeeds = webClient.fetchPosts(user)

      if (userFeeds.medias.nonEmpty) {
        logger.info(s"Found ${userFeeds.medias.size} feed media for @${user.folderName}. Saving in highest quality...")
        val operations = userFeeds.medias.map { media =>
          UrlOperation(media.url, user.folderName, MediaType.Feeds)
        }
        FileService.saveLocally(operations, destinationFolder, maxConcurrency)
        savedFeedsCount += userFeeds.medias.size
      }

      record.lastFeedChecked = Some(System.currentTimeMillis())
      CsvService.saveToCsv(destinationFolder, records)
    }

    logger.info(
      s"Finished feed update run. Checked ${dueRecords.size} users, saved $savedFeedsCount new media files."
    )
  }

  def saveUserHighLightStories(
      destinationFolder: String,
      maxConcurrency: Int = 3,
      intervalDays: Int = 7
  )(using client: Instagram4j): Unit = {
    logger.info(s"Checking highlights for followed users (${intervalDays}-day interval check via CSV)...")
    val records = CsvService.loadOrCreateFollowing(destinationFolder, () => fetchFollowingUsersFromApi())

    val dueRecords = records.filter(r => CsvService.isDueForCheck(r.lastHighlightChecked, intervalDays))
    val skippedCount = records.size - dueRecords.size

    if (skippedCount > 0) {
      logger.info(s"Skipped $skippedCount users whose highlights were checked within the last $intervalDays days.")
    }

    if (dueRecords.isEmpty) {
      logger.info(
        s"All ${records.size} users have highlights checked within the last $intervalDays days. No requests needed."
      )
      return
    }

    logger.info(s"Processing ${dueRecords.size} users due for highlights check...")
    var processedCount = 0
    var savedHighlightsCount = 0

    dueRecords.foreach { record =>
      processedCount += 1
      if (processedCount > 1) {
        Thread.sleep(500)
      }
      val user = record.toUser
      logger.info(s"[$processedCount/${dueRecords.size}] Checking highlights for @${user.folderName}...")
      val userHighlights = webClient.fetchHighlights(user)

      if (userHighlights.medias.nonEmpty) {
        logger.info(
          s"Found ${userHighlights.medias.size} highlights for @${user.folderName}. Saving in highest quality..."
        )
        val operations = userHighlights.medias.map { media =>
          UrlOperation(media.url, s"${user.folderName}/${media.safeTitle}", MediaType.Highlights)
        }
        FileService.saveLocally(operations, destinationFolder, maxConcurrency)
        savedHighlightsCount += userHighlights.medias.size
      }

      record.lastHighlightChecked = Some(System.currentTimeMillis())
      CsvService.saveToCsv(destinationFolder, records)
    }

    logger.info(
      s"Finished highlights update run. Checked ${dueRecords.size} users, " +
        s"saved $savedHighlightsCount highlight media files."
    )
  }
}
