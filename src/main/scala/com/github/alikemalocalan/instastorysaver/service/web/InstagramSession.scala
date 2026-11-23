package com.github.alikemalocalan.instastorysaver.service.web

/**
 * Credentials and identity of an authenticated Instagram web session.
 *
 * Replaces instagram4j's `Instagram4j` session holder; the library exposed nothing else that this
 * project still uses.
 */
case class InstagramSession(sessionId: String, csrfToken: String, username: String = "") {
  /** Numeric account id embedded in the session cookie (`<id>%3A...`). */
  def userId: String = sessionId.split("[:%]").headOption.getOrElse("")

  /** Cookie header value used by the Instagram web endpoints. */
  def cookie: String = {
    val csrfPart = if (csrfToken.nonEmpty) s"; csrftoken=$csrfToken" else ""
    s"sessionid=$sessionId; ds_user_id=$userId$csrfPart"
  }
}
