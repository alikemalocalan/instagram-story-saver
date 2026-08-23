package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.*
import com.instagram4j.web.Instagram4j
import org.apache.commons.logging.{Log, LogFactory}

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

object InstaService {
  private val logger: Log = LogFactory.getLog(getClass)

  def login(userName: String, password: String): Instagram4j =
    LoginService.login(userName, password)

  def fromSession(sessionId: String, csrfToken: String, username: String = ""): Instagram4j =
    LoginService.fromSession(sessionId, csrfToken, username)


  def getFollowingUsers(using client: Instagram4j): LazyList[User] =
    Try {
      val selfPk = client.session.split("[:%]").headOption.getOrElse("")
      logger.info(s"Fetching following users for account ID $selfPk...")

      val paginator = new com.instagram4j.web.paginators.ProfilePaginator(client.session, client.crsf, selfPk, "following", null)
      paginator.asScala.to(LazyList).zipWithIndex.flatMap { case (pageList, pageIdx) =>
        logger.info(s"Loaded followings page ${pageIdx + 1} (${pageList.size} users)...")
        pageList.asScala.map(profile => User(profile.username, profile.pk))
      }
    } match {
      case Success(users) => users
      case Failure(ex) =>
        logger.error(s"Failed to fetch following users: ${ex.getMessage}", ex)
        throw ex
    }

  def getUserStories(user: User)(using client: Instagram4j): UserStories =
    Try {
      val vars = new android.org.json.JSONObject()
      vars.put("reel_ids_arr", new android.org.json.JSONArray(s"""["${user.userId}"]"""))

      val res = com.instagram4j.web.Utils.postGraphQL(client.session, com.instagram4j.web.Constants.GraphQl.USER_STORY, vars)
      val reels = Option(res.optJSONObject("xdt_api__v1__feed__reels_media")).flatMap(o => Option(o.optJSONArray("reels_media")))
      val stories = reels.filter(!_.isEmpty).map { reelArray =>
        val reel = reelArray.getJSONObject(0)
        Option(reel.optJSONArray("items")).map { items =>
          (0 until items.length()).flatMap { j =>
            val item = items.getJSONObject(j)
            val isVideo = item.optInt("media_type") == 2
            val downloadUrl = if (isVideo) {
              Option(item.optJSONArray("video_versions")).filter(!_.isEmpty).map(_.getJSONObject(0).getString("url"))
            } else {
              Option(item.optJSONObject("image_versions2"))
                .flatMap(v => Option(v.optJSONArray("candidates")))
                .filter(!_.isEmpty)
                .map(_.getJSONObject(0).getString("url"))
            }
            downloadUrl.map(url => Media(url, System.currentTimeMillis()))
          }.toList
        }.getOrElse(List.empty)
      }.getOrElse(List.empty)

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

      val response = com.instagram4j.web.Utils.postGraphQL(client.session, com.instagram4j.web.Constants.GraphQl.HIGHLIGHTS, variables)
      val edges = Option(response.optJSONObject("highlights")).flatMap(h => Option(h.optJSONArray("edges")))
      val highlights = edges.filter(!_.isEmpty).map { edgeArray =>
        val ids = (0 until edgeArray.length()).map { i =>
          edgeArray.getJSONObject(i).getJSONObject("node").getString("id")
        }.toArray
        Option(com.instagram4j.web.endpoints.profile.Story.getActualStory(ids, client.session))
          .map(_.asScala.toList.map(s => HighLightStoryMedia(s.download_url, System.currentTimeMillis(), "highlight")))
          .getOrElse(List.empty)
      }.getOrElse(List.empty)

      UserHighLightStoryMedias(user, highlights)
    } match {
      case Success(userHighlights) => userHighlights
      case Failure(ex) =>
        logger.warn(s"Could not get highlights for user @${user.folderName}: ${ex.getMessage}")
        UserHighLightStoryMedias(user, List.empty)
    }

  def getUserPosts(user: User, maxPages: Int = 1)(using client: Instagram4j): UserFeedMedias =
    Try {
      val paginator = new com.instagram4j.web.paginators.PostPaginator(client.session, client.crsf, user.folderName, null)
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


  def getFeedStories()(using client: Instagram4j): LazyList[UserStories] =
    Try {
      logger.info("Fetching feed story tray from Instagram...")
      Option(client.getFeedStories)
        .map(_.asScala.to(LazyList))
        .getOrElse(LazyList.empty)
        .flatMap { storyArray =>
          val storiesList = storyArray.toList
          storiesList.headOption.map { first =>
            val user   = User(first.username, first.userID)
            val medias = storiesList.map(s => Media(s.download_url, System.currentTimeMillis()))
            UserStories(user, medias)
          }
        }
    } match {
      case Success(stories) => stories
      case Failure(ex) =>
        logger.error(s"Failed to fetch feed stories: ${ex.getMessage}")
        LazyList.empty[UserStories]
    }

  def saveStories(
      destinationFolder: String,
      maxConcurrency: Int = 3,
      requestDelayMs: Long = 350L
  )(using client: Instagram4j): Unit = {
    logger.info("Fetching following users to save stories...")
    val users = getFollowingUsers

    var processedCount = 0
    var savedStoriesCount = 0

    users.foreach { user =>
      processedCount += 1
      if (requestDelayMs > 0 && processedCount > 1) Thread.sleep(requestDelayMs)
      logger.info(s"[$processedCount] Checking stories for @${user.folderName}...")
      val userStories = getUserStories(user)
      if (userStories.medias.nonEmpty) {
        logger.info(s"Found ${userStories.medias.size} stories for @${user.folderName}. Saving...")
        val operations = userStories.medias.map { media =>
          UrlOperation(media.url, user.folderName, MediaType.Stories)
        }
        FileService.saveLocally(operations, destinationFolder, maxConcurrency)
        savedStoriesCount += userStories.medias.size
      }
    }
    logger.info(s"Finished saving stories. Checked $processedCount users, saved $savedStoriesCount stories.")
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
    logger.info(s"Finished saving highlights. Checked $processedCount users, saved $savedHighlightsCount highlight media files.")
  }
}



