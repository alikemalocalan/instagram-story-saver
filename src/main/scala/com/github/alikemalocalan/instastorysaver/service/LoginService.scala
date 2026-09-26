package com.github.alikemalocalan.instastorysaver.service

import com.instagram4j.web.Instagram4j
import okhttp3.{Interceptor, Response}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}

object LoginService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * ===============================================================================================
   * ⚠️ WORKAROUND: instagram4j Limitations / Session Management & Validation
   * ===============================================================================================
   * 1. [Infinite Redirect Loop - ProtocolException: 21]:
   *    instagram4j's default OkHttpClient configuration (Utils.client) has automatic redirect
   *    following enabled (followRedirects = true). When a session expires, Instagram redirects
   *    to /accounts/login/ or /login/, which causes a loop of 21 redirect requests and crashes the app.
   *    Workaround: Configured followRedirects(false) and added an OkHttp Interceptor to catch
   *    login redirects and report a clean, actionable error message.
   *
   * 2. [Session Health Verification]:
   *    instagram4j provides no built-in method to test whether session cookies are still valid.
   *    When an expired session is used, requests silently return empty results without error.
   *    Workaround: Validates the session upfront using a GraphQL viewer query (xdt_viewer.user).
   * ===============================================================================================
   */
  private def configureHttpClient(): Unit = {
    com.instagram4j.web.Utils.client = com.instagram4j.web.Utils.client.newBuilder()
      .followRedirects(false)
      .followSslRedirects(false)
      .addInterceptor((chain: Interceptor.Chain) => {
        val response: Response = chain.proceed(chain.request())
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

  def validateSession(client: Instagram4j): Unit = {
    val selfPk = client.session.split("[:%]").headOption.getOrElse("")
    val probeVars = new android.org.json.JSONObject()
    probeVars.put("initial_reel_id", selfPk)
    val reelIds = new android.org.json.JSONArray().put(selfPk)
    probeVars.put("reel_ids", reelIds)
    probeVars.put("first", 1)

    Try {
      val response = InstaService.queryGraphQLWeb(com.instagram4j.web.Constants.GraphQl.STORY, probeVars)(using client)
      val data = Option(response.optJSONObject("data"))
      val viewerUser = data
        .flatMap(d => Option(d.optJSONObject("xdt_viewer")))
        .flatMap(v => Option(v.optJSONObject("user")))

      if (viewerUser.isEmpty) {
        throw new IllegalStateException("Instagram GraphQL response missing xdt_viewer.user (Unauthenticated session).")
      }
      val verifiedPk = viewerUser.get.optString("pk", selfPk)
      logger.info(
        s"✅ Instagram session successfully validated for user @${if (client.username != null && client.username.nonEmpty) client.username else verifiedPk} (ID: $verifiedPk)."
      )
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        val msg =
          s"""
             |================================================================================
             |❌ INSTAGRAM SESSION IS EXPIRED OR INVALID!
             |Details: ${ex.getMessage}
             |
             |The provided --session-id and/or --csrf-token values are no longer valid.
             |
             |To obtain fresh cookies:
             |1. Open instagram.com in your browser (Chrome/Brave/Edge) and ensure you are logged in.
             |2. Press F12 to open Developer Tools.
             |3. Go to Application tab -> Cookies -> select 'https://www.instagram.com'.
             |4. Copy the values of 'sessionid' and 'csrftoken'.
             |5. Re-run the command with the updated cookie values:
             |   --username ${if (client.username != null) client.username else "your_username"} --session-id "..." --csrf-token "..."
             |================================================================================
             |""".stripMargin
        logger.error(msg)
        throw new IllegalStateException(
          s"Instagram session is expired or invalid. Please refresh your --session-id and --csrf-token."
        )
    }
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

    validateSession(client)
    client
  }
}

