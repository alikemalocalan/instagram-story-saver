package com.github.alikemalocalan.instastorysaver

import com.typesafe.config.ConfigFactory
import java.io.File

trait Config {
  private val config = ConfigFactory.load()

  protected val username: String =
    if (config.hasPath("username")) config.getString("username") else ""
  protected val password: String =
    if (config.hasPath("password")) config.getString("password") else ""
  protected val sessionId: Option[String] =
    if (config.hasPath("session-id") && config.getString("session-id").trim.nonEmpty)
      Some(config.getString("session-id").trim)
    else None
  protected val csrfToken: Option[String] =
    if (config.hasPath("csrf-token") && config.getString("csrf-token").trim.nonEmpty)
      Some(config.getString("csrf-token").trim)
    else None

  protected val downloadFolder: String =
    if (config.hasPath("download-folder")) config.getString("download-folder")
    else s"${System.getProperty("user.home")}${File.separator}instagram-stories"

  protected val maxConcurrentDownloads: Int =
    if (config.hasPath("max-concurrent-downloads")) config.getInt("max-concurrent-downloads")
    else 3

  protected val requestDelayMs: Long =
    if (config.hasPath("request-delay-ms")) config.getLong("request-delay-ms")
    else 350L

  protected val retryCount: Int =
    if (config.hasPath("retry-count")) config.getInt("retry-count")
    else 3

  // Aliases for compatibility
  protected def userName: String = username
  protected def passWord: String = password
}


