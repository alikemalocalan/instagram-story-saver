package com.github.alikemalocalan.instastorysaver.service

import com.github.alikemalocalan.instastorysaver.model.User
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneId}
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

case class FollowedUserRecord(
    username: String,
    userId: String,
    var lastFeedChecked: Option[Long] = None, // epoch millis
    var lastHighlightChecked: Option[Long] = None // epoch millis
) {
  def toUser: User = User(username, userId)
}

object CsvService {
  private val logger: Logger = LoggerFactory.getLogger(getClass)
  private val CsvFileName = "following.csv"
  private val Header = "username,user_id,last_feed_checked,last_highlight_checked"

  private val dtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"))

  private def formatTimestamp(tsOpt: Option[Long]): String =
    tsOpt.map(ts => dtFormatter.format(Instant.ofEpochMilli(ts))).getOrElse("")

  private def parseTimestamp(str: String): Option[Long] =
    Option(str).map(_.trim).filter(_.nonEmpty) match {
      case None => None
      case Some(s) =>
        Try(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneId.of("UTC")).toInstant.toEpochMilli)
          .orElse(Try(s.toLong))
          .orElse(Try(Instant.parse(s).toEpochMilli))
          .orElse(Try(java.time.LocalDateTime.parse(s).atZone(ZoneId.of("UTC")).toInstant.toEpochMilli))
          .toOption
    }

  def getCsvPath(destinationFolder: String): Path =
    Paths.get(destinationFolder, CsvFileName)

  /** Loads following records from CSV if it exists; otherwise fetches following from Instagram,
    * initializes timestamps to 1 month ago (e.g. 2026-08-02), and creates following.csv.
    */
  def loadOrCreateFollowing(
      destinationFolder: String,
      fetchFromApi: () => List[User]
  ): List[FollowedUserRecord] = synchronized {
    val path = getCsvPath(destinationFolder)

    if (Files.exists(path)) {
      logger.info(s"📄 Found existing following list at: $path. Reading directly from CSV (0 network calls)...")
      loadFromCsv(path)
    } else {
      logger.info(s"ℹ️ following.csv not found at: $path. Fetching followed users from Instagram to initialize CSV...")
      val users = fetchFromApi()
      // Initialize timestamps to 1 month ago (30 days ago)
      val oneMonthAgo = System.currentTimeMillis() - (30L * 24L * 3600L * 1000L)
      val records = users.map(u => FollowedUserRecord(u.folderName, u.userId, Some(oneMonthAgo), Some(oneMonthAgo)))
      saveToCsv(destinationFolder, records)
      logger.info(s"✅ Saved ${records.size} followed users into $path (initialized with 1-month-old timestamps).")
      records
    }
  }

  def loadFromCsv(path: Path): List[FollowedUserRecord] = {
    val records = ListBuffer[FollowedUserRecord]()
    Try {
      val reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)
      try {
        val header = reader.readLine() // Skip header
        var line = reader.readLine()
        while (line != null) {
          val trimmed = line.trim
          if (trimmed.nonEmpty) {
            val parts = trimmed.split(",", -1).map(_.trim)
            if (parts.length >= 2 && parts(0).nonEmpty) {
              val username = parts(0)
              val userId = parts(1)
              val lastFeed = if (parts.length > 2) parseTimestamp(parts(2)) else None
              val lastHighlight = if (parts.length > 3) parseTimestamp(parts(3)) else None
              records += FollowedUserRecord(username, userId, lastFeed, lastHighlight)
            }
          }
          line = reader.readLine()
        }
      } finally {
        reader.close()
      }
    } match {
      case Success(_) =>
        logger.info(s"Successfully loaded ${records.size} users from CSV.")
        records.toList
      case Failure(ex) =>
        logger.error(s"Failed to read CSV at $path: ${ex.getMessage}", ex)
        List.empty
    }
  }

  def saveToCsv(destinationFolder: String, records: List[FollowedUserRecord]): Unit = synchronized {
    val dir = Paths.get(destinationFolder)
    if (!Files.exists(dir)) Files.createDirectories(dir)

    val targetPath = dir.resolve(CsvFileName)
    val tempPath = dir.resolve(s"$CsvFileName.tmp")

    Try {
      val writer = Files.newBufferedWriter(
        tempPath,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      try {
        writer.write(Header)
        writer.newLine()
        records.foreach { r =>
          val line = s"${r.username},${r.userId},${formatTimestamp(r.lastFeedChecked)},${formatTimestamp(r.lastHighlightChecked)}"
          writer.write(line)
          writer.newLine()
        }
      } finally {
        writer.close()
      }

      Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to write CSV to $targetPath: ${ex.getMessage}", ex)
    }
  }

  def isDueForCheck(lastCheckedOpt: Option[Long], intervalDays: Int = 7): Boolean = {
    val intervalMs = intervalDays.toLong * 24L * 3600L * 1000L
    lastCheckedOpt match {
      case None => true
      case Some(lastChecked) =>
        val elapsed = System.currentTimeMillis() - lastChecked
        elapsed >= intervalMs
    }
  }
}
