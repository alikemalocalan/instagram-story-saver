package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.{UrlOperation, User}
import com.github.alikemalocalan.instastorysaver.service.S3ClientService.{downloadUrl, uploadUrl}
import com.github.instagram4j.instagram4j.IGClient
import com.github.instagram4j.instagram4j.models.media.reel.{ReelImageMedia, ReelVideoMedia}
import com.github.instagram4j.instagram4j.models.media.timeline._
import com.github.instagram4j.instagram4j.requests.feed.FeedUserRequest
import org.apache.commons.logging.{Log, LogFactory}

import scala.annotation.tailrec
import scala.collection.convert.ImplicitConversions._
import scala.util.{Failure, Success, Try}

case class UserStories(username: String, storyUrls: Seq[String])

case class UserFeedMedias(username: String, medias: Seq[UserFeedMedia])

case class UserFeedMedia(url: String, takenOn: Long)

object InstaService {
  val logger: Log = LogFactory.getLog(this.getClass)

  @tailrec
  def getUserStory(user: User, reTryCount: Int = 3)(implicit client: IGClient): UserStories =
    Try(client.actions().story().userStory(user.userId).get()) match {
      case Success(userStory) => {
        val stories = if (userStory.getReel != null)
          userStory.getReel.getItems.flatMap {
            case video: ReelVideoMedia =>
              Some(video.getVideo_versions.maxBy(_.getHeight).getUrl)
            case photo: ReelImageMedia =>
              Some(photo.getImage_versions2.getCandidates.maxBy(_.getHeight).getUrl)
            case _ => None
          }
        else List()
        UserStories(user.username, stories)
      }

      case Failure(_) => if (reTryCount > 0) {
        logger.info(s"Retrying UserStory : $reTryCount")
        getUserStory(user, reTryCount - 1)
      } else UserStories(user.username, List())

    }

  @tailrec
  def getUserFeed(user: User, reTryCount: Int = 3)(implicit client: IGClient): UserFeedMedias = {
    Try(new FeedUserRequest(user.userId).execute(client).get()) match {
      case Success(value) => {
        val feedList = value.getItems
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
        UserFeedMedias(user.username, feedList)
      }
      case Failure(_) => if (reTryCount > 0) {
        logger.info(s"Retrying UserFeed : $reTryCount")
        getUserFeed(user, reTryCount - 1)
      } else UserFeedMedias(user.username, List())
    }

  }

  def getFollowingUsers(implicit client: IGClient): Stream[User] = {
    val user = client.getActions.users().findByUsername(getAccountUserName).get
    user.followingFeed()
      .toStream
      .flatMap(user => user.getUsers.map { user =>
        logger.info(user.getUsername)
        User(user.getUsername, user.getPk)
      })
  }

  def getAccountUserName(implicit client: IGClient): String = {
    client.getSelfProfile.getUsername
  }

  def login(userName: String, password: String): IGClient = {
    LoginService.serializeLogin(userName, password)
  }

  def getStoriesAllFollowing(implicit client: IGClient): Stream[UserStories] = {
    InstaService
      .getFollowingUsers
      .map(user => InstaService.getUserStory(user))
  }

  def getFeedsAllFollowing(implicit client: IGClient): Stream[UserFeedMedias] = {
    InstaService
      .getFollowingUsers
      .map(user => InstaService.getUserFeed(user))
  }

  def saveStoriesToS3(implicit client: IGClient): Unit = {
    getStoriesAllFollowing
      .filter(_.storyUrls.nonEmpty)
      .foreach { user =>
        logger.info(user)
        user.storyUrls.foreach { url =>
          val operation = UrlOperation(url, user.username, "stories")
          logger.info(operation)
          uploadUrl(downloadUrl(operation.url), operation.filefullPath)
        }
      }
  }


  def saveFeedsToS3(implicit client: IGClient): Unit = {
    getFeedsAllFollowing
      .filter(_.medias.nonEmpty)
      .foreach { user =>
        logger.info(user)
        user.medias.foreach { feedMedia =>
          val operation = UrlOperation(feedMedia.url, user.username, "feeds")
          logger.info(operation)
          uploadUrl(downloadUrl(operation.url), operation.filefullPath)
        }
      }
  }

}
