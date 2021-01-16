package com.github.alikemalocalan.instastorysaver

import com.typesafe.config.ConfigFactory

trait Config {
  private val config = ConfigFactory.load

  protected val userName: String = config.getString("username")
  protected val passWord: String = config.getString("password")

  protected val bucketName: String = config.getString("bucket-name")
  protected val region: String = config.getString("region")
  protected val s3Endpoint: String = config.getString("s3-endpoint")

  protected val enableS3Backup: Boolean = config.getBoolean("enable-s3-backup")
  protected val clientS3SettingPath = "setting/igclient.ser"
  protected val cookieS3SettingPath = "setting/cookie.ser"
  protected val clientSettingTMPPath = s"${System.getProperty("java.io.tmpdir")}/igclient.ser"
  protected val cookieSettingTMPPath = s"${System.getProperty("java.io.tmpdir")}/cookie.ser"
}
