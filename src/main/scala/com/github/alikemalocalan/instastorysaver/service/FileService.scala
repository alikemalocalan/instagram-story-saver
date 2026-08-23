package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.UrlOperation
import okhttp3.{ConnectionPool, OkHttpClient, Request}
import org.slf4j.{Logger, LoggerFactory}

import java.io.InputStream
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.{Executors, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Random, Success, Try, Using}

object FileService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val connectionPool = new ConnectionPool(5, 1, TimeUnit.MINUTES)

  private val httpClient: OkHttpClient = new OkHttpClient.Builder()
    .connectionPool(connectionPool)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

  /**
   * Performs an HTTP GET and streams the response directly to the specified destination path.
   * Uses a temporary .part file and atomic rename to prevent corrupted or partial files.
   */
  private def streamToFile(url: String, destinationPath: Path): Try[Path] = {
    val partPath = destinationPath.resolveSibling(s"${destinationPath.getFileName}.part")
    val request  = new Request.Builder().url(url).build()

    Try {
      Option(destinationPath.getParent).foreach(Files.createDirectories(_))

      Using.resource(httpClient.newCall(request).execute()) { response =>
        if (!response.isSuccessful) {
          throw new RuntimeException(s"HTTP ${response.code()} downloading $url")
        }

        val body = Option(response.body()).getOrElse {
          throw new RuntimeException(s"Empty response body received from $url")
        }

        try {
          val buffer = new Array[Byte](65536) // 64 KB optimized buffer to minimize Linux read/write syscalls
          Using.resources(body.byteStream(), new java.io.BufferedOutputStream(Files.newOutputStream(partPath), 65536)) { (in, out) =>
            var bytesRead = in.read(buffer)
            while (bytesRead != -1) {
              out.write(buffer, 0, bytesRead)
              bytesRead = in.read(buffer)
            }
            out.flush()
          }

          moveAtomically(partPath, destinationPath)
          destinationPath
        } finally {
          Files.deleteIfExists(partPath)
        }
      }
    }
  }

  /**
   * Attempts atomic move first, falling back to standard replace if cross-device move occurs.
   */
  private def moveAtomically(source: Path, destination: Path): Unit =
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: Exception =>
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }

  /**
   * Functional retry mechanism with exponential backoff and jitter.
   */
  @tailrec
  private def retryWithBackoff[T](attemptsLeft: Int, maxAttempts: Int = 3)(action: => Try[T]): Try[T] =
    action match {
      case success @ Success(_) => success
      case failure @ Failure(ex) if attemptsLeft > 0 =>
        val attemptNumber = maxAttempts - attemptsLeft + 1
        val backoffMs = (math.pow(2, attemptNumber) * 200 + Random.nextInt(200)).toLong
        logger.warn(s"Attempt $attemptNumber failed (${ex.getMessage}). Retrying in ${backoffMs}ms ($attemptsLeft attempts left)...")
        Thread.sleep(backoffMs)
        retryWithBackoff(attemptsLeft - 1, maxAttempts)(action)
      case failure @ Failure(_) => failure
    }

  /**
   * Downloads a media URL to the target path with automated retries.
   */
  private def downloadFile(url: String, targetPath: Path, maxRetries: Int = 3): Boolean =
    retryWithBackoff(attemptsLeft = maxRetries, maxAttempts = maxRetries) {
      streamToFile(url, targetPath)
    } match {
      case Success(path) =>
        logger.info(s"Saved: $path")
        true
      case Failure(ex) =>
        logger.error(s"Failed to download $url after $maxRetries attempts: ${ex.getMessage}")
        false
    }

  private val MinFreeSpaceBytes = 50L * 1024 * 1024 // 50 MB safety threshold for router storage

  def evictIdleConnections(): Unit = {
    try {
      connectionPool.evictAll()
    } catch {
      case _: Throwable => ()
    }
  }

  def saveLocally(
      operations: Iterable[UrlOperation],
      destinationDir: String,
      maxConcurrency: Int = 3
  ): Unit = {
    val baseDir = Paths.get(destinationDir)

    // Ensure directory exists
    if (!Files.exists(baseDir)) {
      Files.createDirectories(baseDir)
    }

    // Safety check: Don't overflow router NAND / USB storage
    Try(Files.getFileStore(baseDir).getUsableSpace) match {
      case Success(usable) if usable < MinFreeSpaceBytes =>
        logger.error(s"Low disk space alert on $destinationDir: only ${usable / (1024 * 1024)}MB free. Skipping download batch to protect device.")
        return
      case _ => ()
    }

    val pendingOperations = operations.filter { op =>
      val targetPath = baseDir.resolve(op.fileFullPath)
      if (Files.exists(targetPath)) {
        false
      } else {
        true
      }
    }

    if (pendingOperations.isEmpty) return

    val executor = Executors.newFixedThreadPool(math.max(1, maxConcurrency))
    given ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    try {
      val futures = pendingOperations.map { op =>
        Future {
          val targetPath = baseDir.resolve(op.fileFullPath)
          downloadFile(op.url, targetPath)
        }
      }

      Await.result(Future.sequence(futures), 30.minutes)
    } catch {
      case ex: Throwable =>
        logger.error(s"Error during batch download: ${ex.getMessage}", ex)
    } finally {
      executor.shutdown()
      executor.awaitTermination(30, TimeUnit.SECONDS)
      evictIdleConnections()
    }
  }

  def shutdown(): Unit = {
    try {
      httpClient.dispatcher().executorService().shutdown()
      connectionPool.evictAll()
    } catch {
      case _: Throwable => ()
    }
  }
}



