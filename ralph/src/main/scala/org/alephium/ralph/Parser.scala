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

import akka.util.ByteString
import fastparse._

import org.alephium.protocol.vm.{Instr, StatefulContext, StatelessContext, Val}
import org.alephium.ralph.Ast.{Annotation, Argument, FuncId, Statement}
import org.alephium.ralph.error.CompilerError
import org.alephium.ralph.error.FastParseExtension._
import org.alephium.util.AVector

// scalastyle:off number.of.methods file.size.limit
@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
abstract class Parser[Ctx <: StatelessContext] {
  implicit val whitespace: P[_] => P[Unit] = { implicit ctx: P[_] => Lexer.emptyChars(ctx) }

  /*
   * PP: Positioned Parser
   * Help adding source index to the result, it works well on easy case, but it fails for
   * unknown reason when parser ends with is a `.rep` or `.opt`, also with our complex `expr` parser,
   * the end index then adds the trailing spaces.
   */
  def PP[Unknown: P, A, B <: Ast.Positioned](a: => P[A])(f: A => B): P[B] = {
    P(Index ~~ a ~~ Index).map { case (from, v, to) =>
      val q = f(v)
      if (q.sourceIndex.isDefined) {
        q
      } else {
        q.atSourceIndex(from, to)
      }
    }
  }

  def value[Unknown: P]: P[Val] = P(Lexer.typedNum | Lexer.bool | Lexer.bytes | Lexer.address)
  def const[Unknown: P]: P[Ast.Const[Ctx]] = PP(value) { const =>
    Ast.Const.apply[Ctx](const)
  }

  def createArray1[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    PP("[" ~ (expr.rep(1, ",")) ~ "]") { elems =>
      Ast.CreateArrayExpr.apply(elems)
    }
  def createArray2[Unknown: P]: P[Ast.CreateArrayExpr[Ctx]] =
    PP("[" ~ (expr ~ ";" ~ nonNegativeNum("array size")) ~ "]") { case ((expr, size)) =>
      Ast.CreateArrayExpr(Seq.fill(size)(expr))
    }
  def arrayExpr[Unknown: P]: P[Ast.Expr[Ctx]] = P(createArray1 | createArray2)
  def variable[Unknown: P]: P[Ast.Variable[Ctx]] =
    PP(Lexer.ident | Lexer.constantIdent) { name =>
      Ast.Variable.apply[Ctx](name)
    }

  def variableIdOnly[Unknown: P]: P[Ast.Variable[Ctx]] =
    PP(Lexer.ident) { value =>
      Ast.Variable.apply[Ctx](value)
    }
  def alphTokenId[Unknown: P]: P[Ast.Expr[Ctx]] =
    PP(Lexer.token(Keyword.ALPH_CAPS)) { _ =>
      Ast.ALPHTokenId()
    }

  def alphAmount[Unknown: P]: P[Ast.Expr[Ctx]]                       = expr
  def tokenAmount[Unknown: P]: P[(Ast.Expr[Ctx], Ast.Expr[Ctx])]     = P(expr ~ ":" ~ expr)
  def amountList[Unknown: P]: P[Seq[(Ast.Expr[Ctx], Ast.Expr[Ctx])]] = P(tokenAmount.rep(0, ","))
  def approveAssetPerAddress[Unknown: P]: P[Ast.ApproveAsset[Ctx]] =
    PP(expr ~ "->" ~~ Index ~ amountList) { case (address, index, amounts) =>
      if (amounts.isEmpty) {
        throw CompilerError.`Expected non-empty asset(s) for address`(index)
      }
      Ast.ApproveAsset(address, amounts)
    }
  def approveAssets[Unknown: P]: P[Seq[Ast.ApproveAsset[Ctx]]] =
    P("{" ~ approveAssetPerAddress.rep(1, ";") ~ ";".? ~ "}")
  def callAbs[Unknown: P]: P[(Ast.FuncId, Seq[Ast.ApproveAsset[Ctx]], Seq[Ast.Expr[Ctx]])] =
    P(Lexer.funcId ~ approveAssets.? ~ "(" ~ expr.rep(0, ",") ~ ")").map {
      case (funcId, approveAssets, arguments) =>
        (funcId, approveAssets.getOrElse(Seq.empty), arguments)
    }
  def callExpr[Unknown: P]: P[Ast.Expr[Ctx]] =
    PP((Lexer.typeId ~ ".").? ~ callAbs) { case (contractIdOpt, (funcId, approveAssets, expr)) =>
      contractIdOpt match {
        case Some(contractId) =>
          Ast
            .ContractStaticCallExpr(contractId, funcId, approveAssets, expr)
        case None => Ast.CallExpr(funcId, approveAssets, expr)
      }
    }
  def contractConv[Unknown: P]: P[Ast.ContractConv[Ctx]] =
    PP(Lexer.typeId ~ "(" ~ expr ~ ")") { case (typeId, expr) =>
      Ast.ContractConv(typeId, expr)
    }

  def chain[Unknown: P](p: => P[Ast.Expr[Ctx]], op: => P[Operator]): P[Ast.Expr[Ctx]] = {
    P(p ~ (op ~ p).rep).map { case (lhs, rhs) =>
      rhs.foldLeft(lhs) { case (acc, (op, right)) =>
        val sourceIndex = SourceIndex(acc.sourceIndex, right.sourceIndex)
        Ast.Binop(op, acc, right).atSourceIndex(sourceIndex)
      }
    }
  }

  def nonNegativeNum[Unknown: P](errorMsg: String): P[Int] = P(Index ~ Lexer.num).map {
    case (fromIndex, value) =>
      val idx = value.intValue()
      if (idx < 0) {
        throw Compiler.Error(s"Invalid $errorMsg: $idx", Some(SourceIndex(fromIndex)))
      }
      idx
  }

  def arrayIndex[Unknown: P]: P[Ast.Expr[Ctx]] = P("[" ~ expr ~ "]")

  // Optimize chained comparisons
  def expr[Unknown: P]: P[Ast.Expr[Ctx]]    = P(chain(andExpr, Lexer.opOr))
  def andExpr[Unknown: P]: P[Ast.Expr[Ctx]] = P(chain(relationExpr, Lexer.opAnd))
  def relationExpr[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(arithExpr6 ~ comparison.?).flatMap {
      case (lhs, Some(op)) =>
        arithExpr6.map { rhs =>
          val sourceIndex = SourceIndex(lhs.sourceIndex, rhs.sourceIndex)
          Ast.Binop(op, lhs, rhs).atSourceIndex(sourceIndex)
        }
      case (lhs, None) => Pass(lhs)
    }
  def comparison[Unknown: P]: P[TestOperator] =
    P(Lexer.opEq | Lexer.opNe | Lexer.opLe | Lexer.opLt | Lexer.opGe | Lexer.opGt)
  def arithExpr6[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr5, Lexer.opBitOr))
  def arithExpr5[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr4, Lexer.opXor))
  def arithExpr4[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr3, Lexer.opBitAnd))
  def arithExpr3[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr2, Lexer.opSHL | Lexer.opSHR))
  def arithExpr2[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(
      chain(
        arithExpr1,
        Lexer.opByteVecAdd | Lexer.opAdd | Lexer.opSub | Lexer.opModAdd | Lexer.opModSub
      )
    )
  def arithExpr1[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(chain(arithExpr0, Lexer.opMul | Lexer.opDiv | Lexer.opMod | Lexer.opModMul))
  def arithExpr0[Unknown: P]: P[Ast.Expr[Ctx]] = P(chain(unaryExpr, Lexer.opExp | Lexer.opModExp))
  def unaryExpr[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(arrayElementOrStructFieldSelector | PP(Lexer.opNot ~ arrayElementOrStructFieldSelector) {
      case (op, expr) =>
        Ast.UnaryOp.apply[Ctx](op, expr)
    })
  def arrayElementOrStructFieldSelector[Unknown: P]: P[Ast.Expr[Ctx]] =
    P(Index ~~ atom ~ P(P("." ~ Lexer.ident) | arrayIndex).rep(0) ~~ Index).map {
      case (from, expr, list, to) =>
        val result = list.foldLeft(expr) { case (acc, e) =>
          e match {
            case arrayIndex: Ast.Expr[Ctx @unchecked] => Ast.ArrayElement(acc, arrayIndex)
            case ident: Ast.Ident                     => Ast.StructFieldSelector(acc, ident)
          }
        }
        if (list.nonEmpty) {
          result.atSourceIndex(from, to)
        } else {
          result
        }
    }
  def atom[Unknown: P]: P[Ast.Expr[Ctx]]

  def structCtor[Unknown: P]: P[Ast.StructCtor[Ctx]] =
    PP(Lexer.typeId ~ "{" ~ P(Lexer.ident ~ ":" ~ expr).rep(0, ",") ~ "}") {
      case (typeId, fields) =>
        if (fields.isEmpty) {
          throw Compiler.Error(s"No field definition in struct ${typeId.name}", typeId.sourceIndex)
        }
        Ast.StructCtor(typeId, fields)
    }

  def parenExpr[Unknown: P]: P[Ast.ParenExpr[Ctx]] =
    PP("(" ~ expr ~ ")") { case (ex) =>
      Ast.ParenExpr.apply[Ctx](ex)
    }

  def ifBranchExpr[Unknown: P]: P[Ast.IfBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`if`) ~/ "(" ~ expr ~ ")" ~ expr).map { case (ifIndex, condition, expr) =>
      val sourceIndex = SourceIndex(Some(ifIndex), expr.sourceIndex)
      Ast.IfBranchExpr(condition, expr).atSourceIndex(sourceIndex)
    }
  def elseIfBranchExpr[Unknown: P]: P[Ast.IfBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ ifBranchExpr).map { case (elseIndex, ifBranch) =>
      val sourceIndex = SourceIndex(Some(elseIndex), ifBranch.sourceIndex)
      Ast.IfBranchExpr(ifBranch.condition, ifBranch.expr).atSourceIndex(sourceIndex)
    }
  def elseBranchExpr[Unknown: P]: P[Ast.ElseBranchExpr[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ expr).map { case (elseIndex, expr) =>
      val sourceIndex = SourceIndex(Some(elseIndex), expr.sourceIndex)
      Ast.ElseBranchExpr(expr).atSourceIndex(sourceIndex)
    }

  def ifelseExpr[Unknown: P]: P[Ast.IfElseExpr[Ctx]] =
    P(ifBranchExpr ~ elseIfBranchExpr.rep(0) ~~ Index ~ elseBranchExpr.?).map {
      case (ifBranch, elseIfBranches, _, Some(elseBranch)) =>
        val sourceIndex = SourceIndex(ifBranch.sourceIndex, elseBranch.sourceIndex)
        Ast.IfElseExpr(ifBranch +: elseIfBranches, elseBranch).atSourceIndex(sourceIndex)
      case (_, _, index, None) =>
        throw CompilerError.`Expected else statement`(index)
    }

  def stringLiteral[Unknown: P]: P[Ast.StringLiteral[Ctx]] =
    PP("b" ~ Lexer.string) { s =>
      Ast.StringLiteral(Val.ByteVec(ByteString.fromString(s)))
    }

  def ret[Unknown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Index ~~ normalRet.rep(1) ~~ Index).map { case (fromIndex, returnStmts, endIndex) =>
      if (returnStmts.length > 1) {
        throw Compiler.Error(
          "Consecutive return statements are not allowed",
          Some(SourceIndex(fromIndex, endIndex - fromIndex))
        )
      } else {
        returnStmts(0)
      }
    }

  def normalRet[Unknown: P]: P[Ast.ReturnStmt[Ctx]] =
    P(Lexer.token(Keyword.`return`) ~/ expr.rep(0, ",")).map { case (returnIndex, returns) =>
      val too =
        returns.lastOption.flatMap(_.sourceIndex.map(_.endIndex)).getOrElse(returnIndex.endIndex)
      Ast.ReturnStmt.apply[Ctx](returns).atSourceIndex(returnIndex.index, too)
    }

  def stringInterpolator[Unknown: P]: P[Ast.Expr[Ctx]] =
    PP("${" ~ expr ~ "}")(identity)

  def debug[Unknown: P]: P[Ast.Debug[Ctx]] =
    P("emit" ~~ Index ~ "Debug" ~/ "(" ~ Lexer.string(() => stringInterpolator) ~ ")" ~~ Index)
      .map { case (fromIndex, (stringParts, interpolationParts), endIndex) =>
        Ast
          .Debug(
            stringParts.map(s => Val.ByteVec(ByteString.fromString(s))),
            interpolationParts
          )
          .atSourceIndex(fromIndex, endIndex)
      }

  def anonymousVar[Unknown: P]: P[Ast.VarDeclaration] = P("_").map(_ => Ast.AnonymousVar)
  def namedVar[Unknown: P]: P[Ast.VarDeclaration] =
    P(Lexer.mut ~ Lexer.ident).map(Ast.NamedVar.tupled)

  def varDeclaration[Unknown: P]: P[Ast.VarDeclaration] = P(namedVar | anonymousVar)
  def varDeclarations[Unknown: P]: P[Seq[Ast.VarDeclaration]] = P(
    varDeclaration.map(Seq(_)) | "(" ~ varDeclaration.rep(1, ",") ~ ")"
  )
  def varDef[Unknown: P]: P[Ast.VarDef[Ctx]] =
    PP(Lexer.token(Keyword.let) ~/ varDeclarations ~ "=" ~ expr) { case (_, vars, expr) =>
      Ast.VarDef(vars, expr)
    }
  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def assignmentTarget[Unknown: P]: P[Ast.AssignmentTarget[Ctx]] = PP(
    Lexer.ident ~ P(P("." ~ Lexer.ident) | arrayIndex).rep(0)
  ) { case (ident, list) =>
    if (list.isEmpty) {
      Ast.AssignmentSimpleTarget(ident)
    } else {
      val variable: Ast.Expr[Ctx] = Ast.Variable(ident).atSourceIndex(ident.sourceIndex)
      val expr = list.init.foldLeft(variable) { case (acc, e) =>
        e match {
          case arrayIndex: Ast.Expr[Ctx @unchecked] => Ast.ArrayElement(acc, arrayIndex)
          case ident: Ast.Ident                     => Ast.StructFieldSelector(acc, ident)
        }
      }
      list.last match {
        case arrayIndex: Ast.Expr[Ctx @unchecked] =>
          Ast.AssignmentArrayElementTarget(ident, expr, arrayIndex)
        case selector: Ast.Ident => Ast.AssignmentStructFieldTarget(ident, expr, selector)
      }
    }
  }

  def assign[Unknown: P]: P[Ast.Assign[Ctx]] =
    P(assignmentTarget.rep(1, ",") ~ "=" ~ expr).map { case (targets, expr) =>
      val sourceIndex = SourceIndex(targets.headOption.flatMap(_.sourceIndex), expr.sourceIndex)
      Ast.Assign(targets, expr).atSourceIndex(sourceIndex)
    }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def parseType[Unknown: P](contractTypeCtor: Ast.TypeId => Type): P[Type] = {
    P(
      Lexer.typeId.map(id => Lexer.primTpes.getOrElse(id.name, contractTypeCtor(id))) |
        arrayType(parseType(contractTypeCtor))
    )
  }

  // use by-name parameter because of https://github.com/com-lihaoyi/fastparse/pull/204
  def arrayType[Unknown: P](baseType: => P[Type]): P[Type] = {
    P("[" ~ baseType ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (tpe, size) =>
      Type.FixedSizeArray(tpe, size)
    }
  }
  def argument[Unknown: P](
      allowMutable: Boolean
  )(contractTypeCtor: Ast.TypeId => Type): P[Ast.Argument] =
    P(Index ~ Lexer.unused ~ Lexer.mutMaybe(allowMutable) ~ Lexer.ident ~ ":").flatMap {
      case (fromIndex, isUnused, isMutable, ident) =>
        P(parseType(contractTypeCtor) ~~ Index).map { case (tpe, endIndex) =>
          Ast.Argument(ident, tpe, isMutable, isUnused).atSourceIndex(fromIndex, endIndex)
        }
    }
  def funcArgument[Unknown: P]: P[Ast.Argument]   = argument(allowMutable = true)(Type.NamedType)
  def funParams[Unknown: P]: P[Seq[Ast.Argument]] = P("(" ~ funcArgument.rep(0, ",") ~ ")")
  def returnType[Unknown: P]: P[Seq[Type]]        = P(simpleReturnType | bracketReturnType)
  def simpleReturnType[Unknown: P]: P[Seq[Type]] =
    P("->" ~ parseType(Type.NamedType)).map(tpe => Seq(tpe))
  def bracketReturnType[Unknown: P]: P[Seq[Type]] =
    P("->" ~ "(" ~ parseType(Type.NamedType).rep(0, ",") ~ ")")
  def funcTmp[Unknown: P]: P[FuncDefTmp[Ctx]] =
    PP(
      annotation.rep(0) ~
        Index ~ Lexer.FuncModifier.modifiers.rep(0) ~~ Index ~ Lexer
          .token(
            Keyword.fn
          ) ~/ Lexer.funcId ~ funParams ~ returnType ~ ("{" ~ statement.rep ~ "}").?
    ) {
      case (
            annotations,
            modifiersStart,
            modifiers,
            modifiersEnd,
            _,
            funcId,
            params,
            returnType,
            statements
          ) =>
        if (modifiers.toSet.size != modifiers.length) {
          throw Compiler.Error(
            s"Duplicated function modifiers: $modifiers",
            Some(SourceIndex(modifiersStart, modifiersEnd - modifiersStart))
          )
        } else {
          val isPublic = modifiers.contains(Lexer.FuncModifier.Pub)
          val usingAnnotation = Parser.UsingAnnotation.extractFields(
            annotations,
            Parser.UsingAnnotationFields(
              preapprovedAssets = false,
              assetsInContract = false,
              checkExternalCaller = true,
              updateFields = false
            )
          )
          FuncDefTmp(
            annotations,
            funcId,
            isPublic,
            usingAnnotation.preapprovedAssets,
            usingAnnotation.assetsInContract,
            usingAnnotation.checkExternalCaller,
            usingAnnotation.updateFields,
            params,
            returnType,
            statements
          )
        }
    }
  def func[Unknown: P]: P[Ast.FuncDef[Ctx]] = funcTmp.map { f =>
    Ast
      .FuncDef(
        f.annotations,
        f.id,
        f.isPublic,
        f.usePreapprovedAssets,
        f.useContractAssets,
        f.useCheckExternalCaller,
        f.useUpdateFields,
        f.args,
        f.rtypes,
        f.body
      )
      .atSourceIndex(f.sourceIndex)
  }

  def eventFields[Unknown: P]: P[Seq[Ast.EventField]] = P("(" ~ eventField.rep(0, ",") ~ ")")
  def eventDef[Unknown: P]: P[Ast.EventDef] =
    P(Lexer.token(Keyword.event) ~/ Lexer.typeId ~ eventFields ~~ Index)
      .map { case (eventIndex, typeId, fields, endIndex) =>
        if (fields.length >= Instr.allLogInstrs.length) {
          throw Compiler.Error(
            "Max 8 fields allowed for contract events",
            Some(SourceIndex(eventIndex.index, endIndex - eventIndex.index))
          )
        }
        if (typeId.name == "Debug") {
          throw Compiler.Error("Debug is a built-in event name", typeId.sourceIndex)
        }
        Ast.EventDef(typeId, fields).atSourceIndex(eventIndex.index, endIndex)
      }

  def funcCall[Unknown: P]: P[Ast.Statement[Ctx]] =
    (Index ~ (Lexer.typeId ~ ".").? ~ callAbs ~~ Index).map {
      case (fromIndex, contractIdOpt, (funcId, approveAssets, exprs), endIndex) =>
        contractIdOpt match {
          case Some(contractId) =>
            Ast
              .StaticContractFuncCall(contractId, funcId, approveAssets, exprs)
              .atSourceIndex(fromIndex, endIndex)
          case None => Ast.FuncCall(funcId, approveAssets, exprs).atSourceIndex(fromIndex, endIndex)
        }
    }

  def block[Unknown: P]: P[Seq[Ast.Statement[Ctx]]]      = P("{" ~ statement.rep(1) ~ "}")
  def emptyBlock[Unknown: P]: P[Seq[Ast.Statement[Ctx]]] = P("{" ~ "}").map(_ => Seq.empty)
  def ifBranchStmt[Unknown: P]: P[Ast.IfBranchStatement[Ctx]] =
    P(Lexer.token(Keyword.`if`) ~ "(" ~ expr ~ ")" ~ block ~~ Index).map {
      case (ifIndex, condition, body, endIndex) =>
        Ast.IfBranchStatement(condition, body).atSourceIndex(ifIndex.index, endIndex)
    }
  def elseIfBranchStmt[Unknown: P]: P[Ast.IfBranchStatement[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ ifBranchStmt).map { case (elseIndex, ifBranch) =>
      val sourceIndex = SourceIndex(Some(elseIndex), ifBranch.sourceIndex)
      Ast.IfBranchStatement(ifBranch.condition, ifBranch.body).atSourceIndex(sourceIndex)
    }
  def elseBranchStmt[Unknown: P]: P[Ast.ElseBranchStatement[Ctx]] =
    P(Lexer.token(Keyword.`else`) ~ (block | emptyBlock) ~~ Index).map {
      case (index, statement, endIndex) =>
        Ast.ElseBranchStatement(statement).atSourceIndex(index.index, endIndex)
    }
  def ifelseStmt[Unknown: P]: P[Ast.IfElseStatement[Ctx]] =
    P(ifBranchStmt ~ elseIfBranchStmt.rep(0) ~ elseBranchStmt.?)
      .map { case (ifBranch, elseIfBranches, elseBranchOpt) =>
        if (elseIfBranches.nonEmpty && elseBranchOpt.isEmpty) {
          throw Compiler.Error(
            "If ... else if constructs should be terminated with an else statement",
            ifBranch.sourceIndex
          )
        }
        Ast.IfElseStatement(ifBranch +: elseIfBranches, elseBranchOpt)
      }

  def whileStmt[Unknown: P]: P[Ast.While[Ctx]] =
    P(Lexer.token(Keyword.`while`) ~/ "(" ~ expr ~ ")" ~ block ~~ Index).map {
      case (whileIndex, expr, block, endIndex) =>
        Ast.While(expr, block).atSourceIndex(whileIndex.index, endIndex)
    }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def forLoopStmt[Unknown: P]: P[Ast.ForLoop[Ctx]] =
    P(
      Lexer.token(
        Keyword.`for`
      ) ~/ "(" ~ statement.? ~ ";" ~ expr ~ ";" ~ statement.? ~ ")" ~ block ~~ Index
    )
      .map { case (forIndex, initializeOpt, condition, updateOpt, body, endIndex) =>
        if (initializeOpt.isEmpty) {
          throw Compiler.Error("No initialize statement in for loop", Some(forIndex))
        }
        if (updateOpt.isEmpty) {
          throw Compiler.Error("No update statement in for loop", Some(forIndex))
        }
        Ast
          .ForLoop(initializeOpt.get, condition, updateOpt.get, body)
          .atSourceIndex(forIndex.index, endIndex)
      }

  def statement[Unknown: P]: P[Ast.Statement[Ctx]]

  def contractField[Unknown: P](allowMutable: Boolean): P[Ast.Argument] =
    argument(allowMutable)(Type.NamedType)

  def templateParams[Unknown: P]: P[Seq[Ast.Argument]] =
    P("(" ~ contractField(allowMutable = false).rep(0, ",") ~ ")")

  def field[Unknown: P]: P[(Ast.Ident, Type)] = P(Lexer.ident ~ ":").flatMap { ident =>
    parseType(Type.NamedType).map { tpe => (ident, tpe) }
  }

  def eventField[Unknown: P]: P[Ast.EventField]   = P(field).map(Ast.EventField.tupled)
  def structField[Unknown: P]: P[Ast.StructField] = PP(field)(Ast.StructField.tupled)
  def rawStruct[Unknown: P]: P[Ast.Struct] =
    PP(Lexer.token(Keyword.struct) ~/ Lexer.typeId ~ "{" ~ structField.rep ~ "}") {
      case (structIndex, id, fields) =>
        if (fields.isEmpty) {
          val sourceIndex = SourceIndex(Some(structIndex), id.sourceIndex)
          throw Compiler.Error(s"No field definition in struct ${id.name}", sourceIndex)
        }
        Ast.UniqueDef.checkDuplicates(fields, "struct fields")
        Ast.Struct(id, fields)
    }
  def struct[Unknown: P]: P[Ast.Struct] = P(Start ~ rawStruct ~ End)

  def annotationField[Unknown: P]: P[Ast.AnnotationField] =
    P(Index ~ Lexer.ident ~ "=" ~ expr ~~ Index).map {
      case (fromIndex, ident, expr: Ast.Const[_], endIndex) =>
        Ast.AnnotationField(ident, expr.v).atSourceIndex(fromIndex, endIndex)
      case (_, _, expr, _) =>
        throw Compiler.Error(
          s"Expect const value for annotation field, got ${expr}",
          expr.sourceIndex
        )
    }
  def annotationFields[Unknown: P]: P[Seq[Ast.AnnotationField]] =
    P("(" ~ annotationField.rep(1, ",") ~ ")")
  def annotation[Unknown: P]: P[Ast.Annotation] =
    P(Index ~ "@" ~ Lexer.ident ~ annotationFields.? ~~ Index).map {
      case (fromIndex, id, fieldsOpt, endIndex) =>
        Ast.Annotation(id, fieldsOpt.getOrElse(Seq.empty)).atSourceIndex(fromIndex, endIndex)
    }
}

final case class FuncDefTmp[Ctx <: StatelessContext](
    annotations: Seq[Annotation],
    id: FuncId,
    isPublic: Boolean,
    usePreapprovedAssets: Boolean,
    useContractAssets: Boolean,
    useCheckExternalCaller: Boolean,
    useUpdateFields: Boolean,
    args: Seq[Argument],
    rtypes: Seq[Type],
    body: Option[Seq[Statement[Ctx]]]
) extends Ast.Positioned

object Parser {
  sealed trait RalphAnnotation[T] {
    def id: String
    def keys: AVector[String]
    def validate(annotations: Seq[Ast.Annotation]): Unit = {
      annotations.find(_.id.name != id) match {
        case Some(annotation) =>
          throw Compiler.Error(
            s"Invalid annotation, expect @$id annotation",
            annotation.sourceIndex
          )
        case None => ()
      }
    }

    final def extractField[V <: Val](
        annotation: Ast.Annotation,
        key: String,
        tpe: Val.Type
    ): Option[V] = {
      annotation.fields.find(_.ident.name == key) match {
        case Some(Ast.AnnotationField(_, value: V @unchecked)) if tpe == value.tpe => Some(value)
        case Some(field) =>
          throw Compiler.Error(s"Expect $tpe for $key in annotation @$id", field.sourceIndex)
        case None => None
      }
    }

    final def extractField[V <: Val](annotation: Ast.Annotation, key: String, default: V): V = {
      extractField[V](annotation, key, default.tpe).getOrElse(default)
    }

    final def extractFields(annotations: Seq[Ast.Annotation], default: T): T = {
      validate(annotations)
      annotations.headOption match {
        case Some(annotation) =>
          val invalidKeys = annotation.fields.filter(f => !keys.contains(f.ident.name))
          if (invalidKeys.nonEmpty) {
            throw Compiler.Error(
              s"Invalid keys for @$id annotation: ${invalidKeys.map(_.ident.name).mkString(",")}",
              annotation.sourceIndex
            )
          }
          extractFields(annotation, default)
        case None => default
      }
    }
    def extractFields(annotation: Ast.Annotation, default: T): T
  }

  final case class UsingAnnotationFields(
      preapprovedAssets: Boolean,
      assetsInContract: Boolean,
      checkExternalCaller: Boolean,
      updateFields: Boolean
  )

  object UsingAnnotation extends RalphAnnotation[UsingAnnotationFields] {
    val id: String                = "using"
    val usePreapprovedAssetsKey   = "preapprovedAssets"
    val useContractAssetsKey      = "assetsInContract"
    val useCheckExternalCallerKey = "checkExternalCaller"
    val useUpdateFieldsKey        = "updateFields"
    val keys: AVector[String] = AVector(
      usePreapprovedAssetsKey,
      useContractAssetsKey,
      useCheckExternalCallerKey,
      useUpdateFieldsKey
    )

    def extractFields(
        annotation: Ast.Annotation,
        default: UsingAnnotationFields
    ): UsingAnnotationFields = {
      UsingAnnotationFields(
        extractField(annotation, usePreapprovedAssetsKey, Val.Bool(default.preapprovedAssets)).v,
        extractField(annotation, useContractAssetsKey, Val.Bool(default.assetsInContract)).v,
        extractField(
          annotation,
          useCheckExternalCallerKey,
          Val.Bool(default.checkExternalCaller)
        ).v,
        extractField(annotation, useUpdateFieldsKey, Val.Bool(default.updateFields)).v
      )
    }
  }

  final case class InterfaceStdFields(id: ByteString)

  object InterfaceStdAnnotation extends RalphAnnotation[Option[InterfaceStdFields]] {
    val id: String            = "std"
    val keys: AVector[String] = AVector("id")

    def extractFields(
        annotation: Annotation,
        default: Option[InterfaceStdFields]
    ): Option[InterfaceStdFields] = {
      extractField[Val.ByteVec](annotation, keys(0), Val.ByteVec).map { stdId =>
        if (stdId.bytes.isEmpty) {
          throw Compiler.Error(
            "The field id of the @std annotation must be a non-empty ByteVec",
            annotation.sourceIndex
          )
        }
        InterfaceStdFields(Ast.StdInterfaceIdPrefix ++ stdId.bytes)
      }
    }
  }

  final case class ContractStdFields(enabled: Boolean)

  object ContractStdAnnotation extends RalphAnnotation[Option[ContractStdFields]] {
    val id: String            = "std"
    val keys: AVector[String] = AVector("enabled")

    def extractFields(
        annotation: Annotation,
        default: Option[ContractStdFields]
    ): Option[ContractStdFields] = {
      extractField[Val.Bool](annotation, keys(0), Val.Bool).map(field => ContractStdFields(field.v))
    }
  }
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
object StatelessParser extends Parser[StatelessContext] {
  def atom[Unknown: P]: P[Ast.Expr[StatelessContext]] =
    P(
      const | stringLiteral | alphTokenId | callExpr | contractConv |
        structCtor | variable | parenExpr | arrayExpr | ifelseExpr
    )

  def statement[Unknown: P]: P[Ast.Statement[StatelessContext]] =
    P(varDef | assign | debug | funcCall | ifelseStmt | whileStmt | forLoopStmt | ret)

  def assetScript[Unknown: P]: P[Ast.AssetScript] =
    P(
      Start ~ rawStruct.rep(0) ~ Lexer.token(Keyword.AssetScript) ~/ Lexer.typeId ~
        templateParams.? ~ "{" ~ func.rep(1) ~ "}" ~~ Index ~ rawStruct.rep(0) ~ endOfInput
    ).map { case (defs0, assetIndex, typeId, templateVars, funcs, endIndex, defs1) =>
      Ast
        .AssetScript(typeId, templateVars.getOrElse(Seq.empty), funcs, defs0 ++ defs1)
        .updateType()
        .atSourceIndex(assetIndex.index, endIndex)
    }
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
object StatefulParser extends Parser[StatefulContext] {
  def atom[Unknown: P]: P[Ast.Expr[StatefulContext]] =
    P(
      const | stringLiteral | alphTokenId | callExpr | contractCallExpr | contractConv |
        enumFieldSelector | structCtor | variable | parenExpr | arrayExpr | ifelseExpr
    )

  def contractCallExpr[Unknown: P]: P[Ast.Expr[StatefulContext]] =
    P(Index ~ (callExpr | contractConv | variableIdOnly) ~ ("." ~ callAbs).rep(1) ~~ Index).map {
      case (fromIndex, obj, callAbss, endIndex) =>
        callAbss.foldLeft(obj)((acc, callAbs) => {
          val (funcId, approveAssets, arguments) = callAbs
          Ast
            .ContractCallExpr(acc, funcId, approveAssets, arguments)
            .atSourceIndex(fromIndex, endIndex)
        })
    }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def contractCall[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    P(Index ~ (callExpr | contractConv | variableIdOnly) ~ ("." ~ callAbs).rep(1) ~~ Index).map {
      case (fromIndex, obj, callAbss, endIndex) =>
        val base = callAbss.init.foldLeft(obj)((acc, callAbs) => {
          val (funcId, approveAssets, arguments) = callAbs
          Ast
            .ContractCallExpr(acc, funcId, approveAssets, arguments)
            .atSourceIndex(fromIndex, endIndex)
        })
        val (funcId, approveAssets, arguments) = callAbss.last
        Ast.ContractCall(base, funcId, approveAssets, arguments).atSourceIndex(fromIndex, endIndex)
    }

  def statement[Unknown: P]: P[Ast.Statement[StatefulContext]] =
    P(
      varDef | assign | debug | funcCall | contractCall | ifelseStmt | whileStmt | forLoopStmt | ret | emitEvent
    )

  def contractFields[Unknown: P]: P[Seq[Ast.Argument]] =
    P(
      "(" ~ contractField(allowMutable = true).rep(0, ",") ~ ")"
    )

  def rawTxScript[Unknown: P]: P[Ast.TxScript] =
    P(
      annotation.rep ~
        Lexer.token(
          Keyword.TxScript
        ) ~/ Lexer.typeId ~ templateParams.? ~ "{" ~~ Index ~ statement
          .rep(0) ~ func
          .rep(0) ~ "}"
        ~~ Index
    )
      .map {
        case (
              annotations,
              scriptIndex,
              typeId,
              templateVars,
              mainStmtsIndex,
              mainStmts,
              funcs,
              endIndex
            ) =>
          if (mainStmts.isEmpty) {
            throw CompilerError.`Expected main statements`(typeId, mainStmtsIndex)
          } else {
            val usingAnnotation = Parser.UsingAnnotation.extractFields(
              annotations,
              Parser.UsingAnnotationFields(
                preapprovedAssets = true,
                assetsInContract = false,
                checkExternalCaller = true,
                updateFields = false
              )
            )
            val mainFunc = Ast.FuncDef.main(
              mainStmts,
              usingAnnotation.preapprovedAssets,
              usingAnnotation.assetsInContract,
              usingAnnotation.updateFields
            )
            Ast
              .TxScript(typeId, templateVars.getOrElse(Seq.empty), mainFunc +: funcs)
              .atSourceIndex(scriptIndex.index, endIndex)
          }
      }
  def txScript[Unknown: P]: P[Ast.TxScript] = P(Start ~ rawTxScript ~ End)

  def inheritanceTemplateVariable[Unknown: P]: P[Seq[Ast.Ident]] =
    P("<" ~ Lexer.ident.rep(1, ",") ~ ">").?.map(_.getOrElse(Seq.empty))
  def inheritanceFields[Unknown: P]: P[Seq[Ast.Ident]] =
    P("(" ~ Lexer.ident.rep(0, ",") ~ ")")
  def contractInheritance[Unknown: P]: P[Ast.ContractInheritance] =
    P(Lexer.typeId ~ inheritanceFields).map { case (typeId, fields) =>
      Ast.ContractInheritance(typeId, fields)
    }

  def interfaceImplementing[Unknown: P]: P[Seq[Ast.Inheritance]] =
    P(Lexer.token(Keyword.implements) ~ (interfaceInheritance.rep(1, ","))).map {
      case (_, inheritances) => inheritances
    }

  def contractExtending[Unknown: P]: P[Seq[Ast.Inheritance]] =
    P(
      (Lexer.token(Keyword.`extends`) | Lexer.token(Keyword.`embeds`)) ~
        contractInheritance.rep(1, ",")
    ).map { case (_, extendings) => extendings }

  def contractInheritances[Unknown: P]: P[Seq[Ast.Inheritance]] = {
    P(Index ~ contractExtending.? ~ interfaceImplementing.? ~~ Index).map {
      case (fromIndex, extendingsOpt, implementingOpt, endIndex) => {
        val implementedInterfaces = implementingOpt.getOrElse(Seq.empty)
        if (implementedInterfaces.length > 1) {
          val interfaceNames = implementedInterfaces.map(_.parentId.name).mkString(", ")
          throw Compiler.Error(
            s"Contract only supports implementing single interface: $interfaceNames",
            Some(SourceIndex(fromIndex, endIndex - fromIndex))
          )
        }

        extendingsOpt.getOrElse(Seq.empty) ++ implementedInterfaces
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def constantVarDef[Unknown: P]: P[Ast.ConstantVarDef] =
    PP(
      Lexer.token(Keyword.const) ~/ Lexer.constantIdent ~ "=" ~ (value | stringLiteral.map(
        _.string
      ))
    ) { case (_, ident, v) =>
      Ast.ConstantVarDef(ident, v.asInstanceOf[Val])
    }

  def enumFieldSelector[Unknown: P]: P[Ast.EnumFieldSelector[StatefulContext]] =
    PP(Lexer.typeId ~ "." ~ Lexer.constantIdent) { case (enumId, field) =>
      Ast.EnumFieldSelector(enumId, field)
    }

  def enumField[Unknown: P]: P[Ast.EnumField] =
    PP(Lexer.constantIdent ~ "=" ~ (value | stringLiteral.map(_.string))) { case (ident, value) =>
      Ast.EnumField(ident, value)
    }
  def rawEnumDef[Unknown: P]: P[Ast.EnumDef] =
    PP(Lexer.token(Keyword.`enum`) ~/ Lexer.typeId ~ "{" ~ enumField.rep ~ "}") {
      case (enumIndex, id, fields) =>
        if (fields.length == 0) {
          val sourceIndex = SourceIndex(Some(enumIndex), id.sourceIndex)
          throw Compiler.Error(s"No field definition in Enum ${id.name}", sourceIndex)
        }
        Ast.UniqueDef.checkDuplicates(fields, "enum fields")
        if (fields.distinctBy(_.value.tpe).size != 1) {
          throw Compiler.Error(s"Fields have different types in Enum ${id.name}", id.sourceIndex)
        }
        if (fields.distinctBy(_.value).size != fields.length) {
          throw Compiler.Error(s"Fields have the same value in Enum ${id.name}", id.sourceIndex)
        }
        Ast.EnumDef(id, fields)
    }
  def enumDef[Unknown: P]: P[Ast.EnumDef] = P(Start ~ rawEnumDef ~ End)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def rawContract[Unknown: P]: P[Ast.Contract] =
    P(
      annotation.rep ~ Index ~ Lexer.`abstract` ~ Lexer.token(
        Keyword.Contract
      ) ~/ Lexer.typeId ~ contractFields ~
        contractInheritances.? ~ "{" ~ eventDef.rep ~ constantVarDef.rep ~ rawEnumDef.rep ~ func.rep ~ "}"
        ~~ Index
    ).map {
      case (
            annotations,
            fromIndex,
            isAbstract,
            _,
            typeId,
            fields,
            contractInheritances,
            events,
            constantVars,
            enums,
            funcs,
            endIndex
          ) =>
        val contractStdAnnotation = Parser.ContractStdAnnotation.extractFields(annotations, None)
        Ast
          .Contract(
            contractStdAnnotation.map(_.enabled),
            None,
            isAbstract,
            typeId,
            Seq.empty,
            fields,
            funcs,
            events,
            constantVars,
            enums,
            contractInheritances.getOrElse(Seq.empty)
          )
          .atSourceIndex(fromIndex, endIndex)
    }
  def contract[Unknown: P]: P[Ast.Contract] = P(Start ~ rawContract ~ End)

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def interfaceInheritance[Unknown: P]: P[Ast.InterfaceInheritance] =
    P(Lexer.typeId).map(Ast.InterfaceInheritance)
  def interfaceFunc[Unknown: P]: P[Ast.FuncDef[StatefulContext]] = {
    funcTmp.map { f =>
      f.body match {
        case None =>
          Ast
            .FuncDef(
              f.annotations,
              f.id,
              f.isPublic,
              f.usePreapprovedAssets,
              f.useContractAssets,
              f.useCheckExternalCaller,
              f.useUpdateFields,
              f.args,
              f.rtypes,
              None
            )
            .atSourceIndex(f.sourceIndex)
        case _ =>
          throw Compiler.Error(
            s"Interface function ${f.id.name} should not have function body",
            f.sourceIndex
          )
      }
    }
  }
  def rawInterface[Unknown: P]: P[Ast.ContractInterface] =
    P(
      annotation.rep ~ Lexer.token(Keyword.Interface) ~/ Lexer.typeId ~
        (Lexer.token(Keyword.`extends`) ~/ interfaceInheritance.rep(1, ",")).? ~
        "{" ~ eventDef.rep ~ interfaceFunc.rep ~ "}"
        ~~ Index
    ).map { case (annotations, fromIndex, typeId, inheritances, events, funcs, endIndex) =>
      inheritances match {
        case Some((index, parents)) if parents.length > 1 =>
          throw Compiler.Error(
            s"Interface only supports single inheritance: ${parents.map(_.parentId.name).mkString(", ")}",
            Some(index)
          )
        case _ => ()
      }
      if (funcs.length < 1) {
        throw Compiler.Error(
          s"No function definition in Interface ${typeId.name}",
          typeId.sourceIndex
        )
      } else {
        val stdIdOpt = Parser.InterfaceStdAnnotation.extractFields(annotations, None)
        Ast
          .ContractInterface(
            stdIdOpt.map(stdId => Val.ByteVec(stdId.id)),
            typeId,
            funcs,
            events,
            inheritances.map { case (_, inher) => inher }.getOrElse(Seq.empty)
          )
          .atSourceIndex(fromIndex.index, endIndex)
      }
    }
  def interface[Unknown: P]: P[Ast.ContractInterface] = P(Start ~ rawInterface ~ End)

  def contractWithState[Unknown: P]: P[Ast.ContractWithState] = P(
    rawTxScript | rawContract | rawInterface
  )
  def contractWithStateOrStruct[Unknown: P]: P[(Ast.ContractWithState, Seq[Ast.Struct])] = P(
    rawStruct.rep ~ contractWithState ~ rawStruct.rep
  ).map { case (defs0, contract, defs1) =>
    (contract, defs0 ++ defs1)
  }

  def multiContract[Unknown: P]: P[Ast.MultiContract] =
    P(Start ~~ Index ~ contractWithStateOrStruct.rep(1) ~~ Index ~ End)
      .map { case (fromIndex, defs, endIndex) =>
        val contracts = defs.map(_._1)
        val structs   = defs.flatMap(_._2)
        Ast.MultiContract(contracts, structs, None).updateType().atSourceIndex(fromIndex, endIndex)
      }

  def state[Unknown: P]: P[Seq[Ast.Const[StatefulContext]]] =
    P("[" ~ constOrArray.rep(0, ",") ~ "]").map(_.flatten)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def constOrArray[Unknown: P]: P[Seq[Ast.Const[StatefulContext]]] = P(
    const.map(Seq(_)) |
      P("[" ~ constOrArray.rep(0, ",").map(_.flatten) ~ "]") |
      P("[" ~ constOrArray ~ ";" ~ nonNegativeNum("array size") ~ "]").map { case (consts, size) =>
        (0 until size).flatMap(_ => consts)
      }
  )

  def emitEvent[Unknown: P]: P[Ast.EmitEvent[StatefulContext]] =
    P(Index ~ "emit" ~ Lexer.typeId ~ "(" ~ expr.rep(0, ",") ~ ")" ~~ Index)
      .map { case (fromIndex, typeId, exprs, endIndex) =>
        Ast.EmitEvent(typeId, exprs).atSourceIndex(fromIndex, endIndex)
      }
}
