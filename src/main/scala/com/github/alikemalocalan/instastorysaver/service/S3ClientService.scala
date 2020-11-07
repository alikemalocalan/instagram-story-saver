package com.github.alikemalocalan.instastorysaver.service

import java.io.File

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.github.alikemalocalan.instastorysaver.Config
import okhttp3.{OkHttpClient, Request}
import org.apache.commons.io.FileUtils
import org.apache.commons.logging.{Log, LogFactory}

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

  def uploadUrl(file: File, destination: String): Unit = {
    Try {
      // Upload a file as a new object with ContentType and title specified.
      val result = s3Client.putObject(bucketName, destination, file)
      file.deleteOnExit()
      result
    } match {
      case Success(result) =>
        logger.info(result.toString)
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

  def downloadUrl(url: String): File = {
    val request = new Request.Builder()
      .url(url)
      .build()

    val responseInputStream = client.newCall(request).execute().body().byteStream()

    val file = File.createTempFile("prefix", ".tmp")
    FileUtils.copyInputStreamToFile(responseInputStream, file)
    file
  }

}