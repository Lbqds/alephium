// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.message

import java.net.InetSocketAddress

import org.alephium.macros.EnumerationMacros
import org.alephium.protocol.{Protocol, PublicKey, SignatureSchema}
import org.alephium.protocol.config.{GroupConfig, GroupConfigFixture}
import org.alephium.protocol.message.Payload.Code
import org.alephium.protocol.model.{BrokerInfo, CliqueId, NoIndexModelGenerators}
import org.alephium.serde.SerdeError
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp}

class PayloadSpec extends AlephiumSpec with NoIndexModelGenerators {
  implicit val ordering: Ordering[Code] = Ordering.by(Code.toInt(_))
  implicit val groupConfig = new GroupConfig {
    override def groups: Int = 4
  }

  it should "index all payload types" in {
    val codes = EnumerationMacros.sealedInstancesOf[Code]
    Code.values is AVector.from(codes)
  }

  it should "validate Hello message" in {
    val address            = new InetSocketAddress("127.0.0.1", 0)
    val (priKey1, pubKey1) = SignatureSchema.secureGeneratePriPub()
    val (priKey2, _)       = SignatureSchema.secureGeneratePriPub()
    val brokerInfo         = BrokerInfo.unsafe(CliqueId(pubKey1), 0, 1, address)

    val validInput  = Hello.unsafe(brokerInfo.interBrokerInfo, priKey1)
    val validOutput = Hello._deserialize(Hello.serde.serialize(validInput))
    validOutput.map(_.value) isE validInput

    val invalidInput  = Hello.unsafe(brokerInfo.interBrokerInfo, priKey2)
    val invalidOutput = Hello._deserialize(Hello.serde.serialize(invalidInput))
    invalidOutput.leftValue is a[SerdeError]
  }

  it should "serialize/deserialize the Hello payload" in {
    import Hex._

    val publicKeyHex = hex"4b8abc82e1423c4aa234549a3ada5dbc04ce1bc8db1b990c4af3b73fdfd7b301f4"
    val cliqueId     = CliqueId(new PublicKey(publicKeyHex))
    val brokerInfo   = BrokerInfo.unsafe(cliqueId, 0, 1, new InetSocketAddress("127.0.0.1", 0))
    val version: Int = Protocol.version
    val hello        = Hello.unsafe(version, TimeStamp.unsafe(100), brokerInfo.interBrokerInfo)
    val helloBlob    =
      // code id
      hex"00" ++
        // version
        hex"4809" ++
        // timestamp
        hex"0000000000000064" ++
        // clique id
        publicKeyHex ++
        // borker id
        hex"00" ++
        // groupNumPerBroker
        hex"01"

    Payload.serialize(hello) is helloBlob
    Payload.deserialize(helloBlob) isE hello
  }

  it should "serialize/deserialize the Ping/Pong payload" in {
    import Hex._

    val requestId = RequestId.unsafe(1)
    val ping      = Ping(requestId, TimeStamp.unsafe(100))
    val pingBlob  =
      // code id
      hex"01" ++
        // request id
        hex"01" ++
        // timestamp
        hex"0000000000000064"

    Payload.serialize(ping) is pingBlob
    Payload.deserialize(pingBlob) isE ping

    val pong     = Pong(requestId)
    val pongBlob =
      // code id
      hex"02" ++
        // request id
        hex"01"

    Payload.serialize(pong) is pongBlob
    Payload.deserialize(pongBlob) isE pong
  }

  it should "serialize/deserialize the BlocksRequest/BlocksResponse payload" in {
    import Hex._

    val block1       = blockGen.sample.get
    val block2       = blockGen.sample.get
    val requestId    = RequestId.unsafe(1)
    val blockRequest = BlocksRequest(requestId, AVector(block1.hash, block2.hash))

    val blockRequestBlob =
      // code id
      hex"03" ++
        // request id
        hex"01" ++
        // locator number
        hex"02" ++
        // locator 1
        block1.hash.bytes ++
        // locator 2
        block2.hash.bytes

    Payload.serialize(blockRequest) is blockRequestBlob
    Payload.deserialize(blockRequestBlob) isE blockRequest

    val blockResponse = BlocksResponse(requestId, AVector(block1))

    val blockResponseBlob =
      // code id
      hex"04" ++
        // request id
        hex"01" ++
        // blocks number
        hex"01" ++
        // block 1
        serialize(block1)

    Payload.serialize(blockResponse) is blockResponseBlob
    Payload.deserialize(blockResponseBlob) isE blockResponse
  }

  it should "serialize/deserialize the HeadersRequest/HeadersResponse payload" in {
    import Hex._

    val block1         = blockGen.sample.get
    val block2         = blockGen.sample.get
    val requestId      = RequestId.unsafe(1)
    val headersRequest = HeadersRequest(requestId, AVector(block1.hash, block2.hash))

    val headersRequestBlob =
      // code id
      hex"05" ++
        // request id
        hex"01" ++
        // locator number
        hex"02" ++
        // locator 1
        block1.hash.bytes ++
        // locator 2
        block2.hash.bytes

    Payload.serialize(headersRequest) is headersRequestBlob
    Payload.deserialize(headersRequestBlob) isE headersRequest

    val headersResponse = HeadersResponse(requestId, AVector(block1.header, block2.header))

    val headersResponseBlob =
      // code id
      hex"06" ++
        // request id
        hex"01" ++
        // header number
        hex"02" ++
        // header 1
        serialize(block1.header) ++
        // header 2
        serialize(block2.header)

    Payload.serialize(headersResponse) is headersResponseBlob
    Payload.deserialize(headersResponseBlob) isE headersResponse
  }

  it should "serialize/deserialize the InvRequest/InvResponse payload" in {
    import Hex._

    val block1     = blockGen.sample.get
    val block2     = blockGen.sample.get
    val requestId  = RequestId.unsafe(1)
    val invRequest = InvRequest(requestId, AVector(AVector(block1.hash, block2.hash)))

    val invRequestBlob =
      // code id
      hex"07" ++
        // request id
        hex"01" ++
        // locator array number
        hex"01" ++
        // locator number of the first locator array
        hex"02" ++
        // locator 1
        block1.hash.bytes ++
        // locator 2
        block2.hash.bytes

    Payload.serialize(invRequest) is invRequestBlob
    Payload.deserialize(invRequestBlob) isE invRequest

    val invResponse = InvResponse(requestId, AVector(AVector(block1.hash, block2.hash)))

    val invResponseBlob =
      // code id
      hex"08" ++
        // request id
        hex"01" ++
        // hash array number
        hex"01" ++
        // hash number of the first hash array
        hex"02" ++
        // hash 1
        serialize(block1.hash) ++
        // hash 2
        serialize(block2.hash)

    Payload.serialize(invResponse) is invResponseBlob
    Payload.deserialize(invResponseBlob) isE invResponse
  }
}
