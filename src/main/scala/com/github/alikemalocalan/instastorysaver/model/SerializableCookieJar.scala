package com.github.alikemalocalan.instastorysaver.model

import com.github.alikemalocalan.instastorysaver.Utils
import okhttp3.{Cookie, CookieJar, HttpUrl}

import java.io.{ObjectInputStream, ObjectOutputStream}
import java.util
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

class SerializableCookieJar extends CookieJar with Serializable {

  val map: mutable.Map[String, ListBuffer[SerializableCookie]] = mutable.Map[String, ListBuffer[SerializableCookie]]()

  override def loadForRequest(httpUrl: HttpUrl): util.List[Cookie] = {
    map.getOrElse(httpUrl.host(), ListBuffer[SerializableCookie]().empty)
      .map(c => c.cookie)
      .asJava
  }

  override def saveFromResponse(httpUrl: HttpUrl, list: java.util.List[Cookie]): Unit = {
    val cookies = list.asScala.map(SerializableCookie).to(ListBuffer)

    if (map.contains(httpUrl.host())) {
      map(httpUrl.host()).addAll(cookies)
    } else map.put(httpUrl.host(), cookies)
  }

  case class SerializableCookie(var cookie: Cookie) extends Serializable {

    private def writeObject(out: ObjectOutputStream): Unit = {
      Utils.writeObject(cookie, out)
    }

    private def readObject(in: ObjectInputStream): Unit = {
      cookie = Utils.readObject(in)
    }
  }

}
