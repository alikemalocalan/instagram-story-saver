package com.github.alikemalocalan.instastorysaver.service

import java.io.File

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.github.alikemalocalan.instastorysaver.Config
import com.github.alikemalocalan.instastorysaver.model.UrlOperation
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

  def uploadUrl(urlOperation: UrlOperation): Unit = {
    Try {
      val request = new Request.Builder()
        .url(urlOperation.url)
        .build()

      val response = client.newCall(request).execute().body()

      val inputStream = response.byteStream()
      println(urlOperation.filefullPath)

      val metadata = new ObjectMetadata()
      //metadata.setContentType("image/jpeg")
      metadata.setContentLength(response.contentLength())
      //metadata.setHeader("x-amz-acl","'authenticated-read'")


      val file = File.createTempFile("prefix", ".tmp")
      FileUtils.copyInputStreamToFile(inputStream, file)

      // Upload a file as a new object with ContentType and title specified.
      val s3request = new PutObjectRequest(bucketName, urlOperation.filefullPath, file)
      val result = s3Client.putObject(s3request)
      file.deleteOnExit()
      result
    } match {
      case Success(result) =>
        logger.info(result.toString)
      case Failure(e) =>
        logger.error(e)
    }
  }

}