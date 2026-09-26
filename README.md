# Instagram Story Saver (Scala 3 & GraalVM Native)

A lightweight, high-performance background utility to download and archive active Instagram stories (both seen and unseen), highlight stories, and feed posts.

---

## 🚀 Features

- **Scala 3.3 & Java 21:** Modern, functional codebase using pure Java NIO streaming.
- **GraalVM Native Image:** Pre-compiled standalone binaries for **macOS ARM64 (Apple Silicon)** & **Linux ARM64 (ARMv8-A)** with zero external dependencies (no JRE required).

---

## 📦 Downloads (v1.0.0)

Grab the latest release artifacts from the [Releases](https://github.com/alikemalocalan/instagram-story-saver/releases/latest) page:

- **[instastorysaver.jar](https://github.com/alikemalocalan/instagram-story-saver/releases/latest/download/instastorysaver.jar)** (Universal Fat JAR for Java 21+)
- **[instastorysaver-macos-arm64.tar.gz](https://github.com/alikemalocalan/instagram-story-saver/releases/latest/download/instastorysaver-macos-arm64.tar.gz)** (Native Standalone Binary for macOS Apple Silicon / ARM64)
- **[instastorysaver-linux-arm64.zip](https://github.com/alikemalocalan/instagram-story-saver/releases/latest/download/instastorysaver-linux-arm64.zip)** (Standalone Native Bundle for Linux ARM64)

---

## 💻 Usage

### Option 1: Universal Fat JAR (Java 21+)

```bash
java -jar instastorysaver.jar \
  --username "your_username" \
  --session-id "YOUR_SESSION_ID" \
  --csrf-token "YOUR_CSRF_TOKEN" \
  --destination-folder "/path/to/stories"
```

### Option 2: Native Standalone Binary (macOS Apple Silicon / ARM64)

```bash
tar -xzvf instastorysaver-macos-arm64.tar.gz
chmod +x instastorysaver-macos-arm64

./instastorysaver-macos-arm64 \
  --username "your_username" \
  --session-id "YOUR_SESSION_ID" \
  --csrf-token "YOUR_CSRF_TOKEN" \
  --destination-folder "/path/to/stories" \
  --include-highlights \
  --include-feeds
```

### Option 3: Standalone Native Bundle (Linux ARM64)

```bash
unzip instastorysaver-linux-arm64.zip
chmod +x instastorysaver-linux-arm64/instastorysaver instastorysaver-linux-arm64/instastorysaver-bin

./instastorysaver-linux-arm64/instastorysaver \
  --username "your_username" \
  --session-id "YOUR_SESSION_ID" \
  --csrf-token "YOUR_CSRF_TOKEN" \
  --destination-folder "/path/to/stories" \
  --include-highlights \
  --include-feeds
```

---

## ⚙️ CLI Options

| Argument | Default | Description |
| :--- | :--- | :--- |
| `--check-interval-days` | `7` | Days before re-checking feeds or highlights for a user in `following.csv`. |
| `--concurrency` | `3` | Maximum concurrent media file downloads. |
| `--include-feeds` | `false` | Also download feed posts of followed users. |
| `--include-highlights` | `false` | Also download story highlights of followed users. |

---

## ⏰ Autonomous Cron Setup (Linux / OpenWrt / macOS)

Run automatically via cron (e.g. daily at 02:00 AM):

```bash
# Add to crontab
0 2 * * * /path/to/instastorysaver --destination-folder "/path/to/stories" --username "your_username" --session-id "YOUR_SESSION_ID" --csrf-token "YOUR_CSRF_TOKEN" --include-feeds --include-highlights >> /tmp/instastorysaver.log 2>&1
```
