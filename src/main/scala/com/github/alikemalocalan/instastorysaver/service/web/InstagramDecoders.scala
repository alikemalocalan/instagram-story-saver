package com.github.alikemalocalan.instastorysaver.service.web

import com.github.alikemalocalan.instastorysaver.service.web.dto.InstagramDtos.*
import upickle.default.*

/**
 * Central place for all uPickle `ReadWriter`s.
 *
 * DTOs stay plain typed case classes; uPickle derives the JSON codecs here via macros. The
 * recursive carousel shape needs `MediaItem` to be a lazy given.
 */
object InstagramDecoders {
  type RW[A] = ReadWriter[A]

  given [A: RW]: RW[Node[A]] = macroRW
  given [A: RW]: RW[Edge[A]] = macroRW

  given RW[XdtViewer] = macroRW
  given RW[UserRef] = macroRW
  given RW[VideoCandidate] = macroRW
  given RW[ResourceCandidate] = macroRW
  given RW[ImageCandidate] = macroRW
  given RW[ImageVersions] = macroRW
  lazy given mediaItemRW: RW[MediaItem] = macroRW

  given RW[StoryNode] = macroRW
  given RW[IdNode] = macroRW
  given RW[ReelsConnection] = macroRW
  given RW[StoriesData] = macroRW
  given RW[StoriesResponse] = macroRW
  given RW[HighlightsData] = macroRW
  given RW[HighlightsResponse] = macroRW
  given RW[FeedData] = macroRW
  given RW[FeedResponse] = macroRW
  given RW[MediaInfoResponse] = macroRW
  given RW[TrayReel] = macroRW
  given RW[TrayResponse] = macroRW
  given RW[FollowingUser] = macroRW
  given RW[FollowingData] = macroRW
  given RW[FollowingResponse] = macroRW
  given RW[ReelsMediaItem] = macroRW
  given RW[ReelsMediaState] = macroRW
  given RW[StoriesPageResponse] = macroRW
  given RW[GraphQlError] = macroRW
  given RW[GraphQlErrorResponse] = macroRW
}
