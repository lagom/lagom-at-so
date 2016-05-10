# Lagom at Stack Overflow

This program drives the [LagomAtSO](https://twitter.com/LagomAtSO) Twitter account.  It takes one or more Stack Exchange sites/tags, and publishes all the questions to those tags to that Twitter account.

It is deployed to AWS Lambda, and is executed by a scheduler event source every 15 minutes.

## Configuration

An `application.conf` file needs to be created in `src/main/resources` with the following contents:

```
twitter {
  consumer {
    key = "XXX"
    secret = "XXX"
  }
  request {
    token = "XXX"
    secret = "XXX"
  }
}

stackexchange = [
  {
    site = "stackoverflow"
    tag = "lagom"
  }
]
```

The consumer key and secret can be obtained by creating an app in Twitter - it is recommended that the account you're posting the tweets to creates the app.  Then the request token and secret can be created by creating a token in the app administration page for the account.

Multiple tags from multiple Stack Exchange sites can be subscribed to by putting them in the `stackexchange` list.

## Running locally

After creating the above configuration file, run it using `sbt run`.

## Running on AWS lambda

Package the application by running `sbt assembly`.  This will output a file `target/scala-2.11/lagom-at-so-assembly-1.0.jar`.  In the AWS Lambda console, create a new Lambda, upload this jar, select the `java8` runtime, set the handler to `com.lightbend.lagom.so.PostSoTweets::run`, set the memory size to 256, and set the timeout to 2 minutes (it should generally take less than 20 seconds).  Then create a new scheduled event source, and set the desired duration.

## How it works

No database is used, rather, all the SO questions are obtained, and the recent tweets are obtained, and the SO question IDs are parsed from the URLs in the recent tweets.  Any IDs that haven't been tweeted are then tweeted.


It can easily be used to publish Stack 
