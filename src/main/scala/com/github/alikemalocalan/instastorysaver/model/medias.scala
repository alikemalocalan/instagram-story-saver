package com.github.alikemalocalan.instastorysaver.model


trait UserMedias {
  val user: User

  def medias: Seq[TimelinedMedia]

  def folderPathPrefix: String = user.username
}

trait TimelinedMedia {
  val url: String
  val takenOn: Long

  def toUserHighLightStoryMedia(title: String): HighLightStoryMedia = HighLightStoryMedia(url, takenOn, title)
}

case class Media(url: String, takenOn: Long) extends TimelinedMedia

case class HighLightStoryMedia(url: String, takenOn: Long, title: String) extends TimelinedMedia

case class UserStories(user: User, medias: Seq[TimelinedMedia]) extends UserMedias

case class UserFeedMedias(user: User, medias: Seq[TimelinedMedia]) extends UserMedias

case class UserHighLightStoryMedias(user: User, medias: Seq[HighLightStoryMedia]) extends UserMedias