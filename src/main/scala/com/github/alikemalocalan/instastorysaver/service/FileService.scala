package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.UrlOperation
import com.github.alikemalocalan.instastorysaver.service.web.{HttpGateway, InstagramHttpClient}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.*
import scala.annotation.tailrec
import scala.util.*

object FileService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Streams an HTTP response to a temporary `.part` file, then moves it atomically. */
  private def streamToFile(gateway: HttpGateway, url: String, destinationPath: Path): Try[Path] =
    Try {
      val partPath = destinationPath.resolveSibling(s"${destinationPath.getFileName}.part")
      Option(destinationPath.getParent).foreach(Files.createDirectories(_))

      val input = gateway.openDownloadStream(url)
      try {
        Using.resources(
          input,
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

  private def downloadFile(
      gateway: HttpGateway,
      url: String,
      targetPath: Path,
      maxRetries: Int = 3
  ): Boolean =
    // The batch is already filtered; this second check also covers duplicates inside one batch.
    if (Files.exists(targetPath)) {
      logger.info(s"Already exists, skipping download: $targetPath")
      true
    } else {
      retryWithBackoff(attemptsLeft = maxRetries, maxAttempts = maxRetries) {
        streamToFile(gateway, url, targetPath)
      } match {
        case Success(path) =>
          logger.info(s"Saved: $path")
          true
        case Failure(ex) =>
          logger.error(s"Failed to download $url: ${ex.getMessage}")
          false
      }
    }

  private val MinFreeSpaceBytes = 50L * 1024 * 1024 // 50 MB safety threshold

  def saveLocally(
      operations: Iterable[UrlOperation],
      destinationDir: String,
      gateway: HttpGateway = InstagramHttpClient
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

    val validOperations = operations.filter { op =>
      op != null &&
      Option(op.url).exists(u => u.trim.startsWith("http://") || u.trim.startsWith("https://"))
    }
    val pendingOperations = validOperations.filterNot(op => Files.exists(baseDir.resolve(op.fileFullPath)))
    val skippedExisting = validOperations.size - pendingOperations.size
    if (skippedExisting > 0) {
      logger.info(s"Skipping $skippedExisting already downloaded file(s).")
    }

    if (pendingOperations.isEmpty) return

    // One file at a time: no thread pool, no Future pipeline.
    pendingOperations.foreach { op =>
      Try {
        val targetPath = baseDir.resolve(op.fileFullPath)
        downloadFile(gateway, op.url, targetPath)
      } match {
        case Failure(ex) => logger.error(s"Unexpected error downloading ${op.url}: ${ex.getMessage}", ex)
        case Success(_)  => ()
      }
    }
  }
}
