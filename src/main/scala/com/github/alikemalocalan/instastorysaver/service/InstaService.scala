package com.github.alikemalocalan.instastorysaver.service

import java.util.concurrent.CompletableFuture

import com.github.alikemalocalan.instastorysaver.LoginService
import com.github.alikemalocalan.instastorysaver.model.User
import com.github.instagram4j.instagram4j.IGClient
import com.github.instagram4j.instagram4j.models.media.reel.{ReelImageMedia, ReelVideoMedia}

import scala.collection.convert.ImplicitConversionsToScala._

object InstaService {

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
      val x = user.followingFeed()
        .toList
        .flatMap(_.getUsers.map(user => User(user.getUsername, user.getPk)))
      x.foreach(println(_))
      x
    }

  def getAccountUserName(implicit client: IGClient): String = {
    client.getSelfProfile.getUsername
  }

  def login(userName: String, password: String): IGClient = {
    LoginService.serializeLogin(userName, password)
  }

}
