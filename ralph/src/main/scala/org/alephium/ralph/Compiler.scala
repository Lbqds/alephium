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

import scala.collection.{immutable, mutable}

import fastparse.Parsed

import org.alephium.protocol.vm._
import org.alephium.ralph.Ast.MultiContract
import org.alephium.ralph.error.CompilerError
import org.alephium.ralph.error.CompilerError.FastParseError
import org.alephium.util.AVector

//TODO add SourceIndex to warnings
final case class CompiledContract(
    code: StatefulContract,
    ast: Ast.Contract,
    warnings: AVector[String],
    debugCode: StatefulContract
)
final case class CompiledScript(
    code: StatefulScript,
    ast: Ast.TxScript,
    warnings: AVector[String],
    debugCode: StatefulScript
)

// scalastyle:off number.of.methods
// scalastyle:off file.size.limit
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object Compiler {

  def compileAssetScript(
      input: String,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, (StatelessScript, AVector[String])] =
    try {
      fastparse.parse(input, StatelessParser.assetScript(_)) match {
        case Parsed.Success(script, _) =>
          val state = State.buildFor(script)(compilerOptions)
          Right((script.genCodeFull(state), state.getWarnings))
        case failure: Parsed.Failure =>
          Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }

  def compileTxScript(
      input: String,
      index: Int = 0,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, StatefulScript] =
    compileTxScriptFull(input, index, compilerOptions).map(_.debugCode)

  def compileTxScriptFull(
      input: String,
      index: Int = 0,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, CompiledScript] =
    compileStateful(input, _.genStatefulScript(index)(compilerOptions))

  def compileContract(
      input: String,
      index: Int = 0,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, StatefulContract] =
    compileContractFull(input, index, compilerOptions).map(_.debugCode)

  def compileContractFull(
      input: String,
      index: Int = 0,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, CompiledContract] =
    compileStateful(input, _.genStatefulContract(index)(compilerOptions))

  private def compileStateful[T](input: String, genCode: MultiContract => T): Either[Error, T] = {
    try {
      compileMultiContract(input).map(genCode)
    } catch {
      case e: Error => Left(e)
    }
  }

  def compileProject(
      input: String,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, (AVector[CompiledContract], AVector[CompiledScript])] = {
    try {
      compileMultiContract(input).map { multiContract =>
        val statefulContracts =
          multiContract.genStatefulContracts()(compilerOptions).map(c => c._1)
        val statefulScripts = multiContract.genStatefulScripts()(compilerOptions)
        (statefulContracts, statefulScripts)
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  def compileMultiContract(input: String): Either[Error, MultiContract] = {
    try {
      fastparse.parse(input, StatefulParser.multiContract(_)) match {
        case Parsed.Success(multiContract, _) =>
          Right(multiContract.extendedContracts())
        case failure: Parsed.Failure => Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  def compileState(stateRaw: String): Either[Error, AVector[Val]] = {
    try {
      fastparse.parse(stateRaw, StatefulParser.state(_)) match {
        case Parsed.Success(state, _) => Right(AVector.from(state.map(_.v)))
        case failure: Parsed.Failure  => Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  trait FuncInfo[-Ctx <: StatelessContext] {
    def name: String
    def isPublic: Boolean
    def isVariadic: Boolean = false
    def usePreapprovedAssets: Boolean
    def useAssetsInContract: Boolean
    def useUpdateFields: Boolean
    def getReturnType(inputType: Seq[Type], selfContractType: Type): Seq[Type]
    def getReturnLength(inputType: Seq[Type], selfContractType: Type): Int = {
      val retTypes = getReturnType(inputType, selfContractType)
      Type.flattenTypeLength(retTypes)
    }
    def genCodeForArgs[C <: Ctx](args: Seq[Ast.Expr[C]], state: State[C]): Seq[Instr[C]] =
      args.flatMap(_.genCode(state))
    def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]]
    def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]]
  }

  type Error = CompilerError.FormattableError

  object Error {
    // scalastyle:off null
    def apply(message: String, sourceIndex: Option[SourceIndex]): Error =
      new CompilerError.Default(message, sourceIndex, null)
    // scalastyle:on null
    def apply(message: String, sourceIndex: Option[SourceIndex], cause: Throwable): Error =
      new CompilerError.Default(message, sourceIndex, cause)

    def parse(failure: Parsed.Failure): Error = FastParseError(failure)
  }

  def expectOneType(ident: Ast.Ident, tpe: Seq[Type]): Type = {
    if (tpe.length == 1) {
      tpe(0)
    } else {
      throw Error(s"Try to set types $tpe for variable $ident", ident.sourceIndex)
    }
  }

  type VarInfoBuilder = (Type, Boolean, Boolean, Byte, Boolean) => VarInfo
  sealed trait VarInfo {
    def tpe: Type
    def isMutable: Boolean
    def isUnused: Boolean
    def isGenerated: Boolean
    def isLocal: Boolean
  }
  object VarInfo {
    final case class Local(
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        index: Byte,
        isGenerated: Boolean
    ) extends VarInfo {
      def isLocal: Boolean = true
    }
    final case class Field(
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        index: Byte,
        isGenerated: Boolean
    ) extends VarInfo {
      def isLocal: Boolean = false
    }
    final case class Template(tpe: Type, index: Int, isGenerated: Boolean) extends VarInfo {
      def isMutable: Boolean = false
      def isUnused: Boolean  = false
      def isLocal: Boolean   = false
    }
    final case class MultipleVar[Ctx <: StatelessContext](
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean,
        ref: VariablesRef[Ctx]
    ) extends VarInfo {
      def tpe: Type        = ref.tpe
      def isLocal: Boolean = ref.isLocal
    }
    final case class Constant[Ctx <: StatelessContext](
        tpe: Type,
        instrs: Seq[Instr[Ctx]]
    ) extends VarInfo {
      def isMutable: Boolean   = false
      def isUnused: Boolean    = false
      def isGenerated: Boolean = false
      def isLocal: Boolean     = true
    }
  }
  trait ContractFunc[Ctx <: StatelessContext] extends FuncInfo[Ctx] {
    def argsType: Seq[Type]
    def returnType: Seq[Type]
    def isStatic: Boolean = false
  }
  final case class SimpleFunc[Ctx <: StatelessContext](
      id: Ast.FuncId,
      isPublic: Boolean,
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      useUpdateFields: Boolean,
      argsType: Seq[Type],
      returnType: Seq[Type],
      index: Byte
  ) extends ContractFunc[Ctx] {
    def name: String = id.name

    override def getReturnType(inputType: Seq[Type], selfContractType: Type): Seq[Type] = {
      if (inputType == argsType) {
        returnType
      } else {
        throw Error(
          s"Invalid args type ${quote(inputType)} for func $name, expected ${quote(argsType)}",
          None
        )
      }
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      Seq(CallLocal(index))
    }

    override def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]] = {
      if (isPublic) {
        Seq(CallExternal(index))
      } else {
        throw Error(s"Call external private function of ${typeId.name}", typeId.sourceIndex)
      }
    }
  }
  object SimpleFunc {
    def from[Ctx <: StatelessContext](funcs: Seq[Ast.FuncDef[Ctx]]): Seq[SimpleFunc[Ctx]] = {
      funcs.view.zipWithIndex.map { case (func, index) =>
        new SimpleFunc[Ctx](
          func.id,
          func.isPublic,
          func.usePreapprovedAssets,
          func.useAssetsInContract,
          func.useUpdateFields,
          func.args.map(_.tpe),
          func.rtypes,
          index.toByte
        )
      }.toSeq
    }
  }

  final case class EventInfo(typeId: Ast.TypeId, fieldTypes: Seq[Type]) {
    def checkFieldTypes(argTypes: Seq[Type], sourceIndex: Option[SourceIndex]): Unit = {
      if (fieldTypes != argTypes) {
        val eventAbi = s"""${typeId.name}${fieldTypes.mkString("(", ", ", ")")}"""
        throw Error(s"Invalid args type $argTypes for event $eventAbi", sourceIndex)
      }
    }
  }

  object State {
    private[ralph] val maxVarIndex: Int = 0xff

    // scalastyle:off cyclomatic.complexity method.length
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    // we have checked the index type(U256)
    def getConstantIndex[Ctx <: StatelessContext](index: Ast.Expr[Ctx]): Ast.Expr[Ctx] = {
      index match {
        case e: Ast.Const[Ctx @unchecked] => e
        case Ast.Binop(op: ArithOperator, Ast.Const(Val.U256(l)), Ast.Const(Val.U256(r))) =>
          op match {
            case ArithOperator.Add =>
              Ast.Const(
                Val.U256(
                  l.add(r)
                    .getOrElse(throw Error(s"Invalid array index ${index}", index.sourceIndex))
                )
              )
            case ArithOperator.Sub =>
              Ast.Const(
                Val.U256(
                  l.sub(r)
                    .getOrElse(throw Error(s"Invalid array index ${index}", index.sourceIndex))
                )
              )
            case ArithOperator.Mul =>
              Ast.Const(
                Val.U256(
                  l.mul(r)
                    .getOrElse(throw Error(s"Invalid array index ${index}", index.sourceIndex))
                )
              )
            case ArithOperator.Div =>
              Ast.Const(
                Val.U256(
                  l.div(r)
                    .getOrElse(throw Error(s"Invalid array index ${index}", index.sourceIndex))
                )
              )
            case ArithOperator.Mod =>
              Ast.Const(
                Val.U256(
                  l.mod(r)
                    .getOrElse(throw Error(s"Invalid array index ${index}", index.sourceIndex))
                )
              )
            case ArithOperator.ModAdd => Ast.Const(Val.U256(l.modAdd(r)))
            case ArithOperator.ModSub => Ast.Const(Val.U256(l.modSub(r)))
            case ArithOperator.ModMul => Ast.Const(Val.U256(l.modMul(r)))
            case ArithOperator.SHL    => Ast.Const(Val.U256(l.shl(r)))
            case ArithOperator.SHR    => Ast.Const(Val.U256(l.shr(r)))
            case ArithOperator.BitAnd => Ast.Const(Val.U256(l.bitAnd(r)))
            case ArithOperator.BitOr  => Ast.Const(Val.U256(l.bitOr(r)))
            case ArithOperator.Xor    => Ast.Const(Val.U256(l.xor(r)))
            case _ =>
              throw new RuntimeException("Dead branch") // https://github.com/scala/bug/issues/9677
          }
        case e @ Ast.Binop(op, left, right) =>
          val expr = Ast.Binop(op, getConstantIndex(left), getConstantIndex(right))
          if (expr == e) expr else getConstantIndex(expr)
        case _ => index
      }
    }
    // scalastyle:on cyclomatic.complexity

    def checkConstantIndex(index: Int, sourceIndex: Option[SourceIndex]): Unit = {
      if (index < 0 || index >= maxVarIndex) {
        throw Error(s"Invalid array index $index", sourceIndex)
      }
    }

    def getAndCheckConstantIndex[Ctx <: StatelessContext](index: Ast.Expr[Ctx]): Option[Int] = {
      getConstantIndex(index) match {
        case Ast.Const(Val.U256(v)) =>
          val idx =
            v.toInt.getOrElse(throw Compiler.Error(s"Invalid array index: $v", index.sourceIndex))
          checkConstantIndex(idx, index.sourceIndex)
          Some(idx)
        case _ => None
      }
    }

    def buildFor(script: Ast.AssetScript)(implicit
        compilerOptions: CompilerOptions
    ): State[StatelessContext] =
      StateForScript(
        script.ident,
        mutable.HashMap.empty,
        0,
        script.funcTable,
        immutable.Map(script.ident -> ContractInfo(ContractKind.TxScript, script.funcTable)),
        immutable.Map.empty // TODO: support struct in AssetScript
      )

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def buildFor(
        multiContract: MultiContract,
        contractIndex: Int
    )(implicit compilerOptions: CompilerOptions): State[StatefulContext] = {
      val contract = multiContract.get(contractIndex)
      StateForContract(
        contract.ident,
        contract.isInstanceOf[Ast.TxScript],
        mutable.HashMap.empty,
        0,
        contract.funcTable,
        contract.eventsInfo(),
        multiContract.contractsTable,
        multiContract.structs.map(s => (s.id, s)).toMap
      )
    }
  }

  sealed trait ContractKind extends Serializable with Product {
    def instantiable: Boolean
    def inheritable: Boolean

    override def toString(): String = productPrefix
  }
  object ContractKind {
    case object TxScript extends ContractKind {
      def instantiable: Boolean = false
      def inheritable: Boolean  = false
    }
    case object Interface extends ContractKind {
      def instantiable: Boolean = true
      def inheritable: Boolean  = true
    }
    final case class Contract(isAbstract: Boolean) extends ContractKind {
      def instantiable: Boolean = !isAbstract
      def inheritable: Boolean  = isAbstract

      override def toString(): String = {
        if (isAbstract) "Abstract Contract" else "Contract"
      }
    }
  }

  final case class ContractInfo[Ctx <: StatelessContext](
      kind: ContractKind,
      funcs: immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
  )

  trait CallGraph {
    def currentScope: Ast.FuncId

    // caller -> callees
    val internalCalls = mutable.HashMap.empty[Ast.FuncId, mutable.Set[Ast.FuncId]]
    def addInternalCall(callee: Ast.FuncId): Unit = {
      internalCalls.get(currentScope) match {
        case Some(callees) => callees += callee
        case None          => internalCalls.update(currentScope, mutable.Set(callee))
      }
    }
    // callee -> callers
    lazy val internalCallsReversed: mutable.Map[Ast.FuncId, mutable.ArrayBuffer[Ast.FuncId]] = {
      val reversed = mutable.Map.empty[Ast.FuncId, mutable.ArrayBuffer[Ast.FuncId]]
      internalCalls.foreach { case (caller, callees) =>
        callees.foreach { callee =>
          reversed.get(callee) match {
            case None          => reversed.update(callee, mutable.ArrayBuffer(caller))
            case Some(callers) => callers.addOne(caller)
          }
        }
      }
      reversed
    }

    val externalCalls = mutable.HashMap.empty[Ast.FuncId, mutable.Set[(Ast.TypeId, Ast.FuncId)]]
    def addExternalCall(contract: Ast.TypeId, func: Ast.FuncId): Unit = {
      val funcRef = contract -> func
      externalCalls.get(currentScope) match {
        case Some(callees) => callees += funcRef
        case None          => externalCalls.update(currentScope, mutable.Set(funcRef))
      }
    }
  }

  sealed trait AccessVariable
  final case class ReadVariable(name: String)  extends AccessVariable
  final case class WriteVariable(name: String) extends AccessVariable

  // scalastyle:off number.of.methods
  sealed trait State[Ctx <: StatelessContext]
      extends CallGraph
      with Warnings
      with Scope
      with PhaseLike {
    def typeId: Ast.TypeId
    def selfContractType: Type = Type.Contract(typeId)
    def varTable: mutable.HashMap[String, VarInfo]
    var allowDebug: Boolean = false

    val hasInterfaceFuncCallSet: mutable.Set[Ast.FuncId] = mutable.Set.empty
    def addInterfaceFuncCall(funcId: Ast.FuncId): Unit = {
      hasInterfaceFuncCallSet.addOne(funcId)
    }

    def funcIdents: immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
    def contractTable: immutable.Map[Ast.TypeId, ContractInfo[Ctx]]
    def structTable: immutable.Map[Ast.TypeId, Ast.Struct]
    val accessedVars: mutable.Set[AccessVariable] = mutable.Set.empty[AccessVariable]
    def eventsInfo: Seq[EventInfo]

    def getStruct(typeId: Ast.TypeId): Ast.Struct = {
      structTable.get(typeId) match {
        case Some(struct) => struct
        case None =>
          throw Compiler.Error(s"Struct ${quote(typeId.name)} does not exist", typeId.sourceIndex)
      }
    }

    def getOrCreateStructRef(expr: Ast.Expr[Ctx]): (StructRef[Ctx], Seq[Instr[Ctx]]) = {
      val (ref, codes) = getOrCreateVariablesRef(expr)
      ref match {
        case ref: StructRef[Ctx @unchecked] => (ref, codes)
        case _ =>
          throw Compiler.Error(s"Expected struct type, got ${ref.tpe}", expr.sourceIndex)
      }
    }

    def getOrCreateArrayRef(expr: Ast.Expr[Ctx]): (ArrayRef[Ctx], Seq[Instr[Ctx]]) = {
      val (ref, codes) = getOrCreateVariablesRef(expr)
      ref match {
        case ref: ArrayRef[Ctx @unchecked] => (ref, codes)
        case _ =>
          throw Compiler.Error(s"Expected array type, got ${ref.tpe}", expr.sourceIndex)
      }
    }

    @scala.annotation.tailrec
    def getOrCreateVariablesRef(expr: Ast.Expr[Ctx]): (VariablesRef[Ctx], Seq[Instr[Ctx]]) = {
      expr match {
        case Ast.StructFieldSelector(struct, selector) =>
          val (ref, codes) = getOrCreateStructRef(struct)
          (ref.subRef(this, selector), codes)
        case Ast.ArrayElement(array, index) =>
          val (ref, codes) = getOrCreateArrayRef(array)
          (ref.subRef(this, index), codes)
        case Ast.Variable(ident)  => (getVariablesRef(ident), Seq.empty)
        case Ast.ParenExpr(inner) => getOrCreateVariablesRef(inner)
        case _ =>
          val ref = VariablesRef.init(
            this,
            expr.getType(this)(0),
            freshName(),
            isMutable = false,
            isUnused = false,
            isLocal = true,
            isGenerated = true,
            isTemplate = false,
            VarInfo.Local,
            () => currentScopeState.varIndex.toByte,
            () => currentScopeState.varIndex += 1
          )
          val codes = expr.genCode(this) ++ ref.genStoreCode(this).reverse.flatten
          (ref, codes)
      }
    }

    def getArrayIndexVar(): Ast.Ident = {
      getArrayIndexVar(
        addLocalVariable(_, Type.U256, isMutable = true, isUnused = false, isGenerated = true)
      )
    }

    def addVariablesRef(
        ident: Ast.Ident,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean,
        ref: VariablesRef[Ctx]
    ): Unit = {
      val sname = checkNewVariable(ident)
      varTable(sname) = VarInfo.MultipleVar(isMutable, isUnused, isGenerated, ref)
      trackGenCodePhaseNewVars(sname)
    }

    def getVariablesRef(ident: Ast.Ident): VariablesRef[Ctx] = {
      getVariable(ident) match {
        case v: VarInfo.MultipleVar[Ctx @unchecked] => v.ref
        case _ => throw Error(s"Struct ${ident.name} does not exist", ident.sourceIndex)
      }
    }

    protected def scopedName(name: String): String = {
      if (currentScope == Ast.FuncId.empty) name else scopedName(currentScope, name)
    }

    @inline private def scopedNamePrefix(scopeId: Ast.FuncId): String = s"${scopeId.name}."
    protected def scopedName(scopeId: Ast.FuncId, name: String): String = {
      s"${scopedNamePrefix(scopeId)}$name"
    }

    def addTemplateVariable(ident: Ast.Ident, tpe: Type, index: Int): Int = {
      val sname        = checkNewVariable(ident)
      var currentIndex = index
      tpe match {
        case array: Type.FixedSizeArray =>
          VariablesRef.init(
            this,
            array,
            ident.name,
            isMutable = false,
            isUnused = false,
            isLocal = false,
            isGenerated = false,
            isTemplate = true,
            (tpe, _, _, index, isGenerated) => VarInfo.Template(tpe, index.toInt, isGenerated),
            () => currentIndex.toByte,
            () => currentIndex += 1
          )
          currentIndex
        case c: Type.Contract =>
          val varType = Type.Contract(c.id)
          varTable(sname) = VarInfo.Template(varType, index, isGenerated = false)
          currentIndex + 1
        case _ =>
          varTable(sname) = VarInfo.Template(tpe, index, isGenerated = false)
          currentIndex + 1
      }
    }
    def addFieldVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean
    ): Unit = {
      addVariable(
        ident,
        tpe,
        isMutable,
        isUnused,
        isLocal = false,
        isGenerated,
        isTemplate = false,
        VarInfo.Field,
        () => if (isMutable) this.mutFieldsIndex.toByte else this.immFieldsIndex.toByte,
        () => if (isMutable) this.mutFieldsIndex += 1 else this.immFieldsIndex += 1
      )
    }
    def addLocalVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean
    ): Unit = {
      addVariable(
        ident,
        tpe,
        isMutable,
        isUnused,
        isLocal = true,
        isGenerated,
        isTemplate = false,
        VarInfo.Local,
        () => currentScopeState.varIndex.toByte,
        () => currentScopeState.varIndex += 1
      )
    }
    // scalastyle:off parameter.number
    // scalastyle:off method.length
    def addVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        isLocal: Boolean,
        isGenerated: Boolean,
        isTemplate: Boolean,
        varInfoBuilder: Compiler.VarInfoBuilder,
        getIndex: () => Byte,
        increaseIndex: () => Unit
    ): Unit = {
      val sname = checkNewVariable(ident)
      tpe match {
        case _: Type.FixedSizeArray | _: Type.Struct =>
          VariablesRef.init(
            this,
            tpe,
            ident.name,
            isMutable,
            isUnused,
            isLocal,
            isGenerated,
            isTemplate,
            varInfoBuilder,
            getIndex,
            increaseIndex
          )
          ()
        case c: Type.Contract =>
          varTable(sname) = varInfoBuilder(
            Type.Contract(c.id),
            isMutable,
            isUnused,
            getIndex(),
            isGenerated
          )
          trackGenCodePhaseNewVars(sname)
          increaseIndex()
        case _ =>
          varTable(sname) = varInfoBuilder(
            tpe,
            isMutable,
            isUnused,
            getIndex(),
            isGenerated
          )
          trackGenCodePhaseNewVars(sname)
          increaseIndex()
      }
    }
    // scalastyle:on parameter.number
    // scalastyle:off method.length
    def addConstantVariable(ident: Ast.Ident, tpe: Type, instrs: Seq[Instr[Ctx]]): Unit = {
      val sname = checkNewVariable(ident)
      varTable(sname) = VarInfo.Constant(tpe, instrs)
    }

    private def checkNewVariable(ident: Ast.Ident): String = {
      val name  = ident.name
      val sname = scopedName(name)
      if (varTable.contains(name)) {
        throw Error(
          s"Global variable has the same name as local variable: $name",
          ident.sourceIndex
        )
      } else if (varTable.contains(sname)) {
        throw Error(s"Local variables have the same name: $name", ident.sourceIndex)
      } else if (currentScopeState.varIndex >= State.maxVarIndex) {
        throw Error(s"Number of variables more than ${State.maxVarIndex}", ident.sourceIndex)
      }
      sname
    }

    def getVariable(ident: Ast.Ident, isWrite: Boolean = false): VarInfo = {
      val name  = ident.name
      val sname = scopedName(ident.name)
      val (varName, varInfo) = varTable.get(sname) match {
        case Some(varInfo) => (sname, varInfo)
        case None =>
          varTable.get(name) match {
            case Some(varInfo) => (name, varInfo)
            case None          => throw Error(s"Variable $sname does not exist", ident.sourceIndex)
          }
      }
      if (isWrite) {
        currentScopeAccessedVars.add(WriteVariable(varName))
      } else {
        currentScopeAccessedVars.add(ReadVariable(varName))
      }
      varInfo
    }

    def addAccessedVars(vars: Set[AccessVariable]): Unit = accessedVars.addAll(vars)

    def checkUnusedLocalVars(funcId: Ast.FuncId): Unit = {
      val prefix = scopedNamePrefix(funcId)
      val unusedVars = varTable.filter { case (name, varInfo) =>
        name.startsWith(prefix) &&
        !varInfo.isGenerated &&
        !varInfo.isUnused &&
        !accessedVars.contains(ReadVariable(name))
      }
      if (unusedVars.nonEmpty) {
        warnUnusedVariables(typeId, unusedVars)
      }
      accessedVars.filterInPlace {
        case ReadVariable(name) => !name.startsWith(prefix)
        case _                  => true
      }
    }

    def checkUnassignedLocalMutableVars(funcId: Ast.FuncId): Unit = {
      val prefix = scopedNamePrefix(funcId)
      val unassignedMutableVars = varTable.view
        .filter { case (name, varInfo) =>
          varInfo.isMutable &&
          name.startsWith(prefix) &&
          !varInfo.isGenerated &&
          !varInfo.isUnused &&
          !accessedVars.contains(WriteVariable(name))
        }
        .keys
        .toSeq
      if (unassignedMutableVars.nonEmpty) {
        throw Compiler.Error(
          s"There are unassigned mutable local vars in function ${typeId.name}.${funcId.name}: ${unassignedMutableVars
              .mkString(",")}",
          funcId.sourceIndex
        )
      }
      accessedVars.filterInPlace {
        case WriteVariable(name) => !name.startsWith(prefix)
        case _                   => true
      }
    }

    def checkUnusedFields(): Unit = {
      val unusedVars = varTable.filter { case (name, varInfo) =>
        !varInfo.isGenerated && !varInfo.isUnused && !accessedVars.contains(ReadVariable(name))
      }
      val unusedConstants = mutable.ArrayBuffer.empty[String]
      val unusedFields    = mutable.ArrayBuffer.empty[String]
      unusedVars.foreach {
        case (name, _: VarInfo.Constant[_])      => unusedConstants.addOne(name)
        case (name, varInfo) if !varInfo.isLocal => unusedFields.addOne(name)
        case _                                   => ()
      }
      if (unusedConstants.nonEmpty) {
        warnUnusedConstants(typeId, unusedConstants)
      }
      if (unusedFields.nonEmpty) {
        warnUnusedFields(typeId, unusedFields)
      }
    }

    def checkUnassignedMutableFields(): Unit = {
      val unassignedMutableFields = varTable.view
        .filter { case (name, varInfo) =>
          !varInfo.isLocal &&
          varInfo.isMutable &&
          !varInfo.isGenerated &&
          !varInfo.isUnused &&
          !accessedVars.contains(WriteVariable(name))
        }
        .keys
        .toSeq
      if (unassignedMutableFields.nonEmpty) {
        throw Compiler.Error(
          s"There are unassigned mutable fields in contract ${typeId.name}: ${unassignedMutableFields
              .mkString(",")}",
          typeId.sourceIndex
        )
      }
    }

    def getLocalVars(func: Ast.FuncId): Seq[VarInfo] = {
      varTable.view
        .filterKeys(_.startsWith(func.name))
        .values
        .filter(_.isInstanceOf[VarInfo.Local])
        .map(_.asInstanceOf[VarInfo.Local])
        .toSeq
        .sortBy(_.index)
    }

    def checkArrayIndexType(index: Ast.Expr[Ctx]): Unit = {
      index.getType(this) match {
        case Seq(Type.U256) =>
        case tpe =>
          throw Compiler.Error(
            s"Invalid array index type ${quote(tpe)}, expected ${quote("U256")}",
            index.sourceIndex
          )
      }
    }

    def getArrayElementType(array: Ast.Expr[Ctx], index: Ast.Expr[Ctx]): Type = {
      checkArrayIndexType(index)
      array.getType(this) match {
        case Seq(tpe: Type.FixedSizeArray) => tpe.baseType
        case tpe =>
          throw Compiler.Error(
            s"Expected array type, got ${if (tpe.length == 1) quote(tpe(0)) else quote(tpe)}",
            index.sourceIndex
          )
      }
    }

    def getStructFieldType(expr: Ast.Expr[Ctx], selector: Ast.Ident): Type = {
      expr.getType(this) match {
        case Seq(tpe: Type.Struct) =>
          val struct = getStruct(tpe.id)
          struct.getField(selector).tpe
        case tpe => throw Compiler.Error(s"Expected struct type, got $tpe", expr.sourceIndex)
      }
    }

    def genLoadCode(ident: Ast.Ident): Seq[Instr[Ctx]]

    def genLoadCode(ref: VariablesRef[Ctx], tpe: Type, offset: VarOffset[Ctx]): Seq[Instr[Ctx]]

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genLoadTemplateRef(ident: Ast.Ident, tpe: Type, offset: VarOffset[Ctx]): Seq[Instr[Ctx]] = {
      val index = offset.asInstanceOf[ConstantVarOffset[Ctx]].value
      Seq(TemplateVariable(ident.name, tpe.toVal, index))
    }

    def genStoreCode(ident: Ast.Ident): Seq[Seq[Instr[Ctx]]]

    def genStoreCode(offset: VarOffset[Ctx], isLocal: Boolean): Seq[Instr[Ctx]]

    def getType(ident: Ast.Ident): Type = getVariable(ident).tpe

    def getFunc(call: Ast.FuncId): FuncInfo[Ctx] = {
      if (call.isBuiltIn) {
        getBuiltInFunc(call)
      } else {
        getNewFunc(call)
      }
    }

    def getContract(objId: Ast.Ident): Ast.TypeId = {
      getVariable(objId).tpe match {
        case c: Type.Contract => c.id
        case _ => throw Error(s"Invalid contract object id ${objId.name}", objId.sourceIndex)
      }
    }

    def getFunc(typeId: Ast.TypeId, callId: Ast.FuncId): ContractFunc[Ctx] = {
      getContractInfo(typeId).funcs
        .getOrElse(
          callId,
          throw Error(s"Function ${typeId}.${callId.name} does not exist", callId.sourceIndex)
        )
    }

    def getContractInfo(typeId: Ast.TypeId): ContractInfo[Ctx] = {
      contractTable.getOrElse(
        typeId,
        throw Error(s"Contract ${typeId.name} does not exist", typeId.sourceIndex)
      )
    }

    def getEvent(typeId: Ast.TypeId): EventInfo = {
      eventsInfo
        .find(_.typeId.name == typeId.name)
        .getOrElse(
          throw Error(s"Event ${typeId.name} does not exist", typeId.sourceIndex)
        )
    }

    def getBuiltInFunc(call: Ast.FuncId): BuiltIn.BuiltIn[Ctx]

    private def getNewFunc(call: Ast.FuncId): FuncInfo[Ctx] = {
      funcIdents.getOrElse(
        call,
        throw Error(s"Function ${call.name} does not exist", call.sourceIndex)
      )
    }

    def checkArguments(args: Seq[Ast.Argument]): Unit = {
      args.foreach(_.tpe match {
        case c: Type.Contract => checkContractType(c.id)
        case _                =>
      })
    }

    def checkContractType(typeId: Ast.TypeId): Unit = {
      if (!contractTable.contains(typeId)) {
        throw Error(s"Contract ${typeId.name} does not exist", typeId.sourceIndex)
      }
    }

    def checkAssign(ident: Ast.Ident, tpe: Seq[Type]): Unit = {
      checkAssign(ident, expectOneType(ident, tpe))
    }

    def checkAssign(ident: Ast.Ident, tpe: Type): Unit = {
      val varInfo = getVariable(ident)
      if (varInfo.tpe != tpe) {
        throw Error(s"Assign $tpe value to $ident: ${varInfo.tpe.toVal}", ident.sourceIndex)
      }
      if (!varInfo.isMutable) {
        throw Error(s"Assign value to immutable variable $ident", ident.sourceIndex)
      }
    }

    def checkReturn(returnType: Seq[Type], sourceIndex: Option[SourceIndex]): Unit = {
      val rtype = funcIdents(currentScope).returnType
      if (returnType != rtype) {
        throw Error(
          s"Invalid return types ${quote(returnType)} for func ${currentScope.name}, expected ${quote(rtype)}",
          sourceIndex
        )
      }
    }
  }
  // scalastyle:on number.of.methods

  type Contract[Ctx <: StatelessContext] = immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
  final case class StateForScript(
      typeId: Ast.TypeId,
      varTable: mutable.HashMap[String, VarInfo],
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, ContractFunc[StatelessContext]],
      contractTable: immutable.Map[Ast.TypeId, ContractInfo[StatelessContext]],
      structTable: immutable.Map[Ast.TypeId, Ast.Struct]
  )(implicit val compilerOptions: CompilerOptions)
      extends State[StatelessContext] {
    override def eventsInfo: Seq[EventInfo] = Seq.empty

    def getBuiltInFunc(call: Ast.FuncId): BuiltIn.BuiltIn[StatelessContext] = {
      BuiltIn.statelessFuncs
        .getOrElse(
          call.name,
          throw Error(s"Built-in function ${call.name} does not exist", call.sourceIndex)
        )
    }

    private def genVarIndexCode(
        offset: VarOffset[StatelessContext],
        isLocal: Boolean,
        constantIndex: Byte => Instr[StatelessContext],
        varIndex: Instr[StatelessContext]
    ): Seq[Instr[StatelessContext]] = {
      if (!isLocal) {
        throw Error(s"Script should not have fields", typeId.sourceIndex)
      }

      offset match {
        case ConstantVarOffset(value) =>
          State.checkConstantIndex(value, typeId.sourceIndex)
          Seq(constantIndex(value.toByte))
        case VariableVarOffset(instrs) =>
          instrs :+ varIndex
      }
    }

    def genLoadCode(
        ref: VariablesRef[StatelessContext],
        tpe: Type,
        offset: VarOffset[StatelessContext]
    ): Seq[Instr[StatelessContext]] = {
      if (ref.isTemplate) {
        genLoadTemplateRef(ref.ident, tpe, offset)
      } else {
        genVarIndexCode(offset, ref.isLocal, LoadLocal.apply, LoadLocalByIndex)
      }
    }

    def genStoreCode(
        offset: VarOffset[StatelessContext],
        isLocal: Boolean
    ): Seq[Instr[StatelessContext]] =
      genVarIndexCode(offset, isLocal, StoreLocal.apply, StoreLocalByIndex)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genLoadCode(ident: Ast.Ident): Seq[Instr[StatelessContext]] = {
      getVariable(ident) match {
        case _: VarInfo.Field    => throw Error("Script should not have fields", typeId.sourceIndex)
        case v: VarInfo.Local    => Seq(LoadLocal(v.index))
        case v: VarInfo.Template => Seq(TemplateVariable(ident.name, v.tpe.toVal, v.index))
        case v: VarInfo.MultipleVar[StatelessContext @unchecked] => v.ref.genLoadCode(this)
        case v: VarInfo.Constant[StatelessContext @unchecked]    => v.instrs
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genStoreCode(ident: Ast.Ident): Seq[Seq[Instr[StatelessContext]]] = {
      getVariable(ident) match {
        case _: VarInfo.Field => throw Error("Script should not have fields", typeId.sourceIndex)
        case v: VarInfo.Local => Seq(Seq(StoreLocal(v.index)))
        case _: VarInfo.Template =>
          throw Error(s"Unexpected template variable: ${ident.name}", ident.sourceIndex)
        case v: VarInfo.MultipleVar[StatelessContext @unchecked] => v.ref.genStoreCode(this)
        case _: VarInfo.Constant[StatelessContext @unchecked] =>
          throw Error(s"Unexpected constant variable: ${ident.name}", ident.sourceIndex)
      }
    }
  }

  final case class StateForContract(
      typeId: Ast.TypeId,
      isTxScript: Boolean,
      varTable: mutable.HashMap[String, VarInfo],
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, ContractFunc[StatefulContext]],
      eventsInfo: Seq[EventInfo],
      contractTable: immutable.Map[Ast.TypeId, ContractInfo[StatefulContext]],
      structTable: immutable.Map[Ast.TypeId, Ast.Struct]
  )(implicit val compilerOptions: CompilerOptions)
      extends State[StatefulContext] {
    def getBuiltInFunc(call: Ast.FuncId): BuiltIn.BuiltIn[StatefulContext] = {
      BuiltIn.statefulFuncs
        .getOrElse(
          call.name,
          throw Error(s"Built-in function ${call.name} does not exist", call.sourceIndex)
        )
    }

    private def genVarIndexCode(
        offset: VarOffset[StatefulContext],
        isLocal: Boolean,
        localConstantIndex: Byte => Instr[StatefulContext],
        fieldConstantIndex: Byte => Instr[StatefulContext],
        localVarIndex: Instr[StatefulContext],
        fieldVarIndex: Instr[StatefulContext]
    ): Seq[Instr[StatefulContext]] = {
      offset match {
        case ConstantVarOffset(value) =>
          State.checkConstantIndex(value, typeId.sourceIndex)
          val index = value.toByte
          if (isLocal) Seq(localConstantIndex(index)) else Seq(fieldConstantIndex(index))
        case VariableVarOffset(instrs) =>
          val instr = if (isLocal) localVarIndex else fieldVarIndex
          instrs :+ instr
      }
    }

    def genLoadCode(
        ref: VariablesRef[StatefulContext],
        tpe: Type,
        offset: VarOffset[StatefulContext]
    ): Seq[Instr[StatefulContext]] = {
      if (ref.isTemplate) {
        genLoadTemplateRef(ref.ident, tpe, offset)
      } else {
        genVarIndexCode(
          offset,
          ref.isLocal,
          LoadLocal.apply,
          if (ref.isMutable) LoadMutField.apply else LoadImmField.apply,
          LoadLocalByIndex,
          if (ref.isMutable) LoadMutFieldByIndex else LoadImmFieldByIndex
        )
      }
    }

    def genStoreCode(
        offset: VarOffset[StatefulContext],
        isLocal: Boolean
    ): Seq[Instr[StatefulContext]] = {
      genVarIndexCode(
        offset,
        isLocal,
        StoreLocal.apply,
        StoreMutField.apply,
        StoreLocalByIndex,
        StoreMutFieldByIndex
      )
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genLoadCode(ident: Ast.Ident): Seq[Instr[StatefulContext]] = {
      getVariable(ident) match {
        case v: VarInfo.Field =>
          if (v.isMutable) Seq(LoadMutField(v.index)) else Seq(LoadImmField(v.index))
        case v: VarInfo.Local    => Seq(LoadLocal(v.index))
        case v: VarInfo.Template => Seq(TemplateVariable(ident.name, v.tpe.toVal, v.index))
        case v: VarInfo.MultipleVar[StatefulContext @unchecked] => v.ref.genLoadCode(this)
        case v: VarInfo.Constant[StatefulContext @unchecked]    => v.instrs
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genStoreCode(ident: Ast.Ident): Seq[Seq[Instr[StatefulContext]]] = {
      getVariable(ident) match {
        case v: VarInfo.Field => Seq(Seq(StoreMutField(v.index)))
        case v: VarInfo.Local => Seq(Seq(StoreLocal(v.index)))
        case _: VarInfo.Template =>
          throw Error(s"Unexpected template variable: ${ident.name}", ident.sourceIndex)
        case v: VarInfo.MultipleVar[StatefulContext @unchecked] => v.ref.genStoreCode(this)
        case _: VarInfo.Constant[StatefulContext @unchecked] =>
          throw Error(s"Unexpected constant variable: ${ident.name}", ident.sourceIndex)
      }
    }
  }

  def genLogs(logFieldLength: Int, sourceIndex: Option[SourceIndex]): LogInstr = {
    if (logFieldLength >= 0 && logFieldLength < Instr.allLogInstrs.length) {
      Instr.allLogInstrs(logFieldLength)
    } else {
      throw Compiler.Error(s"Contract events allow up to 8 fields", sourceIndex)
    }
  }
}
