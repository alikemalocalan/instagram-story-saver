package com.github.alikemalocalan.instastorysaver

import com.typesafe.config.ConfigFactory

trait Config {
  val bucketName: String = "instagram-private-stories"
  val region: String = hostConfig.getString("bucketName")
  private val config = ConfigFactory.load
  private val hostConfig = config.getConfig("http")
}
