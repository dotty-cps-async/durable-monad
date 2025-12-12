package durable

import scala.quoted.*
import cps.*

/**
 * CpsPreprocessor implementation for Durable monad.
 *
 * Transforms val definitions inside async blocks to use activity:
 *   val x = expr  →  val x = await(ctx.activitySync { expr })
 *
 * Also wraps conditions in if/match for deterministic replay:
 *   if (cond) ...  →  if (await(ctx.activitySync { cond })) ...
 *
 * Transformation scope:
 *   - Transforms top-level vals and control flow
 *   - DOES NOT transform inside lambdas (x => ...) - treated as atomic
 *   - DOES NOT transform inside nested defs - treated as atomic
 */
object DurablePreprocessor:

  /**
   * Main preprocessing implementation.
   * Transforms only top-level statements - does NOT descend recursively.
   */
  def impl[A: Type, C <: Durable.DurableCpsContext: Type](
    body: Expr[A],
    ctx: Expr[C]
  )(using Quotes): Expr[A] =
    import quotes.reflect.*

    val awaitSymbol = Symbol.requiredMethod("cps.await")

    // Recursively widen union types - handles cases like 10 | 42 | 0 -> Int
    def widenAll(tpe: TypeRepr): TypeRepr = tpe match
      case OrType(left, right) =>
        val l = widenAll(left)
        val r = widenAll(right)
        if l =:= r then l else OrType(l, r)
      case other => other.widen

    // Find storage type S by searching for DurableStorage given in scope
    lazy val storageType: TypeRepr =
      Implicits.search(TypeRepr.of[DurableStorage]) match
        case iss: ImplicitSearchSuccess =>
          // Get the concrete storage type (e.g., MemoryStorage)
          iss.tree.tpe.widen
        case isf: ImplicitSearchFailure =>
          report.errorAndAbort("No DurableStorage found. Ensure a given storage (e.g., given MemoryStorage = ...) is in scope.")

    def wrapWithActivity(expr: Term): Term =
      val exprType = widenAll(expr.tpe)

      // Build: ctx.activitySync[A, S](expr)
      // A is from expr type, S is the storage type found above
      val activityCall = Apply(
        TypeApply(
          Select.unique(ctx.asTerm, "activitySync"),
          List(
            TypeTree.of(using exprType.asType),
            TypeTree.of(using storageType.asType)
          )
        ),
        List(expr)
      )

      // Build: await[Durable, T, Durable](activityCall)(using ctx, identityConversion)
      // await is: extension [F[_], T, G[_]](f: F[T])(using ctx: CpsMonadContext[G], conversion: CpsMonadConversion[F, G]).await: T
      val awaitRef = Ref(Symbol.requiredMethod("cps.await"))
      val awaitWithTypes = TypeApply(
        awaitRef,
        List(
          TypeTree.of[Durable],
          TypeTree.of(using exprType.asType),
          TypeTree.of[Durable]
        )
      )
      // First apply: the value F[T]
      val awaitApply1 = Apply(awaitWithTypes, List(activityCall))
      // Second apply: the using parameters (ctx, conversion)
      val identityConversionRef = Ref(Symbol.requiredMethod("cps.CpsMonadConversion.identityConversion"))
      val identityConversionTyped = TypeApply(identityConversionRef, List(TypeTree.of[Durable]))
      Apply(awaitApply1, List(ctx.asTerm, identityConversionTyped))

    /**
     * Transform a statement at top level.
     * Only wraps val definitions - doesn't descend into expressions.
     */
    def transformStatement(stat: Statement): Statement =
      stat match
        // Val definition - wrap RHS with activity
        case vd @ ValDef(name, tpt, Some(rhs)) =>
          val wrappedRhs = wrapWithActivity(rhs)
          ValDef.copy(vd)(name, tpt, Some(wrappedRhs))

        // Def definition - don't transform
        case defDef: DefDef =>
          defDef

        // Term as statement - recurse to handle blocks/if/match at top level
        case term: Term =>
          transformTopLevel(term)

        // Import, etc - pass through
        case imp: Import =>
          imp

        case other =>
          report.errorAndAbort(s"DurablePreprocessor: unexpected statement type: ${other.getClass.getName}", other.pos)

    /**
     * Transform a term at top level.
     * @param isReturnPosition if true, don't wrap leaf expressions (they're at return position)
     */
    def transformTopLevel(term: Term, isReturnPosition: Boolean = false): Term =
      term match
        // Block - transform its statements, final expr is at return position
        case block @ Block(stats, expr) =>
          Block.copy(block)(
            stats.map(transformStatement),
            transformTopLevel(expr, isReturnPosition = true)
          )

        // If expression - wrap condition, branches inherit return position
        case ifTerm @ If(cond, thenBranch, elseBranch) =>
          val wrappedCond = wrapWithActivity(cond)
          If.copy(ifTerm)(
            wrappedCond,
            transformTopLevel(thenBranch, isReturnPosition),
            transformTopLevel(elseBranch, isReturnPosition)
          )

        // Match expression - wrap scrutinee, case bodies inherit return position
        case matchTerm @ Match(scrutinee, cases) =>
          val wrappedScrutinee = wrapWithActivity(scrutinee)
          val transformedCases = cases.map { caseDef =>
            CaseDef.copy(caseDef)(
              caseDef.pattern,
              caseDef.guard,  // don't wrap guards
              transformTopLevel(caseDef.rhs, isReturnPosition)
            )
          }
          Match.copy(matchTerm)(wrappedScrutinee, transformedCases)

        // Try-catch - recurse into body and cases
        case tryTerm @ Try(body, cases, finalizer) =>
          val transformedCases = cases.map { caseDef =>
            CaseDef.copy(caseDef)(
              caseDef.pattern,
              caseDef.guard,
              transformTopLevel(caseDef.rhs, isReturnPosition)
            )
          }
          Try.copy(tryTerm)(
            transformTopLevel(body, isReturnPosition),
            transformedCases,
            finalizer.map(f => transformTopLevel(f, isReturnPosition = false))
          )

        // While loop - transform body (not at return position - while returns Unit)
        case whileTerm @ While(cond, body) =>
          val wrappedCond = wrapWithActivity(cond)
          While.copy(whileTerm)(wrappedCond, transformTopLevel(body, isReturnPosition = false))

        // Typed expression - unwrap and transform
        case Typed(expr, tpt) =>
          Typed(transformTopLevel(expr, isReturnPosition), tpt)

        // Inlined - transform expansion
        case Inlined(call, bindings, expansion) =>
          Inlined(call, bindings, transformTopLevel(expansion, isReturnPosition))

        // Literal, Ident - don't wrap, these are deterministic or references to cached values
        case _: Literal | _: Ident =>
          term

        // Select, Apply, TypeApply - wrap unless at return position or already an await call
        case app @ Apply(Apply(TypeApply(fn, _), _), _) if fn.symbol == awaitSymbol =>
          term  // Already an await call, don't double-wrap

        case _: Select | _: Apply | _: TypeApply =>
          if isReturnPosition then term  // At return position, don't wrap
          else wrapWithActivity(term)

        case other =>
          report.errorAndAbort(s"DurablePreprocessor: unexpected term type: ${other.getClass.getName}", other.pos)

    val transformed = transformTopLevel(body.asTerm, isReturnPosition = true)
    transformed.asExprOf[A]
