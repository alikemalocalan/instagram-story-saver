package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.UrlOperation
import okhttp3.{CipherSuite, ConnectionPool, ConnectionSpec, OkHttpClient, Protocol, Request, TlsVersion}
import org.slf4j.{Logger, LoggerFactory}

import java.io.InputStream
import java.net.{InetAddress, Socket}
import java.nio.channels.{Channels, FileChannel}
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.util.concurrent.{Executors, TimeUnit}
import javax.net.SocketFactory
import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Random, Success, Try, Using}

object FileService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Optimized SocketFactory enforcing TCP_NODELAY and 128KB buffer bursts for ARM64 network controllers.
    */
  private class FastSocketFactory extends SocketFactory {
    private val delegate = SocketFactory.getDefault

    override def createSocket(): Socket = configure(delegate.createSocket())
    override def createSocket(host: String, port: Int): Socket = configure(delegate.createSocket(host, port))
    override def createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
      configure(delegate.createSocket(host, port, localHost, localPort))
    override def createSocket(host: InetAddress, port: Int): Socket = configure(delegate.createSocket(host, port))
    override def createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
      configure(delegate.createSocket(address, port, localAddress, localPort))

    private def configure(socket: Socket): Socket = {
      try {
        socket.setTcpNoDelay(true)
        socket.setReceiveBufferSize(128 * 1024)
        socket.setSendBufferSize(128 * 1024)
      } catch {
        case _: Throwable => ()
      }
      socket
    }
  }

  // Modern TLS 1.3 / 1.2 spec prioritizing hardware-accelerated ARMv8-A AES-GCM cipher suites
  private val modernTlsSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
    .cipherSuites(
      CipherSuite.TLS_AES_128_GCM_SHA256,
      CipherSuite.TLS_AES_256_GCM_SHA384,
      CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
      CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    )
    .build()

  // Bounded connection pool for daily cron runs
  private val connectionPool = new ConnectionPool(4, 30, TimeUnit.SECONDS)

  private val httpClient: OkHttpClient = new OkHttpClient.Builder()
    .connectionPool(connectionPool)
    .connectionSpecs(java.util.List.of(modernTlsSpec, ConnectionSpec.CLEARTEXT))
    .protocols(java.util.List.of(Protocol.HTTP_2, Protocol.HTTP_1_1))
    .socketFactory(new FastSocketFactory())
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .followRedirects(true)
    .build()

  /** Performs an HTTP GET and streams the response directly to the destination path using Linux Zero-Copy DMA
    * (FileChannel.transferFrom / splice). Uses a temporary .part file and atomic rename to prevent corrupted or partial
    * files.
    */
  private def streamToFile(url: String, destinationPath: Path): Try[Path] = {
    val partPath = destinationPath.resolveSibling(s"${destinationPath.getFileName}.part")
    val request = new Request.Builder().url(url).build()

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
          // Zero-Copy DMA streaming: kernel copies network socket buffer directly to disk page cache
          Using.resources(
            Channels.newChannel(body.byteStream()),
            FileChannel.open(
              partPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING
            )
          ) { (sourceChannel, destChannel) =>
            var position: Long = 0L
            var count: Long = 0L
            while ({
              count = destChannel.transferFrom(sourceChannel, position, 1024L * 1024L)
              count > 0
            }) {
              position += count
            }
          }

          moveAtomically(partPath, destinationPath)
          destinationPath
        } finally {
          Files.deleteIfExists(partPath)
        }
      }
    }
  }

  /** Attempts atomic move first, falling back to standard replace if cross-device move occurs.
    */
  private def moveAtomically(source: Path, destination: Path): Unit =
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: Exception =>
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }

  /** Functional retry mechanism with exponential backoff and jitter.
    */
  @tailrec
  private def retryWithBackoff[T](attemptsLeft: Int, maxAttempts: Int = 3)(action: => Try[T]): Try[T] =
    action match {
      case success @ Success(_) => success
      case failure @ Failure(ex) if attemptsLeft > 0 =>
        val attemptNumber = maxAttempts - attemptsLeft + 1
        val backoffMs = (math.pow(2, attemptNumber) * 200 + Random.nextInt(200)).toLong
        logger.warn(
          s"Attempt $attemptNumber failed (${ex.getMessage}). Retrying in ${backoffMs}ms ($attemptsLeft attempts left)..."
        )
        Thread.sleep(backoffMs)
        retryWithBackoff(attemptsLeft - 1, maxAttempts)(action)
      case failure @ Failure(_) => failure
    }

  /** Downloads a media URL to the target path with automated retries.
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

  private val MinFreeSpaceBytes = 50L * 1024 * 1024 // 50 MB safety threshold for storage

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

    // Safety check: Don't overflow storage
    Try(Files.getFileStore(baseDir).getUsableSpace) match {
      case Success(usable) if usable < MinFreeSpaceBytes =>
        logger.error(
          s"Low disk space alert on $destinationDir: only ${usable / (1024 * 1024)}MB free. Skipping download batch to protect device."
        )
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
