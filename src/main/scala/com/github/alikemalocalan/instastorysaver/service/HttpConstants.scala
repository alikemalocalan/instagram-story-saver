package com.github.alikemalocalan.instastorysaver.service

object HttpConstants {
  // Realistic macOS Chrome User-Agent matching modern browsers
  val UserAgent: String =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

  // Modern Client Hints headers
  val SecChUa: String =
    "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\""
  val SecChUaPlatform: String = "\"macOS\""
  val SecChUaMobile: String = "?0"

  val AcceptLanguage: String = "en-US,en;q=0.9,tr;q=0.8"

  // Standard Instagram Web identifiers
  val AsbdId: String = "129477"
  val AppId: String = "936619743392459"
}
