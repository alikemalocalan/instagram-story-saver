package com.github.alikemalocalan.instastorysaver.service

import com.instagram4j.web.Instagram4j
import org.slf4j.{Logger, LoggerFactory}

object LoginService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def fromSession(sessionId: String, csrfToken: String, username: String = ""): Instagram4j = {
    logger.info(
      s"Initializing Instagram client with session cookies for ${if (username.nonEmpty) username else "user"}..."
    )
    val client = Instagram4j.getInstance(sessionId.trim, csrfToken.trim)
    if (username.nonEmpty) {
      client.username = username
    }
    client
  }
}
