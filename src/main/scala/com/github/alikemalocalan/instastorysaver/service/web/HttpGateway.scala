package com.github.alikemalocalan.instastorysaver.service.web

import java.io.InputStream
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets

/**
 * Transport boundary for outbound HTTP calls.
 *
 * [[InstagramHttpClient]] is the JDK-based implementation used today. Keeping the interface small
 * means the web client and the downloader do not depend on a concrete HTTP library.
 */
trait HttpGateway {
  val FormContentType: String = "application/x-www-form-urlencoded"

  /** Request builder with the browser headers shared by all Instagram web calls. */
  def requestBuilder(url: String): HttpRequest.Builder

  /** Executes a text response request and applies session/login redirect checks. */
  def execute(request: HttpRequest): (Int, String)

  /** Opens a streaming media response. Throws on invalid URLs and non-2xx responses. */
  def openDownloadStream(url: String): InputStream

  /** Encodes form fields using `application/x-www-form-urlencoded` rules. */
  final def formBody(fields: Seq[(String, String)]): String =
    fields.map { case (key, value) => s"${encode(key)}=${encode(value)}" }.mkString("&")

  def close(): Unit

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
}
