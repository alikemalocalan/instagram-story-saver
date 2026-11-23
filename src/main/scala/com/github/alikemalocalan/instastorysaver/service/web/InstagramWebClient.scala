package com.github.alikemalocalan.instastorysaver.service.web

import android.org.json.{JSONArray, JSONObject}
import com.github.alikemalocalan.instastorysaver.model.*
import com.github.alikemalocalan.instastorysaver.service.HttpConstants
import com.github.alikemalocalan.instastorysaver.service.web.InstagramJsonParser.*
import okhttp3.{FormBody, Request}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}

/**
 * Low-level client for the Instagram web API (`www.instagram.com`).
 *
 * This class exists because instagram4j's built-in story/feed helpers cannot be used with a
 * browser-issued session:
 *
 *   1. instagram4j omits mandatory web headers (`x-ig-app-id`, `x-csrftoken`, origin/referer),
 *      so Instagram treats the requests as unauthenticated.
 *   2. Its story endpoints target the mobile gateway (`i.instagram.com`), which invalidates web
 *      `sessionid` cookies and returns empty reels.
 *   3. Some bundled GraphQL doc ids are retired (for example `USER_STORY`); the active ids are
 *      defined in this file.
 *
 * The class is intentionally independent of instagram4j; only OkHttp and the shared session model
 * are used.
 */
final class InstagramWebClient(session: InstagramSession) {
  import InstagramWebClient.*

  private def csrf: String = session.csrfToken
  private def cookie: String = session.cookie

  // ---------------------------------------------------------------------------
  // GraphQL
  // ---------------------------------------------------------------------------

  /** Executes a web GraphQL query by doc id. */
  def queryGraphQL(docId: String, variables: JSONObject): JSONObject = {
    val body = new FormBody.Builder()
      .add("doc_id", docId)
      .add("variables", variables.toString)
      .build()

    val request = jsonHeaders(new Request.Builder().url(GraphQlUrl))
      .header("x-csrftoken", csrf)
      .header("cookie", cookie)
      .post(body)
      .build()

    val (status, responseBody) = execute(request)
    if (status < 200 || status >= 300) {
      throw new java.io.IOException(s"GraphQL request failed with HTTP $status: $responseBody")
    }

    val json = new JSONObject(responseBody)
    if (json.has("errors")) resolveGraphQlError(json, responseBody) else json
  }

  /**
   * Instagram reports "Not Found" for a reel that simply has no active stories, so those are
   * converted into an empty result instead of an error.
   */
  private def resolveGraphQlError(json: JSONObject, rawBody: String): JSONObject = {
    val errors = json.getJSONArray("errors")
    val firstError = if (errors.length() > 0) errors.getJSONObject(0) else new JSONObject()
    val summary = firstError.optString("summary", "")
    val message = firstError.optString("message", "Unknown error")

    if (summary.equalsIgnoreCase("Not Found")) emptyReelsMediaResponse
    else throw new java.io.IOException(s"Instagram GraphQL error: $message (response: $rawBody)")
  }

  // ---------------------------------------------------------------------------
  // Endpoints
  // ---------------------------------------------------------------------------

  def fetchStories(user: User): UserStories =
    Try {
      val variables = new JSONObject()
      variables.put("initial_reel_id", user.userId)
      variables.put("reel_ids", jsonArrayOf(user.userId))
      variables.put("first", 10)

      val response = queryGraphQL(StoryDocId, variables)
      parseStories(response, Map(user.userId -> user.folderName)).headOption
        .getOrElse(UserStories(user, List.empty))
    } match {
      case Success(stories) => stories
      case Failure(ex) =>
        logger.warn(s"Could not get stories for user @${user.folderName}: ${ex.getMessage}")
        UserStories(user, List.empty)
    }

  def fetchHighlights(user: User): UserHighLightStoryMedias =
    Try {
      val highlightsResponse = queryGraphQL(
        HighlightsDocId,
        new JSONObject().put("user_id", user.userId)
      )
      val highlightIds = parseHighlightIds(highlightsResponse)

      val medias =
        if (highlightIds.isEmpty) List.empty[HighLightStoryMedia]
        else parseHighlightMedias(queryGraphQL(StoryDocId, storyVariables(highlightIds)))

      UserHighLightStoryMedias(user, medias)
    } match {
      case Success(highlights) => highlights
      case Failure(ex) =>
        logger.warn(s"Could not get highlights for user @${user.folderName}: ${ex.getMessage}")
        UserHighLightStoryMedias(user, List.empty)
    }

  def fetchPosts(user: User): UserFeedMedias =
    Try {
      val (fbDtsg, lsd) = webTokens(user.folderName)
      val variables = profilePostsVariables(user)

      val body = new FormBody.Builder()
        .add("av", session.userId)
        .add("fb_dtsg", fbDtsg)
        .add("lsd", lsd)
        .add("fb_api_caller_class", "RelayModern")
        .add("fb_api_req_friendly_name", ProfilePostsFriendlyName)
        .add("variables", variables.toString)
        .add("doc_id", ProfilePostsDocId)
        .build()

      val request = jsonHeaders(new Request.Builder().url(ApiGraphQlUrl))
        .header("content-type", "application/x-www-form-urlencoded")
        .header("referer", s"$WebBase/${user.folderName}/")
        .header("x-csrftoken", csrf)
        .header("x-fb-friendly-name", ProfilePostsFriendlyName)
        .header("x-fb-lsd", lsd)
        .header("cookie", cookie)
        .post(body)
        .build()

      val (status, responseBody) = execute(request)
      if (status < 200 || status >= 300) {
        logger.warn(s"Could not get posts for user @${user.folderName}: HTTP $status")
        UserFeedMedias(user, List.empty)
      } else {
        val medias = parseFeedMedias(new JSONObject(responseBody))
        logger.info(s"Loaded ${medias.size} feed posts for @${user.folderName}.")
        UserFeedMedias(user, medias)
      }
    } match {
      case Success(feed) => feed
      case Failure(ex) =>
        val message = Option(ex.getMessage).map(_.take(120)).getOrElse("Unknown error")
        logger.warn(s"Could not get posts for user @${user.folderName}: $message")
        UserFeedMedias(user, List.empty)
    }

  /** Reads stories from the server-rendered `/stories/<user>/` page (fallback for the tray flow). */
  def fetchStoriesFromPage(user: User): List[Media] =
    Try {
      val request = htmlHeaders(new Request.Builder().url(s"$WebBase/stories/${user.folderName}/"))
        .header("cookie", cookie)
        .get()
        .build()

      val (status, html) = execute(request)
      if (status < 200 || status >= 300) List.empty else parseStoriesPage(html)
    }.getOrElse(List.empty)

  def fetchMediaInfo(mediaId: String): Option[Media] =
    Try {
      val request = jsonHeaders(new Request.Builder().url(s"$WebBase/api/v1/media/$mediaId/info/"))
        .header("x-csrftoken", csrf)
        .header("cookie", cookie)
        .get()
        .build()

      val (status, responseBody) = execute(request)
      if (status < 200 || status >= 300) None else parseMediaInfo(new JSONObject(responseBody))
    }.toOption.flatten

  def fetchReelsTray(): List[(User, List[String])] =
    Try {
      val request = jsonHeaders(new Request.Builder().url(s"$WebBase/api/v1/feed/reels_tray/"))
        .header("x-csrftoken", csrf)
        .header("cookie", cookie)
        .get()
        .build()

      val (status, responseBody) = execute(request)
      if (status < 200 || status >= 300) {
        logger.warn(s"Failed to fetch reels tray: HTTP $status")
        List.empty
      } else {
        parseReelsTray(new JSONObject(responseBody))
      }
    }.getOrElse(List.empty)

  /**
   * Fetches one page of the authenticated account's `following` list.
   *
   * Reimplementation of instagram4j's `ProfilePaginator` against the web endpoint, because the
   * library is no longer a dependency.
   *
   * @param cursor `next_max_id` returned by the previous page, or `None` for the first page.
   * @return the users on this page and the cursor for the next page, if any.
   */
  def fetchFollowingPage(cursor: Option[String]): (List[User], Option[String]) = {
    val query = cursor.fold("?count=50")(value => s"?count=50&maxId=$value")
    val request = jsonHeaders(
      new Request.Builder().url(s"$WebBase/api/v1/friendships/${session.userId}/following/$query")
    )
      .header("x-csrftoken", csrf)
      .header("cookie", cookie)
      .get()
      .build()

    val (status, responseBody) = InstagramHttpClient.execute(request)
    if (status < 200 || status >= 300) {
      throw new java.io.IOException(s"Failed to fetch following page: HTTP $status")
    }
    parseFollowingPage(new JSONObject(responseBody))
  }

  // ---------------------------------------------------------------------------
  // Web tokens (`fb_dtsg` / `lsd`) required by `/api/graphql`
  // ---------------------------------------------------------------------------

  private def webTokens(username: String): (String, String) = InstagramWebClient.synchronized {
    cachedWebTokens match {
      case Some(tokens) if tokens._1.nonEmpty && tokens._2.nonEmpty => tokens
      case _ =>
        val tokens = fetchWebTokens(username)
        if (tokens._1.nonEmpty || tokens._2.nonEmpty) cachedWebTokens = Some(tokens)
        tokens
    }
  }

  private def fetchWebTokens(username: String): (String, String) = {
    val path = if (username.nonEmpty) s"$username/" else ""
    val request = htmlHeaders(new Request.Builder().url(s"$WebBase/$path"))
      .header("cookie", cookie)
      .get()
      .build()

    val (_, html) = execute(request)
    parseWebTokens(html)
  }

  // ---------------------------------------------------------------------------
  // HTTP plumbing
  // ---------------------------------------------------------------------------

  private def execute(request: Request): (Int, String) = InstagramHttpClient.execute(request)

  private def jsonHeaders(builder: Request.Builder): Request.Builder =
    baseHeaders(builder)
      .header("accept", "*/*")
      .header("origin", WebBase)
      .header("referer", s"$WebBase/")
      .header("sec-fetch-dest", "empty")
      .header("sec-fetch-mode", "cors")
      .header("sec-fetch-site", "same-origin")
      .header("x-asbd-id", HttpConstants.AsbdId)
      .header("x-ig-www-claim", "0")
      .header("x-requested-with", "XMLHttpRequest")

  private def htmlHeaders(builder: Request.Builder): Request.Builder =
    baseHeaders(builder)
      .header("accept", HtmlAccept)
      .header("sec-fetch-dest", "document")
      .header("sec-fetch-mode", "navigate")
      .header("sec-fetch-site", "same-origin")

  private def baseHeaders(builder: Request.Builder): Request.Builder =
    builder
      .header("authority", "www.instagram.com")
      .header("accept-language", HttpConstants.AcceptLanguage)
      .header("sec-ch-ua", HttpConstants.SecChUa)
      .header("sec-ch-ua-mobile", HttpConstants.SecChUaMobile)
      .header("sec-ch-ua-platform", HttpConstants.SecChUaPlatform)
      .header("user-agent", HttpConstants.UserAgent)
      .header("x-ig-app-id", HttpConstants.AppId)

  private def profilePostsVariables(user: User): JSONObject = {
    val data = new JSONObject()
    data.put("count", 12)
    data.put("include_relationship_info", true)
    data.put("latest_besties_reel_media", true)
    data.put("latest_reel_media", true)

    val variables = new JSONObject()
    variables.put("after", JSONObject.NULL)
    variables.put("before", JSONObject.NULL)
    variables.put("data", data)
    variables.put("first", 12)
    variables.put("last", JSONObject.NULL)
    variables.put("username", user.folderName)
    variables.put("__relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider", false)
    variables.put("__relay_internal__pv__PolarisShortDramaEnabledrelayprovider", false)
    variables.put("__relay_internal__pv__PolarisSEOIsCrawlerrelayprovider", false)
    variables.put("__relay_internal__pv__PolarisProductTagIndicatorEnabledrelayprovider", false)
    variables.put("__relay_internal__pv__PolarisThreadsStyleCommentsEnabledrelayprovider", false)
    variables.put("__relay_internal__pv__PolarisReelsRecroDebugOverlayEnabledrelayprovider", false)
    variables
  }
}

object InstagramWebClient {
  private val logger: Logger = LoggerFactory.getLogger(classOf[InstagramWebClient])

  private val WebBase = "https://www.instagram.com"
  private val GraphQlUrl = s"$WebBase/graphql/query"
  private val ApiGraphQlUrl = s"$WebBase/api/graphql"
  private val StoryDocId = "9558504827580036"
  private val HighlightsDocId = "9719220061527365"
  private val ProfilePostsDocId = "30543471925243353"
  private val ProfilePostsFriendlyName = "PolarisProfilePostsQuery"
  private val HtmlAccept =
    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

  private var cachedWebTokens: Option[(String, String)] = None

  private def jsonArrayOf(values: String*): JSONArray = {
    val array = new JSONArray()
    values.foreach(array.put)
    array
  }

  private def storyVariables(highlightIds: List[String]): JSONObject = {
    val variables = new JSONObject()
    variables.put("initial_reel_id", highlightIds.head)
    variables.put("reel_ids", jsonArrayOf(highlightIds*))
    variables.put("first", math.max(highlightIds.size, 10))
    variables
  }

  private def emptyReelsMediaResponse: JSONObject =
    new JSONObject(
      """{"data":{"xdt_api__v1__feed__reels_media__connection":{"edges":[]}}}"""
    )
}
