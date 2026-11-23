# Migration Notes

This document summarizes the dependency migrations in this project and the related build/native-image settings.

- OkHttp → Java HTTP client (`java.net.http.HttpClient`)
- json-android → uPickle JSON (`com.lihaoyi:upickle`)

Preserved behavior: story/feed/highlight downloads, web session cookie/CSRF flow, following-list pagination, GraphQL "Not Found" handling, download retry/backoff and safe writes via `.part` + atomic move.

---

## 1. HTTP layer: OkHttp → Java HTTP

### Dependencies removed / added

| Status | Dependency |
| :--- | :--- |
| Removed | `com.squareup.okhttp3:okhttp-jvm:5.5.0` |
| Removed | `com.squareup.okio:okio:3.18.2` |
| Removed | `org.jetbrains.kotlin:kotlin-stdlib:2.4.20` |
| Now used | JDK 21+ `java.net.http.HttpClient` |

`dependencyOverrides`, the JitPack resolver and all OkHttp/Okio/Kotlin remnants in assembly and native-image settings were also removed.

### Mapping

| OkHttp | Java HTTP equivalent |
| :--- | :--- |
| `OkHttpClient` | `HttpClient.newBuilder()` (immutable, thread-safe) |
| Header normalization via `Interceptor` | `HttpConstants.DefaultHeaders` + `InstagramHttpClient.requestBuilder` |
| `Request.Builder` / `.header()` | `HttpRequest.Builder` / `.setHeader()` |
| `FormBody.Builder` | `InstagramHttpClient.formBody(...)` + `URLEncoder` (UTF-8) |
| `.post(body)` | `.POST(HttpRequest.BodyPublishers.ofString(body))` |
| `.followRedirects(false)` + redirect interceptor | `HttpClient.Redirect.NEVER` + login-redirect check in `InstagramHttpClient.execute` |
| `response.body().string()` | `BodyHandlers.ofString(StandardCharsets.UTF_8)` |
| `ConnectionPool`, `readTimeout` | `connectTimeout(15s)` + per-request `timeout(30s)` |
| `httpClient.dispatcher().executorService().shutdown()` | `httpClient.close()` |
| `body.byteStream()` for media downloads | `BodyHandlers.ofInputStream()` + `InputStream.transferTo` |

### Behavior notes

- Cookies and `x-csrftoken` are still sent manually through `InstagramSession`; Java HttpClient has no cookie jar.
- The API client does not follow redirects. If the `Location` header contains `accounts/login/` or `login`, an `IOException` is thrown; other 3xx responses are returned to the caller as before.
- The media download client uses `HttpClient.Redirect.ALWAYS`; retry/backoff, free-space check, `.part` file and `ATOMIC_MOVE` logic are unchanged.
- The `authority` header was removed; `Host`/`:authority` is derived from the URI.
- Requests do not ask for `Accept-Encoding`; responses are read as identity. If gzip support is needed later, add a `Content-Encoding` check with `GZIPInputStream`.
- Java 21+ is required (`HttpClient.close()` is available from this version).

### Example

```scala
val request = InstagramHttpClient.requestBuilder("https://www.instagram.com/graphql/query")
  .setHeader("content-type", InstagramHttpClient.FormContentType)
  .setHeader("cookie", session.cookie)
  .POST(HttpRequest.BodyPublishers.ofString(
    InstagramHttpClient.formBody(Seq("doc_id" -> docId, "variables" -> ujson.write(variables)))
  ))
  .build()

val (status, body) = InstagramHttpClient.execute(request)
```

---

## 2. JSON layer: json-android → uPickle

### Dependencies removed / added

| Status | Dependency |
| :--- | :--- |
| Removed | `com.github.ErrorxCode:json-android:1.0` (JitPack) |
| Removed | `io.circe:circe-core/parser/generic:0.14.17` (Cats + Jawn chain) |
| Added | `com.lihaoyi:upickle:4.4.3` |

### Mapping

| json-android | uPickle / typed DTO layer |
| :--- | :--- |
| `JSONObject` / `JSONArray` | `ujson.Value` for intermediate JSON; typed `case class` DTOs for response shapes |
| `new JSONObject(raw)` | `InstagramJsonParser.parseJson(raw)` → `Option[Value]`, then `decodeDto[A]` |
| `obj.optJSONObject("x")` | `Option[...] = None` DTO field, populated by `macroRW` |
| `obj.optJSONArray("x")` | `Option[List[A]] = None` field, normalized with `values(...)` |
| `obj.optString("x", "")` | `Option[String] = None` field, normalized with `nonEmpty(...)` |
| `obj.optInt/optLong("x")` | `Option[Int]` / `Option[Long]` fields |
| `JSONObject.NULL` | `ujson.Null` / `None` |
| `jsonObject.toString` | `ujson.write(value)` |
| `new JSONArray().put(...)` | `ujson.Arr(...)` or typed DTO lists |
| `obj.getJSONArray("errors")` | `GraphQlErrorResponse.errors: Option[List[GraphQlError]]` |

Shared helpers (`parseJson`, `decodeDto`, `values`, `nonEmpty`, `url`, `warnOnMissingShape`) keep all parsing code independent of HTTP and free of manual key traversal.

### Error and empty-data behavior

- Malformed JSON now logs a warning and returns `None` instead of throwing; callers continue with `ujson.Obj()`.
- If the GraphQL `errors` array contains `summary: "Not Found"`, an empty reels response is produced; other errors become `IOException`.
- If `xdt_viewer.user` is missing or null, the session is considered expired and `IllegalStateException` is thrown.

### Stories HTML parsing fix

The preloaded state on `/stories/<user>/` sometimes contains extra root fields such as `,"extensions":...` after the main JSON. The old approach parsed the text up to `</script>` as-is; a strict JSON parser treats it as invalid JSON.

`extractJsonObject` now finds the end of the object starting at `{` by counting balanced braces while respecting strings and escapes, ignoring trailing fields. Story counts are therefore correct instead of `0` for users present in the tray.

---

## 3. Build and native-image settings

| Setting | Value |
| :--- | :--- |
| Scala | `3.9.0` |
| sbt | `2.0.12` |
| sbt-native-packager | `1.13.0` (includes sbt 2 compatibility fixes) |
| sbt-assembly | `2.5.0` |
| Java | 21+ |

Updated files:

- `build.sbt` — dependencies, dependency overrides, assembly merge strategy, GraalVM options
- `project/plugins.sbt` — native-packager version
- `project/build.properties` — sbt version
- `.github/workflows/build-native-macos-arm64.yml`
- `.github/workflows/release.yml`
- `Dockerfile.arm64`

For GraalVM native-image, `android.org.json,okio` was removed from build-time initialization:

```
--enable-http
--enable-https
--initialize-at-build-time=org.slf4j,com.typesafe.config
```

`--enable-http` / `--enable-https` are required by `java.net.http.HttpClient` and were kept.

In the earlier Circe setup, build-time initialization pulled Cats/Scala singletons into the image heap and `--strict-image-heap` cascaded into Circe/Jawn errors. Those options were removed. uPickle needs no build-time initialization and keeps the GraalVM configuration minimal.

The compiler source level is pinned with `-source:3.9`. Assembly discards Scala 3 TASTy metadata (`*.tasty`) via `assemblyMergeStrategy`: it is compile-time only and not needed at runtime or by native-image. Replacing Circe with uPickle and keeping the TASTy rule reduced the fat JAR from 14.1 MB / 7,789 entries to **8.0 MB / 4,747 entries** (4,661 classes, 0 TASTy).

---

## 4. Verification

```bash
sbt "clean; compile; test; assembly"
```

After a successful build, check that no old library classes remain in the fat jar:

```bash
jar tf target/out/jvm/scala-3.9.0/instastorysaver/instastorysaver.jar \
  | grep -Ei "okhttp|okio|kotlin|android/org/json"   # no output expected
```

Parser behavior was smoke-tested with story and following JSON samples; `parseStoriesPage` also returned the correct media for an HTML sample containing `,"extensions":...`. End-to-end verification with a real Instagram session should be done via the run configuration.

---

## 5. Agent-friendly safeguards

So AI agents can react quickly when Instagram GraphQL changes again:

- `GraphQlContract.scala`: single source of truth for doc ids, URLs, operations and expected response paths.
- `InstagramJsonParser`: `[GRAPHQL-SHAPE]` warning when an expected path disappears; `[GRAPHQL-ERROR]` for GraphQL/HTTP errors.
- `src/test/resources/graphql/`: sanitized response fixtures.
- `src/test/scala/.../InstagramGraphQlSuite.scala`: fixture-based regression tests (`sbt test`).
- `AGENTS.md` and [GRAPHQL_PLAYBOOK.md](GRAPHQL_PLAYBOOK.md): symptom → file mapping and step-by-step response guide.

A typical change touches: `GraphQlContract.scala` + the relevant DTO + fixture + test. The HTTP client and JSON library are not replaced.

---

## 6. Typed DTO + uPickle codec layer

Manual JSON traversal with `objField` / `arrField` / `str` / `int` / `long` was removed from `InstagramJsonParser`. Wire shapes now live in typed models, and domain conversion is a separate layer.

| File | Purpose |
| :--- | :--- |
| `service/web/dto/InstagramDtos.scala` | `case class` mirrors of Instagram JSON fields; optional fields are `Option[...] = None` |
| `service/web/InstagramDecoders.scala` | All uPickle `ReadWriter`s defined in one place with `macroRW` |
| `service/web/InstagramJsonParser.scala` | Codec selection, DTO → domain conversion, `[GRAPHQL-SHAPE]` warnings |

Key points:

- uPickle only tolerates missing keys when the case class field has a default, so every optional DTO field uses `Option[...] = None`. The `values(...)` helper converts `None` to `Nil` at the domain boundary.
- Fields such as `pk` / reel `id` / `media_ids` may arrive as strings or numbers; `StringOrNumber` uses uPickle's `Reader[String]`, which preserves the raw JSON number text.
- `MediaItem` → `Edge[MediaItem]` (carousel) is recursive, so `mediaItemRW` is declared as a `lazy given`.
- Use `import InstagramDecoders.{given, *}` so the `given`s are visible; a plain `import InstagramDecoders.*` is not enough.
- GraphQL `errors` are decoded with the `GraphQlErrorResponse` DTO; "Not Found" behavior is unchanged.

Additional regression test: the `stories_wrong_shape.json` fixture verifies that `parseStories` does not throw on an unexpected response shape.
