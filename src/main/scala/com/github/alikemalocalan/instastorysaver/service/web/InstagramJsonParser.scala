package com.github.alikemalocalan.instastorysaver.service.web

import com.github.alikemalocalan.instastorysaver.model.*
import com.github.alikemalocalan.instastorysaver.service.web.dto.InstagramDtos.*
import io.circe.{Decoder, Json}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Converts typed Instagram DTOs into domain models.
 *
 * JSON shape knowledge lives in `dto/InstagramDtos.scala` and `InstagramDecoders.scala`; this file
 * only selects the right decoder, maps to domain objects and keeps the diagnostics hooks.
 */
object InstagramJsonParser {
  import InstagramDecoders.{given, *}

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val ReelsMediaMarker = "{\"xdt_api__v1__feed__reels_media\":"
  private val DtsgPattern = "\"DTSGInitialData\"[^\"]*\"token\":\"([^\"]+)\"".r
  private val DtsgAltPattern = "\"dtsg\":\\{\"token\":\"([^\"]+)\"".r
  private val LsdPattern = "\"LSD\"[^\"]*\"token\":\"([^\"]+)\"".r

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  def isValidHttpUrl(url: String): Boolean =
    url != null && (url.trim.startsWith("http://") || url.trim.startsWith("https://"))

  def parseJson(raw: String, context: String = "response"): Option[Json] =
    io.circe.parser.parse(raw) match {
      case Right(json) => Some(json)
      case Left(error) =>
        logger.warn(s"Could not parse $context JSON: ${error.getMessage}")
        None
    }

  private def decodeDto[A: Decoder](json: Json, context: String): Option[A] =
    json.as[A] match {
      case Right(value) => Some(value)
      case Left(error) =>
        logger.warn(s"[GRAPHQL-SHAPE] $context decode failed: ${error.getMessage}")
        None
    }

  /** Stable warning used by AI agents to find the parser affected by an Instagram change. */
  private def warnOnMissingShape(context: String, json: Json, pathPresent: Boolean): Unit =
    if (!pathPresent && json.asObject.exists(_.nonEmpty)) {
      val keys = json.asObject.get.keys.toList.sorted.take(20).mkString(",")
      logger.warn(s"[GRAPHQL-SHAPE] $context: expected path missing; topLevelKeys=[$keys]")
    }

  private def url(value: Option[String]): Option[String] =
    value.map(_.trim).filter(isValidHttpUrl)

  private def nonEmpty(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)

  /** Instagram omits whole collections; the DTO keeps them optional and we normalize here. */
  private def values[A](field: Option[List[A]]): List[A] = field.toList.flatten

  // ---------------------------------------------------------------------------
  // Media selection
  // ---------------------------------------------------------------------------

  private def bestVideoUrl(item: MediaItem): Option[String] = {
    val versions = values(item.video_versions).sortBy { video =>
      val pixels = video.width.getOrElse(0).toLong * video.height.getOrElse(0).toLong * 1000L
      -(pixels + video.bandwidth_bps.getOrElse(0))
    }
    val resources = values(item.video_resources).sortBy(resource =>
      -(resource.config_width.getOrElse(0).toLong * resource.config_height.getOrElse(0).toLong)
    )

    url(versions.headOption.flatMap(_.url))
      .orElse(url(resources.headOption.flatMap(r => r.src.orElse(r.url))))
  }

  private def bestImageUrl(item: MediaItem): Option[String] = {
    val candidates = values(item.image_versions2.flatMap(_.candidates)).sortBy { candidate =>
      -(candidate.width.getOrElse(0).toLong * candidate.height.getOrElse(0).toLong)
    }
    val resources = values(item.display_resources).sortBy(resource =>
      -(resource.config_width.getOrElse(0).toLong * resource.config_height.getOrElse(0).toLong)
    )

    url(candidates.headOption.flatMap(_.url))
      .orElse(url(resources.headOption.flatMap(r => r.src.orElse(r.url))))
      .orElse(url(item.display_url))
  }

  private def carouselItems(item: MediaItem): List[MediaItem] = {
    val direct = values(item.carousel_media)
    if (direct.nonEmpty) direct
    else values(item.edge_sidecar_to_children.flatMap(_.edges)).map(_.node)
  }

  private def mediaItems(items: List[MediaItem]): List[Media] =
    items.flatMap { item =>
      val carousel = carouselItems(item)
      if (carousel.nonEmpty) mediaItems(carousel)
      else {
        val takenAt = item.taken_at.getOrElse(System.currentTimeMillis() / 1000L) * 1000L
        bestVideoUrl(item).orElse(bestImageUrl(item)).map(mediaUrl => Media(mediaUrl, takenAt)).toList
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
  def parseStories(response: Json, userMap: Map[String, String]): List[UserStories] =
    decodeDto[StoriesResponse](response, GraphQlContract.Stories.operation) match {
      case None => List.empty
      case Some(envelope) =>
        val data = envelope.data
        val connection = data.flatMap(_.xdt_api__v1__feed__reels_media__connection)
        warnOnMissingShape(GraphQlContract.Stories.operation, response, connection.nonEmpty)

        // Instagram answers with a null xdt_viewer.user when the session cookie is no longer valid.
        data.flatMap(_.xdt_viewer).foreach { viewer =>
          if (viewer.user.isEmpty) {
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

        values(connection.flatMap(_.edges)).flatMap { edge =>
          val node = edge.node
          val userId = node.user
            .flatMap(user => nonEmpty(user.pk.map(_.value)))
            .orElse(nonEmpty(node.id.map(_.value)))
            .getOrElse("")
          val username = node.user
            .flatMap(user => nonEmpty(user.username))
            .getOrElse(userMap.getOrElse(userId, userId))
          val medias = mediaItems(values(node.items))

          Option.when(medias.nonEmpty)(UserStories(User(username, userId), medias)).toList
        }
    }

  /** Extracts highlight reel ids from the `highlights` GraphQL response. */
  def parseHighlightIds(response: Json): List[String] =
    decodeDto[HighlightsResponse](response, GraphQlContract.Highlights.operation) match {
      case None => List.empty
      case Some(envelope) =>
        val highlights = envelope.data.flatMap(_.highlights)
        warnOnMissingShape(GraphQlContract.Highlights.operation, response, highlights.nonEmpty)
        values(highlights.flatMap(_.edges)).flatMap(edge => edge.node.id.map(_.value))
    }

  /** Parses highlight media from a stories (`STORY` doc id) response. */
  def parseHighlightMedias(response: Json): List[HighLightStoryMedia] =
    decodeDto[StoriesResponse](response, GraphQlContract.Stories.operation) match {
      case None => List.empty
      case Some(envelope) =>
        val connection = envelope.data.flatMap(_.xdt_api__v1__feed__reels_media__connection)
        warnOnMissingShape(GraphQlContract.Stories.operation, response, connection.nonEmpty)
        values(connection.flatMap(_.edges)).flatMap { edge =>
          val node = edge.node
          val title = nonEmpty(node.title).getOrElse("highlight")
          mediaItems(values(node.items)).map(media => HighLightStoryMedia(media.url, media.takenOn, title))
        }
    }

  /** Parses feed posts from the `xdt_api__v1__feed__user_timeline_graphql_connection` shape. */
  def parseFeedMedias(response: Json): List[Media] =
    decodeDto[FeedResponse](response, GraphQlContract.ProfilePosts.operation) match {
      case None => List.empty
      case Some(envelope) =>
        val connection = envelope.data.flatMap(_.xdt_api__v1__feed__user_timeline_graphql_connection)
        warnOnMissingShape(GraphQlContract.ProfilePosts.operation, response, connection.nonEmpty)
        mediaItems(values(connection.flatMap(_.edges)).map(_.node))
    }

  def parseMediaInfo(response: Json): Option[Media] =
    decodeDto[MediaInfoResponse](response, GraphQlContract.MediaInfo.operation) match {
      case None => None
      case Some(envelope) =>
        val items = values(envelope.items)
        warnOnMissingShape(GraphQlContract.MediaInfo.operation, response, items.nonEmpty)
        mediaItems(items).headOption
    }

  // ---------------------------------------------------------------------------
  // Plain endpoints / embedded HTML
  // ---------------------------------------------------------------------------

  def parseReelsTray(response: Json): List[(User, List[String])] =
    decodeDto[TrayResponse](response, GraphQlContract.ReelsTray.operation) match {
      case None => List.empty
      case Some(envelope) =>
        val tray = values(envelope.tray)
        warnOnMissingShape(GraphQlContract.ReelsTray.operation, response, tray.nonEmpty)
        tray.flatMap { reel =>
          reel.user.flatMap { user =>
            val username = nonEmpty(user.username).getOrElse("")
            val userId = reel.id.orElse(user.pk).map(_.value).getOrElse("0")
            val mediaIds = values(reel.media_ids).map(_.value)

            Option.when(username.nonEmpty && mediaIds.nonEmpty)((User(username, userId), mediaIds))
          }
        }
    }

  /** Parses one page of the `friendships/<id>/following` response. */
  def parseFollowingPage(response: Json): (List[User], Option[String]) =
    decodeDto[FollowingResponse](response, GraphQlContract.Following.operation) match {
      case None => (List.empty, None)
      case Some(envelope) =>
        val data = envelope.data
        val users = values(data.flatMap(_.users))
        warnOnMissingShape(GraphQlContract.Following.operation, response, users.nonEmpty)

        val parsedUsers = users.flatMap { user =>
          val username = nonEmpty(user.username).getOrElse("")
          val userId = nonEmpty(user.pk.map(_.value)).getOrElse("")
          Option.when(username.nonEmpty && userId.nonEmpty)(User(username, userId)).toList
        }

        val nextCursor = data
          .flatMap(_.next_max_id)
          .flatMap(cursor => nonEmpty(Some(cursor.value)))
          .filter(value => !value.equalsIgnoreCase("null"))

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
              decodeDto[StoriesPageResponse](parsed, "stories page") match {
                case None => List.empty
                case Some(page) =>
                  page.xdt_api__v1__feed__reels_media
                    .flatMap(state => values(state.reels_media).headOption)
                    .map(reel => mediaItems(values(reel.items)))
                    .getOrElse(List.empty)
              }
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
}
