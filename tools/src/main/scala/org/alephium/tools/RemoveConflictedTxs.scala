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

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.io.Storages
import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.util.Env

object RemoveConflictedTxs extends App with StrictLogging {
  private val rootPath       = Platform.getRootPath()
  private val typesafeConfig = Configs.parseConfigAndValidate(Env.Prod, rootPath, overwrite = true)
  private val config         = AlephiumConfig.load(typesafeConfig, "alephium")

  private val dbPath = rootPath.resolve(config.network.networkId.nodeFolder)
  private val db     = Storages.createRocksDBUnsafe(dbPath, "db")
  db.db.dropColumnFamily(db.handle(ColumnFamily.ConflictedTxs))
  db.close() match {
    case Right(())   => ()
    case Left(error) => throw error
  }
}
