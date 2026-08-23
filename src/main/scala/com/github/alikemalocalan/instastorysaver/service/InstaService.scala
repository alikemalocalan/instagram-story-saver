package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.*
import com.instagram4j.web.Instagram4j
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

object InstaService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def fromSession(sessionId: String, csrfToken: String, username: String = ""): Instagram4j =
    LoginService.fromSession(sessionId, csrfToken, username)

  def getFollowingUsers(using client: Instagram4j): LazyList[User] =
    Try {
      val selfPk = client.session.split("[:%]").headOption.getOrElse("")
      logger.info(s"Fetching following users for account ID $selfPk...")

      val paginator =
        new com.instagram4j.web.paginators.ProfilePaginator(client.session, client.crsf, selfPk, "following", null)
      val seenPks = scala.collection.mutable.Set[String]()

      paginator.asScala.to(LazyList).takeWhile(!_.isEmpty).flatMap { pageList =>
        val newProfiles = pageList.asScala.filter(p => seenPks.add(p.pk)).toList
        if (newProfiles.isEmpty) {
          LazyList.empty[User]
        } else {
          logger.info(s"Loaded ${newProfiles.size} following users (total seen: ${seenPks.size})...")
          newProfiles.map(p => User(p.username, p.pk)).to(LazyList)
        }
      }
    } match {
      case Success(users) => users
      case Failure(ex) =>
        logger.error(s"Failed to fetch following users: ${ex.getMessage}", ex)
        throw ex
    }

  private def extractMediaItems(items: android.org.json.JSONArray): List[Media] =
    (0 until items.length()).flatMap { j =>
      val item = items.getJSONObject(j)
      val videoUrl = Option(item.optJSONArray("video_versions"))
        .filter(!_.isEmpty)
        .map(_.getJSONObject(0).getString("url"))

      val imageUrl = Option(item.optJSONObject("image_versions2"))
        .flatMap(v => Option(v.optJSONArray("candidates")))
        .filter(!_.isEmpty)
        .map(_.getJSONObject(0).getString("url"))

      val downloadUrl = videoUrl.orElse(imageUrl)
      downloadUrl.map(url => Media(url, System.currentTimeMillis()))
    }.toList

  def getUserStories(user: User)(using client: Instagram4j): UserStories =
    Try {
      val vars = new android.org.json.JSONObject()
      vars.put("reel_ids_arr", new android.org.json.JSONArray(s"""["${user.userId}"]"""))

      val res =
        com.instagram4j.web.Utils.postGraphQL(client.session, com.instagram4j.web.Constants.GraphQl.USER_STORY, vars)
      val reels =
        Option(res.optJSONObject("xdt_api__v1__feed__reels_media")).flatMap(o => Option(o.optJSONArray("reels_media")))
      val stories = reels
        .filter(!_.isEmpty)
        .map { reelArray =>
          val reel = reelArray.getJSONObject(0)
          Option(reel.optJSONArray("items")).map(extractMediaItems).getOrElse(List.empty)
        }
        .getOrElse(List.empty)

      UserStories(user, stories)
    } match {
      case Success(userStories) => userStories
      case Failure(ex) =>
        logger.warn(s"Could not get stories for user @${user.folderName}: ${ex.getMessage}")
        UserStories(user, List.empty)
    }

  def getUserHighlights(user: User)(using client: Instagram4j): UserHighLightStoryMedias =
    Try {
      val variables = new android.org.json.JSONObject()
      variables.put("user_id", user.userId)

      val response = com.instagram4j.web.Utils
        .postGraphQL(client.session, com.instagram4j.web.Constants.GraphQl.HIGHLIGHTS, variables)
      val edges = Option(response.optJSONObject("highlights")).flatMap(h => Option(h.optJSONArray("edges")))
      val highlights = edges
        .filter(!_.isEmpty)
        .map { edgeArray =>
          val ids = (0 until edgeArray.length()).map { i =>
            edgeArray.getJSONObject(i).getJSONObject("node").getString("id")
          }.toArray
          Option(com.instagram4j.web.endpoints.profile.Story.getActualStory(ids, client.session))
            .map(
              _.asScala.toList.map(s => HighLightStoryMedia(s.download_url, System.currentTimeMillis(), "highlight"))
            )
            .getOrElse(List.empty)
        }
        .getOrElse(List.empty)

      UserHighLightStoryMedias(user, highlights)
    } match {
      case Success(userHighlights) => userHighlights
      case Failure(ex) =>
        logger.warn(s"Could not get highlights for user @${user.folderName}: ${ex.getMessage}")
        UserHighLightStoryMedias(user, List.empty)
    }

  def getUserPosts(user: User, maxPages: Int = 1)(using client: Instagram4j): UserFeedMedias =
    Try {
      val paginator =
        new com.instagram4j.web.paginators.PostPaginator(client.session, client.crsf, user.folderName, null)
      val medias = paginator.asScala
        .take(maxPages)
        .flatMap { postList =>
          postList.asScala.flatMap { post =>
            Option(post.download_url)
              .map(_.map(url => Media(url, System.currentTimeMillis())).toSeq)
              .getOrElse(Seq.empty)
          }
        }
        .toList

      UserFeedMedias(user, medias)
    } match {
      case Success(userFeeds) => userFeeds
      case Failure(ex) =>
        logger.warn(s"Could not get posts for user @${user.folderName}: ${ex.getMessage}")
        UserFeedMedias(user, List.empty)
    }

  def getFeedStories()(using client: Instagram4j): List[UserStories] =
    Try {
      logger.info("Fetching active stories tray from Instagram...")
      val json = com.instagram4j.web.Utils.getCall(com.instagram4j.web.Constants.Endpoints.STORIES, client.session)
      val tray = Option(json.optJSONArray("tray")).getOrElse(new android.org.json.JSONArray())

      if (tray.isEmpty) {
        logger.info("Reels tray is empty - no followed users currently have active stories.")
        List.empty[UserStories]
      } else {
        logger.info(s"Found ${tray.length()} users with active story reels in tray. Fetching story media...")

        val userMap: Map[String, String] = (0 until tray.length()).flatMap { i =>
          val trayItem = tray.getJSONObject(i)
          val userObj = Option(trayItem.optJSONObject("user"))
          val pk = userObj.map(_.optString("pk", "")).filter(_.nonEmpty).getOrElse(trayItem.optString("id", ""))
          val username = userObj.map(_.optString("username", "")).filter(_.nonEmpty).getOrElse(pk)
          if (pk.nonEmpty) Some(pk -> username) else None
        }.toMap

        val allUserIds = (0 until tray.length())
          .flatMap { i =>
            val trayItem = tray.getJSONObject(i)
            val userObj = Option(trayItem.optJSONObject("user"))
            val pk = userObj.map(_.optString("pk", "")).filter(_.nonEmpty)
            val id = Option(trayItem.optString("id", "")).filter(_.nonEmpty)
            pk.orElse(id)
          }
          .distinct
          .toList

        // Query GraphQL for all users in tray in batches of 15 to get their complete reel (both seen & unseen)
        val userStoriesList = allUserIds
          .grouped(15)
          .flatMap { batchIds =>
            Try {
              val reelIdsArray = new android.org.json.JSONArray()
              batchIds.foreach(reelIdsArray.put)

              val vars = new android.org.json.JSONObject()
              vars.put("initial_reel_id", batchIds.head)
              vars.put("reel_ids", reelIdsArray)
              vars.put("first", 200) // Fetch all active stories per user batch

              val response =
                com.instagram4j.web.Utils.postGraphQL(client.session, com.instagram4j.web.Constants.GraphQl.STORY, vars)
              val edges = Option(response.optJSONObject("xdt_api__v1__feed__reels_media__connection"))
                .flatMap(conn => Option(conn.optJSONArray("edges")))
                .getOrElse(new android.org.json.JSONArray())

              (0 until edges.length()).flatMap { i =>
                val node = edges.getJSONObject(i).getJSONObject("node")
                val nodeUser = Option(node.optJSONObject("user"))
                val userId = nodeUser.map(_.optString("pk", "")).filter(_.nonEmpty).getOrElse(node.optString("id", ""))
                val username = nodeUser
                  .map(_.optString("username", ""))
                  .filter(_.nonEmpty)
                  .getOrElse(userMap.getOrElse(userId, userId))
                val user = User(username, userId)

                val items = Option(node.optJSONArray("items")).getOrElse(new android.org.json.JSONArray())
                val medias = extractMediaItems(items)
                if (medias.nonEmpty) Some(UserStories(user, medias)) else None
              }
            } match {
              case Success(batchStories) => batchStories
              case Failure(ex) =>
                logger.warn(s"Error querying story batch: ${ex.getMessage}")
                List.empty[UserStories]
            }
          }
          .toList

        userStoriesList
      }
    } match {
      case Success(stories) =>
        logger.info(s"Loaded ${stories.map(_.medias.size).sum} stories across ${stories.size} users.")
        stories
      case Failure(ex) =>
        logger.error(s"Failed to fetch feed stories: ${ex.getMessage}", ex)
        List.empty[UserStories]
    }

  def saveStories(
      destinationFolder: String,
      maxConcurrency: Int = 3,
      requestDelayMs: Long = 350L
  )(using client: Instagram4j): Unit = {
    val activeUserStories = getFeedStories()

    if (activeUserStories.nonEmpty) {
      val operations = activeUserStories.flatMap { userStories =>
        userStories.medias.map { media =>
          UrlOperation(media.url, userStories.user.folderName, MediaType.Stories)
        }
      }
      logger.info(s"Saving ${operations.size} active stories across ${activeUserStories.size} users...")
      FileService.saveLocally(operations, destinationFolder, maxConcurrency)
      logger.info(s"Successfully finished saving ${operations.size} stories.")
    } else {
      logger.info("No active stories found in feed tray.")
    }
  }

  def saveFeeds(
      destinationFolder: String,
      maxConcurrency: Int = 3,
      requestDelayMs: Long = 350L
  )(using client: Instagram4j): Unit = {
    logger.info("Starting feed posts processing for following users...")
    val users = getFollowingUsers

    var processedCount = 0
    var savedFeedsCount = 0

    users.foreach { user =>
      processedCount += 1
      if (requestDelayMs > 0 && processedCount > 1) Thread.sleep(requestDelayMs)
      logger.info(s"[$processedCount] Checking feeds for @${user.folderName}...")
      val userFeeds = getUserPosts(user)
      if (userFeeds.medias.nonEmpty) {
        logger.info(s"Found ${userFeeds.medias.size} feed media for @${user.folderName}. Saving...")
        val operations = userFeeds.medias.map { media =>
          UrlOperation(media.url, user.folderName, MediaType.Feeds)
        }
        FileService.saveLocally(operations, destinationFolder, maxConcurrency)
        savedFeedsCount += userFeeds.medias.size
      }
    }
    logger.info(s"Finished saving feeds. Checked $processedCount users, saved $savedFeedsCount media files.")
  }

  def saveUserHighLightStories(
      destinationFolder: String,
      maxConcurrency: Int = 3,
      requestDelayMs: Long = 350L
  )(using client: Instagram4j): Unit = {
    logger.info("Starting highlights processing for following users...")
    val users = getFollowingUsers

    var processedCount = 0
    var savedHighlightsCount = 0

    users.foreach { user =>
      processedCount += 1
      if (requestDelayMs > 0 && processedCount > 1) Thread.sleep(requestDelayMs)
      logger.info(s"[$processedCount] Checking highlights for @${user.folderName}...")
      val userHighlights = getUserHighlights(user)
      if (userHighlights.medias.nonEmpty) {
        logger.info(s"Found ${userHighlights.medias.size} highlights for @${user.folderName}. Saving...")
        val operations = userHighlights.medias.map { media =>
          UrlOperation(media.url, s"${user.folderName}/${media.safeTitle}", MediaType.Highlights)
        }
        FileService.saveLocally(operations, destinationFolder, maxConcurrency)
        savedHighlightsCount += userHighlights.medias.size
      }
    }
    logger.info(
      s"Finished saving highlights. Checked $processedCount users, saved $savedHighlightsCount highlight media files."
    )
  }
}
