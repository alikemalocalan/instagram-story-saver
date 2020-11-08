package com.github.alikemalocalan.instastorysaver

import java.io._

import okhttp3.Cookie

object Utils {
  val NON_VALID_EXPIRES_AT: Long = -1L

  def serialize(o: Any, to: File): Unit = {
    val file = new FileOutputStream(to)
    val out = new ObjectOutputStream(file)
    out.writeObject(o)
    out.close()
    file.close()
  }

  def deserialize[T](file: File): T = {
    val in = new FileInputStream(file)
    val oIn = new ObjectInputStream(in)
    val obj = oIn.readObject.asInstanceOf[T]
    in.close()
    oIn.close()
    obj
  }

  def readObject(in: ObjectInputStream): Cookie = {
    val builder: Cookie.Builder = new Cookie.Builder()

    builder.name(in.readObject().asInstanceOf[String])

    builder.value(in.readObject().asInstanceOf[String])

    val expiresAt = in.readLong()
    if (expiresAt != NON_VALID_EXPIRES_AT)
      builder.expiresAt(expiresAt)

    val domain = in.readObject().asInstanceOf[String]
    builder.domain(domain)

    builder.path(in.readObject().asInstanceOf[String])

    if (in.readBoolean())
      builder.secure()

    if (in.readBoolean())
      builder.httpOnly()

    if (in.readBoolean())
      builder.hostOnlyDomain(domain)

    builder.build()
  }

  def writeObject(cookie: Cookie, out: ObjectOutputStream): Unit = {
    out.writeObject(cookie.name())
    out.writeObject(cookie.value())
    out.writeLong(if (cookie.persistent()) cookie.expiresAt() else NON_VALID_EXPIRES_AT);
    out.writeObject(cookie.domain())
    out.writeObject(cookie.path())
    out.writeBoolean(cookie.secure())
    out.writeBoolean(cookie.httpOnly())
    out.writeBoolean(cookie.hostOnly())
  }


}
