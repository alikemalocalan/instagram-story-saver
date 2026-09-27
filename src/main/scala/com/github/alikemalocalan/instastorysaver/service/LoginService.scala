package com.github.alikemalocalan.instastorysaver.service

import com.instagram4j.web.Instagram4j
import okhttp3.{Interceptor, Response}
import org.slf4j.{Logger, LoggerFactory}

object LoginService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Configures the shared OkHttp client to disable automatic redirect following.
   *
   * Workaround for instagram4j limitation:
   * instagram4j's default OkHttpClient configuration (Utils.client) has automatic redirect
   * following enabled (followRedirects = true). When a session expires, Instagram redirects
   * to /accounts/login/ or /login/, which causes an infinite loop of 21 redirect requests
   * (ProtocolException: 21). We disable redirects and catch login redirect locations cleanly.
   */
  private def configureHttpClient(): Unit = {
    com.instagram4j.web.Utils.client = com.instagram4j.web.Utils.client.newBuilder()
      .followRedirects(false)
      .followSslRedirects(false)
      .addInterceptor((chain: Interceptor.Chain) => {
        val original = chain.request()
        val builder = original.newBuilder()

        // Sanitize User-Agent: Replace fake Chrome/146 or mobile agents with consistent macOS Chrome UA
        val ua = Option(original.header("user-agent")).getOrElse("")
        if (ua.isEmpty || ua.contains("Windows") || ua.contains("Instagram") || ua.contains("146.0.0.0") || ua.contains("okhttp")) {
          builder.header("user-agent", HttpConstants.UserAgent)
        }

        // Add standard modern browser Client Hints and headers if absent
        if (original.header("sec-ch-ua") == null) builder.header("sec-ch-ua", HttpConstants.SecChUa)
        if (original.header("sec-ch-ua-mobile") == null) builder.header("sec-ch-ua-mobile", HttpConstants.SecChUaMobile)
        if (original.header("sec-ch-ua-platform") == null) builder.header("sec-ch-ua-platform", HttpConstants.SecChUaPlatform)
        if (original.header("accept-language") == null) builder.header("accept-language", HttpConstants.AcceptLanguage)

        val response: Response = chain.proceed(builder.build())
        if (response.isRedirect) {
          val location = Option(response.header("Location")).getOrElse("")
          if (location.contains("/accounts/login/") || location.contains("login")) {
            throw new java.io.IOException(
              s"Instagram session is expired or invalid (Redirected to login: $location). Please refresh your cookies."
            )
          }
        }
        response
      })
      .build()
  }

  def fromSession(sessionId: String, csrfToken: String, username: String = ""): Instagram4j = {
    logger.info(
      s"Initializing Instagram client with session cookies for ${if (username.nonEmpty) username else "user"}..."
    )

    configureHttpClient()

    val client = Instagram4j.getInstance(sessionId.trim, csrfToken.trim)
    if (username.nonEmpty) {
      client.username = username
    }

    client
  }
}


