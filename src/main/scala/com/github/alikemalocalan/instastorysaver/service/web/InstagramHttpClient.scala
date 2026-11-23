package com.github.alikemalocalan.instastorysaver.service.web

import com.github.alikemalocalan.instastorysaver.service.HttpConstants
import okhttp3.{Interceptor, OkHttpClient, Request, Response}

import scala.util.Using

/**
 * Shared OkHttp client for all Instagram web requests.
 *
 * Redirect following is disabled so an expired session surfaces as a clean error instead of an
 * infinite redirect loop. The interceptor also normalizes browser headers for any request that
 * does not set them explicitly.
 */
object InstagramHttpClient {
  val client: OkHttpClient = new OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor((chain: Interceptor.Chain) => {
      val original = chain.request()
      val builder = original.newBuilder()

      val userAgent = Option(original.header("user-agent")).getOrElse("")
      if (
        userAgent.isEmpty ||
        userAgent.contains("Windows") ||
        userAgent.contains("Instagram") ||
        userAgent.contains("146.0.0.0") ||
        userAgent.contains("okhttp")
      ) {
        builder.header("user-agent", HttpConstants.UserAgent)
      }

      if (original.header("sec-ch-ua") == null) builder.header("sec-ch-ua", HttpConstants.SecChUa)
      if (original.header("sec-ch-ua-mobile") == null) builder.header("sec-ch-ua-mobile", HttpConstants.SecChUaMobile)
      if (original.header("sec-ch-ua-platform") == null) {
        builder.header("sec-ch-ua-platform", HttpConstants.SecChUaPlatform)
      }
      if (original.header("accept-language") == null) {
        builder.header("accept-language", HttpConstants.AcceptLanguage)
      }
      if (original.header("x-ig-app-id") == null) builder.header("x-ig-app-id", HttpConstants.AppId)
      if (original.header("x-asbd-id") == null) builder.header("x-asbd-id", HttpConstants.AsbdId)

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

  /** Executes a request and returns `(statusCode, body)`. */
  def execute(request: Request): (Int, String) =
    Using.resource(client.newCall(request).execute()) { response =>
      (response.code(), Option(response.body()).map(_.string()).getOrElse(""))
    }
}
