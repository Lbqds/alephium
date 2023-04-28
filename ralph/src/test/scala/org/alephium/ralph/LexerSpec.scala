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

package org.alephium.ralph

import fastparse.Parsed

import org.alephium.crypto.Byte32
import org.alephium.protocol.{ALPH, Hash, PublicKey}
import org.alephium.protocol.model.{Address, ContractId}
import org.alephium.protocol.vm.Val
import org.alephium.ralph.ArithOperator._
import org.alephium.util.{AlephiumSpec, Hex, I256, U256}

class LexerSpec extends AlephiumSpec {
  it should "parse lexer" in {
    val byte32  = Byte32.generate.toHexString
    val address = Address.p2pkh(PublicKey.generate)

    fastparse.parse("5", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(5))
    fastparse.parse("5u", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(5))
    fastparse.parse("5i", Lexer.typedNum(_)).get.value is Val.I256(I256.unsafe(5))
    fastparse.parse("-5i", Lexer.typedNum(_)).get.value is Val.I256(I256.from(-5))
    fastparse.parse("-5", Lexer.typedNum(_)).get.value is Val.I256(I256.from(-5))
    fastparse.parse("0x12", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(18))
    fastparse.parse("5e18", Lexer.typedNum(_)).get.value is Val.U256(ALPH.alph(5))
    fastparse.parse("5.12e18", Lexer.typedNum(_)).get.value is Val.U256(ALPH.cent(512))
    fastparse.parse("-5e18", Lexer.typedNum(_)).get.value is Val.I256(
      I256.unsafe(ALPH.alph(5).toBigInt.negate())
    )
    fastparse.parse("-5.12e18", Lexer.typedNum(_)).get.value is Val.I256(
      I256.unsafe(ALPH.cent(512).toBigInt.negate())
    )
    fastparse.parse("1_000_000", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(1000000))
    fastparse.parse("1alph", Lexer.typedNum(_)).get.value is Val.U256(ALPH.oneAlph)
    fastparse.parse("1 alph", Lexer.typedNum(_)).get.value is Val.U256(ALPH.oneAlph)
    fastparse.parse("0.01 alph", Lexer.typedNum(_)).get.value is Val.U256(ALPH.cent(1))
    fastparse.parse("1e-18 alph", Lexer.typedNum(_)).get.value is Val.U256(U256.One)

    fastparse.parse(s"#$byte32", Lexer.bytes(_)).get.value is Val.ByteVec(
      Hex.from(byte32).get
    )
    fastparse.parse(s"@${address.toBase58}", Lexer.address(_)).get.value is Val.Address(
      address.lockupScript
    )
    fastparse.parse("x", Lexer.ident(_)).get.value is Ast.Ident("x")
    fastparse.parse("U256", Lexer.typeId(_)).get.value is Ast.TypeId("U256")
    fastparse.parse("Foo", Lexer.typeId(_)).get.value is Ast.TypeId("Foo")
    fastparse.parse("x: U256", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = false, isUnused = false)
    fastparse.parse("mut x: U256", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = true, isUnused = false)
    fastparse.parse("@unused mut x: U256", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = true, isUnused = true)
    fastparse
      .parse("@unused mut x: U256", StatelessParser.contractField(allowMutable = true)(_))
      .get
      .value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = true, isUnused = true)
    fastparse.parse("// comment", Lexer.lineComment(_)).isSuccess is true
    fastparse.parse("add", Lexer.funcId(_)).get.value is Ast.FuncId("add", false)
    fastparse.parse("add!", Lexer.funcId(_)).get.value is Ast.FuncId("add", true)
  }

  it should "report CompilerError messages with line number information" in {
    {
      info("when input is not a number type")
      val input   = "a"
      val failure = fastparse.parse(input, Lexer.typedNum(_)).asInstanceOf[Parsed.Failure].trace()

      failure.index is 0
      failure.longMsg is s"""Expected an I256 or U256 value:1:1 / num:1:1 / (hexNum | decNum):1:1, found "$input""""
    }

    {
      info("when input is an invalid U256")
      val input   = "123456789" * 10
      val failure = fastparse.parse(input, Lexer.typedNum(_)).asInstanceOf[Parsed.Failure]

      failure.index is 0

      val foundToken = input.take(10)
      failure.msg is s"""Expected an U256 value:1:1, found "$foundToken""""
    }

    {
      info("when input is an invalid negative I256")
      val input   = "-" + ("123456789" * 10)
      val failure = fastparse.parse(input, Lexer.typedNum(_)).asInstanceOf[Parsed.Failure]

      failure.index is 0

      val foundToken = input.take(10)
      failure.msg is s"""Expected an I256 value:1:1, found "$foundToken""""
    }

    {
      info("when input is an invalid I256")
      val input   = ("123456789" * 10) + "i"
      val failure = fastparse.parse(input, Lexer.typedNum(_)).asInstanceOf[Parsed.Failure]

      failure.index is 0

      val foundToken = input.take(10)
      failure.msg is s"""Expected an I256 value:1:1, found "$foundToken""""
    }

    {

      def errorAssetScript(errorTypedNum: String): String =
        s"""
           |// comment
           |AssetScript Foo {
           |  pub fn bar(a: U256, b: U256) -> (U256) {
           |    let c = $errorTypedNum
           |    return (a + b + c)
           |  }
           |}
           |""".stripMargin

      {
        info("when the error is in a larger ralph program")

        val invalidTypedNum = "123456789" * 10
        val errorScript     = errorAssetScript(invalidTypedNum)

        val failure =
          fastparse.parse(errorScript, StatelessParser.assetScript(_)).asInstanceOf[Parsed.Failure]
        failure.msg is s"""Expected an U256 value:5:13, found "1234567891""""
      }

    }
  }

  it should "special operators" in {
    fastparse.parse("⊕", Lexer.opModAdd(_)).get.value is ModAdd
    fastparse.parse("⊖", Lexer.opModSub(_)).get.value is ModSub
    fastparse.parse("⊗", Lexer.opModMul(_)).get.value is ModMul
    fastparse.parse("|+|", Lexer.opModAdd(_)).get.value is ModAdd
    fastparse.parse("|-|", Lexer.opModSub(_)).get.value is ModSub
    fastparse.parse("|*|", Lexer.opModMul(_)).get.value is ModMul
    fastparse.parse("++", Lexer.opByteVecAdd(_)).get.value is Concat
  }

  it should "parse bytes and address" in {
    val hash     = Hash.random
    val address  = Address.p2pkh(PublicKey.generate)
    val contract = Address.contract(ContractId.random)
    fastparse.parse(s"#${hash.toHexString}", Lexer.bytes(_)).get.value is
      Val.ByteVec(hash.bytes)
    fastparse.parse(s"@${address.toBase58}", Lexer.address(_)).get.value is
      Val.Address(address.lockupScript)
    intercept[Compiler.Error](fastparse.parse(s"#${address.toBase58}", Lexer.bytes(_))) is Compiler
      .Error(s"Invalid byteVec: ${address.toBase58}")
    fastparse.parse(s"#${contract.toBase58}", Lexer.bytes(_)).get.value is
      Val.ByteVec(contract.contractId.bytes)
  }

  it should "parse string" in {
    fastparse.parse("", Lexer.stringPart(_)).get.value is ""
    fastparse.parse("a", Lexer.stringPart(_)).get.value is "a"
    fastparse.parse(" ", Lexer.stringPart(_)).get.value is " "
    fastparse.parse("a b c$", Lexer.stringPart(_)).get.value is "a b c"
    fastparse.parse("a b c$$$`", Lexer.stringPart(_)).get.value is "a b c$`"
    fastparse.parse("a b c$x$$`", Lexer.stringPart(_)).get.value is "a b c"
    fastparse.parse("$", Lexer.stringPart(_)).get.value is ""
    fastparse.parse("`", Lexer.stringPart(_)).get.value is ""
  }

  it should "parse mut declarations" in {
    {
      info("fail mut declarations when mutability is disallowed")
      val code = s"mut foo"

      // when allowMutable is false, it should not let `mut` declarations through.
      val traced =
        fastparse
          .parse(code, Lexer.mutMaybe(allowMutable = false)(_))
          .asInstanceOf[Parsed.Failure]
          .trace()

      // fastparse reports only the first 10 characters.
      val reportedToken = code.take(10)

      traced.longMsg is s"""Expected an immutable variable:1:1 / (letter | digit | "_"):1:1, found "$reportedToken""""
    }

    {
      info("succeed mut declarations when mutability is allowed")
      forAll { right: String =>
        // when mut has an identifier.
        fastparse
          .parse(s"mut $right", Lexer.mutMaybe(allowMutable = true)(_))
          .asInstanceOf[Parsed.Success[Boolean]]
          .value is true
      }

      // when mut does not have an identifier.
      fastparse
        .parse(s"mut", Lexer.mutMaybe(allowMutable = true)(_))
        .asInstanceOf[Parsed.Success[Boolean]]
        .value is true
    }

    {
      info("succeed for immutable declarations")
      forAll { right: String =>
        // immutable declarations should always be allowed.
        fastparse
          .parse(s"$right", Lexer.mutMaybe(allowMutable = true)(_))
          .asInstanceOf[Parsed.Success[Boolean]]
          .value is false

        fastparse
          .parse(s"$right", Lexer.mutMaybe(allowMutable = false)(_))
          .asInstanceOf[Parsed.Success[Boolean]]
          .value is false
      }
    }
  }
}
