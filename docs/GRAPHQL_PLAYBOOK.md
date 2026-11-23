# GraphQL Değişiklikleri İçin Müdahale Rehberi

Instagram web GraphQL/HTML yapısını değiştirdiğinde bu rehber, bir AI agent’ın sorunu hızlıca teşhis edip **küçük bir diff** ile çözmesini sağlar.

Prefix’ler loglarda kararlıdır:

- `[GRAPHQL-SHAPE]` — beklenen response path kayboldu; hangi parser uyardıysa oraya bak.
- `[GRAPHQL-ERROR]` — HTTP hatası veya GraphQL `errors` dizisi; doc id / cookie / csrf adayı.

---

## 1. Belirti → muhtemel sebep → dosya

| Belirti | Muhtemel sebep | Önce bakılacak yer |
| :--- | :--- | :--- |
| Tray’de kullanıcı var ama “Loaded 0 active stories” | stories response path değişti veya HTML preloader shape’i değişti | `[GRAPHQL-SHAPE] stories` → `InstagramJsonParser.parseStories` / `parseStoriesPage`, fixture `stories.json`, `stories_page.html` |
| `Could not parse stories page JSON` | HTML’de ana JSON’dan sonra yeni kök alanlar eklendi | `extractJsonObject` (`InstagramJsonParser`), fixture `stories_page.html` |
| `xdt_viewer.user is null` | Oturum süresi dolmuş; kod hatası değil | Kullanıcıya cookie yenilemesi söylenmeli; `InstagramSession.cookie` |
| `GraphQL request failed with HTTP 400/401` | doc_id retird oldu, cookie/csrf eksik veya header değişti | `GraphQlContract.Stories.docId`, `InstagramWebClient.queryGraphQL` header’ları |
| `summary: "Not Found"` | Reel aktif story’siz; beklenen davranış | `resolveGraphQlError` → boş reelsresponse; “düzeltmeye” çalışma |
| Feed 0 post | ProfilePosts doc id / variables / response path değişti | `[GRAPHQL-SHAPE] PolarisProfilePostsQuery`, `GraphQlContract.ProfilePosts`, fixture `feed.json` |
| Highlight 0 | highlights doc id veya `data.highlights.edges` path’i değişti | `GraphQlContract.Highlights`, fixture `highlights.json` / `highlight_medias.json` |
| following boş veya pagination durdu | `data.users` / `data.next_max_id` alanları değişti | `GraphQlContract.Following`, `parseFollowingPage`, fixture `following.json` |
| Medya URL’leri alınamıyor | `video_versions` / `image_versions2` / `display_url` shape’i değişti | `bestVideoUrl` / `bestImageUrl` / `mediaItems` |

---

## 2. Güvenli teşhis adımları

1. **Testleri çalıştır:** `sbt test`. Fixture testleri hangi shape’in bozulduğunu hemen gösterir.
2. **Logları tara:** `[GRAPHQL-SHAPE]` veya `[GRAPHQL-ERROR]` satırındaki `operation` ve `topLevelKeys` alanları hangi endpoint’in değiştiğini söyler.
3. **Gerçek yanıtı al:** Tarayıcıda ilgili isteği kopyala; cookie, csrf, token ve gerçek medya URL’lerini çıkar.
4. **Fixture ekle/güncelle:** `src/test/resources/graphql/<endpoint>.json` altına sanitized örnek koy. URL’leri `https://cdn.example.com/...` yap, kullanıcı adlarını dummy yap.
5. **Kontratı güncelle:** doc id / URL / operation değiştiyse **sadece** `GraphQlContract.scala` üzerinde değiştir. `InstagramWebClient` kontrat alias’larını kullanır.
6. **Parser’ı güncelle:** Yeni path’i `objField` / `arrField` / `str` / `int` / `long` yardımcılarıyla oku. Ham `Json` gezintisini her yere dağıtma.
7. **Testi güncelle:** Fixture + beklenen değer aynı commit’te değişmeli. `GraphQlContract` doc id assert’lerini bilerek güncelle.
8. **Doğrula:** `sbt "clean; compile; test; assembly"`.

---

## 3. Sanitization kontrol listesi

Fixture veya log örneği eklerken:

- [ ] `sessionid`, `csrftoken`, `ds_user_id` yok
- [ ] Gerçek kullanıcı adı yok (`alice`, `bob` kullan)
- [ ] Gerçek CDN imzalı URL’leri yok (`cdn.example.com`)
- [ ] Device id / fb_dtsg / lsd token’ı yok
- [ ] JSON geçerli; örnek `sbt test` ile doğrulandı

---

## 4. Küçük diff için altın kurallar

- Yeni bir HTTP istemcisi veya JSON kütüphanesi ekleme; Java HttpClient ve Circe yeterli.
- Doc id’leri `InstagramWebClient` içine gizleme; `GraphQlContract` tek kaynak.
- Parser’da sessizce boş liste döndürme; yeni path beklenmiyorsa `[GRAPHQL-SHAPE]` uyarısı zaten loglanır.
- `parseStoriesPage` için `</script>`’e kadar parse etme; `extractJsonObject` dengeli süslü parantez kullanır.
- “Not Found” GraphQL hatasını aktif story sanıp tekrar deneme; bu Instagram’ın boş reel davranışı.

---

## 5. Değişiklik sonrası kontrol

```bash
sbt "clean; compile; test; assembly"
git diff --stat   # değişiklik küçük olmalı: kontrat + parser + fixture + test
```

Gerçek oturumla uçtan uca doğrulama için IDE run configuration kullanılabilir; cookie’ler `.idea/workspace.xml` içinde tutulur, commit edilmez.
