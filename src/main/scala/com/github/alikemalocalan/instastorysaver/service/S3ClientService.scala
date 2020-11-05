package com.github.alikemalocalan.instastorysaver.service

import java.io.File

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model._
import com.github.alikemalocalan.instastorysaver.Config
import com.github.alikemalocalan.instastorysaver.model.UrlOperation
import okhttp3.{OkHttpClient, Request}
import org.apache.commons.io.FileUtils
import org.apache.commons.logging.{Log, LogFactory}

import scala.util.{Failure, Success, Try}


object S3ClientService extends Config {
  val logger: Log = LogFactory.getLog(this.getClass)


  val clientRegion: Regions = Regions.US_EAST_1
  val s3Client = AmazonS3ClientBuilder.standard()
    .withCredentials(new EnvironmentVariableCredentialsProvider())
    .withPathStyleAccessEnabled(true)
    .withChunkedEncodingDisabled(false)
    .withRegion(clientRegion)
    .build()

  //val transfer = TransferManagerBuilder
  //.standard()
  //.withS3Client(s3Client)
  //.build()

  def uploadUrl(urlOperation: UrlOperation): Unit = {
    Try {

      val client = new OkHttpClient
      val request = new Request.Builder()
        .url(urlOperation.url)
        .build()

      val response = client.newCall(request).execute().body()

      val inputStream = response.byteStream()
      //val contents = IOUtils.toByteArray(inputStream)
      //val stream: ByteArrayInputStream = new ByteArrayInputStream(contents)
      println(urlOperation.fileNameAndPath)

      val metadata = new ObjectMetadata()
      //metadata.setContentType("image/jpeg")
      metadata.setContentLength(response.contentLength())
      //metadata.setHeader("x-amz-acl","'authenticated-read'")


      val file = File.createTempFile("prefix", ".tmp")
      FileUtils.copyInputStreamToFile(inputStream, file)

      // Upload a file as a new object with ContentType and title specified.
      val s3request = new PutObjectRequest(bucketName, urlOperation.fileNameAndPath, file)
      //s3request.setStorageClass(StorageClass.Standard)


      //val upload=transfer.upload(bucketName,urlOperation.fileNameAndPath,inputStream,metadata)
      //upload.waitForException()

      // s3request.withMetadata(metadata)

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