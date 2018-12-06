package org.alephium.protocol.model

import java.math.BigInteger

import org.alephium.crypto._
import org.scalacheck.Gen

object ModelGen {
  private val (sk, pk) = ED25519.generateKeyPair()

  val txInputGen: Gen[TxInput] = for {
    index <- Gen.choose(0, 10)
  } yield TxInput(Keccak256.zero, index) // Has to use zero here to pass test on ubuntu

  val txOutputGen: Gen[TxOutput] = for {
    value <- Gen.choose(0l, 100l)
  } yield TxOutput(BigInteger.valueOf(value), pk)

  val transactionGen = for {
    inputNum  <- Gen.choose(0, 5)
    inputs    <- Gen.listOfN(inputNum, txInputGen)
    outputNum <- Gen.choose(0, 5)
    outputs   <- Gen.listOfN(outputNum, txOutputGen)
  } yield Transaction.from(UnsignedTransaction(inputs, outputs), sk)

  val blockGen: Gen[Block] = for {
    txNum <- Gen.choose(0, 100)
    txs   <- Gen.listOfN(txNum, transactionGen)
  } yield Block.from(Seq.empty, txs, BigInteger.ZERO)

  def blockGenWith(deps: Seq[Keccak256]): Gen[Block] =
    for {
      txNum <- Gen.choose(0, 100)
      txs   <- Gen.listOfN(txNum, transactionGen)
    } yield Block.from(deps, txs, BigInteger.ZERO)
}