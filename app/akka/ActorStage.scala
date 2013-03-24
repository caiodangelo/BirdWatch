package akka

import akka.actor.{ Actor, ActorSystem, DeadLetter, Props }
import play.api.libs.ws.WS
import play.api.libs.oauth._
import play.api.libs.iteratee._
import utils._
import reactivemongo.api._
import reactivemongo.bson._
import reactivemongo.bson.handlers._
import reactivemongo.api.gridfs._
import reactivemongo.api.gridfs.Implicits._
import play.api.libs.concurrent.Execution.Implicits._
import models.TweetImplicits._
import models._

/** "Singleton" object for BirdWatch actor system */
object ActorStage {
  
  /** BirdWatch actor system */
  val actorSystem = ActorSystem("BirdWatch")
  
  /** eventStream of BirdWatch actor system */
  val eventStream = actorSystem.eventStream
  
  /** Actor for receiving Tweets from eventStream and inserting them into MongoDB. */
  val tweetStreamSubscriber = ActorStage.actorSystem.actorOf(Props(new Actor {
    def receive = {
      case t: Tweet => {
        Mongo.tweets.insert(t)
        
        // send Tweet for retrieving image 
        ActorStage.imageRetrievalActor ! t
      }
    }
  }))
  // attach tweetStreamSubscriber to eventStream
  eventStream.subscribe(tweetStreamSubscriber, classOf[Tweet])
    
  /** Image retrieval actor, receives Tweets, retrieves the Twitter profile images for each user and passes them on to conversion actor. */
  val imageRetrievalActor = ActorStage.actorSystem.actorOf(Props(new Actor {
    def receive = {
      case t: Tweet => {
        
        WS.url("http://" + t.profile_image_url).get().map { r =>
          val body = r.getAHCResponse.getResponseBodyAsBytes // body as byte array
          
          imageConversionActor ! (t, body)
        }
      }
    }
  }))
  
  /** Image conversion actor, receives (Tweet, Array[Byte]), converts images and saves them into MongoDB. */
  val imageConversionActor = ActorStage.actorSystem.actorOf(Props(new Actor {
    def receive = {
      case (t: Tweet, data: Array[Byte]) => {
        val contentType = "image/jpeg"

        // create Enumerator from body of WS request
        val enumerator = Enumerator(data)

        // saves content of enumerator into GridFS
        Mongo.imagesGridFS.save(enumerator, DefaultFileToSave(t.profile_image_url, Some(contentType), None))
      }
    }
  }))
  
}
