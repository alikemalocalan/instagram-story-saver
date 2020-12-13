package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model._
import com.github.instagram4j.instagram4j.IGClient
import com.github.instagram4j.instagram4j.models.media.reel.{ReelImageMedia, ReelMedia, ReelVideoMedia}
import com.github.instagram4j.instagram4j.models.media.timeline._
import com.github.instagram4j.instagram4j.requests.feed.{FeedReelsMediaRequest, FeedUserRequest, FeedUserStoryRequest}
import com.github.instagram4j.instagram4j.requests.highlights.HighlightsUserTrayRequest
import com.github.instagram4j.instagram4j.utils.IGUtils
import org.apache.commons.logging.{Log, LogFactory}

import java.util
import java.util.concurrent.CompletableFuture
import scala.collection.convert.ImplicitConversions._


object InstaService {
  val logger: Log = LogFactory.getLog(this.getClass)

  def getFollowingUsers(implicit client: IGClient): Stream[User] = {
    client.getActions.users().findByUsername(getAccountUserName).thenApply[Stream[User]] { user =>
      user.followingFeed()
        .toStream
        .flatMap(user => user.getUsers.map { user =>
          User(user.getUsername, user.getPk)
        })
    }.join()
  }

  def getAccountUserName(implicit client: IGClient): String = {
    client.getSelfProfile.getUsername
  }

  def login(userName: String, password: String): IGClient = {
    LoginService.serializeLogin(userName, password)
  }

  def getStoriesAllFollowing(users: Stream[User])(implicit client: IGClient): Stream[UserStories] =
    users.map(user => InstaService.getUserStory(user).join())

  def getFeedsAllFollowing(users: Stream[User])(implicit client: IGClient): Stream[UserFeedMedias] =
    users.map(user => InstaService.getUserFeed(user).join())

  def getUserHighLightStoriesAllFollowing(users: Stream[User])(implicit client: IGClient): Stream[UserHighLightStoryMedias] =
    users.map(user => InstaService.getUserHighLightStory(user).join())

  def saveStories(enableS3: Boolean, defaultFolder: Option[String] = None)(implicit client: IGClient): Unit = {
    val users: Stream[UserStories] = getStoriesAllFollowing(InstaService.getFollowingUsers)

    val operations = users
      .flatMap { userStories =>
        userStories.medias
          .map { media =>
            UrlOperation(media.url, userStories.folderPathPrefix, "stories")
          }
      }
    S3ClientService.upload(operations, enableS3, defaultFolder)
  }

  def saveFeeds(enableS3: Boolean, defaultFolder: Option[String] = None)(implicit client: IGClient): Unit = {
    val users = getFeedsAllFollowing(InstaService.getFollowingUsers)

    val operations = users
      .flatMap { userStories =>
        userStories.medias
          .map { media =>
            UrlOperation(media.url, userStories.folderPathPrefix, "feeds")
          }
      }
    S3ClientService.upload(operations, enableS3, defaultFolder)
  }

  def saveUserHighLightStories(enableS3: Boolean, defaultFolder: Option[String] = None)(implicit client: IGClient): Unit = {
    val users = getUserHighLightStoriesAllFollowing(InstaService.getFollowingUsers)

    val operations = users
      .flatMap { userStories =>
        userStories.medias
          .map { media =>
            UrlOperation(media.url, s"${userStories.folderPathPrefix}/${media.title}", "highLightStories")
          }
      }
    S3ClientService.upload(operations, enableS3, defaultFolder)
  }

  private def reelMediaToUserStories(item: ReelMedia): Option[Media] = {
    item match {
      case video: ReelVideoMedia =>
        Some(Media(video.getVideo_versions.maxBy(_.getHeight).getUrl, video.getTaken_at))
      case photo: ReelImageMedia =>
        Some(Media(photo.getImage_versions2.getCandidates.maxBy(_.getHeight).getUrl, photo.getTaken_at))
      case _ => None
    }
  }

  private def timelineMediaToUserFeeds(item: TimelineMedia): Seq[TimelinedMedia] = {
    item match {
      case photo: TimelineImageMedia =>
        List(Media(photo.getImage_versions2.getCandidates.maxBy(_.getHeight).getUrl, photo.getTaken_at))
      case video: TimelineVideoMedia => List(Media(video.getVideo_versions.maxBy(_.getHeight).getUrl, video.getTaken_at))
      case album: TimelineCarouselMedia => album.getCarousel_media.toList.flatMap {
        case albumPhoto: ImageCaraouselItem =>
          Some(Media(albumPhoto.getImage_versions2.getCandidates.maxBy(_.getHeight).getUrl, album.getTaken_at))
        case albumVideo: VideoCaraouselItem => Some(Media(albumVideo.getVideo_versions.maxBy(_.getHeight).getUrl, album.getTaken_at))
        case _ => None
      }
      case _ => List()
    }

  }

  private def getUserStory(user: User)(implicit client: IGClient): CompletableFuture[UserStories] =
    new FeedUserStoryRequest(user.userId).execute(client).thenApply[UserStories] { userStory =>
      val stories = if (userStory.getReel != null)
        userStory.getReel.getItems.flatMap(reelMediaToUserStories)
      else List()
      UserStories(user, stories)
    }.exceptionally { _ =>
      UserStories(user, List())
    }

  private def getUserFeed(user: User)(implicit client: IGClient): CompletableFuture[UserFeedMedias] =
    new FeedUserRequest(user.userId).execute(client).thenApply[UserFeedMedias] { feed =>
      val feedList = feed.getItems.flatMap(timelineMediaToUserFeeds)
      UserFeedMedias(user, feedList)
    }.exceptionally { _ =>
      UserFeedMedias(user, List())
    }

  private def getUserHighLightStory(user: User)(implicit client: IGClient): CompletableFuture[UserHighLightStoryMedias] =
    new HighlightsUserTrayRequest(user.userId).execute(client).thenApply[UserHighLightStoryMedias] { response =>
      type linkedMap = util.LinkedHashMap[String, Any]
      val stories: List[HighLightStoryMedia] = response.getTray.toList.flatMap { story =>
        new FeedReelsMediaRequest(story.getId).execute(client).get()
          .getExtraProperties.get("reels")
          .asInstanceOf[linkedMap]
          .get(story.getId).asInstanceOf[linkedMap]
          .get("items")
          .asInstanceOf[util.List[linkedMap]]
          .map(item => IGUtils.convertToView(item, classOf[TimelineMedia]))
          .flatMap(timelineMediaToUserFeeds)
          .map(_.toUserHighLightStoryMedia(story.getTitle))
      }
      UserHighLightStoryMedias(user, stories)
    }.exceptionally { _ =>
      UserHighLightStoryMedias(user, List())
    }
}
