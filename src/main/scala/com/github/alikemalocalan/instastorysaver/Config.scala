package com.github.alikemalocalan.instastorysaver

import com.typesafe.config.ConfigFactory

trait Config {
  private val config = ConfigFactory.load

  val userName: String = config.getString("username")
  val passWord: String = config.getString("password")

  val bucketName: String = config.getString("bucket-name")
  val region: String = config.getString("region")
  val s3Endpoint: String = config.getString("s3-endpoint")

  val doesSettingOnS3: Boolean = config.getBoolean("setting-on-s3")
  val clientS3SettingPath = "setting/igclient.ser"
  val cookieS3SettingPath = "setting/cookie.ser"
  val clientSettingTMPPath = "/tmp/igclient.ser"
  val cookieSettingTMPPath = "/tmp/cookie.ser"
}
