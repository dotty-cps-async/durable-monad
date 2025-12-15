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
 *
 * First resolves DurableStorageBackend from scope to get the backend type S,
 * then resolves DurableStorage[A, S] for each activity. This ensures all
 * activities in a workflow share the same backend.
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

    // First, find DurableStorageBackend in scope to get the backend type S
    val backendType: TypeRepr = Implicits.search(TypeRepr.of[DurableStorageBackend]) match
      case iss: ImplicitSearchSuccess =>
        iss.tree.tpe.widen
      case isf: ImplicitSearchFailure =>
        report.errorAndAbort(
          s"No DurableStorageBackend found in scope. Add: given DurableStorageBackend = yourBackend",
          body.asTerm.pos
        )

    // Recursively widen union types - handles cases like 10 | 42 | 0 -> Int
    def widenAll(tpe: TypeRepr): TypeRepr = tpe match
      case OrType(left, right) =>
        val l = widenAll(left)
        val r = widenAll(right)
        if l =:= r then l else OrType(l, r)
      case other => other.widen

    def wrapWithActivity(expr: Term): Term =
      val exprType = widenAll(expr.tpe)

      // Build: ctx.activitySync[A, S](expr, RetryPolicy.default)
      // where S is the DurableStorageBackend type resolved earlier
      // DurableStorage[A, S] is resolved via normal given resolution
      // We use RetryPolicy.default for preprocessor-generated activities
      // Users can override by using Durable.activity(..., customPolicy) directly

      // Get RetryPolicy.default
      val retryPolicyModule = Symbol.requiredModule("durable.RetryPolicy")
      val defaultPolicy = Select(Ref(retryPolicyModule), retryPolicyModule.fieldMember("default"))

      // Build: ctx.activitySync[A, S](expr, RetryPolicy.default)
      val activityCall = Apply(
        TypeApply(
          Select.unique(ctx.asTerm, "activitySync"),
          List(
            TypeTree.of(using exprType.asType),
            TypeTree.of(using backendType.asType)
          )
        ),
        List(expr, defaultPolicy)
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
     * Transform a catch case pattern to also match ReplayedException.
     *
     * Transforms: case e: SomeException => body
     * To: case e @ (_: SomeException | _: ReplayedException) if ReplayedException.matches[SomeException](e) => body
     *
     * This allows catch blocks to handle both original exceptions and replayed exceptions
     * transparently. The user's code doesn't need to change.
     */
    def transformCatchCase(caseDef: CaseDef, isReturnPosition: Boolean): CaseDef =
      caseDef.pattern match
        // Pattern: e: SomeException (Bind with Typed)
        case bind @ Bind(name, typed @ Typed(_, tpt)) if isThrowableType(tpt.tpe) =>
          val exceptionType = tpt.tpe
          val replayedExceptionType = TypeRepr.of[ReplayedException]

          // Build: _: SomeException | _: ReplayedException
          val wildcardOriginal = Typed(Wildcard(), tpt)
          val wildcardReplayed = Typed(Wildcard(), TypeTree.of[ReplayedException])
          val alternativePattern = Alternatives(List(wildcardOriginal, wildcardReplayed))

          // Build: e @ (_: SomeException | _: ReplayedException)
          val newPattern = Bind(bind.symbol, alternativePattern)

          // Build guard: ReplayedException.matches[SomeException](e)
          val replayedModule = Ref(Symbol.requiredModule("durable.ReplayedException"))
          val matchesMethod = Select(replayedModule, replayedModule.symbol.methodMember("matches").head)
          val matchesTyped = TypeApply(matchesMethod, List(TypeTree.of(using exceptionType.asType)))
          val boundVar = Ref(bind.symbol)
          val guardExpr = Apply(matchesTyped, List(boundVar))

          // Combine with existing guard if present
          val combinedGuard = caseDef.guard match
            case Some(existingGuard) =>
              // existingGuard && ReplayedException.matches[T](e)
              val andMethod = Select.unique(existingGuard, "&&")
              Some(Apply(andMethod, List(guardExpr)))
            case None =>
              Some(guardExpr)

          CaseDef(newPattern, combinedGuard, transformTopLevel(caseDef.rhs, isReturnPosition))

        // Pattern: _: SomeException (Typed without Bind)
        case typed @ Typed(Wildcard(), tpt) if isThrowableType(tpt.tpe) =>
          val exceptionType = tpt.tpe

          // Build: _: SomeException | _: ReplayedException
          val wildcardOriginal = Typed(Wildcard(), tpt)
          val wildcardReplayed = Typed(Wildcard(), TypeTree.of[ReplayedException])
          val alternativePattern = Alternatives(List(wildcardOriginal, wildcardReplayed))

          // For wildcard patterns without binding, we need to create a temporary binding for the guard
          // Build: _tmp @ (_: SomeException | _: ReplayedException)
          val tmpName = "_$replayGuard"
          val tmpSymbol = Symbol.newVal(Symbol.spliceOwner, tmpName, TypeRepr.of[Throwable], Flags.EmptyFlags, Symbol.noSymbol)
          val newPattern = Bind(tmpSymbol, alternativePattern)

          // Build guard: ReplayedException.matches[SomeException](_tmp)
          val replayedModule = Ref(Symbol.requiredModule("durable.ReplayedException"))
          val matchesMethod = Select(replayedModule, replayedModule.symbol.methodMember("matches").head)
          val matchesTyped = TypeApply(matchesMethod, List(TypeTree.of(using exceptionType.asType)))
          val tmpRef = Ref(tmpSymbol)
          val guardExpr = Apply(matchesTyped, List(tmpRef))

          // Combine with existing guard
          val combinedGuard = caseDef.guard match
            case Some(existingGuard) =>
              Some(Apply(Select.unique(existingGuard, "&&"), List(guardExpr)))
            case None =>
              Some(guardExpr)

          CaseDef(newPattern, combinedGuard, transformTopLevel(caseDef.rhs, isReturnPosition))

        // Other patterns (e.g., case _ =>, case e =>) - just transform RHS
        case _ =>
          CaseDef.copy(caseDef)(
            caseDef.pattern,
            caseDef.guard,
            transformTopLevel(caseDef.rhs, isReturnPosition)
          )

    /** Check if a type is a subtype of Throwable */
    def isThrowableType(tpe: TypeRepr): Boolean =
      tpe <:< TypeRepr.of[Throwable]

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

        // Try-catch - recurse into body and transform catch cases for ReplayedException
        case tryTerm @ Try(body, cases, finalizer) =>
          val transformedCases = cases.map { caseDef =>
            transformCatchCase(caseDef, isReturnPosition)
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

        // Assignment to var - not allowed in durable workflows (breaks replay semantics)
        case Assign(lhs, rhs) =>
          report.errorAndAbort(
            "Mutable variables (var) are not supported in durable workflows. " +
            "Use continueWith for loops or immutable state.",
            term.pos
          )

        case other =>
          report.errorAndAbort(s"DurablePreprocessor: unexpected term type: ${other.getClass.getName}", other.pos)

    val transformed = transformTopLevel(body.asTerm, isReturnPosition = true)
    transformed.asExprOf[A]
