package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.{MediaType, UrlOperation}
import com.github.alikemalocalan.instastorysaver.service.web.HttpGateway
import munit.FunSuite

import java.io.InputStream
import java.net.http.HttpRequest
import java.nio.file.{Files, Path}

final class FileServiceSuite extends FunSuite {
  test("saveLocally does not download when the target file already exists") {
    val destination: Path = Files.createTempDirectory("instastorysaver-skip")
    val operation = UrlOperation("https://cdn.example.com/existing.jpg", "alice", MediaType.Stories)
    val target = destination.resolve(operation.fileFullPath)
    Files.createDirectories(target.getParent)
    Files.writeString(target, "already saved")

    var downloadAttempts = 0
    val gateway = new HttpGateway {
      override def requestBuilder(url: String): HttpRequest.Builder =
        throw new AssertionError("requestBuilder must not be used when skipping")

      override def execute(request: HttpRequest): (Int, String) =
        throw new AssertionError("execute must not be used when skipping")

      override def openDownloadStream(url: String): InputStream = {
        downloadAttempts += 1
        throw new AssertionError("openDownloadStream must not be used when skipping")
      }

      override def close(): Unit = ()
    }

    try {
      FileService.saveLocally(List(operation), destination.toString, gateway = gateway)

      assertEquals(downloadAttempts, 0)
      assertEquals(Files.readString(target), "already saved")
    } finally {
      Files.walk(destination).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
    }
  }
}
