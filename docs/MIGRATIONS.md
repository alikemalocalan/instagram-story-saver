# Migration Notes

This document summarizes the two dependency migrations in this project and the related build/native-image settings.

- OkHttp → Java HTTP client (`java.net.http.HttpClient`)
- json-android → Circe JSON (`io.circe`)

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
    InstagramHttpClient.formBody(Seq("doc_id" -> docId, "variables" -> variables.noSpaces))
  ))
  .build()

val (status, body) = InstagramHttpClient.execute(request)
```

---

## 2. JSON layer: json-android → Circe

### Dependencies removed / added

| Status | Dependency |
| :--- | :--- |
| Removed | `com.github.ErrorxCode:json-android:1.0` (JitPack) |
| Added | `io.circe:circe-core:0.14.17` |
| Added | `io.circe:circe-parser:0.14.17` |
| Added | `io.circe:circe-generic:0.14.17` |

### Mapping

| json-android | Circe / typed DTO layer |
| :--- | :--- |
| `JSONObject` / `JSONArray` | `io.circe.Json` for parsing; typed `case class` DTOs for response shapes |
| `new JSONObject(raw)` | `InstagramJsonParser.parseJson(raw)` → `Option[Json]`, then `decodeDto[A]` |
| `obj.optJSONObject("x")` | `Option[...]` field on the matching DTO, populated by `deriveDecoder` |
| `obj.optJSONArray("x")` | `Option[List[A]]` field, normalized with `values(...)` |
| `obj.optString("x", "")` | `Option[String]` field, normalized with `nonEmpty(...)` |
| `obj.optInt/optLong("x")` | `Option[Int]` / `Option[Long]` fields |
| `JSONObject.NULL` | `Json.Null` / `None` |
| `jsonObject.toString` | `json.noSpaces` |
| `new JSONArray().put(...)` | `Json.arr(...)` or typed DTO lists |
| `obj.getJSONArray("errors")` | `GraphQlErrorResponse.errors: Option[List[GraphQlError]]` |

Shared helpers (`parseJson`, `decodeDto`, `values`, `nonEmpty`, `url`, `warnOnMissingShape`) keep all parsing code independent of HTTP and free of manual key traversal.

### Error and empty-data behavior

- Malformed JSON now logs a warning and returns `None` instead of throwing; callers continue with `Json.obj()`.
- If the GraphQL `errors` array contains `summary: "Not Found"`, an empty reels response is produced; other errors become `IOException`.
- If `xdt_viewer.user` is missing or null, the session is considered expired and `IllegalStateException` is thrown.

### Stories HTML parsing fix

The preloaded state on `/stories/<user>/` sometimes contains extra root fields such as `,"extensions":...` after the main JSON. The old approach parsed the text up to `</script>` as-is; Circe treated it as invalid JSON.

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

For GraalVM native-image, `android.org.json,okio` was removed and `io.circe` added:

```
--enable-http
--enable-https
--initialize-at-build-time=org.slf4j,com.typesafe.config,io.circe
```

`--enable-http` / `--enable-https` are required by `java.net.http.HttpClient` and were kept.

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

## 6. Typed DTO + Circe Decoder layer

Manual JSON traversal with `objField` / `arrField` / `str` / `int` / `long` was removed from `InstagramJsonParser`. Wire shapes now live in typed models, and domain conversion is a separate layer.

| File | Purpose |
| :--- | :--- |
| `service/web/dto/InstagramDtos.scala` | `case class` mirrors of Instagram JSON fields; all fields are `Option` |
| `service/web/InstagramDecoders.scala` | All `Decoder`s defined in one place with `deriveDecoder` |
| `service/web/InstagramJsonParser.scala` | Decoder selection, DTO → domain conversion, `[GRAPHQL-SHAPE]` warnings |

Key points:

- Derived Circe decoders **do not apply case-class defaults**. Missing fields must decode to `None`; the `values(...)` helper converts them to `Nil` at the domain boundary. That is why DTO fields use `Option[List[A]]`.
- Fields such as `pk` / reel `id` / `media_ids` may arrive as strings or numbers; a single `StringOrNumber` decoder normalizes them.
- `MediaItem` → `Edge[MediaItem]` (carousel) is recursive, so `mediaItemDecoder` is declared as a `lazy given`.
- Use `import InstagramDecoders.{given, *}` so the `given`s are visible; a plain `import InstagramDecoders.*` is not enough.
- GraphQL `errors` are now decoded with the `GraphQlErrorResponse` DTO; "Not Found" behavior is unchanged.

Additional regression test: the `stories_wrong_shape.json` fixture verifies that `parseStories` does not throw on an unexpected response shape.
