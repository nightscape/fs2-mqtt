/*
 * Copyright 2014 Frédéric Cabestre
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

import net.sigusr.mqtt.SpecUtils._
import net.sigusr.mqtt.api._
import net.sigusr.mqtt.impl.frames._
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import scodec.bits.ByteVector

import scala.util.Random

object ProtocolSpec extends Specification with Protocol with NoTimeConversions {

  "The transportNotReady() function" should {
    "Define the action to perform when the transport is not ready" in {
      transportNotReady() shouldEqual SendToClient(MQTTNotReady)
    }
  }

  "The transportReady() function" should {
    "Define the action to perform when the transport is ready" in {
      transportReady() shouldEqual SendToClient(MQTTReady)
    }
  }

  "The connectionClosed() function" should {
    "Define the action to perform when the connection is closed" in {
      connectionClosed() shouldEqual SendToClient(MQTTDisconnected)
    }
  }

  "The handleApiMessages() function" should {
    "Define the action to perform to handle a MQTTConnect API message" in {
      val clientId = "client id"
      val keepAlive = 60
      val cleanSession = false
      val topic = Some("topic")
      val message = Some("message")
      val user = Some("user")
      val password = Some("password")
      val input = MQTTConnect(clientId, keepAlive, cleanSession, topic, message, user, password)
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val variableHeader = ConnectVariableHeader(user.isDefined, password.isDefined, willRetain = false, AtLeastOnce.enum, willFlag = false, cleanSession, keepAlive)
      val result =Sequence(Seq(
        SetKeepAliveValue(keepAlive * 1000),
        SendToNetwork(ConnectFrame(header, variableHeader, clientId, topic, message, user, password))))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle a MQTTDisconnect API message" in {
      val input = MQTTDisconnect
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      SendToNetwork(DisconnectFrame(header))
      val result = SendToNetwork(DisconnectFrame(header))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle a MQTTSubscribe API message" in {
      val topicsInput = Vector(("topic0", AtMostOnce), ("topic1", ExactlyOnce), ("topic2", AtLeastOnce))
      val messageId = Random.nextInt(65535)
      val input = MQTTSubscribe(topicsInput, messageId)
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val topicsResult = Vector(("topic0", AtMostOnce.enum), ("topic1", ExactlyOnce.enum), ("topic2", AtLeastOnce.enum))
      val result = SendToNetwork(SubscribeFrame(header, messageId, topicsResult))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle a MQTTPublish API message with QoS of 'At most once'" in {
      val topic = "topic0"
      val qos = AtMostOnce
      val retain = true
      val payload = makeRandomByteVector(48)
      val messageId = Random.nextInt(65535)
      val input = MQTTPublish(topic, payload, qos, Some(messageId), retain, retain = true)
      val header = Header(dup = true, qos.enum, retain)
      val result = SendToNetwork(PublishFrame(header, topic, messageId, ByteVector(payload)))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle a MQTTPublish API message with QoS of 'at least once' or 'exactly once'" in {
      val topic = "topic0"
      val qos = AtLeastOnce
      val retain = true
      val payload = makeRandomByteVector(32)
      val messageId = Random.nextInt(65535)
      val input = MQTTPublish(topic, payload, qos, Some(messageId), retain)
      val header = Header(dup = false, qos.enum, retain)
      val result = SendToNetwork(PublishFrame(header, topic, messageId, ByteVector(payload)))
      handleApiMessages(input) should_== result
    }

    "Define the action to perform to handle an API message that should not be sent by the user" in {
      val input = MQTTReady
      val result = SendToClient(MQTTWrongClientMessage(MQTTReady))
      handleApiMessages(input) should_== result
    }
  }

  "The timerSignal() function" should {
    "Define the action to perform to handle a SendKeepAlive internal API message while not waiting for a ping response and messages were recently sent" in {
      val result = StartTimer(29500)
      timerSignal(120000500, 30000, 120000000, isPingResponsePending = false) should_== result
    }

    "Define the action to perform to handle a SendKeepAlive internal API message while not waiting for a ping response but no messages were recently sent" in {
      val result = Sequence(Seq(
        SetPendingPingResponse(isPending = true),
        StartTimer(30000),
        SendToNetwork(PingReqFrame(Header(dup = false, AtMostOnce.enum, retain = false)))))
      timerSignal(120029001, 30000, 120000000, isPingResponsePending = false) should_== result
    }

    "Define the action to perform to handle a SendKeepAlive internal API message while waiting for a ping response" in {
      val result = CloseTransport
      timerSignal(120029999, 30000, 120000000, isPingResponsePending = true) should_== result
    }
  }

  "The handleNetworkFrames() function" should {

    "Provide no actions when the frame should not be handled" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val input = PingReqFrame(header)
      val result = Sequence()
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a ConnackFrame (successful connection)" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val input = ConnackFrame(header, 0)
      val result = Sequence(Seq(StartTimer(30000), SendToClient(MQTTConnected)))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a ConnackFrame (failed connection)" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val reason = BadUserNameOrPassword
      val input = ConnackFrame(header, reason.enum)
      val result = SendToClient(MQTTConnectionFailure(reason))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PingRespFrame" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val input = PingRespFrame(header)
      val result = SetPendingPingResponse(isPending = false)
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PublishFrame" in {
      val header = Header(dup = false, AtLeastOnce.enum, retain = false)
      val topic = "topic"
      val payload = makeRandomByteVector(64)
      val input = PublishFrame(header, topic, Random.nextInt(65535), ByteVector(payload))
      val result = SendToClient(MQTTMessage(topic, payload))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PubackFrame" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val messageId = Random.nextInt(65535)
      val input = PubackFrame(header, messageId)
      val result = SendToClient(MQTTPublished(messageId))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PubrecFrame" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val messageId = Random.nextInt(65535)
      val input = PubrecFrame(header, messageId)
      val result = SendToNetwork(PubrelFrame(header, messageId))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a PubcompFrame" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val messageId = Random.nextInt(65535)
      val input = PubcompFrame(header, messageId)
      val result = SendToClient(MQTTPublished(messageId))
      handleNetworkFrames(input, 30000) should_== result
    }

    "Define the actions to perform to handle a SubackFrame" in {
      val header = Header(dup = false, AtMostOnce.enum, retain = false)
      val messageId = Random.nextInt(65535)
      val qosInput = Vector(AtLeastOnce.enum, ExactlyOnce.enum)
      val qosResult = Vector(AtLeastOnce, ExactlyOnce)
      val input = SubackFrame(header, messageId, qosInput)
      val result = SendToClient(MQTTSubscribed(qosResult, messageId))
      handleNetworkFrames(input, 30000) should_== result
    }
  }
}
