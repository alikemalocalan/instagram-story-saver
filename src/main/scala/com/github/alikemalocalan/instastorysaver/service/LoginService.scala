package com.github.alikemalocalan.instastorysaver.service

import com.instagram4j.web.Instagram4j
import org.apache.commons.logging.{Log, LogFactory}

object LoginService {
  private val logger: Log = LogFactory.getLog(getClass)

  def login(username: String, password: String): Instagram4j = {
    logger.info(s"Logging in as $username...")
    new Instagram4j(username, password)
  }

  def fromSession(sessionId: String, csrfToken: String, username: String = ""): Instagram4j = {
    logger.info(s"Initializing Instagram client with session cookies for ${if (username.nonEmpty) username else "user"}...")
    val client = Instagram4j.getInstance(sessionId, csrfToken)
    if (username.nonEmpty) {
      client.username = username
    }
    client
  }
}



