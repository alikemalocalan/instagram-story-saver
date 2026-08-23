package com.github.alikemalocalan.instastorysaver.model

case class User(username: String, userId: String = "") {
  def folderName: String =
    Option(username)
      .map(_.trim)
      .filter(s => s.nonEmpty && !s.equalsIgnoreCase("null"))
      .orElse(Option(userId).map(_.trim).filter(s => s.nonEmpty && !s.equalsIgnoreCase("null")))
      .getOrElse("unknown_user")
}

