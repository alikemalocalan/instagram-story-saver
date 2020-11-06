package com.github.alikemalocalan.instastorysaver.service

import java.util.concurrent.CompletableFuture

import com.github.alikemalocalan.instastorysaver.LoginService
import com.github.alikemalocalan.instastorysaver.model.{UrlOperation, User}
import com.github.instagram4j.instagram4j.IGClient
import com.github.instagram4j.instagram4j.models.media.reel.{ReelImageMedia, ReelVideoMedia}
import org.apache.commons.logging.{Log, LogFactory}

import scala.collection.convert.ImplicitConversionsToScala._

object InstaService {
  val logger: Log = LogFactory.getLog(this.getClass)

  def getUserStory(userPk: Long)(implicit client: IGClient): CompletableFuture[List[Option[String]]] =
    client.actions().story().userStory(userPk).thenApply { story =>
      if (story.getReel != null)
        story.getReel.getItems.toList.map {
          case video: ReelVideoMedia =>
            Some(video.getVideo_versions.maxBy(_.getHeight).getUrl)
          case photo: ReelImageMedia =>
            Some(photo.getImage_versions2.getCandidates.maxBy(_.getHeight).getUrl)
          case _ => None
        }
      else
        List()
    }

  def getFollowingUsers(implicit client: IGClient): CompletableFuture[List[User]] =
    client.getActions.users().findByUsername(getAccountUserName).thenApply { user =>
      val users = user.followingFeed()
        .toList
        .flatMap(_.getUsers.map(user => User(user.getUsername, user.getPk)))
      logger.debug(users.mkString(","))
      users
    }

  def getAccountUserName(implicit client: IGClient): String = {
    client.getSelfProfile.getUsername
  }

  def login(userName: String, password: String): IGClient = {
    LoginService.serializeLogin(userName, password)
  }

  def saveStoriesToS3(implicit client: IGClient): Unit = {
    val followingUsers = InstaService.getFollowingUsers

    followingUsers.thenAccept { userList =>
      userList.foreach { user =>
        InstaService.getUserStory(user.userId).thenAccept { list =>
          list.flatten.foreach { url =>
            val operation = UrlOperation(url, user.username)
            logger.debug(operation)
            S3ClientService.uploadUrl(operation)
          }
        }
      }
    }
  }

}
