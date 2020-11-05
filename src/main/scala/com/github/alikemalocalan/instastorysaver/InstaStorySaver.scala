package com.github.alikemalocalan.instastorysaver

import com.github.alikemalocalan.instastorysaver.service.InstaService
import com.github.instagram4j.instagram4j.IGClient

object InstaStorySaver extends App {

  implicit val client: IGClient = InstaService
    .login("username", "password")

  val followingUsers = InstaService.getFollowingUsers

  followingUsers.thenAccept { userList =>
    userList.foreach { user =>
      InstaService.getUserStory(user.userId).thenAccept { list =>
        list.filter(_.isDefined).foreach(println)
      }
    }
  }
}
