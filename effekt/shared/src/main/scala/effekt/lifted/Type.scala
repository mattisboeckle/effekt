package effekt
package lifted
package typed

import effekt.core.Id


/**
 * Design Decisions:
 * - we don't track effects anymore (no stack-shape, no capture sets)
 * - evidence simply has kind EV.
 * - boxing is not supported (yet), but we include it as an unsafe feature.
 */
sealed trait Type

enum ValueType extends Type {
  case Var(name: Id)
  case Data(name: Id, targs: List[ValueType])
  case Boxed(tpe: BlockType) // WARNING not supported
}

enum BlockType extends Type {

  //   [A, B, C] (X, Y, Z)   {  ev_f  :   S }    =>    T
  //    ^^^^^^^   ^^^^^^^     ^^^^^^^^^^^^^          ^^^
  //    tparams   vparams   eparams zip bparams   result
  //
  // we will render the type more often as:
  //
  //   [A, B, C, ev_f] (X, Y, Z) {S} => T
  //
  // ignoring the fact that |ev_f| = |S|
  case Function(tparams: List[Id], eparams: List[Id], vparams: List[ValueType], bparams: List[BlockType], result: ValueType)
  case Interface(name: effekt.core.Id, targs: List[ValueType])
}

object Type {
//
//  // The subtyping lattice
//  val TTop = ValueType.Data(builtins.TopSymbol, Nil)
//  val TBottom = ValueType.Data(builtins.BottomSymbol, Nil)
//
//  val TUnit   = ValueType.Data(builtins.UnitSymbol, Nil)
//  val TInt = ValueType.Data(builtins.IntSymbol, Nil)
//  val TBoolean = ValueType.Data(builtins.BooleanSymbol, Nil)
//  val TString = ValueType.Data(builtins.StringSymbol, Nil)
//  val TDouble = ValueType.Data(builtins.DoubleSymbol, Nil)
//
//  val TRegion = BlockType.Interface(builtins.RegionSymbol, Nil)
//
//  /**
//   * Function types are the only type constructor that we have subtyping on.
//   */
//  def merge(tpe1: ValueType, tpe2: ValueType, covariant: Boolean): ValueType = (tpe1, tpe2) match {
//    case (ValueType.Boxed(btpe1, capt1), ValueType.Boxed(btpe2, capt2)) =>
//      ValueType.Boxed(merge(btpe1, btpe2, covariant), merge(capt1, capt2, covariant))
//    case (tpe1, tpe2) if covariant =>
//      if (isSubtype(tpe1, tpe2)) tpe2 else tpe1
//    case (tpe1, tpe2) if !covariant =>
//      if (isSubtype(tpe1, tpe2)) tpe1 else tpe2
//    case _ => tpe1
//  }
//  private def isSubtype(tpe1: ValueType, tpe2: ValueType): Boolean = (tpe1, tpe2) match {
//    case (tpe1, TTop) => true
//    case (TBottom, tpe1) => true
//    case _ => false // conservative :)
//  }
//
//  def merge(tpe1: BlockType, tpe2: BlockType, covariant: Boolean): BlockType = (tpe1, tpe2) match {
//    case (BlockType.Function(tparams1, cparams1, vparams1, bparams1, result1), tpe2: BlockType.Function) =>
//      val BlockType.Function(_, _, vparams2, bparams2, result2) = instantiate(tpe2, tparams1.map(ValueType.Var.apply), cparams1.map(c => Set(c)))
//      val vparams = (vparams1 zip vparams2).map { case (tpe1, tpe2) => merge(tpe1, tpe2, !covariant) }
//      val bparams = (bparams1 zip bparams2).map { case (tpe1, tpe2) => merge(tpe1, tpe2, !covariant) }
//      BlockType.Function(tparams1, cparams1, vparams, bparams, merge(result1, result2, covariant))
//    case (tpe1, tpe2) => tpe1
//  }
//
//  def merge(capt1: Captures, capt2: Captures, covariant: Boolean): Captures =
//    if covariant then capt1 union capt2 else capt1 intersect capt2
//
//  def instantiate(f: BlockType.Function, targs: List[ValueType], cargs: List[Captures]): BlockType.Function = f match {
//    case BlockType.Function(tparams, cparams, vparams, bparams, result) =>
//      assert(targs.size == tparams.size, "Wrong number of type arguments")
//      assert(cargs.size == cparams.size, "Wrong number of capture arguments")
//
//      val vsubst = (tparams zip targs).toMap
//      val csubst = (cparams zip cargs).toMap
//      BlockType.Function(Nil, Nil,
//        vparams.map { tpe => substitute(tpe, vsubst, Map.empty) },
//        bparams.map { tpe => substitute(tpe, vsubst, Map.empty) },
//        substitute(result, vsubst, csubst))
//  }
//
//  def substitute(capt: Captures, csubst: Map[Id, Captures]): Captures = capt.flatMap {
//    case id if csubst.isDefinedAt(id) => csubst(id)
//    case c => Set(c)
//  }
//
//  def substitute(tpe: BlockType, vsubst: Map[Id, ValueType], csubst: Map[Id, Captures]): BlockType = tpe match {
//    case BlockType.Function(tparams, cparams, vparams, bparams, result) =>
//      // names are unique symbols so shadowing should NOT take place; we still subtract to be safe.
//      val vsubstLocal = vsubst -- tparams
//      val csubstLocal = csubst -- cparams
//
//      BlockType.Function(tparams, cparams,
//        vparams.map { tpe => substitute(tpe, vsubstLocal, csubst) }, // technically in source, cparams are not bound in value arguments
//        bparams.map { tpe => substitute(tpe, vsubstLocal, csubstLocal) },
//        substitute(result, vsubstLocal, csubstLocal))
//
//    case BlockType.Interface(sym, targs) =>
//      BlockType.Interface(sym, targs map { tpe => substitute(tpe, vsubst, csubst) })
//  }
//
//  def substitute(tpe: ValueType, vsubst: Map[Id, ValueType], csubst: Map[Id, Captures]): ValueType = tpe match {
//    case ValueType.Var(id) if vsubst.isDefinedAt(id) => vsubst(id)
//    case ValueType.Var(id) => tpe
//
//    case ValueType.Data(sym, targs) => ValueType.Data(sym, targs.map(t => substitute(t, vsubst, csubst)))
//
//    case ValueType.Boxed(tpe, capt) =>
//      ValueType.Boxed(substitute(tpe, vsubst, csubst), substitute(capt, csubst))
//  }
//
//  def inferType(block: Block): BlockType = block match {
//    case Block.BlockVar(id, tpe, capt) => tpe
//    case Block.BlockLit(tparams, cparams, vps, bps, body) =>
//      val vparams = vps.map { p => p.tpe }
//      val bparams = bps.map { p => p.tpe }
//
//      BlockType.Function(tparams, cparams, vparams, bparams, body.tpe)
//    case Block.Member(b, field, tpe) => tpe
//    case Block.Unbox(pure) => pure.tpe.asInstanceOf[ValueType.Boxed].tpe
//    case Block.New(impl) => impl.tpe
//  }
//  def inferCapt(block: Block): Captures = block match {
//    case Block.BlockVar(id, tpe, capt) => capt
//    case Block.BlockLit(tparams, cparams, vparams, bparams, body) =>
//      body.capt -- cparams
//    case Block.Member(block, field, tpe) => block.capt
//    case Block.Unbox(pure) => pure.tpe.asInstanceOf[ValueType.Boxed].capt
//    case Block.New(impl) => impl.capt
//  }
//
//  def inferType(stmt: Stmt): ValueType = stmt match {
//    case Stmt.Scope(definitions, body) => body.tpe
//    case Stmt.Return(expr) => expr.tpe
//    case Stmt.Val(id, binding, body) => body.tpe
//    case Stmt.App(callee, targs, vargs, bargs) =>
//      instantiate(callee.functionType, targs, bargs.map(_.capt)).result
//
//    case Stmt.If(cond, thn, els) => merge(thn.tpe, els.tpe, covariant = true)
//    case Stmt.Match(scrutinee, clauses, default) =>
//      val allTypes = clauses.map { case (_, cl) => cl.returnType } ++ default.map(_.tpe).toList
//      allTypes.fold(TBottom) { case (tpe1, tpe2) => merge(tpe1, tpe2, covariant = true) }
//
//    case Stmt.State(id, init, region, body) => body.tpe
//    case Stmt.Try(body, handler) => body.returnType
//    case Stmt.Region(body) => body.returnType
//
//    case Stmt.Hole() => TBottom
//  }
//
//  def inferCapt(defn: Definition): Captures = defn match {
//    case Definition.Def(id, block) => block.capt
//    case Definition.Let(id, binding) => binding.capt
//  }
//
//  def inferCapt(stmt: Stmt): Captures = stmt match {
//    case Stmt.Scope(definitions, body) => definitions.foldLeft(body.capt)(_ ++ _.capt)
//    case Stmt.Return(expr) => Set.empty
//    case Stmt.Val(id, binding, body) => binding.capt ++ body.capt
//    case Stmt.App(callee, targs, vargs, bargs) => callee.capt ++ bargs.flatMap(_.capt).toSet
//    case Stmt.If(cond, thn, els) => thn.capt ++ els.capt
//    case Stmt.Match(scrutinee, clauses, default) => clauses.flatMap { (_, cl) => cl.capt }.toSet ++ default.toSet.flatMap(s => s.capt)
//    case Stmt.State(id, init, region, body) => Set(region) ++ body.capt
//    case Stmt.Try(body, handlers) => body.capt ++ handlers.flatMap(_.capt).toSet
//    case Stmt.Region(body) => body.capt
//    case Stmt.Hole() => Set.empty
//  }
//
//  def inferType(expr: Expr): ValueType = expr match {
//    case DirectApp(callee, targs, vargs, bargs) =>
//      instantiate(callee.functionType, targs, bargs.map(_.capt)).result
//    case Run(s) => s.tpe
//    case Pure.ValueVar(id, tpe) => tpe
//    case Pure.Literal(value, tpe) => tpe
//    case Pure.PureApp(callee, targs, args) => instantiate(callee.functionType, targs, Nil).result
//    case Pure.Select(target, field, annotatedType) => annotatedType
//    case Pure.Box(block, capt) => ValueType.Boxed(block.tpe, capt)
//  }
//
//  /**
//   * Invariant: can only be {} or {io}
//   */
//  def inferCapt(expr: Expr): Captures = expr match {
//    case DirectApp(callee, targs, vargs, bargs) =>
//       callee.capt ++ bargs.flatMap(_.capt).toSet
//    case Run(s) => s.capt
//    case pure: Pure => Set.empty
//  }
//
//  extension (block: Block) {
//    def returnType: ValueType = block.functionType.result
//    def functionType: BlockType.Function = block.tpe.asInstanceOf
//  }
}
