package com.lightbend.lagom.so

import java.io.File
import java.time.{Duration, Instant, OffsetTime}
import java.time.format.DateTimeFormatter
import java.util.Locale

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import play.api.{Configuration, Environment, Mode}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.oauth.{ConsumerKey, OAuthCalculator, RequestToken}
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.libs.ws.ahc.{AhcWSClient, AhcWSClientConfig}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object Main extends App {
  println(PostSoTweets.run())
}

object PostSoTweets {

  val config = Configuration.load(Environment(new File("."), PostSoTweets.getClass.getClassLoader, Mode.Prod)).underlying

  val TwitterKey = ConsumerKey(config.getString("twitter.consumer.key"), config.getString("twitter.consumer.secret"))
  val TwitterToken = RequestToken(config.getString("twitter.request.token"), config.getString("twitter.request.secret"))
  case class StackExchangeTag(site: String, tag: String)
  val StackExchangeTags = config.getObjectList("stackexchange").asScala.map { obj =>
    val conf = obj.toConfig
    StackExchangeTag(conf.getString("site"), conf.getString("tag"))
  }
  val TweetPostLimit = 110
  val StackExchangePageSize = 5

  case class StackExchangePost(id: Long, creationDate: Instant, url: String, title: String)
  object StackExchangePost {
    implicit val reads: Reads[StackExchangePost] =
      (
        (__ \ "question_id").read[Long] and
        (__ \ "creation_date").read[Long].map(Instant.ofEpochSecond) and
        (__ \ "link").read[String] and
        (__ \ "title").read[String]
      ).apply(StackExchangePost.apply _)
  }


  case class TweetUrl(expanded: String)
  object TweetUrl {
    implicit val reads: Reads[TweetUrl] = (__ \ "expanded_url").read[String].map(TweetUrl.apply)
  }

  case class TweetEntities(urls: Seq[TweetUrl])
  object TweetEntities {
    implicit val reads: Reads[TweetEntities] = (__ \ "urls").readNullable[Seq[TweetUrl]]
      .map(urls => TweetEntities(urls.getOrElse(Nil)))
  }

  case class Tweet(id: String, creationDate: Instant, urls: Seq[String])
  object Tweet {
    implicit val instantReads: Reads[Instant] =
      Reads.instantReads(DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH))
    implicit val reads: Reads[Tweet] =
      (
        (__ \ "id_str").read[String] and
        (__ \ "created_at").read[Instant](instantReads) and
        (__ \ "entities").readNullable[TweetEntities]
          .map(entities => entities.fold(Seq.empty[String])(_.urls.map(_.expanded)))
      ).apply(Tweet.apply _)
  }

  val StackExchangeUrl = "https?://[^/]+/questions/([0-9]+)/.*".r

  def run(): String = withWsClient { implicit ws =>
    // First get all stack exchange questions
    val processResult = for {
      posts <- loadSoPosts()
      userId <- loadTwitterUserId()
      tweets <- loadTweets(userId)
    } yield {
      println("Retrieved tweets:")
      tweets.foreach(println)
      println()

      println("Retrieved posts:")
      posts.foreach(println)
      println()

      // Tweeted SE
      val tweetedSeIds = tweets.flatMap { tweet =>
        tweet.urls.collectFirst {
          case StackExchangeUrl(url) => url.toLong
        }
      }.toSet

      println("Stack exchanges questions already tweeted:" + tweetedSeIds)
      println()

      val minPostAge = if (tweets.nonEmpty) {
        tweets.maxBy(_.creationDate.toEpochMilli).creationDate.minus(Duration.ofMinutes(15))
      } else Instant.MIN

      // Exclude all the posts that have already been tweeted, and also any posts that are more than 15 minutes older
      // than the most recent tweet
      val newPosts = posts.filterNot(post => tweetedSeIds.contains(post.id) ||
        post.creationDate.isBefore(minPostAge))

      println("Tweeting:")
      newPosts.foreach(println)
      println()

      newPosts.foldLeft(Future.successful(0)) { (future, post) =>
        future.flatMap { count =>
          postTweet(post).map(_ => count + 1)
        }
      }
    }

    val tweetsPosted = Await.result(processResult.flatMap(identity), 2.minutes)

    s"Posted $tweetsPosted tweets."
  }

  def postTweet(post: StackExchangePost)(implicit ws: WSClient) = {
    val postTitle = if (post.title.length > TweetPostLimit) {
      post.title.take(TweetPostLimit).reverse.dropWhile(!_.isWhitespace).reverse.trim + "..."
    } else post.title

    val tweetText = postTitle + " " + post.url
    ws.url("https://api.twitter.com/1.1/statuses/update.json")
      .withQueryString("status" -> tweetText)
      .withMethod("POST")
      .sign(OAuthCalculator(TwitterKey, TwitterToken))
      .execute()
      .map(handleResponse(identity))
  }

  def loadTweets(userId: String)(implicit ws: WSClient) = {
    ws.url("https://api.twitter.com/1.1/statuses/user_timeline.json")
      .withQueryString(
        "user_id" -> userId,
        "count" -> (StackExchangePageSize * (StackExchangeTags.size + 1)).toString
      ).sign(OAuthCalculator(TwitterKey, TwitterToken))
      .get()
      .map(handleResponse(_.json.as[Seq[Tweet]]))
  }

  def loadTwitterUserId()(implicit ws: WSClient) = {
    ws.url("https://api.twitter.com/1.1/account/verify_credentials.json")
      .sign(OAuthCalculator(TwitterKey, TwitterToken))
      .get()
      .map(handleResponse(response => (response.json \ "id_str").as[String]))
  }

  def loadSoPosts()(implicit ws: WSClient) = {
    Future.sequence(StackExchangeTags.map {
      case StackExchangeTag(site, tag) =>
        ws.url("https://api.stackexchange.com/2.2/questions")
          .withQueryString(
            "sort" -> "creation",
            "order" -> "desc",
            "pagesize" -> StackExchangePageSize.toString,
            "site" -> site,
            "tagged" -> tag
          ).get()
          .map(handleResponse(response => (response.json \ "items").as[Seq[StackExchangePost]]))
    }).map(_.flatten)
  }

  def handleResponse[T](block: WSResponse => T): WSResponse => T = { response =>
    if (response.status < 200 || response.status >= 300) {
      throw sys.error(s"Unexpected response code: '${response.status} ${response.statusText}' with body: '${response.body}'")
    } else {
      try {
        block(response)
      } catch {
        case NonFatal(e) =>
          throw new RuntimeException("Error parsing response body: '" + response.body + "'", e)
      }
    }
  }

  def withWsClient[T](block: WSClient => T): T = {
    implicit val system = ActorSystem()
    implicit val mat = ActorMaterializer()
    val ws = AhcWSClient()
    try {
      block(ws)
    } finally {
      ws.close()
      system.terminate()
    }
  }
}
