package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.Config
import com.github.alikemalocalan.instastorysaver.service.S3ClientService._
import com.github.instagram4j.instagram4j.IGClient
import org.apache.commons.logging.{Log, LogFactory}

import java.io._

object LoginService extends Config {
  val logger: Log = LogFactory.getLog(this.getClass)

  def serializeLogin(username: String, password: String): IGClient = {
    val (clientFile: File, cookieFile: File) = getSavingClientSettingFiles

    if (clientFile.exists() && cookieFile.exists()) {
      logger.info("Deserializing. . .")
      IGClient.deserialize(clientFile, cookieFile)
    } else {

      val client = new IGClient.Builder()
        .username(username)
        .password(password)
        .login()
      logger.info("Serializing. . .")
      client.serialize(clientFile, cookieFile)
      if (enableS3Backup) {
        uploadToS3(clientS3SettingPath)(() => clientFile)
        uploadToS3(cookieS3SettingPath)(() => clientFile)
      }
      client
    }

  }

  private def getSavingClientSettingFiles: (File, File) =
    if (enableS3Backup && existS3file(clientS3SettingPath) && existS3file(cookieS3SettingPath))
      (getS3file(clientS3SettingPath, clientSettingTMPPath), getS3file(cookieS3SettingPath, cookieSettingTMPPath))
    else (new File(clientSettingTMPPath), new File(cookieSettingTMPPath))

}
