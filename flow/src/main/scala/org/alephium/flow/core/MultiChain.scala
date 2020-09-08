package org.alephium.flow.core

import scala.reflect.ClassTag

import org.alephium.flow.model.BlockState
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp}

// scalastyle:off number.of.methods
trait MultiChain extends BlockPool with BlockHeaderPool {
  implicit def brokerConfig: BrokerConfig

  def groups: Int

  protected def aggregateHash[T: ClassTag](f: BlockHashPool => T)(op: (T, T) => T): T

  protected def aggregateHashE[T: ClassTag](f: BlockHashPool => IOResult[T])(
      op: (T, T)                                             => T): IOResult[T]

  protected def aggregateHeaderE[T: ClassTag](f: BlockHeaderPool => IOResult[T])(
      op: (T, T)                                                 => T): IOResult[T]

  def numHashes: Int = aggregateHash(_.numHashes)(_ + _)

  /* BlockHash apis */
  def contains(hash: Hash): IOResult[Boolean] = {
    val index = ChainIndex.from(hash)
    val chain = getHashChain(index)
    chain.contains(hash)
  }

  def containsUnsafe(hash: Hash): Boolean = {
    val index = ChainIndex.from(hash)
    val chain = getHashChain(index)
    chain.containsUnsafe(hash)
  }

  def getIndex(hash: Hash): ChainIndex = {
    ChainIndex.from(hash)
  }

  protected def getHashChain(from: GroupIndex, to: GroupIndex): BlockHashChain

  def getHashChain(chainIndex: ChainIndex): BlockHashChain = {
    getHashChain(chainIndex.from, chainIndex.to)
  }

  def getHashChain(hash: Hash): BlockHashChain = {
    val index = ChainIndex.from(hash)
    getHashChain(index.from, index.to)
  }

  def isTip(hash: Hash): Boolean = {
    getHashChain(hash).isTip(hash)
  }

  def getHeightedBlockHeaders(fromTs: TimeStamp,
                              toTs: TimeStamp): IOResult[AVector[(BlockHeader, Int)]] =
    aggregateHeaderE(_.getHeightedBlockHeaders(fromTs, toTs))(_ ++ _)

  def getHashesAfter(locator: Hash): IOResult[AVector[Hash]] =
    getHashChain(locator).getHashesAfter(locator)

  def getPredecessor(hash: Hash, height: Int): IOResult[Hash] =
    getHashChain(hash).getPredecessor(hash, height)

  def chainBack(hash: Hash, heightUntil: Int): IOResult[AVector[Hash]] =
    getHashChain(hash).chainBack(hash, heightUntil)

  def getState(hash: Hash): IOResult[BlockState] =
    getHashChain(hash).getState(hash)

  def getStateUnsafe(hash: Hash): BlockState =
    getHashChain(hash).getStateUnsafe(hash)

  def getHeight(hash: Hash): IOResult[Int] =
    getHashChain(hash).getHeight(hash)

  def getHeightUnsafe(hash: Hash): Int =
    getHashChain(hash).getHeightUnsafe(hash)

  def getWeight(hash: Hash): IOResult[BigInt] =
    getHashChain(hash).getWeight(hash)

  def getWeightUnsafe(hash: Hash): BigInt =
    getHashChain(hash).getWeightUnsafe(hash)

  def getChainWeight(hash: Hash): IOResult[BigInt] =
    getHashChain(hash).getChainWeight(hash)

  def getChainWeightUnsafe(hash: Hash): BigInt =
    getHashChain(hash).getChainWeightUnsafe(hash)

  def getBlockHashSlice(hash: Hash): IOResult[AVector[Hash]] =
    getHashChain(hash).getBlockHashSlice(hash)

  /* BlockHeader apis */

  protected def getHeaderChain(from: GroupIndex, to: GroupIndex): BlockHeaderChain

  def getHeaderChain(chainIndex: ChainIndex): BlockHeaderChain =
    getHeaderChain(chainIndex.from, chainIndex.to)

  def getHeaderChain(header: BlockHeader): BlockHeaderChain =
    getHeaderChain(header.chainIndex)

  def getHeaderChain(hash: Hash): BlockHeaderChain =
    getHeaderChain(ChainIndex.from(hash))

  def getBlockHeader(hash: Hash): IOResult[BlockHeader] =
    getHeaderChain(hash).getBlockHeader(hash)

  def getBlockHeaderUnsafe(hash: Hash): BlockHeader =
    getHeaderChain(hash).getBlockHeaderUnsafe(hash)

  def add(header: BlockHeader): IOResult[Unit]

  def getHashes(chainIndex: ChainIndex, height: Int): IOResult[AVector[Hash]] =
    getHeaderChain(chainIndex).getHashes(height)

  def getMaxHeight(chainIndex: ChainIndex): IOResult[Int] =
    getHeaderChain(chainIndex).maxHeight

  /* BlockChain apis */

  protected def getBlockChain(from: GroupIndex, to: GroupIndex): BlockChain

  def getBlockChain(chainIndex: ChainIndex): BlockChain =
    getBlockChain(chainIndex.from, chainIndex.to)

  def getBlockChain(block: Block): BlockChain = getBlockChain(block.chainIndex)

  def getBlockChain(hash: Hash): BlockChain = {
    getBlockChain(ChainIndex.from(hash))
  }

  def getBlockUnsafe(hash: Hash): Block = {
    getBlockChain(hash).getBlockUnsafe(hash)
  }

  def getBlock(hash: Hash): IOResult[Block] = {
    getBlockChain(hash).getBlock(hash)
  }

  def add(block: Block): IOResult[Unit]
}
