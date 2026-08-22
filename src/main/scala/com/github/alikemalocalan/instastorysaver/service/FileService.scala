package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.UrlOperation
import okhttp3.{OkHttpClient, Request}
import org.apache.commons.io.FileUtils
import org.apache.commons.logging.{Log, LogFactory}

import java.io.File
import java.nio.file.Files
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try, Using}

object FileService {
  private val logger: Log = LogFactory.getLog(getClass)
  private val httpClient  = new OkHttpClient()

  @tailrec
  private def downloadToTempFile(url: String, retryCount: Int = 3): Option[File] = {
    val request = new Request.Builder().url(url).build()

    val downloadResult: Try[File] = Try {
      val tempFile = Files.createTempFile("instastory_", ".tmp").toFile
      try {
        Using.resource(httpClient.newCall(request).execute()) { response =>
          val body = response.body()
          if (response.isSuccessful && body != null) {
            Using.resource(body.byteStream()) { inputStream =>
              FileUtils.copyInputStreamToFile(inputStream, tempFile)
            }
            tempFile
          } else {
            throw new RuntimeException(s"Failed to download $url - HTTP ${response.code()}")
          }
        }
      } catch {
        case ex: Throwable =>
          FileUtils.deleteQuietly(tempFile)
          throw ex
      }
    }

    downloadResult match {
      case Success(file) =>
        logger.info(s"Downloaded: $url")
        Some(file)

      case Failure(ex) if retryCount > 0 =>
        logger.warn(s"Retrying ($retryCount left) downloading $url due to: ${ex.getMessage}")
        downloadToTempFile(url, retryCount - 1)

      case Failure(ex) =>
        logger.error(s"Failed to download URL: $url after retries", ex)
        None
    }
  }

  def saveLocally(operations: Iterable[UrlOperation], destinationDir: String): Unit = {
    operations.foreach { op =>
      val destinationFile = new File(destinationDir, op.fileFullPath)
      if (destinationFile.exists()) {
        logger.info(s"File already exists: ${destinationFile.getAbsolutePath}")
      } else {
        downloadToTempFile(op.url) match {
          case Some(tempFile) =>
            Try {
              FileUtils.forceMkdirParent(destinationFile)
              FileUtils.moveFile(tempFile, destinationFile)
            } match {
              case Success(_) =>
                logger.info(s"Saved: ${destinationFile.getAbsolutePath}")
              case Failure(ex) =>
                logger.error(s"Failed to save file to ${destinationFile.getAbsolutePath}", ex)
            }
            FileUtils.deleteQuietly(tempFile)

          case None =>
            logger.error(s"Skipping save for ${op.url} as download failed.")
        }
      }
    }
  }
}

