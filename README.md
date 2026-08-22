# instagram story saver with Scala

this is scheduler app about storing your or your feeds instagram stories (as invisible) for looking later.

Default scheduler is 24 hours

Firstly, set this environment variables your workground;

run your local, download fat jar,

[instastorysaver.jar](https://github.com/alikemalocalan/instagram-story-saver/releases/download/0.1.4/instastorysaver.jar)

### Running locally

Run using username and password:
```bash
java -jar instastorysaver.jar --username "your_username" --password "your_password"
```

Or using browser session cookies (recommended to bypass Instagram bot blocks):
```bash
java -jar instastorysaver.jar --username "your_username" --session-id "YOUR_SESSION_ID" --csrf-token "YOUR_CSRF_TOKEN"
```

Or via environment variables / `application.conf`:
- `USERNAME`
- `PASSWORD`
- `SESSION_ID`
- `CSRF_TOKEN`
- `DOWNLOAD_FOLDER`



### TODO list

- [x] Save cookie to s3 for logout&restart problem on Heroku
- [x] saving to local machine option
