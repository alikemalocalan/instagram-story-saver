package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.UrlOperation
import okhttp3.*
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.*
import java.util.concurrent.{Executors, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.*

object FileService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val connectionPool = new ConnectionPool(8, 30, TimeUnit.SECONDS)

  private val httpClient: OkHttpClient = new OkHttpClient.Builder()
    .connectionPool(connectionPool)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .followRedirects(true)
    .build()

  /** Streams HTTP response directly to target path using a temporary .part file and atomic move.
    */
  private def streamToFile(url: String, destinationPath: Path): Try[Path] = {
    Try {
      val parsedUrl = Option(HttpUrl.parse(url.trim)).getOrElse {
        throw new IllegalArgumentException(s"Invalid or malformed HTTP URL: '$url'")
      }

      val partPath = destinationPath.resolveSibling(s"${destinationPath.getFileName}.part")
      val request = new Request.Builder().url(parsedUrl).build()

      Option(destinationPath.getParent).foreach(Files.createDirectories(_))

      Using.resource(httpClient.newCall(request).execute()) { response =>
        if (!response.isSuccessful) {
          throw new RuntimeException(s"HTTP ${response.code()} downloading $url")
        }

        val body = Option(response.body()).getOrElse {
          throw new RuntimeException(s"Empty response body received from $url")
        }

        try {
          Using.resources(
            body.byteStream(),
            Files.newOutputStream(
              partPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING
            )
          ) { (in, out) =>
            in.transferTo(out)
          }

          moveAtomically(partPath, destinationPath)
          destinationPath
        } finally {
          Files.deleteIfExists(partPath)
        }
      }
    }
  }

  private def moveAtomically(source: Path, destination: Path): Unit =
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: Exception =>
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }

  @tailrec
  private def retryWithBackoff[T](attemptsLeft: Int, maxAttempts: Int = 3)(action: => Try[T]): Try[T] =
    action match {
      case success @ Success(_) => success
      case failure @ Failure(ex) if attemptsLeft > 1 =>
        val attemptNumber = maxAttempts - attemptsLeft + 1
        val backoffMs = (math.pow(2, attemptNumber) * 200 + Random.nextInt(200)).toLong
        logger.warn(
          s"Attempt $attemptNumber failed (${ex.getMessage}). Retrying in ${backoffMs}ms ($attemptsLeft attempts left)..."
        )
        Thread.sleep(backoffMs)
        retryWithBackoff(attemptsLeft - 1, maxAttempts)(action)
      case failure @ Failure(_) => failure
    }

  private def downloadFile(url: String, targetPath: Path, maxRetries: Int = 3): Boolean =
    retryWithBackoff(attemptsLeft = maxRetries, maxAttempts = maxRetries) {
      streamToFile(url, targetPath)
    } match {
      case Success(path) =>
        logger.info(s"Saved: $path")
        true
      case Failure(ex) =>
        logger.error(s"Failed to download $url: ${ex.getMessage}")
        false
    }

  private val MinFreeSpaceBytes = 50L * 1024 * 1024 // 50 MB safety threshold

  def saveLocally(
      operations: Iterable[UrlOperation],
      destinationDir: String,
      maxConcurrency: Int = 3
  ): Unit = {
    val baseDir = Paths.get(destinationDir)

    if (!Files.exists(baseDir)) {
      Files.createDirectories(baseDir)
    }

    Try(Files.getFileStore(baseDir).getUsableSpace) match {
      case Success(usable) if usable < MinFreeSpaceBytes =>
        logger.error(
          s"Low disk space alert on $destinationDir: only ${usable / (1024 * 1024)}MB free. Skipping download batch."
        )
        return
      case _ => ()
    }

    // Filter valid URLs and skip already downloaded files
    val pendingOperations = operations.filter { op =>
      op != null &&
      Option(op.url).exists(u => u.trim.startsWith("http://") || u.trim.startsWith("https://")) &&
      !Files.exists(baseDir.resolve(op.fileFullPath))
    }

    if (pendingOperations.isEmpty) return

    val executor = Executors.newFixedThreadPool(math.max(1, maxConcurrency))
    given ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    try {
      val futures = pendingOperations.map { op =>
        Future {
          val targetPath = baseDir.resolve(op.fileFullPath)
          Try(downloadFile(op.url, targetPath)).getOrElse(false)
        }
      }

      Await.result(Future.sequence(futures), 30.minutes)
    } catch {
      case ex: Throwable =>
        logger.error(s"Error during batch download: ${ex.getMessage}", ex)
    } finally {
      executor.shutdown()
      executor.awaitTermination(30, TimeUnit.SECONDS)
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
