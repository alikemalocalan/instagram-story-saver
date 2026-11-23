package com.github.alikemalocalan.instastorysaver.service.web.dto

import ujson.Value
import upickle.default.ReadWriter

/**
 * Typed mirrors of the Instagram web JSON shapes.
 *
 * Every field is optional with a `None` default: Instagram omits fields depending on media type,
 * account and rollout, and uPickle only tolerates missing keys when the case class has a default.
 * Domain conversion in `InstagramJsonParser` normalizes `None` to empty values.
 */
object InstagramDtos {

  /** Instagram returns ids both as strings and as numbers. */
  final case class StringOrNumber(value: String)

  object StringOrNumber {
    // Reader[String] preserves the raw JSON number text, including large Instagram ids.
    given ReadWriter[StringOrNumber] =
      upickle.default.readwriter[String].bimap[StringOrNumber](_.value, StringOrNumber(_))
  }

  final case class Node[A](node: A)
  final case class Edge[A](edges: Option[List[Node[A]]] = None)

  final case class UserRef(pk: Option[StringOrNumber] = None, username: Option[String] = None)
  final case class XdtViewer(user: Option[Value] = None)

  final case class VideoCandidate(
      width: Option[Int] = None,
      height: Option[Int] = None,
      bandwidth_bps: Option[Int] = None,
      url: Option[String] = None
  )

  final case class ResourceCandidate(
      config_width: Option[Int] = None,
      config_height: Option[Int] = None,
      src: Option[String] = None,
      url: Option[String] = None
  )

  final case class ImageCandidate(
      width: Option[Int] = None,
      height: Option[Int] = None,
      url: Option[String] = None
  )
  final case class ImageVersions(candidates: Option[List[ImageCandidate]] = None)

  final case class MediaItem(
      taken_at: Option[Long] = None,
      video_versions: Option[List[VideoCandidate]] = None,
      video_resources: Option[List[ResourceCandidate]] = None,
      image_versions2: Option[ImageVersions] = None,
      display_resources: Option[List[ResourceCandidate]] = None,
      display_url: Option[String] = None,
      carousel_media: Option[List[MediaItem]] = None,
      edge_sidecar_to_children: Option[Edge[MediaItem]] = None
  )

  final case class StoryNode(
      id: Option[StringOrNumber] = None,
      user: Option[UserRef] = None,
      items: Option[List[MediaItem]] = None,
      title: Option[String] = None
  )

  final case class IdNode(id: Option[StringOrNumber] = None)
  final case class ReelsConnection(edges: Option[List[Node[StoryNode]]] = None)

  final case class StoriesData(
      xdt_viewer: Option[XdtViewer] = None,
      xdt_api__v1__feed__reels_media__connection: Option[ReelsConnection] = None
  )
  final case class StoriesResponse(data: Option[StoriesData] = None)

  final case class HighlightsData(highlights: Option[Edge[IdNode]] = None)
  final case class HighlightsResponse(data: Option[HighlightsData] = None)

  final case class FeedData(
      xdt_api__v1__feed__user_timeline_graphql_connection: Option[Edge[MediaItem]] = None
  )
  final case class FeedResponse(data: Option[FeedData] = None)

  final case class MediaInfoResponse(items: Option[List[MediaItem]] = None)

  final case class TrayReel(
      id: Option[StringOrNumber] = None,
      user: Option[UserRef] = None,
      media_ids: Option[List[StringOrNumber]] = None
  )
  final case class TrayResponse(tray: Option[List[TrayReel]] = None)

  final case class FollowingUser(pk: Option[StringOrNumber] = None, username: Option[String] = None)
  final case class FollowingData(
      users: Option[List[FollowingUser]] = None,
      next_max_id: Option[StringOrNumber] = None
  )
  final case class FollowingResponse(data: Option[FollowingData] = None)

  final case class ReelsMediaState(reels_media: Option[List[ReelsMediaItem]] = None)
  final case class ReelsMediaItem(items: Option[List[MediaItem]] = None)
  final case class StoriesPageResponse(xdt_api__v1__feed__reels_media: Option[ReelsMediaState] = None)

  final case class GraphQlError(summary: Option[String] = None, message: Option[String] = None)
  final case class GraphQlErrorResponse(errors: Option[List[GraphQlError]] = None)
}
