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

package org.alephium.flow.model

import java.math.BigInteger

import akka.util.ByteString

import org.alephium.protocol.Hash
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp}

final case class MiningBlob(
    headerBlob: ByteString,
    target: BigInteger,
    txsBlob: ByteString,
    unclesBlob: ByteString
)

object MiningBlob {
  def from(template: BlockFlowTemplate)(implicit networkConfig: NetworkConfig): MiningBlob = {
    from(
      template.deps,
      template.depStateHash,
      Block.calUncleHash(template.uncles),
      template.txsHash,
      template.target,
      template.templateTs,
      template.transactions,
      template.uncles
    )
  }

  def from(block: Block)(implicit networkConfig: NetworkConfig): MiningBlob = {
    val header = block.header
    from(
      header.blockDeps.deps,
      header.depStateHash,
      header.uncleHash,
      header.txsHash,
      header.target,
      header.timestamp,
      block.transactions,
      block.uncles
    )
  }

  // scalastyle:off parameter.number
  private def from(
      deps: AVector[BlockHash],
      depStateHash: Hash,
      uncleHash: Hash,
      txsHash: Hash,
      target: Target,
      blockTs: TimeStamp,
      transactions: AVector[Transaction],
      uncles: AVector[BlockHeader]
  )(implicit networkConfig: NetworkConfig): MiningBlob = {
    val dummyHeader =
      BlockHeader.unsafe(
        BlockDeps.unsafe(deps),
        depStateHash,
        uncleHash,
        txsHash,
        blockTs,
        target,
        Nonce.zero
      )

    val headerBlob = serialize(dummyHeader)
    val txsBlob    = serialize(transactions)
    val unclesBlob = serialize(uncles)
    MiningBlob(headerBlob.drop(Nonce.byteLength), target.value, txsBlob, unclesBlob)
  }
}
