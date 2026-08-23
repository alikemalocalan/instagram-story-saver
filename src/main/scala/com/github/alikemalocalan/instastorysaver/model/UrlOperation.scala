package com.github.alikemalocalan.instastorysaver.model

case class UrlOperation(url: String, subPath: String, mediaType: MediaType) {
  private val sanitizedSubPath: String =
    Option(subPath)
      .map(_.trim)
      .filter(s => s.nonEmpty && !s.equalsIgnoreCase("null"))
      .getOrElse("unknown_user")

  def fileName: String =
    url.split('/').lastOption.getOrElse("unnamed").split('?').head

  def fileFullPath: String = s"${mediaType.folderName}/$sanitizedSubPath/$fileName"
}