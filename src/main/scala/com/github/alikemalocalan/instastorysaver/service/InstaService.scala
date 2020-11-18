package com.github.alikemalocalan.instastorysaver.service

import java.util.concurrent.CompletableFuture

import com.github.alikemalocalan.instastorysaver.model.{UrlOperation, User}
import com.github.alikemalocalan.instastorysaver.service.S3ClientService.{downloadUrl, uploadUrl}
import com.github.instagram4j.instagram4j.IGClient
import com.github.instagram4j.instagram4j.models.media.reel.{ReelImageMedia, ReelVideoMedia}
import com.github.instagram4j.instagram4j.models.media.timeline._
import com.github.instagram4j.instagram4j.requests.feed.FeedUserRequest
import org.apache.commons.logging.{Log, LogFactory}

import scala.collection.convert.ImplicitConversions._
import scala.concurrent.duration.DurationInt

case class UserStories(user: User, storyUrls: Seq[String])

case class UserFeedMedias(user: User, medias: Seq[UserFeedMedia])

case class UserFeedMedia(url: String, takenOn: Long)

object InstaService {
  val logger: Log = LogFactory.getLog(this.getClass)

  def getUserStory(user: User)(implicit client: IGClient): CompletableFuture[UserStories] =
    client.actions().story().userStory(user.userId).thenApply[UserStories](userStory => {
      val stories = if (userStory.getReel != null)
        userStory.getReel.getItems.flatMap {
          case video: ReelVideoMedia =>
            Some(video.getVideo_versions.maxBy(_.getHeight).getUrl)
          case photo: ReelImageMedia =>
            Some(photo.getImage_versions2.getCandidates.maxBy(_.getHeight).getUrl)
          case _ => None
        }
      else List()
      UserStories(user, stories)
    }).exceptionally { _ =>
      UserStories(user, List())
    }

  def getUserFeed(user: User)(implicit client: IGClient): CompletableFuture[UserFeedMedias] =
    new FeedUserRequest(user.userId).execute(client).thenApply[UserFeedMedias] { feed =>
      val feedList = feed.getItems
        .flatMap {
          case photo: TimelineImageMedia =>
            List(UserFeedMedia(photo.getImage_versions2.getCandidates.maxBy(_.getHeight).getUrl, photo.getTaken_at))
          case video: TimelineVideoMedia => List(UserFeedMedia(video.getVideo_versions.maxBy(_.getHeight).getUrl, video.getTaken_at))
          case album: TimelineCarouselMedia => album.getCarousel_media.flatMap {
            case albumPhoto: ImageCaraouselItem =>
              Some(UserFeedMedia(albumPhoto.getImage_versions2.getCandidates.maxBy(_.getHeight).getUrl, album.getTaken_at))
            case albumVideo: VideoCaraouselItem => Some(UserFeedMedia(albumVideo.getVideo_versions.maxBy(_.getHeight).getUrl, album.getTaken_at))
            case _ => None
          }
          case _ => List()
        }
      UserFeedMedias(user, feedList)
    }.exceptionally { _ =>
      UserFeedMedias(user, List())
    }

  def getFollowingUsers(implicit client: IGClient): Stream[User] = {
    client.getActions.users().findByUsername(getAccountUserName).thenApply[Stream[User]] { user =>
      user.followingFeed()
        .toStream
        .flatMap(user => user.getUsers.map { user =>
          logger.info(user.getUsername)
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

  def saveStoriesToS3(implicit client: IGClient): Unit = {
    val users: Stream[UserStories] = getStoriesAllFollowing(InstaService.getFollowingUsers)

    def uploadS3(users: Stream[UserStories]): Unit = {
      users.foreach { userStories =>
        logger.info(userStories)
        userStories.storyUrls
          .foreach { storyUrl =>
            val operation = UrlOperation(storyUrl, userStories.user.username, "stories")
            logger.info(operation)
            val file = downloadUrl(operation.url)
            uploadUrl(file, operation.filefullPath)
            file.delete()
          }
      }
    }

    uploadS3(users)
    Thread.sleep(5.minutes.toMillis)

    val handledUsers: Stream[UserStories] = getStoriesAllFollowing(users.filter(_.storyUrls.isEmpty).map(_.user))
    uploadS3(handledUsers)

  }


  def saveFeedsToS3(implicit client: IGClient): Unit = {
    val users = getFeedsAllFollowing(InstaService.getFollowingUsers)

    def uploadS3(users: Stream[UserFeedMedias]): Unit = {
      users.foreach { userFeedMedias =>
        logger.info(userFeedMedias)
        userFeedMedias.medias.foreach { feedMedia =>
          val operation = UrlOperation(feedMedia.url, userFeedMedias.user.username, "feeds")
          logger.info(operation)
          val file = downloadUrl(operation.url)
          uploadUrl(file, operation.filefullPath)
          file.delete()
        }
      }
    }

    uploadS3(users)
    Thread.sleep(5.minutes.toMillis)

    val handledUsers: Stream[UserFeedMedias] = getFeedsAllFollowing(users.filter(_.medias.isEmpty).map(_.user))
    uploadS3(handledUsers)
  }

}
