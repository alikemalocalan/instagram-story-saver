package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.Config
import com.github.alikemalocalan.instastorysaver.Utils.{deserialize, serialize}
import com.github.alikemalocalan.instastorysaver.model.SerializableCookieJar
import com.github.alikemalocalan.instastorysaver.service.S3ClientService._
import com.github.instagram4j.instagram4j.IGClient
import com.github.instagram4j.instagram4j.utils.IGUtils
import okhttp3.OkHttpClient
import org.apache.commons.logging.{Log, LogFactory}

import java.io._

object LoginService extends Config {
  val logger: Log = LogFactory.getLog(this.getClass)

  def serializeLogin(username: String, password: String): IGClient = {
    val (clientFile: File, cookieFile: File) = getSavingClientSettingFiles

    if (clientFile.exists() && cookieFile.exists()) {
      logger.info("Deserializing. . .")
      IGClient.from(new FileInputStream(clientFile),
        formTestHttpClient(deserialize[SerializableCookieJar](cookieFile)))
    } else {

      val jar = new SerializableCookieJar()
      val client = new IGClient.Builder().username(username).password(password)
        .client(formTestHttpClient(jar))
        .login()
      logger.info("Serializing. . .")
      serialize(client, clientFile)
      serialize(jar, cookieFile)
      if (enableS3Backup) {
        uploadToS3(() => clientFile, clientS3SettingPath)
        uploadToS3(() => cookieFile, cookieS3SettingPath)
      }
      client
    }

  }

  private def formTestHttpClient(jar: SerializableCookieJar): OkHttpClient =
    IGUtils.defaultHttpClientBuilder.cookieJar(jar).build

  private def getSavingClientSettingFiles: (File, File) =
    if (enableS3Backup && existS3file(clientS3SettingPath) && existS3file(cookieS3SettingPath))
      (getS3file(clientS3SettingPath, clientSettingTMPPath), getS3file(cookieS3SettingPath, cookieSettingTMPPath))
    else (new File(clientSettingTMPPath), new File(cookieSettingTMPPath))

}
