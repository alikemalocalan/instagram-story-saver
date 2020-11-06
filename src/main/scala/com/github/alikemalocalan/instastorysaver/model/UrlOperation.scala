package com.github.alikemalocalan.instastorysaver.model

case class UrlOperation(url: String, filePath: String) {
  def fileName: String = url.split("/").last.split("\\?").head

  def filefullPath = s"$filePath/$fileName"
}