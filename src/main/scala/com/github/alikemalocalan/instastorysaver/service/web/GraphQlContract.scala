package com.github.alikemalocalan.instastorysaver.service.web

/**
 * Single source of truth for Instagram web endpoints used by the app.
 *
 * If Instagram changes a doc id, URL, operation name or response shape, this is the first file an
 * AI agent (or a human) should update. `InstagramWebClient` and the parser tests reference these
 * values/strings, so a change stays small and discoverable.
 */
object GraphQlContract {
  val WebBase: String = "https://www.instagram.com"

  final case class Endpoint(
      name: String,
      url: String,
      docId: String,
      operation: String,
      responsePaths: Seq[String],
      parser: String
  )

  val Stories = Endpoint(
    name = "stories",
    url = s"$WebBase/graphql/query",
    docId = "9558504827580036",
    operation = "xdt_api__v1__feed__reels_media__connection",
    responsePaths = Seq("data.xdt_api__v1__feed__reels_media__connection.edges"),
    parser = "InstagramJsonParser.parseStories"
  )

  val Highlights = Endpoint(
    name = "highlights",
    url = s"$WebBase/graphql/query",
    docId = "9719220061527365",
    operation = "highlights",
    responsePaths = Seq("data.highlights.edges"),
    parser = "InstagramJsonParser.parseHighlightIds"
  )

  val ProfilePosts = Endpoint(
    name = "profile-posts",
    url = s"$WebBase/api/graphql",
    docId = "30543471925243353",
    operation = "PolarisProfilePostsQuery",
    responsePaths = Seq("data.xdt_api__v1__feed__user_timeline_graphql_connection.edges"),
    parser = "InstagramJsonParser.parseFeedMedias"
  )

  val ReelsTray = Endpoint(
    name = "reels-tray",
    url = s"$WebBase/api/v1/feed/reels_tray/",
    docId = "",
    operation = "reels_tray",
    responsePaths = Seq("tray"),
    parser = "InstagramJsonParser.parseReelsTray"
  )

  val MediaInfo = Endpoint(
    name = "media-info",
    url = s"$WebBase/api/v1/media/{mediaId}/info/",
    docId = "",
    operation = "media_info",
    responsePaths = Seq("items"),
    parser = "InstagramJsonParser.parseMediaInfo"
  )

  val Following = Endpoint(
    name = "following",
    url = s"$WebBase/api/v1/friendships/{userId}/following/",
    docId = "",
    operation = "friendships_following",
    responsePaths = Seq("data.users", "data.next_max_id"),
    parser = "InstagramJsonParser.parseFollowingPage"
  )

  val all: Seq[Endpoint] =
    Seq(Stories, Highlights, ProfilePosts, ReelsTray, MediaInfo, Following)

  /** Human/AI readable map used in logs and documentation. */
  def describe: String =
    all.map { endpoint =>
      s"- ${endpoint.name}: docId=${endpoint.docId} operation=${endpoint.operation} " +
        s"paths=${endpoint.responsePaths.mkString(",")} parser=${endpoint.parser}"
    }.mkString("\n")
}
