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


import java.math.BigInteger

import scala.concurrent.ExecutionContext

import org.alephium.api.model.{BuildExecuteScriptTx, Token}
import org.alephium.app.{ApiConfig, ServerUtils}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, Configs}
import org.alephium.io.RocksDBSource.ProdSettings
import org.alephium.protocol.PublicKey
import org.alephium.protocol.model.{Address, ContractId, TokenId}
import org.alephium.protocol.vm
import org.alephium.protocol.vm.{Method, StatefulContext, StatefulScript, Val}
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Env, Files, Hex, U256}

// scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.IterableOps", "org.wartremover.warts.OptionPartial", "org.wartremover.warts.AsInstanceOf"))
object AyinTest extends App {
  private val rootPath       = Files.homeDir.resolve("/mnt/shared-linux/.alephium")
  private val typesafeConfig = Configs.parseConfigAndValidate(Env.Prod, rootPath, overwrite = true)
  private val config         = AlephiumConfig.load(typesafeConfig, "alephium")
  private val apiConfig      = ApiConfig.load(typesafeConfig, "alephium.api")
  private val dbPath         = rootPath.resolve(config.network.networkId.nodeFolder)
  private val storages =
    Storages.createUnsafe(dbPath, "db", ProdSettings.writeOptions)(config.broker, config.node)
  private val blockFlow = BlockFlow.fromStorageUnsafe(config, storages)

  @SuppressWarnings(Array("org.wartremover.warts.GlobalExecutionContext"))
  private val executionContext: ExecutionContext =
    ExecutionContext.fromExecutor(new java.util.concurrent.ForkJoinPool(4))

  private val serverUtils = new ServerUtils()(
    config.broker,
    config.consensus,
    config.network,
    apiConfig,
    config.node.eventLog,
    executionContext
  )

  private val fromPublicKey = PublicKey.unsafe(Hex.unsafe("033a08f83e1f8ca42afdf174539c2b15dde54bde3071317aa2615df9ba300ee0b0"))
  private val fromAddress = Address.fromBase58("15oqG71Cf5Bw8sWHGhfJcZD12qSpVwp6nbaVBrF24BkjJ").toOption.get.asInstanceOf[Address.Asset]
  private val contractId = ContractId.from(Hex.unsafe("b22a5c6a5a0280ada506c5370c7a81882c86d9d3a89d872322e3ed92e9441700")).get
  private val tokenId = TokenId.from(contractId)
  private val amount = Val.U256(U256.unsafe(new BigInteger("320829488246914734546")))
  private val method: Method[StatefulContext] = Method(
    isPublic = true,
    usePreapprovedAssets = true,
    useContractAssets = false,
    usePayToContractOnly = false,
    argsLength = 0,
    localsLength = 0,
    returnLength = 0,
    instrs = AVector(
      vm.BytesConst(Val.ByteVec(Hex.unsafe("b22a5c6a5a0280ada506c5370c7a81882c86d9d3a89d872322e3ed92e9441700"))),
      vm.StoreLocal(0),
      vm.CallerAddress,
      vm.LoadLocal(0),
      vm.U256Const(amount),
      vm.ApproveToken,
      vm.U256Const(amount),
      vm.U256Const1,
      vm.U256Const1,
      vm.BytesConst(Val.ByteVec(Hex.unsafe("b22a5c6a5a0280ada506c5370c7a81882c86d9d3a89d872322e3ed92e9441700"))),
      vm.CallExternal(14),
      vm.Pop
    )
  )
  private val script = StatefulScript.unsafe(AVector(method))
  private val bytecode = serialize(script)
  private val result = serverUtils.buildExecuteScriptTx(
    blockFlow,
    BuildExecuteScriptTx(fromPublicKey.bytes, bytecode = bytecode, tokens = Some(AVector(Token(tokenId, amount.v))))
  )
  println(s"============ result: $result")

  storages.close() match {
    case Left(error) => throw error
    case Right(_)    =>
  }
}
