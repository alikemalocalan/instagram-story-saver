package com.github.alikemalocalan.instastorysaver.service.web.dto

import io.circe.Decoder

/**
 * Typed mirrors of the Instagram web JSON shapes.
 *
 * Every field is optional: Instagram omits fields depending on media type, account and rollout, and
 * Circe's derived decoder does not apply case-class defaults. Domain conversion in
 * `InstagramJsonParser` normalizes `None` to empty values, so a missing field never breaks parsing.
 */
object InstagramDtos {

  /** Instagram returns ids both as strings and as numbers. */
  final case class StringOrNumber(value: String)

  object StringOrNumber {
    given Decoder[StringOrNumber] =
      Decoder.decodeString
        .map(StringOrNumber(_))
        .or(Decoder.decodeLong.map(n => StringOrNumber(n.toString)))
        .or(Decoder.decodeBigDecimal.map(n => StringOrNumber(n.toString)))
  }

  final case class Node[A](node: A)
  final case class Edge[A](edges: Option[List[Node[A]]])

  final case class UserRef(pk: Option[StringOrNumber], username: Option[String])
  final case class XdtViewer(user: Option[io.circe.Json])

  final case class VideoCandidate(
      width: Option[Int],
      height: Option[Int],
      bandwidth_bps: Option[Int],
      url: Option[String]
  )

  final case class ResourceCandidate(
      config_width: Option[Int],
      config_height: Option[Int],
      src: Option[String],
      url: Option[String]
  )

  final case class ImageCandidate(width: Option[Int], height: Option[Int], url: Option[String])
  final case class ImageVersions(candidates: Option[List[ImageCandidate]])

  final case class MediaItem(
      taken_at: Option[Long],
      video_versions: Option[List[VideoCandidate]],
      video_resources: Option[List[ResourceCandidate]],
      image_versions2: Option[ImageVersions],
      display_resources: Option[List[ResourceCandidate]],
      display_url: Option[String],
      carousel_media: Option[List[MediaItem]],
      edge_sidecar_to_children: Option[Edge[MediaItem]]
  )

  final case class StoryNode(
      id: Option[StringOrNumber],
      user: Option[UserRef],
      items: Option[List[MediaItem]],
      title: Option[String]
  )

  final case class IdNode(id: Option[StringOrNumber])
  final case class ReelsConnection(edges: Option[List[Node[StoryNode]]])

  final case class StoriesData(
      xdt_viewer: Option[XdtViewer],
      xdt_api__v1__feed__reels_media__connection: Option[ReelsConnection]
  )
  final case class StoriesResponse(data: Option[StoriesData])

  final case class HighlightsData(highlights: Option[Edge[IdNode]])
  final case class HighlightsResponse(data: Option[HighlightsData])

  final case class FeedData(xdt_api__v1__feed__user_timeline_graphql_connection: Option[Edge[MediaItem]])
  final case class FeedResponse(data: Option[FeedData])

  final case class MediaInfoResponse(items: Option[List[MediaItem]])

  final case class TrayReel(
      id: Option[StringOrNumber],
      user: Option[UserRef],
      media_ids: Option[List[StringOrNumber]]
  )
  final case class TrayResponse(tray: Option[List[TrayReel]])

  final case class FollowingUser(pk: Option[StringOrNumber], username: Option[String])
  final case class FollowingData(users: Option[List[FollowingUser]], next_max_id: Option[StringOrNumber])
  final case class FollowingResponse(data: Option[FollowingData])

  final case class ReelsMediaState(reels_media: Option[List[ReelsMediaItem]])
  final case class ReelsMediaItem(items: Option[List[MediaItem]])
  final case class StoriesPageResponse(xdt_api__v1__feed__reels_media: Option[ReelsMediaState])

  final case class GraphQlError(summary: Option[String], message: Option[String])
  final case class GraphQlErrorResponse(errors: Option[List[GraphQlError]])
}
