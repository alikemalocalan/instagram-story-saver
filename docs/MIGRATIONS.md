# Migration Notes

Bu doküman projede yapılan iki bağımlılık migrasyonunu ve bunlara bağlı build/native-image ayarlarını özetler.

- OkHttp → Java HTTP istemcisi (`java.net.http.HttpClient`)
- json-android → Circe JSON (`io.circe`)

Korunan davranışlar: story/feed/highlight indirme, web oturumu cookie/CSRF akışı, takip listesi pagination, GraphQL “Not Found” durumu, indirme retry/backoff ve `.part` + atomic move ile güvenli dosya yazımı.

---

## 1. HTTP katmanı: OkHttp → Java HTTP

### Kaldırılan / eklenen bağımlılıklar

| Durum | Bağımlılık |
| :--- | :--- |
| Kaldırıldı | `com.squareup.okhttp3:okhttp-jvm:5.5.0` |
| Kaldırıldı | `com.squareup.okio:okio:3.18.2` |
| Kaldırıldı | `org.jetbrains.kotlin:kotlin-stdlib:2.4.20` |
| Kullanılmıyor | JDK 21+ `java.net.http.HttpClient` |

`dependencyOverrides`, JitPack resolver'ı ve assembly/native-image içindeki okhttp/okio/kotlin kalıntıları da kaldırıldı.

### Eşleştirmeler

| OkHttp | Java HTTP karşılığı |
| :--- | :--- |
| `OkHttpClient` | `HttpClient.newBuilder()` (immutable, thread-safe) |
| `Interceptor` ile header normalizasyonu | `HttpConstants.DefaultHeaders` + `InstagramHttpClient.requestBuilder` |
| `Request.Builder` / `.header()` | `HttpRequest.Builder` / `.setHeader()` |
| `FormBody.Builder` | `InstagramHttpClient.formBody(...)` + `URLEncoder` (UTF-8) |
| `.post(body)` | `.POST(HttpRequest.BodyPublishers.ofString(body))` |
| `.followRedirects(false)` + redirect interceptor | `HttpClient.Redirect.NEVER` + `InstagramHttpClient.execute` içinde login redirect kontrolü |
| `response.body().string()` | `BodyHandlers.ofString(StandardCharsets.UTF_8)` |
| `ConnectionPool`, `readTimeout` | `connectTimeout(15s)` + istek başına `timeout(30s)` |
| `httpClient.dispatcher().executorService().shutdown()` | `httpClient.close()` |
| Medya indirmede `body.byteStream()` | `BodyHandlers.ofInputStream()` + `InputStream.transferTo` |

### Davranış notları

- Cookie ve `x-csrftoken` değerleri hâlâ `InstagramSession` üzerinden manuel gönderilir; Java HttpClient cookie jar sağlamaz.
- API istemcisi redirect’i takip etmez. `Location` başlığı `accounts/login/` veya `login` içeriyorsa `IOException` fırlatılır; diğer 3xx yanıtları eskisi gibi çağırana döndürülür.
- Medya indirme istemcisi `HttpClient.Redirect.ALWAYS` kullanır; retry/backoff, disk alanı kontrolü, `.part` dosyası ve `ATOMIC_MOVE` mantığı değişmedi.
- `authority` başlığı kaldırıldı; `Host`/`:authority` değeri URI’den türetilir.
- İstekler `Accept-Encoding` istemez; yanıtlar identity olarak okunur. Gzip gereksinimi doğarsa `Content-Encoding` kontrolü ile `GZIPInputStream` eklenebilir.
- Java 21+ gereklidir (`HttpClient.close()` bu sürümde kullanılabilir).

### Kullanım örneği

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

## 2. JSON katmanı: json-android → Circe

### Kaldırılan / eklenen bağımlılıklar

| Durum | Bağımlılık |
| :--- | :--- |
| Kaldırıldı | `com.github.ErrorxCode:json-android:1.0` (JitPack) |
| Eklendi | `io.circe:circe-core:0.14.17` |
| Eklendi | `io.circe:circe-parser:0.14.17` |

### Eşleştirmeler

| json-android | Circe |
| :--- | :--- |
| `JSONObject` / `JSONArray` | `io.circe.Json` / `List[Json]` |
| `new JSONObject(raw)` | `InstagramJsonParser.parseJson(raw)` → `Option[Json]` |
| `obj.optJSONObject("x")` | `objField(obj, "x")` |
| `obj.optJSONArray("x")` | `arrField(obj, "x")` |
| `obj.optString("x", "")` | `strOr(obj, "x", "")` |
| `obj.optInt/optLong("x")` | `int(obj, "x")` / `long(obj, "x")` |
| `JSONObject.NULL` | `Json.Null` |
| `jsonObject.toString` | `json.noSpaces` |
| `new JSONArray().put(...)` | `Json.arr(...)` |
| `obj.getJSONArray("errors")` | `arrField(obj, "errors")` |

`InstagramJsonParser` içindeki yardımcılar (`objField`, `arrField`, `field`, `str`, `int`, `long`, `bool`, `parseJson`) tüm ayrıştırma kodunun tek noktadan OkHttp/Circe bağımsız kalmasını sağlar.

### Hata ve boş veri davranışı

- Bozuk JSON artık istisna fırlatmak yerine uyarı loglayıp `None` döndürür; çağıranlar `Json.obj()` ile devam eder.
- GraphQL `errors` dizisinde `summary: "Not Found"` varsa boş reels yanıtı üretilir; diğer hatalar `IOException` olur.
- `xdt_viewer.user` yoksa/null ise oturum süresi dolmuş sayılır ve `IllegalStateException` fırlatılır.

### Stories HTML ayrıştırma düzeltmesi

`/stories/<user>/` sayfasındaki preloaded state bazen ana JSON’dan sonra `,"extensions":...` gibi ek kök alanlar içerir. Eski yaklaşım `</script>` kadar olan metni olduğu gibi parse etmeye çalışıyordu; Circe bunu geçersiz JSON sayıyordu.

`extractJsonObject` artık `{` ile başlayan objeyi string/escape duyarlı şekilde dengeli süslü parantez sayarak bitirir, trailing alanları yok sayar. Böylece story sayısı `0` yerine tray’deki kullanıcılar için doğru şekilde bulunur.

---

## 3. Build ve native-image ayarları

| Ayar | Değer |
| :--- | :--- |
| Scala | `3.9.0` |
| sbt | `2.0.12` |
| sbt-native-packager | `1.13.0` (sbt 2 uyumluluk düzeltmeleri) |
| sbt-assembly | `2.5.0` |
| Java | 21+ |

Güncellenen dosyalar:

- `build.sbt` — bağımlılıklar, dependency overrides, assembly merge strategy, GraalVM seçenekleri
- `project/plugins.sbt` — native-packager sürümü
- `project/build.properties` — sbt sürümü
- `.github/workflows/build-native-macos-arm64.yml`
- `.github/workflows/release.yml`
- `Dockerfile.arm64`

GraalVM native-image için `android.org.json,okio` kaldırılıp yerine `io.circe` eklendi:

```
--enable-http
--enable-https
--initialize-at-build-time=org.slf4j,com.typesafe.config,io.circe
```

`--enable-http` / `--enable-https` bayrakları `java.net.http.HttpClient` için gereklidir ve korundu.

---

## 4. Doğrulama

```bash
sbt "clean; compile; test; assembly"
```

Başarılı çıktı sonrası fat jar içinde eski kütüphane sınıflarının bulunmadığı kontrol edilebilir:

```bash
jar tf target/out/jvm/scala-3.9.0/instastorysaver/instastorysaver.jar \
  | grep -Ei "okhttp|okio|kotlin|android/org/json"   # çıktı olmamalı
```

Parser davranışı için story ve following JSON örnekleriyle smoke test yapıldı; ayrıca `,"extensions":...` içeren HTML örneğinde `parseStoriesPage` doğru medyayı döndürdü. Gerçek Instagram oturumu ile uçtan uca doğrulama run configuration üzerinden yapılmalıdır.

---

## 5. Agent-friendly korumalar

Instagram GraphQL’i tekrar değiştiğinde AI agent’ların hızlı müdahale etmesi için:

- `GraphQlContract.scala`: doc id, URL, operation ve beklenen response path’lerin tek kaynağı.
- `InstagramJsonParser`: beklenen path kaybolunca `[GRAPHQL-SHAPE]` uyarısı; GraphQL/HTTP hatalarında `[GRAPHQL-ERROR]`.
- `src/test/resources/graphql/`: sanitized response fixture’ları.
- `src/test/scala/.../InstagramGraphQlSuite.scala`: fixture bazlı regresyon testleri (`sbt test`).
- `AGENTS.md` ve [GRAPHQL_PLAYBOOK.md](GRAPHQL_PLAYBOOK.md): belirti → dosya eşleştirmesi ve adım adım müdahale rehberi.

Bir değişiklik gerektiğinde genellikle güncellenmesi gereken dosyalar: `GraphQlContract.scala` + ilgili parser + fixture + test. HTTP istemcisi veya JSON kütüphanesi değiştirilmez.
