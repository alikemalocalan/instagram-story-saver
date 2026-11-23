package com.github.alikemalocalan.instastorysaver.service.web

import com.github.alikemalocalan.instastorysaver.model.*
import com.github.alikemalocalan.instastorysaver.service.HttpConstants
import com.github.alikemalocalan.instastorysaver.service.web.InstagramJsonParser.*
import com.github.alikemalocalan.instastorysaver.service.web.dto.InstagramDtos.*
import ujson.Value
import org.slf4j.{Logger, LoggerFactory}

import java.net.http.HttpRequest
import scala.util.{Failure, Success, Try}

/**
 * Low-level client for the Instagram web API (`www.instagram.com`).
 *
 * Uses the shared Java HTTP client and uPickle JSON values. The browser-style headers and cookies
 * are required for a web-issued session to be accepted by Instagram.
 */
final class InstagramWebClient(session: InstagramSession, gateway: HttpGateway = InstagramHttpClient) {
  import InstagramWebClient.*
  import InstagramDecoders.{given, *}

  private def csrf: String = session.csrfToken
  private def cookie: String = session.cookie

  // ---------------------------------------------------------------------------
  // GraphQL
  // ---------------------------------------------------------------------------

  /** Executes a web GraphQL query by doc id. */
  def queryGraphQL(docId: String, variables: Value): Value = {
    val body = gateway.formBody(
      Seq("doc_id" -> docId, "variables" -> ujson.write(variables))
    )

    val request = jsonHeaders(gateway.requestBuilder(GraphQlUrl))
      .setHeader("content-type", gateway.FormContentType)
      .setHeader("x-csrftoken", csrf)
      .setHeader("cookie", cookie)
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

    val (status, responseBody) = execute(request)
    if (status < 200 || status >= 300) {
      logger.warn(s"[GRAPHQL-ERROR] $docId ($GraphQlUrl) HTTP $status: ${responseBody.take(500)}")
      throw new java.io.IOException(s"GraphQL request failed with HTTP $status: $responseBody")
    }

    val json = parseJson(responseBody, "GraphQL response").getOrElse(ujson.Obj())
    val errors = decodeDto[GraphQlErrorResponse](json, s"$docId errors").flatMap(_.errors).getOrElse(Nil)
    if (errors.nonEmpty) {
      logger.warn(s"[GRAPHQL-ERROR] $docId returned errors: ${responseBody.take(500)}")
      resolveGraphQlError(errors.head, responseBody)
    } else json
  }

  /**
   * Instagram reports "Not Found" for a reel that simply has no active stories, so those are
   * converted into an empty result instead of an error.
   */
  private[web] def resolveGraphQlError(error: GraphQlError, rawBody: String): Value = {
    val summary = error.summary.map(_.trim).getOrElse("")
    val message = error.message.map(_.trim).filter(_.nonEmpty).getOrElse("Unknown error")

    if (summary.equalsIgnoreCase("Not Found")) emptyReelsMediaResponse
    else throw new java.io.IOException(s"Instagram GraphQL error: $message (response: $rawBody)")
  }

  // ---------------------------------------------------------------------------
  // Endpoints
  // ---------------------------------------------------------------------------

  def fetchStories(user: User): UserStories =
    Try {
      val variables = ujson.Obj(
        "initial_reel_id" -> user.userId,
        "reel_ids" -> jsonArrayOf(user.userId),
        "first" -> 10
      )

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
        ujson.Obj("user_id" -> user.userId)
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

      val body = gateway.formBody(
        Seq(
          "av" -> session.userId,
          "fb_dtsg" -> fbDtsg,
          "lsd" -> lsd,
          "fb_api_caller_class" -> "RelayModern",
          "fb_api_req_friendly_name" -> ProfilePostsFriendlyName,
          "variables" -> ujson.write(variables),
          "doc_id" -> ProfilePostsDocId
        )
      )

      val request = jsonHeaders(gateway.requestBuilder(ApiGraphQlUrl))
        .setHeader("content-type", gateway.FormContentType)
        .setHeader("referer", s"$WebBase/${user.folderName}/")
        .setHeader("x-csrftoken", csrf)
        .setHeader("x-fb-friendly-name", ProfilePostsFriendlyName)
        .setHeader("x-fb-lsd", lsd)
        .setHeader("cookie", cookie)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

      val (status, responseBody) = execute(request)
      if (status < 200 || status >= 300) {
        logger.warn(
          s"[GRAPHQL-ERROR] ${GraphQlContract.ProfilePosts.operation} HTTP $status for @${user.folderName}"
        )
        UserFeedMedias(user, List.empty)
      } else {
        val json = parseJson(responseBody, "feed response").getOrElse(ujson.Obj())
        val graphErrors =
          decodeDto[GraphQlErrorResponse](json, s"${GraphQlContract.ProfilePosts.operation} errors")
            .flatMap(_.errors)
            .getOrElse(Nil)

        if (graphErrors.nonEmpty) {
          val first = graphErrors.head
          val summary = first.summary.map(_.trim).filter(_.nonEmpty).getOrElse("no summary")
          val message = first.message.map(_.trim).filter(_.nonEmpty).getOrElse("no message")
          logger.warn(
            s"[GRAPHQL-ERROR] ${GraphQlContract.ProfilePosts.operation} for @${user.folderName}: " +
              s"$summary | $message | body=${responseBody.take(500)}"
          )
          UserFeedMedias(user, List.empty)
        } else {
          val medias = parseFeedMedias(json)
          logger.info(s"Loaded ${medias.size} feed posts for @${user.folderName}.")
          UserFeedMedias(user, medias)
        }
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
      val request = htmlHeaders(gateway.requestBuilder(s"$WebBase/stories/${user.folderName}/"))
        .setHeader("cookie", cookie)
        .GET()
        .build()

      val (status, html) = execute(request)
      if (status < 200 || status >= 300) List.empty else parseStoriesPage(html)
    }.getOrElse(List.empty)

  def fetchMediaInfo(mediaId: String): Option[Media] =
    Try {
      val request = jsonHeaders(gateway.requestBuilder(s"$WebBase/api/v1/media/$mediaId/info/"))
        .setHeader("x-csrftoken", csrf)
        .setHeader("cookie", cookie)
        .GET()
        .build()

      val (status, responseBody) = execute(request)
      if (status < 200 || status >= 300) None
      else parseMediaInfo(parseJson(responseBody, "media info response").getOrElse(ujson.Obj()))
    }.toOption.flatten

  def fetchReelsTray(): List[(User, List[String])] =
    Try {
      val request = jsonHeaders(gateway.requestBuilder(s"$WebBase/api/v1/feed/reels_tray/"))
        .setHeader("x-csrftoken", csrf)
        .setHeader("cookie", cookie)
        .GET()
        .build()

      val (status, responseBody) = execute(request)
      if (status < 200 || status >= 300) {
        logger.warn(s"Failed to fetch reels tray: HTTP $status")
        List.empty
      } else {
        parseReelsTray(parseJson(responseBody, "reels tray response").getOrElse(ujson.Obj()))
      }
    }.getOrElse(List.empty)

  /**
   * Fetches one page of the authenticated account's `following` list.
   *
   * @param cursor `next_max_id` returned by the previous page, or `None` for the first page.
   * @return the users on this page and the cursor for the next page, if any.
   */
  def fetchFollowingPage(cursor: Option[String]): (List[User], Option[String]) = {
    val query = cursor.fold("?count=50")(value => s"?count=50&maxId=$value")
    val request = jsonHeaders(
      gateway.requestBuilder(s"$WebBase/api/v1/friendships/${session.userId}/following/$query")
    )
      .setHeader("x-csrftoken", csrf)
      .setHeader("cookie", cookie)
      .GET()
      .build()

    val (status, responseBody) = execute(request)
    if (status < 200 || status >= 300) {
      throw new java.io.IOException(s"Failed to fetch following page: HTTP $status")
    }
    parseFollowingPage(parseJson(responseBody, "following response").getOrElse(ujson.Obj()))
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
    val request = htmlHeaders(gateway.requestBuilder(s"$WebBase/$path"))
      .setHeader("cookie", cookie)
      .GET()
      .build()

    val (_, html) = execute(request)
    val tokens = parseWebTokens(html)
    if (tokens._1.isEmpty || tokens._2.isEmpty) {
      logger.warn(
        s"[GRAPHQL-SHAPE] parseWebTokens: missing fb_dtsg/lsd tokens for @$username; " +
          "profile page HTML shape may have changed"
      )
    }
    tokens
  }

  // ---------------------------------------------------------------------------
  // HTTP plumbing
  // ---------------------------------------------------------------------------

  private def execute(request: HttpRequest): (Int, String) = gateway.execute(request)

  private def jsonHeaders(builder: HttpRequest.Builder): HttpRequest.Builder =
    baseHeaders(builder)
      .setHeader("accept", "*/*")
      .setHeader("origin", WebBase)
      .setHeader("referer", s"$WebBase/")
      .setHeader("sec-fetch-dest", "empty")
      .setHeader("sec-fetch-mode", "cors")
      .setHeader("sec-fetch-site", "same-origin")
      .setHeader("x-asbd-id", HttpConstants.AsbdId)
      .setHeader("x-ig-www-claim", "0")
      .setHeader("x-requested-with", "XMLHttpRequest")

  private def htmlHeaders(builder: HttpRequest.Builder): HttpRequest.Builder =
    baseHeaders(builder)
      .setHeader("accept", HtmlAccept)
      .setHeader("sec-fetch-dest", "document")
      .setHeader("sec-fetch-mode", "navigate")
      .setHeader("sec-fetch-site", "same-origin")

  private def baseHeaders(builder: HttpRequest.Builder): HttpRequest.Builder =
    builder
      .setHeader("accept-language", HttpConstants.AcceptLanguage)
      .setHeader("sec-ch-ua", HttpConstants.SecChUa)
      .setHeader("sec-ch-ua-mobile", HttpConstants.SecChUaMobile)
      .setHeader("sec-ch-ua-platform", HttpConstants.SecChUaPlatform)
      .setHeader("user-agent", HttpConstants.UserAgent)
      .setHeader("x-ig-app-id", HttpConstants.AppId)

  private[web] def profilePostsVariables(user: User): Value = {
    val data = ujson.Obj(
      "count" -> 12,
      "include_relationship_info" -> true,
      "latest_besties_reel_media" -> true,
      "latest_reel_media" -> true
    )

    ujson.Obj(
      "after" -> ujson.Null,
      "before" -> ujson.Null,
      "data" -> data,
      "first" -> 12,
      "last" -> ujson.Null,
      "username" -> user.folderName,
      "__relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider" -> false,
      "__relay_internal__pv__PolarisShortDramaEnabledrelayprovider" -> false,
      "__relay_internal__pv__PolarisSEOIsCrawlerrelayprovider" -> false,
      "__relay_internal__pv__PolarisProductTagIndicatorEnabledrelayprovider" -> false,
      "__relay_internal__pv__PolarisThreadsStyleCommentsEnabledrelayprovider" -> false,
      "__relay_internal__pv__PolarisReelsRecoDebugOverlayEnabledrelayprovider" -> false
    )
  }
}

object InstagramWebClient {
  private val logger: Logger = LoggerFactory.getLogger(classOf[InstagramWebClient])

  // Aliases keep call sites short; values live in GraphQlContract (single source of truth).
  private val WebBase = GraphQlContract.WebBase
  private val GraphQlUrl = GraphQlContract.Stories.url
  private val ApiGraphQlUrl = GraphQlContract.ProfilePosts.url
  private val StoryDocId = GraphQlContract.Stories.docId
  private val HighlightsDocId = GraphQlContract.Highlights.docId
  private val ProfilePostsDocId = GraphQlContract.ProfilePosts.docId
  private val ProfilePostsFriendlyName = GraphQlContract.ProfilePosts.operation
  private val HtmlAccept =
    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

  private var cachedWebTokens: Option[(String, String)] = None

  private def jsonArrayOf(values: String*): Value =
    ujson.Arr(values.map(value => ujson.Str(value))*)

  private def storyVariables(highlightIds: List[String]): Value =
    ujson.Obj(
      "initial_reel_id" -> highlightIds.head,
      "reel_ids" -> jsonArrayOf(highlightIds*),
      "first" -> math.max(highlightIds.size, 10)
    )

  private val emptyReelsMediaResponse: Value =
    parseJson("""{"data":{"xdt_api__v1__feed__reels_media__connection":{"edges":[]}}}""")
      .getOrElse(ujson.Obj())
}
