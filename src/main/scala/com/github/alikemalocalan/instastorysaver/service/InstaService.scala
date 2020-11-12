package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.{UrlOperation, User}
import com.github.alikemalocalan.instastorysaver.service.S3ClientService.{downloadUrl, uploadUrl}
import com.github.instagram4j.instagram4j.IGClient
import com.github.instagram4j.instagram4j.models.media.reel.{ReelImageMedia, ReelVideoMedia}
import org.apache.commons.logging.{Log, LogFactory}

import scala.collection.convert.ImplicitConversions._

case class UserStories(username: String, storyUrls: Seq[String])

object InstaService {
  val logger: Log = LogFactory.getLog(this.getClass)

  def getUserStory(user: User)(implicit client: IGClient): UserStories = {
    val userStory = client.actions().story().userStory(user.userId).get()
    val stories =
      if (userStory.getReel != null)
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

  def saveStoriesToS3(implicit client: IGClient): Unit = {
    getStoriesAllFollowing
      .filter(_.storyUrls.nonEmpty)
      .foreach { user =>
        logger.info(user)
        user.storyUrls.foreach { url =>
          val operation = UrlOperation(url, user.username)
          logger.info(operation)
          uploadUrl(downloadUrl(operation.url), operation.filefullPath)
        }
      }
  }

}
