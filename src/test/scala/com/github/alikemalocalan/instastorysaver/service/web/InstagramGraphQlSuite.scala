package com.github.alikemalocalan.instastorysaver.service.web

import com.github.alikemalocalan.instastorysaver.model.User
import com.github.alikemalocalan.instastorysaver.service.web.dto.InstagramDtos.GraphQlErrorResponse
import io.circe.Json
import munit.FunSuite

import scala.io.Source

/**
 * Fixture-based regression tests for Instagram GraphQL/HTML parsing.
 *
 * When Instagram changes a response shape, update the JSON under `src/test/resources/graphql`
 * together with the parser. The [GRAPHQL-SHAPE] / [GRAPHQL-ERROR] log prefixes point at the exact
 * parser that needs attention.
 */
final class InstagramGraphQlSuite extends FunSuite {
  import InstagramJsonParser.*

  private def fixture(name: String): Json = {
    val stream = Option(getClass.getResourceAsStream(s"/graphql/$name"))
      .getOrElse(throw new IllegalArgumentException(s"Missing fixture: $name"))
    val raw = Source.fromInputStream(stream, "UTF-8")
    try parseJson(raw.mkString, name).getOrElse(throw new IllegalArgumentException(s"Invalid fixture: $name"))
    finally raw.close()
  }

  private def htmlFixture(name: String): String = {
    val stream = Option(getClass.getResourceAsStream(s"/graphql/$name"))
      .getOrElse(throw new IllegalArgumentException(s"Missing fixture: $name"))
    val raw = Source.fromInputStream(stream, "UTF-8")
    try raw.mkString
    finally raw.close()
  }

  test("stories: picks the highest-quality video and the username from the node") {
    val stories = parseStories(fixture("stories.json"), Map.empty)
    assertEquals(stories.size, 1)
    assertEquals(stories.head.user.username, "alice")
    assertEquals(stories.head.user.userId, "123")
    assertEquals(stories.head.medias.map(_.url), List("https://cdn.example.com/high.mp4"))
    assertEquals(stories.head.medias.head.takenOn, 1700000000000L)
  }

  test("stories: an unexpected response shape degrades to an empty result instead of throwing") {
    assertEquals(parseStories(fixture("stories_wrong_shape.json"), Map.empty), List.empty)
  }

  test("stories: expired session is detected") {
    intercept[IllegalStateException] {
      parseStories(fixture("expired_session.json"), Map.empty)
    }
  }

  test("feed: flattens carousel children and single images") {
    val medias = parseFeedMedias(fixture("feed.json"))
    assertEquals(
      medias.map(_.url),
      List(
        "https://cdn.example.com/carousel-1.jpg",
        "https://cdn.example.com/carousel-2.jpg",
        "https://cdn.example.com/single.jpg"
      )
    )
  }

  test("highlights: parses ids and titled medias") {
    assertEquals(parseHighlightIds(fixture("highlights.json")), List("hl-1", "hl-2"))

    val medias = parseHighlightMedias(fixture("highlight_medias.json"))
    assertEquals(medias.map(_.url), List("https://cdn.example.com/highlight.jpg"))
    assertEquals(medias.map(_.title), List("Trip"))
  }

  test("following: parses users and next cursor") {
    val (users, cursor) = parseFollowingPage(fixture("following.json"))
    assertEquals(users.map(_.username), List("alice", "bob"))
    assertEquals(cursor, Some("cursor-123"))
  }

  test("reels tray: parses user, id and media ids") {
    val tray = parseReelsTray(fixture("reels_tray.json"))
    assertEquals(tray.size, 1)
    assertEquals(tray.head._1, User("alice", "123"))
    assertEquals(tray.head._2, List("10", "11"))
  }

  test("media info: parses a single media") {
    assertEquals(parseMediaInfo(fixture("media_info.json")).map(_.url), Some("https://cdn.example.com/info.jpg"))
  }

  test("stories page: ignores trailing extensions object in preloaded HTML") {
    assertEquals(
      parseStoriesPage(htmlFixture("stories_page.html")).map(_.url),
      List("https://cdn.example.com/page.jpg")
    )
  }

  test("GraphQL Not Found error maps to an empty reels response") {
    import InstagramDecoders.{given, *}
    val client = new InstagramWebClient(InstagramSession("123:abc", "csrf", "alice"))
    val error = fixture("not_found_error.json")
      .as[GraphQlErrorResponse]
      .toOption
      .flatMap(_.errors)
      .getOrElse(Nil)
      .head
    val result = client.resolveGraphQlError(error, "not found")
    assertEquals(result.noSpaces.contains("\"edges\":[]"), true)
  }

  test("form body encoding uses x-www-form-urlencoded rules") {
    assertEquals(
      InstagramHttpClient.formBody(Seq("a b" -> "x&y", "doc_id" -> "1")),
      "a+b=x%26y&doc_id=1"
    )
  }

  test("GraphQlContract exposes the active doc ids") {
    assertEquals(GraphQlContract.Stories.docId, "9558504827580036")
    assertEquals(GraphQlContract.Highlights.docId, "9719220061527365")
    assertEquals(GraphQlContract.ProfilePosts.docId, "30543471925243353")
  }
}
