package com.github.alikemalocalan.instastorysaver.service.web

import com.github.alikemalocalan.instastorysaver.service.HttpConstants

import java.io.{IOException, InputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

/** JDK `HttpClient` implementation of [[HttpGateway]]. */
object InstagramHttpClient extends HttpGateway {
  private val webClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  // Media CDNs may redirect (signed URLs, region redirects); web requests must not.
  private val downloadClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.ALWAYS)
      .build()

  private val DownloadHeaders: Seq[(String, String)] = Seq(
    "user-agent" -> HttpConstants.UserAgent,
    "accept" -> "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
    "accept-language" -> HttpConstants.AcceptLanguage,
    "sec-ch-ua" -> HttpConstants.SecChUa,
    "sec-ch-ua-mobile" -> HttpConstants.SecChUaMobile,
    "sec-ch-ua-platform" -> HttpConstants.SecChUaPlatform,
    "sec-fetch-dest" -> "image",
    "sec-fetch-mode" -> "no-cors",
    "sec-fetch-site" -> "cross-site"
  )

  override def requestBuilder(url: String): HttpRequest.Builder = {
    val builder = HttpRequest.newBuilder(URI.create(url.trim)).timeout(Duration.ofSeconds(30))
    HttpConstants.DefaultHeaders.foreach { case (key, value) => builder.setHeader(key, value) }
    builder
  }

  override def execute(request: HttpRequest): (Int, String) =
    checkLoginRedirect(request, sendWeb(request))

  override def openDownloadStream(url: String): InputStream = {
    val uri = URI.create(url.trim)
    if (uri.getHost == null || (uri.getScheme != "https" && uri.getScheme != "http")) {
      throw new IllegalArgumentException(s"Invalid or malformed HTTP URL: '$url'")
    }

    val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET()
    DownloadHeaders.foreach { case (key, value) => builder.setHeader(key, value) }

    val response =
      try downloadClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
      catch {
        case ex: InterruptedException =>
          Thread.currentThread().interrupt()
          throw new IOException("Media download interrupted", ex)
      }

    val status = response.statusCode()
    if (status < 200 || status >= 300) {
      try response.body().close()
      catch { case _: Throwable => () }
      throw new IOException(s"HTTP $status downloading $url")
    }
    response.body()
  }

  override def close(): Unit = {
    try webClient.close()
    catch { case _: Throwable => () }
    try downloadClient.close()
    catch { case _: Throwable => () }
  }

  private def sendWeb(request: HttpRequest): HttpResponse[String] =
    try webClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    catch {
      case ex: InterruptedException =>
        Thread.currentThread().interrupt()
        throw new IOException("HTTP request interrupted", ex)
    }

  private def checkLoginRedirect(request: HttpRequest, response: HttpResponse[String]): (Int, String) = {
    val status = response.statusCode()
    if (status >= 300 && status < 400) {
      response.headers().firstValue("Location").orElse("") match {
        case location if location.contains("/accounts/login/") || location.contains("login") =>
          throw new IOException(
            s"Instagram session is expired or invalid (Redirected to login: $location). Please refresh your cookies. requestUrl=${request.uri()}"
          )
        case _ => ()
      }
    }
    (status, response.body())
  }
}
