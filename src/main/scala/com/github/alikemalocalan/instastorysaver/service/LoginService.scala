package com.github.alikemalocalan.instastorysaver.service

import java.io._

import com.github.alikemalocalan.instastorysaver.SerializableCookieJar
import com.github.instagram4j.instagram4j.IGClient
import com.github.instagram4j.instagram4j.utils.IGUtils
import okhttp3.OkHttpClient
import org.apache.commons.logging.{Log, LogFactory}

object LoginService {
  val logger: Log = LogFactory.getLog(this.getClass)

  def serializeLogin(username: String, password: String): IGClient = {
    val to = new File("/tmp/igclient.ser")
    val cookFile = new File("/tmp/cookie.ser")

    if (to.exists() && cookFile.exists()) {
      logger.info("Deserializing. . .")
      IGClient.from(new FileInputStream(to),
        formTestHttpClient(deserialize(cookFile, classOf[SerializableCookieJar])))
    } else {
      val jar = new SerializableCookieJar()
      val client = new IGClient.Builder().username(username).password(password)
        .client(formTestHttpClient(jar))
        .login()
      logger.info("Serializing. . .")
      serialize(client, to)
      serialize(jar, cookFile)
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
