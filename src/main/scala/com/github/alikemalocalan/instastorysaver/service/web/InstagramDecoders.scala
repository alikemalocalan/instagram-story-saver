package com.github.alikemalocalan.instastorysaver.service.web

import com.github.alikemalocalan.instastorysaver.service.web.dto.InstagramDtos.*
import io.circe.Decoder
import io.circe.generic.semiauto.*

/** All Circe decoders for Instagram web responses, kept in one place for fast agent updates. */
object InstagramDecoders {
  given [A: Decoder]: Decoder[Node[A]] = deriveDecoder
  given [A: Decoder]: Decoder[Edge[A]] = deriveDecoder

  // MediaItem <-> Edge[MediaItem] is recursive; lazy initialization breaks the cycle.
  lazy given mediaItemDecoder: Decoder[MediaItem] = deriveDecoder[MediaItem]

  given Decoder[ResourceCandidate] = deriveDecoder
  given Decoder[ImageCandidate] = deriveDecoder
  given Decoder[ImageVersions] = deriveDecoder
  given Decoder[VideoCandidate] = deriveDecoder
  given Decoder[XdtViewer] = deriveDecoder
  given Decoder[UserRef] = deriveDecoder
  given Decoder[StoryNode] = deriveDecoder
  given Decoder[IdNode] = deriveDecoder
  given Decoder[ReelsConnection] = deriveDecoder
  given Decoder[StoriesData] = deriveDecoder
  given Decoder[StoriesResponse] = deriveDecoder
  given Decoder[HighlightsData] = deriveDecoder
  given Decoder[HighlightsResponse] = deriveDecoder
  given Decoder[FeedData] = deriveDecoder
  given Decoder[FeedResponse] = deriveDecoder
  given Decoder[MediaInfoResponse] = deriveDecoder
  given Decoder[TrayReel] = deriveDecoder
  given Decoder[TrayResponse] = deriveDecoder
  given Decoder[FollowingUser] = deriveDecoder
  given Decoder[FollowingData] = deriveDecoder
  given Decoder[FollowingResponse] = deriveDecoder
  given Decoder[ReelsMediaItem] = deriveDecoder
  given Decoder[ReelsMediaState] = deriveDecoder
  given Decoder[StoriesPageResponse] = deriveDecoder
  given Decoder[GraphQlError] = deriveDecoder
  given Decoder[GraphQlErrorResponse] = deriveDecoder
}
