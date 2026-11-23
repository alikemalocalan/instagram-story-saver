# AGENTS.md

Instagram Story Saver — AI agent guide.

## Project

- Scala `3.9.0`, sbt `2.0.12`, Java `21+`.
- HTTP: JDK `java.net.http.HttpClient` (no OkHttp).
- JSON: uPickle with typed DTOs and macro-derived `ReadWriter`s (no json-android/Circe). No manual key traversal.
- CLI parser: `mainargs`.
- Native: GraalVM native-image (`sbt-native-packager` 1.13.0).

## Rules

1. Never bring back OkHttp, okio, kotlin-stdlib, json-android, instagram4j or the JitPack resolver.
2. Never commit real cookies, sessions or tokens. Use `cdn.example.com` in test fixtures.
3. For Instagram GraphQL/response changes, read [docs/GRAPHQL_PLAYBOOK.md](docs/GRAPHQL_PLAYBOOK.md) first.
4. Make endpoint / doc id / response path changes in `GraphQlContract.scala`; do not add hidden constants in `InstagramWebClient.scala`.
5. If you change parser behavior, update the `src/test/resources/graphql` fixture and the matching test in the same commit.
6. If you see `[GRAPHQL-SHAPE]` or `[GRAPHQL-ERROR]` in logs, use the prefix to locate the broken parser directly.
7. Model new/changed response fields as `Option[...] = None` in `dto/InstagramDtos.scala`; uPickle needs the default to tolerate missing keys. Derive the codec with `macroRW` in `InstagramDecoders.scala`.
8. Do not spread JSON key reading through `InstagramJsonParser`; domain conversion belongs there, wire shape stays in the DTOs.

## Key files

| File | Purpose |
| :--- | :--- |
| `service/web/GraphQlContract.scala` | Single source of truth for endpoints, doc ids, operations and response paths |
| `service/web/InstagramWebClient.scala` | HTTP requests, cookies/headers, form body, GraphQL error handling |
| `service/web/dto/InstagramDtos.scala` | Typed `case class` mirrors of the Instagram wire shape (`Option[...] = None`) |
| `service/web/InstagramDecoders.scala` | All uPickle `ReadWriter`s derived in one place |
| `service/web/InstagramJsonParser.scala` | DTO → domain conversion + shape-change warnings |
| `service/web/InstagramHttpClient.scala` | Shared Java HTTP client, redirect/login check |
| `service/FileService.scala` | Media download, retry, `.part` + atomic move |
| `src/test/resources/graphql/` | Sanitized Instagram response fixtures |
| `src/test/scala/.../InstagramGraphQlSuite.scala` | Fixture-based parser regression tests |

## Commands

```bash
sbt "clean; compile; test; assembly"
sbt test        # parser fixture tests only
sbt assembly    # fat jar
```

CI: `.github/workflows/scala.yml` runs the same command. CircleCI is not used.

## Detailed docs

- [docs/GRAPHQL_PLAYBOOK.md](docs/GRAPHQL_PLAYBOOK.md) — step-by-step response to Instagram changes
- [docs/MIGRATIONS.md](docs/MIGRATIONS.md) — OkHttp → Java HTTP and json-android → uPickle migrations
