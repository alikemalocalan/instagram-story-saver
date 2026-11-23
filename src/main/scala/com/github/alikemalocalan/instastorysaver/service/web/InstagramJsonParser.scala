package com.github.alikemalocalan.instastorysaver.service.web

import com.github.alikemalocalan.instastorysaver.model.*
import io.circe.{Json, JsonObject}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Pure JSON/HTML parsers for Instagram web responses.
 *
 * All methods here are side-effect free (apart from logging) and independent of HTTP, which makes
 * the fragile Instagram response handling easy to reason about and reuse.
 */
object InstagramJsonParser {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val ReelsMediaMarker = "{\"xdt_api__v1__feed__reels_media\":"
  private val DtsgPattern = "\"DTSGInitialData\"[^\"]*\"token\":\"([^\"]+)\"".r
  private val DtsgAltPattern = "\"dtsg\":\\{\"token\":\"([^\"]+)\"".r
  private val LsdPattern = "\"LSD\"[^\"]*\"token\":\"([^\"]+)\"".r

  // ---------------------------------------------------------------------------
  // JSON helpers
  // ---------------------------------------------------------------------------

  def obj(json: Json): Option[JsonObject] = json.asObject
  def arr(json: Option[Json]): List[Json] = json.flatMap(_.asArray).map(_.toList).getOrElse(List.empty)
  def field(json: Json, name: String): Option[Json] = json.asObject.flatMap(_(name)).filterNot(_.isNull)
  def objField(json: Json, name: String): Option[Json] = field(json, name).filter(_.isObject)
  def arrField(json: Json, name: String): List[Json] = arr(field(json, name))
  def str(json: Json, name: String): Option[String] = field(json, name).flatMap(_.asString).map(_.trim)
  def strOr(json: Json, name: String, default: String = ""): String = str(json, name).getOrElse(default)
  def long(json: Json, name: String): Option[Long] = field(json, name).flatMap(_.asNumber).flatMap(_.toLong)
  def int(json: Json, name: String): Option[Int] = field(json, name).flatMap(_.asNumber).flatMap(_.toInt)
  def bool(json: Json, name: String): Option[Boolean] = field(json, name).flatMap(_.asBoolean)

  def isValidHttpUrl(url: String): Boolean =
    url != null && (url.trim.startsWith("http://") || url.trim.startsWith("https://"))

  /**
   * Emits a stable, grep-friendly warning when an expected GraphQL response path disappears.
   * AI agents can search logs for GRAPHQL-SHAPE and jump straight to the relevant parser.
   */
  private def warnOnMissingShape(context: String, json: Json, pathPresent: Boolean): Unit =
    if (!pathPresent && json.asObject.exists(_.nonEmpty)) {
      val keys = json.asObject.get.keys.toList.sorted.take(20).mkString(",")
      logger.warn(s"[GRAPHQL-SHAPE] $context: expected path missing; topLevelKeys=[$keys]")
    }

  // ---------------------------------------------------------------------------
  // Media extraction
  // ---------------------------------------------------------------------------

  def bestVideoUrl(item: Json): Option[String] =
    Option.when(arrField(item, "video_versions").nonEmpty)(arrField(item, "video_versions"))
      .flatMap { versions =>
        versions
          .sortBy { v =>
            val pixels = int(v, "width").getOrElse(0).toLong * int(v, "height").getOrElse(0).toLong * 1000L
            -(pixels + int(v, "bandwidth_bps").getOrElse(0))
          }
          .headOption
          .flatMap(v => str(v, "url").filter(isValidHttpUrl))
      }
      .orElse {
        val resources = arrField(item, "video_resources")
        Option.when(resources.nonEmpty)(resources)
          .flatMap { list =>
            list
              .sortBy(vr => -(int(vr, "config_width").getOrElse(0).toLong * int(vr, "config_height").getOrElse(0).toLong))
              .headOption
              .flatMap(vr => str(vr, "src").orElse(str(vr, "url")).filter(isValidHttpUrl))
          }
      }

  def bestImageUrl(item: Json): Option[String] =
    objField(item, "image_versions2")
      .flatMap(v => Option.when(arrField(v, "candidates").nonEmpty)(arrField(v, "candidates")))
      .flatMap { candidates =>
        candidates
          .sortBy(c => -(int(c, "width").getOrElse(0).toLong * int(c, "height").getOrElse(0).toLong))
          .headOption
          .flatMap(c => str(c, "url").filter(isValidHttpUrl))
      }
      .orElse {
        val resources = arrField(item, "display_resources")
        Option.when(resources.nonEmpty)(resources)
          .flatMap { list =>
            list
              .sortBy(dr => -(int(dr, "config_width").getOrElse(0).toLong * int(dr, "config_height").getOrElse(0).toLong))
              .headOption
              .flatMap(dr => str(dr, "src").orElse(str(dr, "url")).filter(isValidHttpUrl))
          }
      }
      .orElse(str(item, "display_url").filter(isValidHttpUrl))

  /** Returns carousel children for both mobile (`carousel_media`) and web (`edge_sidecar_to_children`) shapes. */
  def carouselItems(item: Json): List[Json] = {
    val mobile = arrField(item, "carousel_media")
    if (mobile.nonEmpty) mobile
    else
      objField(item, "edge_sidecar_to_children")
        .map(sidecar => arrField(sidecar, "edges").flatMap(edge => objField(edge, "node")))
        .getOrElse(List.empty)
  }

  def mediaItems(items: List[Json]): List[Media] =
    items.flatMap { item =>
      val carousel = carouselItems(item)
      if (carousel.nonEmpty) mediaItems(carousel)
      else {
        val downloadUrl = bestVideoUrl(item).orElse(bestImageUrl(item)).filter(isValidHttpUrl)
        val takenAt = long(item, "taken_at").getOrElse(System.currentTimeMillis() / 1000L) * 1000L
        downloadUrl.map(url => Media(url, takenAt)).toList
      }
    }

  // ---------------------------------------------------------------------------
  // GraphQL responses
  // ---------------------------------------------------------------------------

  /**
   * Parses stories from the `xdt_api__v1__feed__reels_media__connection` GraphQL shape.
   *
   * @param userMap fallback user id -> username map, used when the response omits the user object.
   */
  def parseStories(response: Json, userMap: Map[String, String]): List[UserStories] = {
    val data = dataOf(response)
    warnOnMissingShape(
      GraphQlContract.Stories.operation,
      data,
      objField(data, "xdt_api__v1__feed__reels_media__connection").nonEmpty
    )

    // Instagram answers with a null xdt_viewer.user when the session cookie is no longer valid.
    field(data, "xdt_viewer").foreach { viewer =>
      if (field(viewer, "user").isEmpty) {
        val message =
          """
            |================================================================================
            |❌ INSTAGRAM SESSION IS EXPIRED OR INVALID!
            |
            |Instagram GraphQL responded as unauthenticated viewer (xdt_viewer.user is null).
            |Please refresh your --session-id and --csrf-token values from your browser.
            |================================================================================
            |""".stripMargin
        logger.error(message)
        throw new IllegalStateException("Instagram session is expired or invalid. Please refresh cookies.")
      }
    }

    reelsMediaEdges(data).flatMap { edge =>
      val node = objField(edge, "node").getOrElse(Json.obj())
      val nodeUser = objField(node, "user")
      val userId = nodeUser.flatMap(user => str(user, "pk")).filter(_.nonEmpty).orElse(str(node, "id")).getOrElse("")
      val username = nodeUser
        .flatMap(user => str(user, "username"))
        .filter(_.nonEmpty)
        .getOrElse(userMap.getOrElse(userId, userId))
      val medias = mediaItems(arrField(node, "items"))
      Option.when(medias.nonEmpty)(UserStories(User(username, userId), medias)).toList
    }
  }

  /** Extracts highlight reel ids from the `highlights` GraphQL response. */
  def parseHighlightIds(response: Json): List[String] = {
    val data = dataOf(response)
    val highlights = objField(data, "highlights")
    warnOnMissingShape(GraphQlContract.Highlights.operation, data, highlights.nonEmpty)
    highlights
      .map(h => arrField(h, "edges").flatMap(edge => objField(edge, "node")))
      .getOrElse(List.empty)
      .flatMap(node => str(node, "id"))
  }

  /** Parses highlight media from a stories (`STORY` doc id) response. */
  def parseHighlightMedias(response: Json): List[HighLightStoryMedia] =
    reelsMediaEdges(dataOf(response)).flatMap { edge =>
      val node = objField(edge, "node").getOrElse(Json.obj())
      val title = str(node, "title").filter(_.nonEmpty).getOrElse("highlight")
      mediaItems(arrField(node, "items")).map(media => HighLightStoryMedia(media.url, media.takenOn, title))
    }

  /** Parses feed posts from the `xdt_api__v1__feed__user_timeline_graphql_connection` shape. */
  def parseFeedMedias(response: Json): List[Media] = {
    val connection = field(response, "data")
      .flatMap(data => objField(data, "xdt_api__v1__feed__user_timeline_graphql_connection"))
    warnOnMissingShape(GraphQlContract.ProfilePosts.operation, response, connection.nonEmpty)
    val edges = connection.map(c => arrField(c, "edges")).getOrElse(List.empty)

    mediaItems(edges.flatMap(edge => objField(edge, "node")))
  }

  def parseMediaInfo(response: Json): Option[Media] = {
    warnOnMissingShape(GraphQlContract.MediaInfo.operation, response, field(response, "items").nonEmpty)
    Option.when(arrField(response, "items").nonEmpty)(arrField(response, "items"))
      .flatMap(items => mediaItems(items).headOption)
  }

  // ---------------------------------------------------------------------------
  // Plain endpoints / embedded HTML
  // ---------------------------------------------------------------------------

  def parseReelsTray(response: Json): List[(User, List[String])] = {
    warnOnMissingShape(GraphQlContract.ReelsTray.operation, response, field(response, "tray").nonEmpty)
    arrField(response, "tray").flatMap { reel =>
      objField(reel, "user").flatMap { user =>
        val username = strOr(user, "username")
        val userId = long(reel, "id")
          .orElse(long(user, "pk"))
          .map(String.valueOf)
          .getOrElse("0")
        val mediaIds = arrField(reel, "media_ids").flatMap(id => id.asNumber.flatMap(_.toLong).map(String.valueOf))

        Option.when(username.nonEmpty && mediaIds.nonEmpty)((User(username, userId), mediaIds))
      }
    }
  }

  /** Parses one page of the `friendships/<id>/following` response. */
  def parseFollowingPage(response: Json): (List[User], Option[String]) = {
    val data = dataOf(response)
    warnOnMissingShape(GraphQlContract.Following.operation, data, field(data, "users").nonEmpty)
    val users = arrField(data, "users")

    val parsedUsers = users.flatMap { user =>
      val username = strOr(user, "username")
      val userId = strOr(user, "pk")
      Option.when(username.nonEmpty && userId.nonEmpty)(User(username, userId)).toList
    }

    val nextCursor = str(data, "next_max_id")
      .filter(value => value.nonEmpty && !value.equalsIgnoreCase("null"))

    (parsedUsers, nextCursor)
  }

  /** Extracts story media from the preloaded reels state embedded in a `/stories/<user>/` HTML page. */
  def parseStoriesPage(html: String): List[Media] = {
    val start = html.indexOf(ReelsMediaMarker)
    if (start < 0) {
      List.empty
    } else {
      extractJsonObject(html, start) match {
        case None => List.empty
        case Some(rawJson) =>
          parseJson(rawJson, "stories page") match {
            case None => List.empty
            case Some(parsed) =>
              field(parsed, "xdt_api__v1__feed__reels_media")
                .flatMap(reels => arrField(reels, "reels_media").headOption)
                .map(reel => mediaItems(arrField(reel, "items")))
                .getOrElse(List.empty)
          }
      }
    }
  }

  /**
   * Finds the end of the JSON object that starts at `start`, ignoring braces inside strings.
   * Instagram's preloaded state often contains several comma-separated root objects in one script
   * tag, so slicing up to `</script>` produces invalid JSON.
   */
  private def extractJsonObject(html: String, start: Int): Option[String] = {
    var depth = 0
    var inString = false
    var escaped = false
    var index = start

    while (index < html.length) {
      val ch = html.charAt(index)
      if (inString) {
        if (escaped) escaped = false
        else if (ch == '\\') escaped = true
        else if (ch == '"') inString = false
      } else {
        ch match {
          case '{' => depth += 1
          case '}' =>
            depth -= 1
            if (depth == 0) return Some(html.substring(start, index + 1))
          case '"' => inString = true
          case _   => ()
        }
      }
      index += 1
    }
    None
  }

  /** Extracts the `fb_dtsg` and `lsd` tokens required by the `/api/graphql` endpoint. */
  def parseWebTokens(html: String): (String, String) = {
    val dtsg = DtsgPattern
      .findFirstMatchIn(html)
      .orElse(DtsgAltPattern.findFirstMatchIn(html))
      .map(_.group(1))
      .getOrElse("")
    val lsd = LsdPattern.findFirstMatchIn(html).map(_.group(1)).getOrElse("")
    (dtsg, lsd)
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  def parseJson(raw: String, context: String = "response"): Option[Json] =
    io.circe.parser.parse(raw) match {
      case Right(json) => Some(json)
      case Left(error) =>
        logger.warn(s"Could not parse $context JSON: ${error.getMessage}")
        None
    }

  private def dataOf(response: Json): Json = objField(response, "data").getOrElse(response)

  private def reelsMediaEdges(data: Json): List[Json] =
    objField(data, "xdt_api__v1__feed__reels_media__connection")
      .map(connection => arrField(connection, "edges"))
      .getOrElse(List.empty)
}
