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

package org.alephium.tools

import java.nio.file.Paths

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.Node
import org.alephium.flow.validation.BlockValidation
import org.alephium.protocol.model.BlockHash
import org.alephium.util.Hex

object CheckConflictedTxs extends App with StrictLogging {
  private val rootPath              = Paths.get("/mnt/shared-linux/temp")
  private val (blockFlow, storages) = Node.buildBlockFlowUnsafe(rootPath)

  private val blockHash = BlockHash.unsafe(Hex.unsafe("0000000000006cd113c0719f577a3c7884d24a1fae1febb7f128290563a164f0"))
  private val block     = blockFlow.getBlockUnsafe(blockHash)

  private val validator = BlockValidation.build(blockFlow)
  private val _         = validator.validate(block, blockFlow)

  private val conflictedTxsStorage = blockFlow.conflictedTxsStorage
  private val result = conflictedTxsStorage.conflictedTxsReversedIndex.getOptUnsafe(blockHash)
  print(s"result: $result\n")

  storages.closeUnsafe()
}
