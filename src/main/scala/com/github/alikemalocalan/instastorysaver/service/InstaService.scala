package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.*
import com.instagram4j.web.Instagram4j
import org.apache.commons.logging.{Log, LogFactory}

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

object InstaService {
  private val logger: Log = LogFactory.getLog(getClass)

  def login(userName: String, password: String): Instagram4j =
    LoginService.login(userName, password)

  def fromSession(sessionId: String, csrfToken: String, username: String = ""): Instagram4j =
    LoginService.fromSession(sessionId, csrfToken, username)


  def getFollowingUsers(using client: Instagram4j): LazyList[User] =
    Try {
      val selfProfile = client.getProfile(client.username)
      Option(selfProfile.getFollowings(client.session))
        .map { paginator =>
          paginator.asScala.to(LazyList).flatMap { pageList =>
            pageList.asScala.map(profile => User(profile.username, profile.pk))
          }
        }
        .getOrElse(LazyList.empty[User])
    } match {
      case Success(users) => users
      case Failure(ex) =>
        logger.error(s"Failed to fetch following users: ${ex.getMessage}", ex)
        throw ex
    }

  def getUserStories(user: User)(using client: Instagram4j): UserStories =
    Try {
      val profile = client.getProfile(user.username)
      val stories = Option(profile.getStory)
        .map(_.asScala.toList)
        .getOrElse(List.empty)
        .map(story => Media(story.download_url, System.currentTimeMillis()))

      UserStories(user, stories)
    } match {
      case Success(userStories) => userStories
      case Failure(ex) =>
        logger.error(s"Failed to get stories for user ${user.username}: ${ex.getMessage}")
        UserStories(user, List.empty)
    }

  def getUserHighlights(user: User)(using client: Instagram4j): UserHighLightStoryMedias =
    Try {
      val profile = client.getProfile(user.username)
      val highlights = Option(profile.getHighlights)
        .map(_.asScala.toList)
        .getOrElse(List.empty)
        .map(story => HighLightStoryMedia(story.download_url, System.currentTimeMillis(), "highlight"))

      UserHighLightStoryMedias(user, highlights)
    } match {
      case Success(userHighlights) => userHighlights
      case Failure(ex) =>
        logger.error(s"Failed to get highlights for user ${user.username}: ${ex.getMessage}")
        UserHighLightStoryMedias(user, List.empty)
    }

  def getUserPosts(user: User, maxPages: Int = 1)(using client: Instagram4j): UserFeedMedias =
    Try {
      val profile = client.getProfile(user.username)
      val medias = Option(profile.getPosts(client.session))
        .map { paginator =>
          paginator.asScala
            .take(maxPages)
            .flatMap { postList =>
              postList.asScala.flatMap { post =>
                Option(post.download_url)
                  .map(_.map(url => Media(url, System.currentTimeMillis())).toSeq)
                  .getOrElse(Seq.empty)
              }
            }
            .toList
        }
        .getOrElse(List.empty)

      UserFeedMedias(user, medias)
    } match {
      case Success(userFeeds) => userFeeds
      case Failure(ex) =>
        logger.error(s"Failed to get posts for user ${user.username}: ${ex.getMessage}")
        UserFeedMedias(user, List.empty)
    }

  def getFeedStories()(using client: Instagram4j): LazyList[UserStories] =
    Try {
      Option(client.getFeedStories)
        .map(_.asScala.to(LazyList))
        .getOrElse(LazyList.empty)
        .flatMap { storyArray =>
          val storiesList = storyArray.toList
          storiesList.headOption.map { first =>
            val user   = User(first.username, first.userID)
            val medias = storiesList.map(s => Media(s.download_url, System.currentTimeMillis()))
            UserStories(user, medias)
          }
        }
    } match {
      case Success(stories) => stories
      case Failure(ex) =>
        logger.error(s"Failed to fetch feed stories: ${ex.getMessage}")
        LazyList.empty[UserStories]
    }

  def saveStories(destinationFolder: String)(using client: Instagram4j): Unit = {
    val operations = getFollowingUsers.flatMap { user =>
      getUserStories(user).medias.map { media =>
        UrlOperation(media.url, user.username, MediaType.Stories)
      }
    }
    FileService.saveLocally(operations, destinationFolder)
  }

  def saveFeeds(destinationFolder: String)(using client: Instagram4j): Unit = {
    val operations = getFollowingUsers.flatMap { user =>
      getUserPosts(user).medias.map { media =>
        UrlOperation(media.url, user.username, MediaType.Feeds)
      }
    }
    FileService.saveLocally(operations, destinationFolder)
  }

  def saveUserHighLightStories(destinationFolder: String)(using client: Instagram4j): Unit = {
    val operations = getFollowingUsers.flatMap { user =>
      getUserHighlights(user).medias.map { media =>
        UrlOperation(media.url, s"${user.username}/${media.title}", MediaType.Highlights)
      }
    }
    FileService.saveLocally(operations, destinationFolder)
  }
}

