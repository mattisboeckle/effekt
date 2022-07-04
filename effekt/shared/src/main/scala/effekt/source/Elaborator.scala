package effekt
package source

import effekt.context.{ Annotations, Context, ContextOps }
import effekt.symbols.*
import effekt.context.assertions.*
import effekt.source.Tree.{ Query, Rewrite }
import effekt.typer.typeMapToSubstitution

/**
 * Transformation on source trees that translates programs into explicit capability-passing style
 *
 * That is, block parameters are introduced to bind capabilities and arguments are introduced at
 * the call sites. Resume is currently _not_ introduced as a block parameter.
 *
 * Also applies elaboration which conceptually is done by Typer. After this phase
 *   - there are no `Do` nodes anymore (replaced by method calls)
 *   - `l.foo(x)` where `foo` is a function and not an operation is desugared to `foo(l, x)`
 *   - all method calls `l.op()` will have `op : Operation`
 *
 * TODO maybe also insert explicit box and unbox
 */
object Elaborator extends Phase[Typechecked, Typechecked] with Rewrite {

  val phaseName = "capability-passing"

  def run(input: Typechecked)(implicit C: Context) = {
    val transformedTree = rewrite(input.tree)

    AnnotateCaptures.annotate(transformedTree)

    Some(input.copy(tree = transformedTree))
  }

  override def defn(implicit C: Context) = {
    case f @ FunDef(id, tps, vps, bps, ret, body) =>
      val capabilities = C.annotation(Annotations.BoundCapabilities, f)
      val capParams = capabilities.map(Context.definitionFor)
      f.copy(bparams = bps ++ capParams, body = rewrite(body))
  }

  override def expr(implicit C: Context) = {

    // an effect call -- translate to method call on the inferred capability
    case c @ Do(effect, id, targs, vargs) =>
      val transformedValueArgs = vargs.map { a => rewrite(a) }

      // the receiver of this effect operation call
      val receiver = C.annotation(Annotations.CapabilityReceiver, c)

      // for bidirectional effects
      val others = C.annotation(Annotations.CapabilityArguments, c)

      // the remaining capabilities are provided as arguments
      val capabilityArgs = others.map { e => InterfaceArg(C.freshReferenceTo(e)) }

      val typeArguments = C.annotation(Annotations.TypeArguments, c)
      val typeArgs = typeArguments.map { e => ValueTypeTree(e) }

      // construct the member selection on the capability as receiver
      MethodCall(Var(C.freshReferenceTo(receiver)).inheritPosition(id), id, typeArgs, transformedValueArgs, capabilityArgs)

    // the function is a field, desugar to select
    case c @ Call(fun: IdTarget, targs, List(receiver), Nil) if fun.definition.isInstanceOf[Field] =>
      Select(rewrite(receiver), fun.id)

    case c @ MethodCall(receiver, id, targs, vargs, bargs) =>
      val valueArgs = vargs.map { a => rewrite(a) }
      val blockArgs = bargs.map { a => rewrite(a) }

      val capabilities = C.annotation(Annotations.CapabilityArguments, c)
      val capabilityArgs = capabilities.map { e => InterfaceArg(C.freshReferenceTo(e)) }

      val typeArguments = C.annotation(Annotations.TypeArguments, c)
      val typeArgs = typeArguments.map { e => ValueTypeTree(e) }

      val recv = rewrite(receiver)

      MethodCall(recv, id, typeArgs, valueArgs, blockArgs ++ capabilityArgs)

    case c @ Call(recv, targs, vargs, bargs) =>
      val receiver = rewrite(recv)
      val valueArgs = vargs.map { a => rewrite(a) }
      val blockArgs = bargs.map { a => rewrite(a) }

      val capabilities = C.annotation(Annotations.CapabilityArguments, c)
      val capabilityArgs = capabilities.map { e => InterfaceArg(C.freshReferenceTo(e)) }

      val typeArguments = C.annotation(Annotations.TypeArguments, c)
      val typeArgs = typeArguments.map { e => ValueTypeTree(e) }

      Call(receiver, typeArgs, valueArgs, blockArgs ++ capabilityArgs)

    case h @ TryHandle(prog, handlers) =>
      val body = rewrite(prog)

      val capabilities = C.annotation(Annotations.BoundCapabilities, h)

      assert(capabilities.size == handlers.size)

      // here we use the invariant that the order of capabilities is the same as the order of handlers

      val hs = (handlers zip capabilities).map {
        case (h, cap) => visit(h) {
          case h @ Handler(eff, _, clauses) =>
            val cls = clauses.map { cl =>
              visit(cl) {
                case OpClause(id, tparams, params, body, resume: IdDef) =>

                  // OpClause also binds a block parameter for resume, which is _not_ annotated here
                  OpClause(id, tparams, params, rewrite(body), resume)
              }
            }

            // here we annotate the synthesized capability
            Handler(eff, Some(Context.definitionFor(cap)), cls)
        }
      }

      TryHandle(body, hs)
  }

  def rewrite(target: source.CallTarget)(implicit C: Context): source.CallTarget = visit(target) {
    case i: IdTarget => target
    case ExprTarget(expr) => ExprTarget(rewrite(expr))
  }

  override def rewrite(b: source.FunctionArg)(implicit C: Context): source.FunctionArg = visit(b) {
    case b @ source.FunctionArg(tps, vps, bps, body) =>
      val capabilities = C.annotation(Annotations.BoundCapabilities, b)
      val capParams = capabilities.map(Context.definitionFor)
      source.FunctionArg(tps, vps, bps ++ capParams, rewrite(body))
  }

  /**
   * Copies all annotations and position information from source to target
   */
  override def visit[T <: Tree](source: T)(block: T => T)(implicit C: Context): T = {
    val target = block(source)
    target.inheritPosition(source)
    C.copyAnnotations(source, target)
    target
  }
}
trait ElaborationOps extends ContextOps { Context: Context =>

  private[source] def freshReferenceTo(s: symbols.BlockParam): IdRef =
    val id = IdRef(s.name.name)
    assignSymbol(id, s)
    id

  private[source] def definitionFor(s: symbols.BlockParam): source.BlockParam =
    val id = IdDef(s.name.name)
    assignSymbol(id, s)
    val tree = source.BlockParam(id, source.BlockTypeTree(s.tpe))
    tree

}


/**
 * Computes the capture of each subexpression, provided that Typer already solved the constraints.
 *
 * TODO remove self-regions
 * TODO annotate unbox in Typer and use it here
 *
 * TODO move this pass after capability-passing / elaboration
 */
object AnnotateCaptures extends Query[Unit, CaptureSet] {
  def empty: CaptureSet = CaptureSet.empty
  def combine(r1: CaptureSet, r2: CaptureSet): CaptureSet = r1 ++ r2

  override def expr(using Context, Unit) = {
    case source.Var(id) => id.symbol match {
      case b: BlockSymbol => captureOf(b)
      case x: ValueSymbol => CaptureSet.empty
    }

    case e @ source.Assign(id, expr) =>
      query(expr) ++ captureOf(id.symbol.asBlockSymbol)

    case l @ source.Box(annotatedCapture, block) =>
      query(block)
      CaptureSet.empty

    case source.Unbox(term) =>
      query(term)
      CaptureSet.empty

    case t @ source.Do(effect, op, targs, vargs) =>
      val cap = Context.annotation(Annotations.CapabilityReceiver, t)
      combineAll(vargs.map(query)) ++ CaptureSet(cap.capture)

    case t @ source.TryHandle(prog, handlers) =>
      val progCapture = query(prog)
      val selfRegion = Context.annotation(Annotations.SelfRegion, t)
      val boundCapture = boundCapabilities(t) ++ CaptureSet(selfRegion)
      val usedCapture = combineAll(handlers.map(query))
      (progCapture -- boundCapture) ++ usedCapture

    case c @ source.Call(target, targs, vargs, bargs) =>
      // TODO what's with unboxed value references???
      //  maybe that's solved by inserting explicit box and unbox in Elaboration
      val tcaps = target match {
        case IdTarget(id) => captureOf(id.symbol.asBlockSymbol)
        case ExprTarget(receiver) => query(receiver)
      }

      tcaps ++ combineAll(vargs map query) ++ combineAll(bargs map query)
  }

  override def defn(using Context, Unit) = {
    /**
     * For functions we check that the self region does not leave as part of the return type.
     */
    case tree @ source.FunDef(id, tps, vps, bps, ret, body) =>
      val selfRegion = Context.annotation(Annotations.SelfRegion, tree)
      query(body) -- boundCapabilities(tree) -- CaptureSet(selfRegion :: bps.map(_.symbol.capture))
  }

  override def query(b: source.BlockArg)(using Context, Unit): CaptureSet = visit(b) {
    case b: source.FunctionArg  => query(b)
    case b: source.InterfaceArg => captureOf(b.definition)
  }

  override def query(b: source.FunctionArg)(using Context, Unit): CaptureSet = visit(b) {
    case source.FunctionArg(tps, vps, bps, body) =>
      val selfRegion = Context.annotation(Annotations.SelfRegion, b)
      query(body) -- boundCapabilities(b) -- CaptureSet(selfRegion :: bps.map(_.symbol.capture))
  }

  def boundCapabilities(t: Tree)(using Context): CaptureSet =
    val bound = Context.annotation(Annotations.BoundCapabilities, t)
    CaptureSet(bound.map(_.capture))

  def captureOf(b: BlockSymbol)(using Context): CaptureSet =
    asConcreteCaptureSet(Context.captureOf(b))

  def asConcreteCaptureSet(c: Captures)(using Context): CaptureSet = c match {
    case c: CaptureSet => c
    case _ => Context.panic("All capture unification variables should have been replaced by now.")
  }

  def Context(implicit ctx: Context): Context = ctx

  def annotate(tree: source.ModuleDecl)(using Context): Unit =
    given Unit = ();
    query(tree)

  override def visit[T <: Tree](t: T)(visitor: T => CaptureSet)(using Context, Unit): CaptureSet =
    val capt = visitor(t)
    Context.annotate(Annotations.InferredCapture, t, capt)
    capt

}