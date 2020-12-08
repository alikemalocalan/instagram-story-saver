# instagram story saver with Scala

this is scheduler app about storing your or your feeds instagram stories (as invisible) for looking later.

Default scheduler is 24 hours

Firstly, set this environment variables your workground;

run your local, download fat jar,

[instastorysaver.jar](https://github.com/alikemalocalan/instagram-story-saver/releases/download/0.1.4/instastorysaver.jar)

and 

```bash
java -jar instastorysaver.jar --username "crazylenin1917" --password "internationalismnotismisnotemparialism"
```

and look your home directory for stories !!!

or Deploy Haroku for saving to AWS S3

[![Deploy](https://www.herokucdn.com/deploy/button.svg)](https://heroku.com/deploy?template=https://github.com/alikemalocalan/instagram-story-saver/tree/master)


### TODO list

- [x] Save cookie to s3 for logout&restart problem on Heroku
- [x] saving to local machine option
