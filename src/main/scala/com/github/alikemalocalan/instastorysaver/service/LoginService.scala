package com.github.alikemalocalan.instastorysaver.service

import java.io._

import com.github.alikemalocalan.instastorysaver.service.S3ClientService._
import com.github.alikemalocalan.instastorysaver.{Config, SerializableCookieJar}
import com.github.instagram4j.instagram4j.IGClient
import com.github.instagram4j.instagram4j.utils.IGUtils
import okhttp3.OkHttpClient
import org.apache.commons.logging.{Log, LogFactory}

object LoginService extends Config {
  val logger: Log = LogFactory.getLog(this.getClass)

  def serializeLogin(username: String, password: String): IGClient = {
    val (clientFile: File, cookieFile: File) = {
      val (clientFile, cookieFile) = (new File(clientSettingTMPPath), new File(cookieSettingTMPPath))
      if (doesSettingOnS3 & existS3file(clientS3SettingPath) & existS3file(cookieS3SettingPath)) {
        (getS3file(clientS3SettingPath, clientSettingTMPPath), getS3file(cookieS3SettingPath, cookieSettingTMPPath))
      } else (clientFile, cookieFile)
    }

    if (clientFile.exists() && cookieFile.exists()) {
      logger.info("Deserializing. . .")
      IGClient.from(new FileInputStream(clientFile),
        formTestHttpClient(deserialize(cookieFile, classOf[SerializableCookieJar])))
    } else {

      val jar = new SerializableCookieJar()
      val client = new IGClient.Builder().username(username).password(password)
        .client(formTestHttpClient(jar))
        .login()
      logger.info("Serializing. . .")
      serialize(client, clientFile)
      serialize(jar, cookieFile)
      if (doesSettingOnS3) {
        uploadUrl(clientFile, clientS3SettingPath)
        uploadUrl(cookieFile, cookieS3SettingPath)
      }
      client
    }

  }

  def serialize(o: Any, to: File): Unit = {
    val file = new FileOutputStream(to)
    val out = new ObjectOutputStream(file)
    out.writeObject(o)
    out.close()
    file.close()
  }

  def deserialize[T](file: File, clazz: Class[T]): T = {
    val in = new FileInputStream(file)
    val oIn = new ObjectInputStream(in)
    val t = clazz.cast(oIn.readObject)
    in.close()
    oIn.close()
    t
  }

  def formTestHttpClient(jar: SerializableCookieJar): OkHttpClient =
    IGUtils.defaultHttpClientBuilder.cookieJar(jar).build

}
