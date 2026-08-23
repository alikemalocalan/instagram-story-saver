package com.github.alikemalocalan.instastorysaver.model

sealed trait TimelinedMedia {
  def url: String
  def takenOn: Long

  def toUserHighLightStoryMedia(title: String): HighLightStoryMedia =
    HighLightStoryMedia(url, takenOn, title)
}

case class Media(url: String, takenOn: Long) extends TimelinedMedia

case class HighLightStoryMedia(url: String, takenOn: Long, title: String) extends TimelinedMedia {
  def safeTitle: String =
    Option(title).map(_.trim).filter(s => s.nonEmpty && !s.equalsIgnoreCase("null")).getOrElse("highlights")
}

sealed trait UserMedias {
  def user: User
  def medias: Seq[TimelinedMedia]
  def folderPathPrefix: String = user.folderName
}

case class UserStories(user: User, medias: Seq[TimelinedMedia]) extends UserMedias

case class UserFeedMedias(user: User, medias: Seq[TimelinedMedia]) extends UserMedias

case class UserHighLightStoryMedias(user: User, medias: Seq[HighLightStoryMedia]) extends UserMedias