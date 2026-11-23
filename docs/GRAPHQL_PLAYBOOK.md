# GraphQL Change Response Playbook

When Instagram changes its web GraphQL/HTML structure, this guide helps an AI agent diagnose the problem quickly and fix it with a **small diff**.

Log prefixes are stable:

- `[GRAPHQL-SHAPE]` — an expected response path disappeared; check the parser named in the warning.
- `[GRAPHQL-ERROR]` — an HTTP error or a GraphQL `errors` array; suspect doc id / cookie / csrf.

---

## 1. Symptom → likely cause → file

| Symptom | Likely cause | Check first |
| :--- | :--- | :--- |
| Users in the tray but "Loaded 0 active stories" | Stories response path changed, or the HTML preloader shape changed | `[GRAPHQL-SHAPE] xdt_api__v1__feed__reels_media__connection decode failed` → `InstagramDtos.StoriesData` / `parseStories`, fixtures `stories.json`, `stories_page.html` |
| `Could not parse stories page JSON` | New root fields were appended after the main JSON in the HTML | `extractJsonObject` in `InstagramJsonParser`, fixture `stories_page.html` |
| `xdt_viewer.user is null` | Session expired; not a code bug | Tell the user to refresh cookies; `InstagramSession.cookie` |
| `GraphQL request failed with HTTP 400/401` | Retired doc id, missing cookie/csrf, or changed headers | `GraphQlContract.Stories.docId`, headers in `InstagramWebClient.queryGraphQL` |
| `summary: "Not Found"` | Reel has no active stories; expected behavior | `resolveGraphQlError` → empty reels response. Do not "fix" this. |
| Feed shows 0 posts | ProfilePosts doc id / variables / response path changed | `[GRAPHQL-SHAPE] PolarisProfilePostsQuery`, `GraphQlContract.ProfilePosts`, fixture `feed.json` |
| Highlights show 0 | Highlights doc id or `data.highlights.edges` path changed | `GraphQlContract.Highlights`, fixtures `highlights.json` / `highlight_medias.json` |
| Following is empty or pagination stops | `data.users` / `data.next_max_id` fields changed | `GraphQlContract.Following`, `parseFollowingPage`, fixture `following.json` |
| Media URLs not found | `video_versions` / `image_versions2` / `display_url` shape changed | `InstagramDtos.MediaItem` / `VideoCandidate` / `ResourceCandidate`, then `bestVideoUrl` / `bestImageUrl` |
| `missing keys in dictionary` warning | A new field was added without a default, or a field type changed | Make the DTO field `Option[...] = None`; `InstagramDecoders` already derives the codec |

---

## 2. Safe diagnosis steps

1. **Run the tests:** `sbt test`. The fixture tests show immediately which shape broke.
2. **Scan the logs:** the `operation` and `topLevelKeys` fields on `[GRAPHQL-SHAPE]` / `[GRAPHQL-ERROR]` lines identify the affected endpoint.
3. **Capture a real response:** copy the request in the browser, then strip cookies, csrf, tokens and real media URLs.
4. **Add/update a fixture:** put a sanitized example under `src/test/resources/graphql/<endpoint>.json`. Use `https://cdn.example.com/...` URLs and dummy usernames.
5. **Update the contract:** if a doc id / URL / operation changed, edit **only** `GraphQlContract.scala`. `InstagramWebClient` uses the contract aliases.
6. **Update the DTO:** add the new field as `Option[...] = None` to the relevant `case class` in `dto/InstagramDtos.scala` (uPickle needs the default for missing keys). Reuse `StringOrNumber` for string|number fields. For a new DTO, add one `given ... = macroRW` line in `InstagramDecoders.scala`. Do not write raw `ujson.Value` traversal.
7. **Update the domain conversion:** only in `InstagramJsonParser`, map the new field to the domain model. The `values(...)` helper turns missing lists into `Nil`.
8. **Update the test:** fixture and expected values must change in the same commit. Update the `GraphQlContract` doc id assertions deliberately.
9. **Verify:** `sbt "clean; compile; test; assembly"`.

---

## 3. Sanitization checklist

When adding a fixture or log example:

- [ ] No `sessionid`, `csrftoken`, `ds_user_id`
- [ ] No real usernames (use `alice`, `bob`)
- [ ] No real signed CDN URLs (use `cdn.example.com`)
- [ ] No device id / fb_dtsg / lsd token
- [ ] Valid JSON, verified with `sbt test`

---

## 4. Golden rules for a small diff

- Do not add a new HTTP client or JSON library; Java HttpClient and uPickle are enough.
- Do not hide doc ids inside `InstagramWebClient`; `GraphQlContract` is the single source.
- Use `Option[...] = None` on every optional DTO field; uPickle reports `missing keys in dictionary` otherwise. Do not use `= Nil` / `= ""` on a non-`Option` field.
- Use `import InstagramDecoders.{given, *}` instead of `import InstagramDecoders.*`; otherwise the `given`s are not in implicit scope.
- Do not silently return an empty list in the parser; the `[GRAPHQL-SHAPE]` warning already records an unexpected path.
- For `parseStoriesPage`, do not parse up to `</script>`; `extractJsonObject` scans balanced braces.
- Do not retry a "Not Found" GraphQL error as if it were an active story; that is Instagram's empty-reel behavior.

---

## 5. Post-change verification

```bash
sbt "clean; compile; test; assembly"
git diff --stat   # keep it small: contract + DTO/parser + fixture + test
```

For end-to-end verification with a real session, use the IDE run configuration. Cookies live in `.idea/workspace.xml` and are never committed.
