package com.github.alikemalocalan.instastorysaver.model

enum MediaType(val folderName: String) {
  case Stories extends MediaType("stories")
  case Feeds extends MediaType("feeds")
  case Highlights extends MediaType("highLightStories")
}
