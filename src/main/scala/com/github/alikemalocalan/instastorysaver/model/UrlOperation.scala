package com.github.alikemalocalan.instastorysaver.model

case class UrlOperation(url: String, filePath: String, `type`: String) {
  def fileName: String = url.split("/").last.split("\\?").head

  def filefullPath = s"${`type`}/$filePath/$fileName"
}