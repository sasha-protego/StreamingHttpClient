package com.example

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

object HttpClient extends App {
  implicit val system =
    ActorSystem() // to get an implicit ExecutionContext into scope

  def periodically(): Source[(HttpRequest, Int), Cancellable] =
    // This could even be a lazy/infinite stream. For this example we have a finite one:
    Source.tick(1.second, 10.seconds, bestBlockRequest -> 0)

  val poolBestBlockFlow =
    Http().cachedHostConnectionPool[Int]("172.26.207.181", 3002)

  val poolTransactionsFlow =
    Http().cachedHostConnectionPool[String]("172.26.207.181", 3002)

  def bestBlockRequest: HttpRequest = {
    HttpRequest(
      method = HttpMethods.GET,
      uri = "http://172.26.207.181:3002/blocks/tip/hash"
    )
  }

  def blockTransactionsRequest(blockHash: String): HttpRequest = {
    HttpRequest(
      method = HttpMethods.GET,
      uri = s"http://172.26.207.181:3002/block/$blockHash/txids"
    )
  }

  periodically()
    .via(poolBestBlockFlow)
    .mapAsync(1) {
      case (Success(response), _) =>
        Unmarshal(response).to[String]
      case (Failure(ex), _) =>
        Future.failed(ex)
    }
    .map { blockHash => blockTransactionsRequest(blockHash) -> blockHash }
    .via(poolTransactionsFlow)
    .mapAsync(1) {
      case (Success(response), _) =>
        Unmarshal(response).to[List[String]]
      case (Failure(ex), _) =>
        Future.failed(ex)
    }
    .mapConcat(lot => lot)
    .runForeach { response =>
      println(s"transaction hash: $response")
    }
}
