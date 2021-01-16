package com.github.alikemalocalan.instastorysaver.service

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.github.alikemalocalan.instastorysaver.Config
import com.github.alikemalocalan.instastorysaver.model.UrlOperation
import okhttp3.{OkHttpClient, Request}
import org.apache.commons.io.FileUtils
import org.apache.commons.logging.{Log, LogFactory}

import java.io.File
import scala.util.{Failure, Success, Try}

object S3ClientService extends Config {
  val logger: Log = LogFactory.getLog(this.getClass)

  val client = new OkHttpClient
  val s3Client: AmazonS3 = AmazonS3ClientBuilder.standard()
    .withCredentials(new EnvironmentVariableCredentialsProvider())
    .withPathStyleAccessEnabled(true)
    .withChunkedEncodingDisabled(false)
    .withEndpointConfiguration(new EndpointConfiguration(s3Endpoint, region))
    .build()

  def uploadToS3(file: File, destination: String): Unit = {
    Try {
      if (!s3Client.doesObjectExist(bucketName, destination)) {
        // Upload a file as a new object with ContentType and title specified.
        s3Client.putObject(bucketName, destination, file)
        file.deleteOnExit()
      }
      else
        logger.info(s"File also exist : $destination")
    } match {
      case Success(_) =>
      case Failure(e) =>
        logger.error(e)
    }
  }

  def getS3file(filePath: String, destinationPath: String): File = {
    val file = new File(destinationPath)
    s3Client.getObject(new GetObjectRequest(bucketName, filePath), file)
    file
  }

  def existS3file(filePath: String): Boolean =
    s3Client.doesObjectExist(bucketName, filePath)

  def downloadUrl(url: String, reTryCount: Int = 3): File = {
    val request = new Request.Builder()
      .url(url)
      .build()

    var file: File = null

    Try {
      file = File.createTempFile("prefix", ".tmp")
      val inputStream = client.newCall(request).execute().body().byteStream()
      FileUtils.copyInputStreamToFile(inputStream, file)
    } match {
      case Success(_) => logger.debug("Finish Download")
      case Failure(_) => if (reTryCount > 0) {
        logger.info(s"Retrying $reTryCount. download file: $url ")
        Try(file.delete())
        downloadUrl(url, reTryCount - 1)
      }
      else logger.error(s"I cant download this url: $url")
    }

    file
  }

  def upload(operations: LazyList[UrlOperation], enableS3: Boolean, defaultFolder: Option[String]): Unit =
    operations.foreach { operation =>
      logger.info(operation)
      val file = downloadUrl(operation.url)
      if (enableS3) {
        uploadToS3(file, operation.fileFullPath)
      } else {
        val destinationFile = new File(s"${defaultFolder.get}/${operation.fileFullPath}")
        if (!destinationFile.exists())
          FileUtils.moveFile(file, destinationFile)
      }
      file.delete()
    }

}