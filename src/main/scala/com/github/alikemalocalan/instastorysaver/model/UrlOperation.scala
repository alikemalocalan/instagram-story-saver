package com.github.alikemalocalan.instastorysaver.model

case class UrlOperation(url: String, subPath: String, mediaType: MediaType) {
  def fileName: String =
    url.split('/').lastOption.getOrElse("unnamed").split('?').head

  def fileFullPath: String = s"${mediaType.folderName}/$subPath/$fileName"
}