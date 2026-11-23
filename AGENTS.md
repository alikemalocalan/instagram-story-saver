# AGENTS.md

Instagram Story Saver — AI agent kılavuzu.

## Proje

- Scala `3.9.0`, sbt `2.0.12`, Java `21+`.
- HTTP: JDK `java.net.http.HttpClient` (OkHttp **yok**).
- JSON: Circe `io.circe` (json-android **yok**).
- CLI parser: `mainargs`.
- Native: GraalVM native-image (`sbt-native-packager` 1.13.0).

## Kurallar

1. OkHttp, okio, kotlin-stdlib, json-android, instagram4j veya JitPack resolver'ı **geri getirme**.
2. Gerçek cookie/session/token değerlerini asla commit etme. Test fixture’larında `cdn.example.com` kullan.
3. Instagram GraphQL/response değişiklikleri için önce [docs/GRAPHQL_PLAYBOOK.md](docs/GRAPHQL_PLAYBOOK.md) oku.
4. Endpoint/doc id/response path değişikliklerini `GraphQlContract.scala` üzerinden yap; `InstagramWebClient.scala` içinde gizli sabit ekleme.
5. Parser davranışını değiştiriyorsan `src/test/resources/graphql` fixture’ını ve ilgili testi aynı commit’te güncelle.
6. Loglarda `[GRAPHQL-SHAPE]` veya `[GRAPHQL-ERROR]` görürsen hangi parser’ın bozulduğunu doğrudan oradan bul.

## Kritik dosyalar

| Dosya | Görev |
| :--- | :--- |
| `service/web/GraphQlContract.scala` | Endpoint, doc id, operation ve response path’lerin tek kaynağı |
| `service/web/InstagramWebClient.scala` | HTTP istekleri, cookie/header, form body, GraphQL hata yönetimi |
| `service/web/InstagramJsonParser.scala` | Saf JSON/HTML parser’ları + shape-change uyarıları |
| `service/web/InstagramHttpClient.scala` | Paylaşılan Java HTTP istemcisi, redirect/login kontrolü |
| `service/FileService.scala` | Medya indirme, retry, `.part` + atomic move |
| `src/test/resources/graphql/` | Sanitized Instagram response fixture’ları |
| `src/test/scala/.../InstagramGraphQlSuite.scala` | Parser regresyon testleri |

## Komutlar

```bash
sbt "clean; compile; test; assembly"
sbt test        # sadece parser fixture testleri
sbt assembly    # fat jar
```

CI: `.github/workflows/scala.yml` aynı komutu çalıştırır. CircleCI kullanılmıyor.

## Detaylı dokümanlar

- [docs/GRAPHQL_PLAYBOOK.md](docs/GRAPHQL_PLAYBOOK.md) — Instagram değişikliklerinde adım adım müdahale
- [docs/MIGRATIONS.md](docs/MIGRATIONS.md) — OkHttp → Java HTTP ve json-android → Circe migrasyonları
