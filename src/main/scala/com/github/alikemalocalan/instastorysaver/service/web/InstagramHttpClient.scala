package com.github.alikemalocalan.instastorysaver.service.web

import com.github.alikemalocalan.instastorysaver.service.HttpConstants

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.jdk.CollectionConverters.*

object InstagramHttpClient {
  val FormContentType: String = "application/x-www-form-urlencoded"

  private val client: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  /** Creates a request builder with the normalized browser headers used by Instagram web. */
  def requestBuilder(url: String): HttpRequest.Builder = {
    val builder = HttpRequest.newBuilder(URI.create(url.trim)).timeout(Duration.ofSeconds(30))
    HttpConstants.DefaultHeaders.foreach { case (key, value) => builder.setHeader(key, value) }
    builder
  }

  /** Encodes form fields with `application/x-www-form-urlencoded` rules (UTF-8, `+` for spaces). */
  def formBody(fields: Seq[(String, String)]): String =
    fields
      .map { case (key, value) =>
        s"${encode(key)}=${encode(value)}"
      }
      .mkString("&")

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  /** Executes a request and returns `(statusCode, body)`. Login redirects are surfaced as errors. */
  def execute(request: HttpRequest): (Int, String) = {
    checkLoginRedirect(request, send(request))
  }

  def close(): Unit = {
    try client.close()
    catch { case _: Throwable => () }
  }

  private def send(request: HttpRequest): HttpResponse[String] =
    try client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
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
