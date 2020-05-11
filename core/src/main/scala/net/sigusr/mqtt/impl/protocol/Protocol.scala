/*
 * Copyright 2020 Frédéric Cabestre
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sigusr.mqtt.impl.protocol

import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.effect.{ Concurrent, Timer }
import cats.implicits._
import fs2.concurrent.{ Queue, SignallingRef }
import fs2.{ INothing, Pipe, Pull, Stream }
import net.sigusr.mqtt.api.ConnectionFailureReason
import net.sigusr.mqtt.api.Errors.{ ConnectionFailure, ProtocolError }
import net.sigusr.mqtt.api.QualityOfService.{ AtLeastOnce, AtMostOnce, ExactlyOnce }
import net.sigusr.mqtt.impl.frames._
import Builders.connectFrame
import net.sigusr.mqtt.impl.protocol.Result.{ Empty, QoS }
import scodec.bits.ByteVector

trait Protocol[F[_]] {

  def send: Frame => F[Unit]

  def sendReceive(frame: Frame, messageId: Int): F[Result]

  def cancel: F[Unit]

  def messages: Stream[F, Message]

}

object Protocol {

  def apply[F[_]: Concurrent: Timer](
    sessionConfig: SessionConfig,
    transport: Transport[F],
    inFlightOutBound: AtomicMap[F, Int, Frame]): F[Protocol[F]] = {

    def inboundMessagesInterpreter(
                                    messageQueue: Queue[F, Message],
                                    frameQueue: Queue[F, Frame],
                                    pendingResults: AtomicMap[F, Int, Deferred[F, Result]],
                                    connackReceived: Deferred[F, Int]): Pipe[F, Frame, Unit] = {

      def loop(s: Stream[F, Frame], inFlightInBound: Set[Int]): Pull[F, INothing, Unit] = s.pull.uncons1.flatMap {
        case Some((hd, tl)) => hd match {

          case PublishFrame(header: Header, topic: String, messageIdentifier: Option[Int], payload: ByteVector) =>
            (header.qos, messageIdentifier) match {
              case (AtMostOnce.value, None) =>
                Pull.eval(messageQueue.enqueue1(Message(topic, payload.toArray.toVector))) >>
                  loop(tl, inFlightInBound)
              case (AtLeastOnce.value, Some(id)) =>
                Pull.eval(messageQueue.enqueue1(Message(topic, payload.toArray.toVector))) >>
                  Pull.eval(frameQueue.enqueue1(PubackFrame(Header(), id))) >>
                  loop(tl, inFlightInBound)
              case (ExactlyOnce.value, Some(id)) =>
                if (inFlightInBound.contains(id))
                  Pull.eval(frameQueue.enqueue1(PubrecFrame(Header(), id)))
                else
                  Pull.eval(messageQueue.enqueue1(Message(topic, payload.toArray.toVector))) >>
                    Pull.eval(frameQueue.enqueue1(PubrecFrame(Header(), id))) >>
                    loop(tl, inFlightInBound + id)
              case (_, _) => Pull.raiseError[F](ProtocolError)
            }

          case PubackFrame(_: Header, messageIdentifier) =>
            Pull.eval(inFlightOutBound.remove(messageIdentifier)) >>
              Pull.eval(pendingResults.remove(messageIdentifier) >>=
                (_.fold(Concurrent[F].pure(()))(_.complete(Empty)))) >>
              loop(tl, inFlightInBound)

          case PubrelFrame(header, messageIdentifier) =>
            Pull.eval(frameQueue.enqueue1(PubcompFrame(header.copy(qos = 0), messageIdentifier))) >>
              loop(tl, inFlightInBound - messageIdentifier)

          case PubcompFrame(_, messageIdentifier) =>
            Pull.eval(inFlightOutBound.remove(messageIdentifier)) >>
              Pull.eval(pendingResults.remove(messageIdentifier) >>=
                (_.fold(Concurrent[F].pure(()))(_.complete(Empty)))) >>
              loop(tl, inFlightInBound)

          case PubrecFrame(header, messageIdentifier) =>
            val pubrelFrame = PubrelFrame(header.copy(qos = 1), messageIdentifier)
            Pull.eval(inFlightOutBound.update(messageIdentifier, pubrelFrame)) >>
              Pull.eval(frameQueue.enqueue1(pubrelFrame)) >>
              loop(tl, inFlightInBound)

          case PingRespFrame(_: Header) =>
            Pull.eval(Concurrent[F].delay(println(s" ${Console.CYAN}Todo: Handle ping responses${Console.RESET}"))) >>
              loop(tl, inFlightInBound)

          case UnsubackFrame(_: Header, messageIdentifier) =>
            Pull.eval(pendingResults.remove(messageIdentifier) >>=
              (_.fold(Concurrent[F].pure(()))(_.complete(Empty)))) >>
              loop(tl, inFlightInBound)

          case SubackFrame(_: Header, messageIdentifier, topics) =>
            Pull.eval(pendingResults.remove(messageIdentifier) >>=
              (_.fold(Concurrent[F].pure(()))(_.complete(QoS(topics))))) >>
              loop(tl, inFlightInBound)

          case ConnackFrame(_: Header, returnCode) =>
            Pull.eval(connackReceived.complete(returnCode)) >>
              loop(tl, inFlightInBound)

          case _ =>
            Pull.raiseError[F](ProtocolError)
        }

        case None => Pull.done
      }
      in => loop(in, Set.empty[Int]).stream
    }

    def outboundMessagesInterpreter(
      inFlightOutBound: AtomicMap[F, Int, Frame],
      pingTicker: Ticker[F]): Pipe[F, Frame, Frame] = {
      def loop(s: Stream[F, Frame]): Pull[F, Frame, Unit] = s.pull.uncons1.flatMap {
        case Some((hd, tl)) => (hd match {
          case PublishFrame(_: Header, _, messageIdentifier, _) =>
            Pull.eval(messageIdentifier.fold(Concurrent[F].pure[Unit](()))(inFlightOutBound.update(_, hd)))
          case _ => Pull.eval(Concurrent[F].pure[Unit](()))
        }) >> Pull.output1(hd) >> Pull.eval(pingTicker.reset) >> loop(tl)
        case None => Pull.done
      }
      loop(_).stream
    }

    for {
      connackReceived <- Deferred[F, Int]
      messageQueue <- Queue.bounded[F, Message](QUEUE_SIZE)
      frameQueue <- Queue.bounded[F, Frame](QUEUE_SIZE)
      stopSignal <- SignallingRef[F, Boolean](false)
      pingTicker <- Ticker(sessionConfig.keepAlive.toLong, frameQueue.enqueue1(PingReqFrame(Header())))
      pendingResults <- AtomicMap[F, Int, Deferred[F, Result]]

      outbound <- frameQueue.dequeue
        .through(outboundMessagesInterpreter(inFlightOutBound, pingTicker))
        .through(transport.outFrameStream)
        .compile.drain.start

      inbound <- transport.inFrameStream
        .through(inboundMessagesInterpreter(messageQueue, frameQueue, pendingResults, connackReceived))
        .onComplete(Stream.eval(stopSignal.set(true)))
        .compile.drain.start

      _ <- transport.status.evalMap{
        s => Concurrent[F].delay(println(s"${Console.BLUE}${if (s) "Connected" else "Disconnected"}${Console.RESET}"))
      }.compile.drain.start

      _ <- frameQueue.enqueue1(connectFrame(sessionConfig))
      r <- connackReceived.get

    } yield if (r == 0) new Protocol[F] {

      override def cancel: F[Unit] = pingTicker.cancel *> outbound.cancel *> inbound.cancel

      override def send: Frame => F[Unit] = frameQueue.enqueue1

      override def sendReceive(frame: Frame, messageId: Int): F[Result] = for {
        d <- Deferred[F, Result]
        _ <- pendingResults.update(messageId, d)
        _ <- frameQueue.enqueue1(frame)
        r <- d.get
      } yield r

      override def messages: Stream[F, Message] = messageQueue.dequeue.interruptWhen(stopSignal)
    }
    else throw ConnectionFailure(ConnectionFailureReason.withValue(r))
  }
}
