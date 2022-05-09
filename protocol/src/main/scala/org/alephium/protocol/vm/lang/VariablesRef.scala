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

package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.StatelessContext

sealed trait VariablesRef extends Serializable with Product {
  def toSimpleVariable: Option[Ast.Ident] = this match {
    case v: SimpleVariable => Some(v.ident)
    case _                 => None
  }

  def allVariables[Ctx <: StatelessContext](state: Compiler.State[Ctx]): Seq[Ast.Ident]
}

final case class SimpleVariable(ident: Ast.Ident) extends VariablesRef {
  def allVariables[Ctx <: StatelessContext](state: Compiler.State[Ctx]): Seq[Ast.Ident] = Seq(ident)
}

sealed trait MultipleVariables extends VariablesRef {
  def tpe: Type
}

final case class StructRef(tpe: Type.Struct, fields: Map[Ast.Ident, VariablesRef])
    extends MultipleVariables {
  def allVariables[Ctx <: StatelessContext](state: Compiler.State[Ctx]): Seq[Ast.Ident] = {
    val structInfo = state.getStruct(tpe)
    structInfo.fields.flatMap { field =>
      fields
        .getOrElse(
          field.ident,
          throw Compiler.Error(
            s"Field ${field.ident.name} does not exist in struct ${structInfo.id.name}"
          )
        )
        .allVariables(state)
    }
  }

  def getArrayBySelector(selector: Ast.Ident): Option[ArrayRef] = {
    fields.get(selector) match {
      case Some(ref: ArrayRef) => Some(ref)
      case _                   => None
    }
  }

  def getStructBySelector(selector: Ast.Ident): Option[StructRef] = {
    fields.get(selector) match {
      case Some(ref: StructRef) => Some(ref)
      case _                    => None
    }
  }
}

final case class ArrayRef(tpe: Type.FixedSizeArray, fields: Seq[VariablesRef])
    extends MultipleVariables {
  def allVariables[Ctx <: StatelessContext](state: Compiler.State[Ctx]): Seq[Ast.Ident] = {
    (0 until tpe.size).flatMap { idx =>
      VariablesRef.checkArrayIndex(idx, tpe.size)
      fields(idx).allVariables(state)
    }
  }

  def getSubArray(index: Int): Option[ArrayRef] = {
    VariablesRef.checkArrayIndex(index, tpe.size)
    fields(index) match {
      case ref: ArrayRef => Some(ref)
      case _             => None
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def getSubArray(indexes: Seq[Int]): Option[ArrayRef] = {
    if (indexes.isEmpty) {
      Some(this)
    } else {
      getSubArray(indexes(0)).flatMap(_.getSubArray(indexes.drop(1)))
    }
  }

  def getArrayElement(index: Int): VariablesRef = {
    VariablesRef.checkArrayIndex(index, tpe.size)
    fields(index)
  }

  @scala.annotation.tailrec
  def getArrayElement(indexes: Seq[Int]): Option[VariablesRef] = {
    if (indexes.isEmpty) {
      Some(this)
    } else {
      getArrayElement(indexes(0)) match {
        case subArray: ArrayRef => subArray.getArrayElement(indexes.drop(1))
        case _                  => None
      }
    }
  }

  def getStructRef(index: Int): Option[StructRef] = {
    VariablesRef.checkArrayIndex(index, tpe.size)
    fields(index) match {
      case ref: StructRef => Some(ref)
      case _              => None
    }
  }
}

object VariablesRef {
  @inline def checkArrayIndex(index: Int, arraySize: Int): Unit = {
    if (index < 0 || index >= arraySize) {
      throw Compiler.Error(s"Invalid index: $index, array size: $arraySize")
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def typeLength[Ctx <: StatelessContext](state: Compiler.State[Ctx], tpe: Type): Int = {
    tpe match {
      case tpe: Type.FixedSizeArray =>
        tpe.size * typeLength(state, tpe.baseType)
      case tpe: Type.Struct =>
        val struct = state.getStruct(tpe)
        struct.fields.map(field => typeLength(state, field.tpe)).sum
      case _ => 1
    }
  }

  def flattenTypeLength[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      types: Seq[Type]
  ): Int =
    types.map(typeLength(state, _)).sum

  def addArgs[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      args: Seq[Ast.Argument]
  ): Unit = {
    args.foreach { case Ast.Argument(ident, tpe, isMutable) =>
      tpe match {
        case tpe: Type.FixedSizeArray =>
          state.addVariable(ident, tpe, isMutable)
          fromArray(state, tpe, ident.name, isMutable)
        case tpe: Type.Struct =>
          state.addVariable(ident, tpe, isMutable)
          fromStruct(state, tpe, ident.name, isMutable)
        case _ => state.addVariable(ident, tpe, isMutable)
      }
    }
  }

  @inline def arrayElementName(baseName: String, idx: Int): String = s"_$baseName-$idx"
  @inline def structFieldName(baseName: String, field: Ast.Ident): String =
    s"_$baseName-${field.name}"

  private def arrayVarsRef[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type.FixedSizeArray,
      baseName: String,
      isMutable: Boolean
  ): Seq[VariablesRef] = {
    tpe.baseType match {
      case baseType: Type.FixedSizeArray =>
        (0 until tpe.size).map { idx =>
          val newBaseName = arrayElementName(baseName, idx)
          fromArray(state, baseType, newBaseName, isMutable)
        }
      case baseType: Type.Struct =>
        (0 until tpe.size).map { idx =>
          val newBaseName = arrayElementName(baseName, idx)
          fromStruct(state, baseType, newBaseName, isMutable)
        }
      case baseType =>
        (0 until tpe.size).map { idx =>
          val ident = Ast.Ident(arrayElementName(baseName, idx))
          state.addVariable(ident, baseType, isMutable)
          SimpleVariable(ident)
        }
    }
  }

  def fromArray[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type.FixedSizeArray,
      baseName: String,
      isMutable: Boolean
  ): ArrayRef = {
    val ref = ArrayRef(tpe, arrayVarsRef(state, tpe, baseName, isMutable))
    state.addVariablesRef(Ast.Ident(baseName), ref)
    ref
  }

  private def structVarsRef[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      structInfo: Ast.Struct,
      baseName: String,
      isMutable: Boolean
  ): Map[Ast.Ident, VariablesRef] = {
    structInfo.fields.map { case Ast.StructField(ident, fieldType) =>
      fieldType match {
        case tpe: Type.FixedSizeArray =>
          val newBaseName = structFieldName(baseName, ident)
          val ref         = fromArray(state, tpe, newBaseName, isMutable)
          ident -> ref
        case tpe: Type.Struct =>
          val newBaseName = structFieldName(baseName, ident)
          val ref         = fromStruct(state, tpe, newBaseName, isMutable)
          ident -> ref
        case tpe =>
          val varIdent = Ast.Ident(structFieldName(baseName, ident))
          state.addVariable(varIdent, tpe, isMutable)
          ident -> SimpleVariable(varIdent)
      }
    }.toMap
  }

  def fromStruct[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type.Struct,
      baseName: String,
      isMutable: Boolean
  ): StructRef = {
    val structInfo = state.getStruct(tpe)
    val ref        = StructRef(tpe, structVarsRef(state, structInfo, baseName, isMutable))
    state.addVariablesRef(Ast.Ident(baseName), ref)
    ref
  }
}
