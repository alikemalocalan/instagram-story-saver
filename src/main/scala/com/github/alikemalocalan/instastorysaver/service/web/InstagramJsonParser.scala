package com.github.alikemalocalan.instastorysaver.service.web

import android.org.json.{JSONArray, JSONObject}
import com.github.alikemalocalan.instastorysaver.model.*
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
  // Media extraction
  // ---------------------------------------------------------------------------

  def isValidHttpUrl(url: String): Boolean =
    url != null && (url.trim.startsWith("http://") || url.trim.startsWith("https://"))

  def bestVideoUrl(item: JSONObject): Option[String] =
    Option(item.optJSONArray("video_versions"))
      .filter(!_.isEmpty)
      .flatMap { versions =>
        (0 until versions.length())
          .map(versions.getJSONObject)
          .sortBy { v =>
            val pixels = v.optInt("width", 0).toLong * v.optInt("height", 0).toLong * 1000L
            -(pixels + v.optInt("bandwidth_bps", 0))
          }
          .headOption
          .flatMap(v => Option(v.optString("url", "")).filter(isValidHttpUrl))
      }
      .orElse {
        Option(item.optJSONArray("video_resources"))
          .filter(!_.isEmpty)
          .flatMap { resources =>
            (0 until resources.length())
              .map(resources.getJSONObject)
              .sortBy(vr => -(vr.optInt("config_width", 0).toLong * vr.optInt("config_height", 0).toLong))
              .headOption
              .flatMap { vr =>
                Option(vr.optString("src", ""))
                  .orElse(Option(vr.optString("url", "")))
                  .filter(isValidHttpUrl)
              }
          }
      }

  def bestImageUrl(item: JSONObject): Option[String] =
    Option(item.optJSONObject("image_versions2"))
      .flatMap(v => Option(v.optJSONArray("candidates")))
      .filter(!_.isEmpty)
      .flatMap { candidates =>
        (0 until candidates.length())
          .map(candidates.getJSONObject)
          .sortBy(c => -(c.optInt("width", 0).toLong * c.optInt("height", 0).toLong))
          .headOption
          .flatMap(c => Option(c.optString("url", "")).filter(isValidHttpUrl))
      }
      .orElse {
        Option(item.optJSONArray("display_resources"))
          .filter(!_.isEmpty)
          .flatMap { resources =>
            (0 until resources.length())
              .map(resources.getJSONObject)
              .sortBy(dr => -(dr.optInt("config_width", 0).toLong * dr.optInt("config_height", 0).toLong))
              .headOption
              .flatMap { dr =>
                Option(dr.optString("src", ""))
                  .orElse(Option(dr.optString("url", "")))
                  .filter(isValidHttpUrl)
              }
          }
      }
      .orElse(Option(item.optString("display_url", "")).filter(isValidHttpUrl))

  /** Returns carousel children for both mobile (`carousel_media`) and web (`edge_sidecar_to_children`) shapes. */
  def carouselItems(item: JSONObject): Option[JSONArray] =
    Option(item.optJSONArray("carousel_media"))
      .orElse(
        Option(item.optJSONObject("edge_sidecar_to_children")).flatMap { sidecar =>
          Option(sidecar.optJSONArray("edges")).map { edges =>
            val items = new JSONArray()
            for (index <- 0 until edges.length()) {
              Option(edges.getJSONObject(index).optJSONObject("node")).foreach(items.put)
            }
            items
          }
        }
      )

  def mediaItems(items: JSONArray): List[Media] =
    (0 until items.length()).flatMap { index =>
      val item = items.getJSONObject(index)
      carouselItems(item) match {
        case Some(carousel) if !carousel.isEmpty => mediaItems(carousel)
        case _ =>
          val downloadUrl = bestVideoUrl(item).orElse(bestImageUrl(item)).filter(isValidHttpUrl)
          val takenAt = item.optLong("taken_at", System.currentTimeMillis() / 1000L) * 1000L
          downloadUrl.map(url => Media(url.trim, takenAt)).toList
      }
    }.toList

  // ---------------------------------------------------------------------------
  // GraphQL responses
  // ---------------------------------------------------------------------------

  /**
   * Parses stories from the `xdt_api__v1__feed__reels_media__connection` GraphQL shape.
   *
   * @param userMap fallback user id -> username map, used when the response omits the user object.
   */
  def parseStories(response: JSONObject, userMap: Map[String, String]): List[UserStories] = {
    val data = dataOf(response)

    // Instagram answers with a null xdt_viewer.user when the session cookie is no longer valid.
    Option(data.optJSONObject("xdt_viewer")).foreach { viewer =>
      if (viewer.isNull("user") || viewer.optJSONObject("user") == null) {
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

    val edges = reelsMediaEdges(data)
    (0 until edges.length()).flatMap { index =>
      val node = edges.getJSONObject(index).getJSONObject("node")
      val nodeUser = Option(node.optJSONObject("user"))
      val userId = nodeUser.map(_.optString("pk", "")).filter(_.nonEmpty).getOrElse(node.optString("id", ""))
      val username = nodeUser
        .map(_.optString("username", ""))
        .filter(_.nonEmpty)
        .getOrElse(userMap.getOrElse(userId, userId))
      val items = Option(node.optJSONArray("items")).getOrElse(new JSONArray())
      val medias = mediaItems(items)
      Option.when(medias.nonEmpty)(UserStories(User(username, userId), medias))
    }.toList
  }

  /** Extracts highlight reel ids from the `highlights` GraphQL response. */
  def parseHighlightIds(response: JSONObject): List[String] =
    Option(dataOf(response).optJSONObject("highlights"))
      .flatMap(highlights => Option(highlights.optJSONArray("edges")))
      .filter(!_.isEmpty)
      .map { edges =>
        (0 until edges.length())
          .map(index => edges.getJSONObject(index).getJSONObject("node").getString("id"))
          .toList
      }
      .getOrElse(List.empty)

  /** Parses highlight media from a stories (`STORY` doc id) response. */
  def parseHighlightMedias(response: JSONObject): List[HighLightStoryMedia] = {
    val edges = reelsMediaEdges(dataOf(response))
    (0 until edges.length()).flatMap { index =>
      val node = edges.getJSONObject(index).getJSONObject("node")
      val title = Option(node.optString("title")).filter(_.nonEmpty).getOrElse("highlight")
      val items = Option(node.optJSONArray("items")).getOrElse(new JSONArray())
      mediaItems(items).map(media => HighLightStoryMedia(media.url, media.takenOn, title))
    }.toList
  }

  /** Parses feed posts from the `xdt_api__v1__feed__user_timeline_graphql_connection` shape. */
  def parseFeedMedias(response: JSONObject): List[Media] = {
    val edges = Option(response.optJSONObject("data"))
      .flatMap(data => Option(data.optJSONObject("xdt_api__v1__feed__user_timeline_graphql_connection")))
      .flatMap(connection => Option(connection.optJSONArray("edges")))
      .getOrElse(new JSONArray())

    val nodes = new JSONArray()
    for (index <- 0 until edges.length()) {
      Option(edges.getJSONObject(index).optJSONObject("node")).foreach(nodes.put)
    }
    mediaItems(nodes)
  }

  def parseMediaInfo(response: JSONObject): Option[Media] =
    Option(response.optJSONArray("items"))
      .filter(_.length() > 0)
      .flatMap(items => mediaItems(items).headOption)

  // ---------------------------------------------------------------------------
  // Plain endpoints / embedded HTML
  // ---------------------------------------------------------------------------

  def parseReelsTray(response: JSONObject): List[(User, List[String])] =
    Option(response.optJSONArray("tray"))
      .map { tray =>
        (0 until tray.length()).flatMap { index =>
          val reel = tray.getJSONObject(index)
          Option(reel.optJSONObject("user")).flatMap { user =>
            val username = user.optString("username", "")
            val userId = String.valueOf(reel.optLong("id", user.optLong("pk", 0)))
            val mediaIds = Option(reel.optJSONArray("media_ids"))
              .map(ids => (0 until ids.length()).map(position => String.valueOf(ids.get(position))).toList)
              .getOrElse(List.empty[String])

            Option.when(username.nonEmpty && mediaIds.nonEmpty)((User(username, userId), mediaIds))
          }
        }.toList
      }
      .getOrElse(List.empty)

  /** Extracts story media from the preloaded reels state embedded in a `/stories/<user>/` HTML page. */
  def parseStoriesPage(html: String): List[Media] = {
    val start = html.indexOf(ReelsMediaMarker)
    if (start < 0) {
      List.empty
    } else {
      val end = html.indexOf("</script>", start)
      val rawJson = html.substring(start, if (end > 0) end else html.length)
      val reelsMedia = new JSONObject(rawJson.stripSuffix(";"))
        .getJSONObject("xdt_api__v1__feed__reels_media")
        .getJSONArray("reels_media")

      if (reelsMedia.length() == 0) List.empty
      else {
        Option(reelsMedia.getJSONObject(0).optJSONArray("items"))
          .map(mediaItems)
          .getOrElse(List.empty)
      }
    }
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

  private def dataOf(response: JSONObject): JSONObject =
    Option(response.optJSONObject("data")).getOrElse(response)

  private def reelsMediaEdges(data: JSONObject): JSONArray =
    Option(data.optJSONObject("xdt_api__v1__feed__reels_media__connection"))
      .flatMap(connection => Option(connection.optJSONArray("edges")))
      .getOrElse(new JSONArray())
}
