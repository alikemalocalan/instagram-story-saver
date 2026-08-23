# Instagram Story Saver (Scala 3 & GraalVM Native)

A lightweight, high-performance background utility to download and archive active Instagram stories (both seen and unseen), highlight stories, and feed posts.

---

## 🚀 Features

- **Scala 3.3 & Java 21:** Modern, functional codebase using pure Java NIO streaming.
- **GraalVM Native Image:** Pre-compiled standalone binary for **Linux ARM64 (ARMv8-A)** with zero external dependencies (no JRE required).
- **Deep I/O & Memory Optimization:** 64 KB streaming buffers, low-overhead direct file transfer, and bounded heap.
- **Session Cookie Authentication:** Bypasses bot verification via browser `sessionid` and `csrftoken`.

---

## 📦 Downloads (v1.0.0)

Grab the latest release artifacts from the [Releases](https://github.com/alikemalocalan/instagram-story-saver/releases/latest) page:

- **[instastorysaver.jar](https://github.com/alikemalocalan/instagram-story-saver/releases/latest/download/instastorysaver.jar)** (Universal Fat JAR for Java 21+)
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

### Option 2: Standalone Native Bundle (Linux ARM64)

```bash
unzip instastorysaver-linux-arm64.zip
chmod +x instastorysaver-linux-arm64/instastorysaver instastorysaver-linux-arm64/instastorysaver-bin

./instastorysaver-linux-arm64/instastorysaver \
  --username "your_username" \
  --session-id "YOUR_SESSION_ID" \
  --csrf-token "YOUR_CSRF_TOKEN" \
  --destination-folder "/path/to/stories"
```

---

## ⚙️ Environment Variables (Optional)

Alternatively, configure via environment variables or `application.conf`:
- `USERNAME`
- `PASSWORD`
- `SESSION_ID`
- `CSRF_TOKEN`
- `DOWNLOAD_FOLDER`
