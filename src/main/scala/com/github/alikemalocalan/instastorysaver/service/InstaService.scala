package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.*
import com.instagram4j.web.Instagram4j
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try, Using}

object InstaService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

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

  def ensureFollowingCsvExists(destinationFolder: String)(using client: Instagram4j): Unit = {
    CsvService.loadOrCreateFollowing(destinationFolder, () => fetchFollowingUsersFromApi())
  }

  private def isValidHttpUrl(url: String): Boolean =
    url != null && (url.trim.startsWith("http://") || url.trim.startsWith("https://"))

  private def extractBestVideoUrl(item: android.org.json.JSONObject): Option[String] =
    Option(item.optJSONArray("video_versions"))
      .filter(!_.isEmpty)
      .flatMap { vArray =>
        (0 until vArray.length())
          .map(vArray.getJSONObject)
          .sortBy(v => -(v.optInt("width", 0).toLong * v.optInt("height", 0).toLong * 1000L + v.optInt("bandwidth_bps", 0)))
          .headOption
          .flatMap(v => Option(v.optString("url", "")).filter(isValidHttpUrl))
      }
      .orElse {
        Option(item.optJSONArray("video_resources"))
          .filter(!_.isEmpty)
          .flatMap { vrArray =>
            (0 until vrArray.length())
              .map(vrArray.getJSONObject)
              .sortBy(vr => -(vr.optInt("config_width", 0).toLong * vr.optInt("config_height", 0).toLong))
              .headOption
              .flatMap(vr => Option(vr.optString("src", "")).orElse(Option(vr.optString("url", ""))).filter(isValidHttpUrl))
          }
      }

  private def extractBestImageUrl(item: android.org.json.JSONObject): Option[String] =
    Option(item.optJSONObject("image_versions2"))
      .flatMap(v => Option(v.optJSONArray("candidates")))
      .filter(!_.isEmpty)
      .flatMap { cArray =>
        (0 until cArray.length())
          .map(cArray.getJSONObject)
          .sortBy(c => -(c.optInt("width", 0).toLong * c.optInt("height", 0).toLong))
          .headOption
          .flatMap(c => Option(c.optString("url", "")).filter(isValidHttpUrl))
      }
      .orElse {
        Option(item.optJSONArray("display_resources"))
          .filter(!_.isEmpty)
          .flatMap { drArray =>
            (0 until drArray.length())
              .map(drArray.getJSONObject)
              .sortBy(dr => -(dr.optInt("config_width", 0).toLong * dr.optInt("config_height", 0).toLong))
              .headOption
              .flatMap(dr => Option(dr.optString("src", "")).orElse(Option(dr.optString("url", ""))).filter(isValidHttpUrl))
          }
      }
      .orElse(Option(item.optString("display_url", "")).filter(isValidHttpUrl))

  private def extractCarouselArray(item: android.org.json.JSONObject): Option[android.org.json.JSONArray] =
    Option(item.optJSONArray("carousel_media"))
      .orElse(
        Option(item.optJSONObject("edge_sidecar_to_children")).flatMap(o =>
          Option(o.optJSONArray("edges")).map { edges =>
            val arr = new android.org.json.JSONArray()
            for (k <- 0 until edges.length()) {
              val node = edges.getJSONObject(k).optJSONObject("node")
              if (node != null) arr.put(node)
            }
            arr
          }
        )
      )

  private def extractMediaItems(items: android.org.json.JSONArray): List[Media] =
    (0 until items.length()).flatMap { j =>
      val item = items.getJSONObject(j)
      val carousel = extractCarouselArray(item)

      if (carousel.exists(!_.isEmpty)) {
        extractMediaItems(carousel.get)
      } else {
        val downloadUrl = extractBestVideoUrl(item).orElse(extractBestImageUrl(item)).filter(isValidHttpUrl)
        val takenAt = item.optLong("taken_at", System.currentTimeMillis() / 1000L) * 1000L
        downloadUrl.map(url => Media(url.trim, takenAt)).toList
      }
    }.toList

  /**
   * ===============================================================================================
   * ⚠️ WORKAROUND: instagram4j Library Limitations / Manual GraphQL Query
   * ===============================================================================================
   * Why can't we use instagram4j's built-in methods (`Utils.postGraphQLWeb` / `Utils.getCall`) directly?
   *
   * 1. [Missing Header - x-ig-app-id]:
   *    instagram4j (web:3.0) does not include the required `x-ig-app-id` (936619743392459) header
   *    in GraphQL requests. The Instagram Web API rejects or treats calls without this header
   *    as unauthenticated.
   *
   * 2. [Mobile API vs. Web Session Cookie Incompatibility]:
   *    instagram4j's story endpoints target `https://i.instagram.com/api/v1/feed/...` (Mobile API).
   *    When browser-issued web session cookies are sent to the mobile gateway, Instagram deletes
   *    the cookie (`Set-Cookie: sessionid=""`) and returns empty reels.
   *    Therefore, requests must go to the official web endpoint `https://www.instagram.com/graphql/query`.
   *
   * 3. [Deprecated / Retired Query IDs]:
   *    instagram4j's `Constants.GraphQl.USER_STORY` (9551577494970907) is retired by Meta
   *    and returns code 1675002 "Incorrect Query / The query provided was invalid".
   *    The active, working query ID is `Constants.GraphQl.STORY` (9558504827580036).
   *
   * 4. [Required Web Security Headers]:
   *    Instagram Web GraphQL requires `x-csrftoken`, `origin: https://www.instagram.com`,
   *    and `referer: https://www.instagram.com/` for CSRF protection and proper resolution.
   *
   * ℹ️ If a future version of instagram4j adds these headers and updates doc_id constants,
   * this manual method can be replaced by `Utils.postGraphQLWeb()`.
   * ===============================================================================================
   */

  def queryGraphQLWeb(
      docId: String,
      variables: android.org.json.JSONObject
  )(using client: Instagram4j): android.org.json.JSONObject = {
    val selfPk = client.session.split("[:%]").headOption.getOrElse("")
    val cookie =
      s"sessionid=${client.session}; ds_user_id=$selfPk${if (client.crsf != null && client.crsf.nonEmpty) s"; csrftoken=${client.crsf}" else ""}"

    val formBody = new okhttp3.FormBody.Builder()
      .add("doc_id", docId)
      .add("variables", variables.toString)
      .build()

    val req = new okhttp3.Request.Builder()
      .url("https://www.instagram.com/graphql/query")
      .header("authority", "www.instagram.com")
      .header("accept", "*/*")
      .header("accept-language", HttpConstants.AcceptLanguage)
      .header("origin", "https://www.instagram.com")
      .header("referer", "https://www.instagram.com/")
      .header("sec-ch-ua", HttpConstants.SecChUa)
      .header("sec-ch-ua-mobile", HttpConstants.SecChUaMobile)
      .header("sec-ch-ua-platform", HttpConstants.SecChUaPlatform)
      .header("sec-fetch-dest", "empty")
      .header("sec-fetch-mode", "cors")
      .header("sec-fetch-site", "same-origin")
      .header("user-agent", HttpConstants.UserAgent)
      .header("x-asbd-id", HttpConstants.AsbdId)
      .header("x-ig-app-id", HttpConstants.AppId)
      .header("x-ig-www-claim", "0")
      .header("x-requested-with", "XMLHttpRequest")
      .header("x-csrftoken", if (client.crsf != null) client.crsf else "")
      .header("cookie", cookie)
      .post(formBody)
      .build()

    Using.resource(com.instagram4j.web.Utils.client.newCall(req).execute()) { response =>
      val body = Option(response.body()).map(_.string()).getOrElse("")
      if (!response.isSuccessful) {
        throw new java.io.IOException(s"GraphQL request failed with HTTP ${response.code()}: $body")
      }
      val json = new android.org.json.JSONObject(body)
      if (json.has("errors")) {
        val errors = json.getJSONArray("errors")
        val errorMsg =
          if (errors.length() > 0) errors.getJSONObject(0).optString("message", "Unknown error") else "GraphQL error"
        throw new java.io.IOException(s"Instagram GraphQL error: $errorMsg (response: $body)")
      }
      json
    }
  }

  private def parseStoriesFromGraphQLResponse(
      response: android.org.json.JSONObject,
      userMap: Map[String, String]
  ): List[UserStories] = {
    val data = Option(response.optJSONObject("data")).getOrElse(response)

    // Check if Instagram treated the request as unauthenticated
    Option(data.optJSONObject("xdt_viewer")).foreach { viewer =>
      if (viewer.isNull("user") || viewer.optJSONObject("user") == null) {
        val msg =
          """
            |================================================================================
            |❌ INSTAGRAM SESSION IS EXPIRED OR INVALID!
            |
            |Instagram GraphQL responded as unauthenticated viewer (xdt_viewer.user is null).
            |Please refresh your --session-id and --csrf-token values from your browser.
            |================================================================================
            |""".stripMargin
        logger.error(msg)
        throw new IllegalStateException("Instagram session is expired or invalid. Please refresh cookies.")
      }
    }

    val edges = Option(data.optJSONObject("xdt_api__v1__feed__reels_media__connection"))
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
    }.toList
  }

  def getUserStories(user: User)(using client: Instagram4j): UserStories =
    Try {
      val vars = new android.org.json.JSONObject()
      vars.put("initial_reel_id", user.userId)
      val arr = new android.org.json.JSONArray()
      arr.put(user.userId)
      vars.put("reel_ids", arr)
      vars.put("first", 10)

      val res = queryGraphQLWeb(com.instagram4j.web.Constants.GraphQl.STORY, vars)
      val stories = parseStoriesFromGraphQLResponse(res, Map(user.userId -> user.folderName))
      stories.headOption.getOrElse(UserStories(user, List.empty))
    } match {
      case Success(userStories) => userStories
      case Failure(ex) =>
        logger.warn(s"Could not get stories for user @${user.folderName}: ${ex.getMessage}")
        UserStories(user, List.empty)
    }

  private def getUserHighlights(user: User)(using client: Instagram4j): UserHighLightStoryMedias =
    Try {
      val variables = new android.org.json.JSONObject()
      variables.put("user_id", user.userId)

      val response = queryGraphQLWeb(com.instagram4j.web.Constants.GraphQl.HIGHLIGHTS, variables)
      val edges = Option(response.optJSONObject("data"))
        .orElse(Some(response))
        .flatMap(d => Option(d.optJSONObject("highlights")))
        .flatMap(h => Option(h.optJSONArray("edges")))

      val highlights = edges
        .filter(!_.isEmpty)
        .map { edgeArray =>
          val ids = (0 until edgeArray.length()).map { i =>
            edgeArray.getJSONObject(i).getJSONObject("node").getString("id")
          }.toList

          if (ids.isEmpty) {
            List.empty[HighLightStoryMedia]
          } else {
            val vars = new android.org.json.JSONObject()
            vars.put("initial_reel_id", ids.head)
            val reelIdsArray = new android.org.json.JSONArray()
            ids.foreach(reelIdsArray.put)
            vars.put("reel_ids", reelIdsArray)
            vars.put("first", math.max(ids.size, 10))

            val res = queryGraphQLWeb(com.instagram4j.web.Constants.GraphQl.STORY, vars)
            val reelEdges = Option(res.optJSONObject("data"))
              .orElse(Some(res))
              .flatMap(d => Option(d.optJSONObject("xdt_api__v1__feed__reels_media__connection")))
              .flatMap(c => Option(c.optJSONArray("edges")))
              .getOrElse(new android.org.json.JSONArray())

            (0 until reelEdges.length()).flatMap { j =>
              val node = reelEdges.getJSONObject(j).getJSONObject("node")
              val title = Option(node.optString("title")).filter(_.nonEmpty).getOrElse("highlight")
              val items = Option(node.optJSONArray("items")).getOrElse(new android.org.json.JSONArray())
              extractMediaItems(items).map { m =>
                HighLightStoryMedia(m.url, m.takenOn, title)
              }
            }.toList
          }
        }
        .getOrElse(List.empty)

      UserHighLightStoryMedias(user, highlights)
    } match {
      case Success(userHighlights) => userHighlights
      case Failure(ex) =>
        logger.warn(s"Could not get highlights for user @${user.folderName}: ${ex.getMessage}")
        UserHighLightStoryMedias(user, List.empty)
    }

  private def getUserPosts(user: User, maxPages: Int = 1)(using client: Instagram4j): UserFeedMedias =
    Try {
      val paginator =
        new com.instagram4j.web.paginators.PostPaginator(client.session, client.crsf, user.folderName, null)
      val medias = paginator.asScala
        .take(maxPages)
        .flatMap { postList =>
          postList.asScala.flatMap { post =>
            Option(post.download_url)
              .map(
                _.filter(isValidHttpUrl)
                  .map(url => Media(url.trim, System.currentTimeMillis()))
                  .toSeq
              )
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

  private def getFeedStories(destinationFolder: String = "")(using client: Instagram4j): List[UserStories] = {
    logger.info("Fetching active stories for followed users...")

    val followedUsers: List[User] = Try {
      if (
        destinationFolder.nonEmpty && java.nio.file.Files.exists(
          java.nio.file.Paths.get(destinationFolder, "following.csv")
        )
      ) {
        CsvService.loadOrCreateFollowing(destinationFolder, () => fetchFollowingUsersFromApi()).map(_.toUser)
      } else {
        fetchFollowingUsersFromApi()
      }
    }.getOrElse(List.empty)

    if (followedUsers.isEmpty) {
      logger.warn("No followed users available to check for stories.")
      return List.empty[UserStories]
    }

    logger.info(s"Checking active stories across ${followedUsers.size} followed users (including previously viewed)...")

    val userMap = followedUsers.map(u => u.userId -> u.folderName).toMap

    val userStoriesList = followedUsers
      .grouped(10)
      .zipWithIndex
      .flatMap { case (batchUsers, batchIndex) =>
        if (batchIndex > 0) {
          // Polite delay between batches to respect rate limits and prevent spam triggers
          Thread.sleep(600)
        }
        Try {
          val reelIdsArray = new android.org.json.JSONArray()
          batchUsers.foreach(u => reelIdsArray.put(u.userId))

          val vars = new android.org.json.JSONObject()
          vars.put("initial_reel_id", batchUsers.head.userId)
          vars.put("reel_ids", reelIdsArray)
          vars.put("first", math.max(batchUsers.size, 10))

          val response = queryGraphQLWeb(com.instagram4j.web.Constants.GraphQl.STORY, vars)
          parseStoriesFromGraphQLResponse(response, userMap)
        } match {
          case Success(stories) => stories
          case Failure(graphEx) =>
            logger.warn(
              s"GraphQL batch story query failed (${graphEx.getMessage}). Falling back to individual queries for this batch..."
            )
            batchUsers.flatMap { u =>
              Thread.sleep(500)
              val single = getUserStories(u)
              if (single.medias.nonEmpty) Some(single) else None
            }
        }
      }
      .toList

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
      val userFeeds = getUserPosts(user)

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
      val userHighlights = getUserHighlights(user)

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
      s"Finished highlights update run. Checked ${dueRecords.size} users, saved $savedHighlightsCount highlight media files."
    )
  }
}
