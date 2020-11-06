package com.github.alikemalocalan.instastorysaver

import com.typesafe.config.ConfigFactory

trait Config {
  private val config = ConfigFactory.load

  val userName: String = config.getString("username")
  val passWord: String = config.getString("password")

  val bucketName: String = config.getString("bucket-name")
  val region: String = config.getString("region")
  val s3Endpoint: String = config.getString("s3-endpoint")
}
