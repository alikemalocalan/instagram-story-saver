# instagram story saver with Scala

this is scheduler app about storing your or your feeds instagram stories (as invisible) for looking later.

Default scheduler is 24 hours

Firstly, set this environment variables your workground;

```bash
USERNAME    // instagram username
PASSWORD    // instagram password

BUCKETNAME  //sample-bucket
REGION      // us-east-1
ENPOINT     // like s3.us-east-1.amazonaws.com

AWS_ACCESS_KEY_ID  
AWS_SECRET_ACCESS_KEY
```

and run...

or Deploy Haroku

[![Deploy](https://www.herokucdn.com/deploy/button.svg)](https://heroku.com/deploy?template=https://github.com/alikemalocalan/instagram-story-saver/tree/master)


### TODO list

- [x] Save cookie to s3 for logout&restart problem on Heroku
- [x] saving to local machine option
