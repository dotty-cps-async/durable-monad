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
 * Resource handling:
 *   - When val has DurableEphemeral[T], wraps rest of block in WithSessionResource
 *   - val file: FileHandle = openFile("x") → await(Durable.withResourceSync(openFile("x"), release){ file => restOfBlock })
 *
 * Transformation scope:
 *   - Transforms top-level vals and control flow
 *   - Transforms lambda bodies (x => ...) if they contain await calls
 *   - Does NOT transform nested def bodies (dotty-cps-async doesn't handle await in named defs)
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
  def impl[A: Type, C <: Durable.DurableContext: Type](
    body: Expr[A],
    ctx: Expr[C]
  )(using Quotes): Expr[A] =
    import quotes.reflect.*

    val awaitSymbol = Symbol.requiredMethod("cps.await")

    // First, find DurableStorageBackend in scope to get the backend type S
    // Moved here so it's available to FutureConvertibleAwaitTransformer
    val backendType: TypeRepr = Implicits.search(TypeRepr.of[DurableStorageBackend]) match
      case iss: ImplicitSearchSuccess =>
        iss.tree.tpe.widen
      case isf: ImplicitSearchFailure =>
        report.errorAndAbort(
          s"No DurableStorageBackend found in scope. Add: given DurableStorageBackend = yourBackend",
          body.asTerm.pos
        )

    // Get the symbol for durableFormalConversion for direct comparison
    val durableFormalConversionSymbol = Symbol.requiredMethod("durable.Durable.durableFormalConversion")


    /**
     * TreeMap to transform F[T].await for types convertible to Future.
     *
     * When an await call uses our durableFormalConversion, we transform it to:
     *   await(Durable.Activity(() => conversion.apply(f), storage, policy))
     *
     * This runs before transformTopLevel to handle nested awaits in expressions.
     */
    class FutureConvertibleAwaitTransformer extends TreeMap:

      /** Check if a conversion term uses our durableFormalConversion.
        * durableFormalConversion[F,S](backend, toFuture) has structure: Apply(TypeApply(method, typeArgs), args)
        */
      private def isDurableFormalConversion(conversion: Term): Boolean =
        conversion match
          case Apply(TypeApply(method, _), _) => method.symbol == durableFormalConversionSymbol
          case _ => false

      override def transformTerm(term: Term)(owner: Symbol): Term =
        term match
          // Check for .await extension method call on Future/etc (before CPS expansion)
          // Pattern: Apply(Select(future, "await"), List(ctx, conversion))
          case app @ Apply(Apply(sel @ Select(effectArg, "await"), List(ctxArg)), List(conversion))
              if effectArg.tpe.widen match
                case AppliedType(f, _) => !(f =:= TypeRepr.of[Durable]) // Not Durable[T]
                case _ => false
              =>
            if isDurableFormalConversion(conversion) then
              effectArg.tpe.widen match
                case AppliedType(fType, List(innerType)) =>
                  val transformedEffectArg = transformTerm(effectArg)(owner)
                  val toFutureConversion = extractToFutureEvidence(conversion)
                  transformFutureConvertibleAwait(transformedEffectArg, fType, innerType, toFutureConversion)
                case _ =>
                  super.transformTerm(term)(owner)
            else
              super.transformTerm(term)(owner)

          // await call: Apply(Apply(TypeApply(fn, typeArgs), List(effectArg)), List(ctx, conversion))
          case app @ Apply(Apply(TypeApply(fn, typeArgs), List(effectArg)), List(ctxArg, conversion))
              if fn.symbol == awaitSymbol =>
            if isDurableFormalConversion(conversion) then
              effectArg.tpe.widen match
                case AppliedType(fType, List(innerType)) =>
                  // Transform the effectArg recursively first
                  val transformedEffectArg = transformTerm(effectArg)(owner)
                  // Get the CpsMonadConversion[F, Future] from formal's evidence
                  val toFutureConversion = extractToFutureEvidence(conversion)
                  transformFutureConvertibleAwait(transformedEffectArg, fType, innerType, toFutureConversion)
                case _ =>
                  super.transformTerm(term)(owner)
            else
              // Not our formal conversion - still recurse into arguments
              super.transformTerm(term)(owner)
          case _ =>
            super.transformTerm(term)(owner)

      /** Extract the CpsMonadConversion[F, Future] evidence from our formal conversion */
      // TODO: verify that the structure is exactly as expected (backend, toFuture)
      //       and that toFuture is actually a CpsMonadConversion[F, Future]
      private def extractToFutureEvidence(formalConversion: Term): Term =
        // Structure: durableFormalConversion[F, S](backend, toFuture)
        // We need toFuture which is the second argument
        formalConversion match
          case Apply(_, args) if args.length >= 2 =>
            // Return second arg (toFuture conversion) - it's CpsMonadConversion[F, Future]
            args(1)
          case Apply(_, args) =>
            report.errorAndAbort(
              s"Expected durableFormalConversion(backend, toFuture) with 2 args, got ${args.length} args: ${formalConversion.show}",
              formalConversion.pos
            )
          case _ =>
            report.errorAndAbort(
              s"Expected Apply structure for durableFormalConversion, got: ${formalConversion.getClass.getSimpleName}: ${formalConversion.show}",
              formalConversion.pos
            )

      /** Transform F[T].await to await(Durable.Activity(...)) */
      private def transformFutureConvertibleAwait(
          effectExpr: Term,
          fType: TypeRepr,
          innerType: TypeRepr,
          toFutureConversion: Term
      ): Term =
        // Resolve DurableStorage[T, S] for the inner type
        val storageType = TypeRepr.of[DurableStorage].appliedTo(List(innerType, backendType))
        val storage = Implicits.search(storageType) match
          case iss: ImplicitSearchSuccess => iss.tree
          case isf: ImplicitSearchFailure =>
            report.errorAndAbort(
              s"No DurableStorage[${innerType.show}, ${backendType.show}] found for ${fType.show}.await",
              effectExpr.pos
            )

        // Get RetryPolicy.noRetry
        val retryPolicyModule = Symbol.requiredModule("durable.RetryPolicy")
        val noRetryPolicy = Select(Ref(retryPolicyModule), retryPolicyModule.fieldMember("noRetry"))

        // Build: toFutureConversion.apply[innerType](effectExpr) which returns Future[T]
        val applyMethod = toFutureConversion.tpe.widen.typeSymbol.methodMember("apply").head
        val toFutureCall = Apply(
          TypeApply(
            Select(toFutureConversion, applyMethod),
            List(TypeTree.of(using innerType.asType))
          ),
          List(effectExpr)
        )

        // Build lambda: () => toFutureCall
        val futureType = TypeRepr.of[scala.concurrent.Future].appliedTo(innerType)
        val lambdaMethodType = MethodType(Nil)(_ => Nil, _ => futureType)
        val lambda = Lambda(
          Symbol.spliceOwner,
          lambdaMethodType,
          (meth: Symbol, params: List[Tree]) => toFutureCall.changeOwner(meth)
        )

        // Build SourcePos from effectExpr position
        val pos = effectExpr.pos
        val fileName = Literal(StringConstant(pos.sourceFile.name))
        val lineNum = Literal(IntConstant(pos.startLine + 1))  // 1-based
        val sourcePosModule = Symbol.requiredModule("durable.runtime.SourcePos")
        val sourcePos = Apply(
          Select(Ref(sourcePosModule), sourcePosModule.methodMember("apply").head),
          List(fileName, lineNum)
        )

        // Build: Durable.Activity[T, S](() => ..., storage, noRetryPolicy, sourcePos)
        val activityClass = Symbol.requiredClass("durable.Durable.Activity")

        // Activity case class takes: compute: () => Future[A], storage: DurableStorage[A, S], retryPolicy: RetryPolicy, sourcePos: SourcePos
        val activityCall = Apply(
          TypeApply(
            Ref(activityClass.companionModule).select(activityClass.companionModule.methodMember("apply").head),
            List(
              TypeTree.of(using innerType.asType),
              TypeTree.of(using backendType.asType)
            )
          ),
          List(lambda, storage, noRetryPolicy, sourcePos)
        )

        // Wrap with await[Durable, T, Durable](activityCall)(using ctx, identityConversion)
        val awaitRef = Ref(Symbol.requiredMethod("cps.await"))
        val awaitWithTypes = TypeApply(
          awaitRef,
          List(
            TypeTree.of[Durable],
            TypeTree.of(using innerType.asType),
            TypeTree.of[Durable]
          )
        )
        val awaitApply1 = Apply(awaitWithTypes, List(activityCall))
        val identityConversionRef = Ref(Symbol.requiredMethod("cps.CpsMonadConversion.identityConversion"))
        val identityConversionTyped = TypeApply(identityConversionRef, List(TypeTree.of[Durable]))
        Apply(awaitApply1, List(ctx.asTerm, identityConversionTyped))

    // Apply FutureConvertibleAwaitTransformer to transform all F[_].await calls
    val futureTransformer = new FutureConvertibleAwaitTransformer
    val bodyAfterFutureTransform = futureTransformer.transformTerm(body.asTerm)(Symbol.spliceOwner)

    // Recursively widen union types - handles cases like 10 | 42 | 0 -> Int
    def widenAll(tpe: TypeRepr): TypeRepr = tpe match
      case OrType(left, right) =>
        val l = widenAll(left)
        val r = widenAll(right)
        if l =:= r then l else OrType(l, r)
      case other => other.widen

    /**
     * Check if a term contains any await calls.
     * Used to decide whether to recursively transform lambda bodies and arguments.
     */
    def containsAwait(term: Term): Boolean =
      var found = false
      val traverser = new TreeTraverser:
        override def traverseTree(tree: Tree)(owner: Symbol): Unit =
          if !found then
            tree match
              // await function call: Apply(Apply(TypeApply(await, types), List(expr)), List(ctx, conv))
              case Apply(Apply(TypeApply(fn, _), _), _) if fn.symbol == awaitSymbol =>
                found = true
              // .await extension method: Apply(Select(expr, "await"), args)
              case Apply(Select(_, "await"), _) =>
                found = true
              // Also check for Apply(Apply(Select(...), ...), ...) form of .await
              case Apply(Apply(sel @ Select(_, "await"), _), _) =>
                found = true
              case _ =>
                super.traverseTree(tree)(owner)
      traverser.traverseTree(term)(Symbol.spliceOwner)
      found

    def wrapWithActivity(expr: Term): Term =
      val exprType = widenAll(expr.tpe)

      // Check if exprType is F[T] where DurableAsync[F] exists in scope
      exprType match
        case AppliedType(fType, List(tType)) =>
          // Try to find DurableAsync[F] in scope
          val wrapperType = TypeRepr.of[DurableAsync].appliedTo(fType)
          Implicits.search(wrapperType) match
            case iss: ImplicitSearchSuccess =>
              // Use activityAsync for F[T] types with wrapper
              wrapWithActivityAsync(expr, exprType, fType, tType)
            case isf: ImplicitSearchFailure =>
              // No wrapper available, use regular activitySync
              wrapWithActivitySync(expr, exprType)
        case _ =>
          // Not an applied type, use regular activitySync
          wrapWithActivitySync(expr, exprType)

    def wrapWithActivitySync(expr: Term, exprType: TypeRepr): Term =
      // Build: ctx.activitySync[A, S](expr, RetryPolicy.default, sourcePos)
      // where S is the DurableStorageBackend type resolved earlier
      // DurableStorage[A, S] is resolved via normal given resolution
      // We use RetryPolicy.default for preprocessor-generated activities
      // Users can override by using Durable.activity(..., customPolicy) directly

      // Get RetryPolicy.default
      val retryPolicyModule = Symbol.requiredModule("durable.RetryPolicy")
      val defaultPolicy = Select(Ref(retryPolicyModule), retryPolicyModule.fieldMember("default"))

      // Build SourcePos from expr position
      val pos = expr.pos
      val fileName = Literal(StringConstant(pos.sourceFile.name))
      val lineNum = Literal(IntConstant(pos.startLine + 1))  // 1-based
      val sourcePosModule = Symbol.requiredModule("durable.runtime.SourcePos")
      val sourcePos = Apply(
        Select(Ref(sourcePosModule), sourcePosModule.methodMember("apply").head),
        List(fileName, lineNum)
      )

      // Build: ctx.activitySync[A, S](expr, RetryPolicy.default, sourcePos)
      val activityCall = Apply(
        TypeApply(
          Select.unique(ctx.asTerm, "activitySync"),
          List(
            TypeTree.of(using exprType.asType),
            TypeTree.of(using backendType.asType)
          )
        ),
        List(expr, defaultPolicy, sourcePos)
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

    def wrapWithActivityAsync(expr: Term, exprType: TypeRepr, fType: TypeRepr, tType: TypeRepr): Term =
      // Build: ctx.activityAsync[F, T, S](expr, RetryPolicy.default)
      // where F is the effect type (e.g., Future), T is the inner type, S is the backend type
      // DurableAsyncWrapper[F] and DurableStorage[T, S] are resolved via normal given resolution

      // Get RetryPolicy.default
      val retryPolicyModule = Symbol.requiredModule("durable.RetryPolicy")
      val defaultPolicy = Select(Ref(retryPolicyModule), retryPolicyModule.fieldMember("default"))

      // Build: ctx.activityAsync[F, T, S](expr, RetryPolicy.default)
      val activityCall = Apply(
        TypeApply(
          Select.unique(ctx.asTerm, "activityAsync"),
          List(
            TypeTree.of(using fType.asType),
            TypeTree.of(using tType.asType),
            TypeTree.of(using backendType.asType)
          )
        ),
        List(expr, defaultPolicy)
      )

      // Build: await[Durable, F[T], Durable](activityCall)(using ctx, identityConversion)
      // This extracts F[T] from Durable[F[T]]
      val awaitRef = Ref(Symbol.requiredMethod("cps.await"))
      val awaitWithTypes = TypeApply(
        awaitRef,
        List(
          TypeTree.of[Durable],
          TypeTree.of(using exprType.asType),  // F[T]
          TypeTree.of[Durable]
        )
      )
      // First apply: the value Durable[F[T]]
      val awaitApply1 = Apply(awaitWithTypes, List(activityCall))
      // Second apply: the using parameters (ctx, conversion)
      val identityConversionRef = Ref(Symbol.requiredMethod("cps.CpsMonadConversion.identityConversion"))
      val identityConversionTyped = TypeApply(identityConversionRef, List(TypeTree.of[Durable]))
      Apply(awaitApply1, List(ctx.asTerm, identityConversionTyped))

    /**
     * Substitute all references to oldSymbol with references to newSymbol in a term.
     */
    def substituteSymbol(term: Term, oldSymbol: Symbol, newSymbol: Symbol): Term =
      val mapper = new TreeMap:
        override def transformTerm(t: Term)(owner: Symbol): Term = t match
          case ident: Ident if ident.symbol == oldSymbol =>
            Ref(newSymbol)
          case _ =>
            super.transformTerm(t)(owner)
      mapper.transformTerm(term)(Symbol.spliceOwner)

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

          // Create a NEW symbol with Throwable type to handle both exception types
          // The original symbol has the specific exception type which causes ClassCastException
          val newSymbol = Symbol.newVal(
            Symbol.spliceOwner,
            bind.symbol.name,
            TypeRepr.of[Throwable],
            Flags.EmptyFlags,
            Symbol.noSymbol
          )

          // Build: e @ (_: SomeException | _: ReplayedException)
          val newPattern = Bind(newSymbol, alternativePattern)

          // Build guard: ReplayedException.matches[SomeException](e)
          val replayedModule = Ref(Symbol.requiredModule("durable.ReplayedException"))
          val matchesMethod = Select(replayedModule, replayedModule.symbol.methodMember("matches").head)
          val matchesTyped = TypeApply(matchesMethod, List(TypeTree.of(using exceptionType.asType)))
          val boundVar = Ref(newSymbol)
          val guardExpr = Apply(matchesTyped, List(boundVar))

          // Combine with existing guard if present
          val combinedGuard = caseDef.guard match
            case Some(existingGuard) =>
              // existingGuard && ReplayedException.matches[T](e)
              val andMethod = Select.unique(existingGuard, "&&")
              Some(Apply(andMethod, List(guardExpr)))
            case None =>
              Some(guardExpr)

          // Transform the RHS, substituting the old symbol with the new one
          val transformedRhs = transformTopLevel(caseDef.rhs, isReturnPosition)
          val substitutedRhs = substituteSymbol(transformedRhs, bind.symbol, newSymbol)

          CaseDef(newPattern, combinedGuard, substitutedRhs)

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
     * TreeMap to substitute symbol references.
     * Used when restructuring blocks for resource vals.
     */
    class SymbolSubstitutor(from: Symbol, to: Symbol) extends TreeMap:
      override def transformTerm(term: Term)(owner: Symbol): Term =
        term match
          case ident: Ident if ident.symbol == from =>
            Ref(to)
          case _ =>
            super.transformTerm(term)(owner)

    /**
     * Process block statements, detecting resource vals and restructuring.
     * When a val has WorkflowSessionResource[T], wraps the rest of the block in WithSessionResource.
     */
    def transformBlockStatements(stats: List[Statement], expr: Term, isReturnPosition: Boolean): Term =
      println(s"[PREPROC] transformBlockStatements: stats.size=${stats.size}, expr=${expr.show.take(50)}, isReturnPosition=$isReturnPosition")
      stats match
        case Nil =>
          transformTopLevel(expr, isReturnPosition)

        case (vd @ ValDef(name, tpt, Some(rhs))) :: rest =>
          println(s"[PREPROC] ValDef case: name=$name, rhs=${rhs.show.take(50)}")
          val valType = widenAll(tpt.tpe)
          val ephemeralType = TypeRepr.of[DurableEphemeral].appliedTo(valType)

          Implicits.search(ephemeralType) match
            case iss: ImplicitSearchSuccess =>
              // This is an ephemeral resource val - wrap rest of block in WithSessionResource
              wrapWithEphemeralResource(vd, rhs, rest, expr, valType, iss.tree, isReturnPosition)

            case _ =>
              // Not a resource - transform the RHS (which will wrap as activity if needed)
              // Use transformTopLevel to process lambdas and nested expressions
              val transformedRhs = transformTopLevel(rhs, isReturnPosition = false)
              println(s"[PREPROC] ValDef transformed: name=$name, transformedRhs=${transformedRhs.show.take(80)}")
              val transformedVal = ValDef.copy(vd)(name, tpt, Some(transformedRhs))
              val transformedRest = transformBlockStatements(rest, expr, isReturnPosition)
              transformedRest match
                case Block(restStats, restExpr) =>
                  Block(transformedVal :: restStats, restExpr)
                case _ =>
                  Block(List(transformedVal), transformedRest)

        case (defDef: DefDef) :: rest =>
          // Named def definitions are NOT transformed here.
          // Note: Lambdas (Block(List(defDef), Closure)) are handled separately
          // in transformTopLevel before reaching here.
          // Standalone named defs cannot use await inside - dotty-cps-async
          // doesn't handle await inside nested named functions.
          val transformedRest = transformBlockStatements(rest, expr, isReturnPosition)
          transformedRest match
            case Block(restStats, restExpr) =>
              Block(defDef :: restStats, restExpr)
            case _ =>
              Block(List(defDef), transformedRest)

        case (imp: Import) :: rest =>
          // Import - pass through
          val transformedRest = transformBlockStatements(rest, expr, isReturnPosition)
          transformedRest match
            case Block(restStats, restExpr) =>
              Block(imp :: restStats, restExpr)
            case _ =>
              Block(List(imp), transformedRest)

        case (term: Term) :: rest =>
          // Term as statement - recurse to handle blocks/if/match at top level
          val transformedTerm = transformTopLevel(term)
          val transformedRest = transformBlockStatements(rest, expr, isReturnPosition)
          transformedRest match
            case Block(restStats, restExpr) =>
              Block(transformedTerm :: restStats, restExpr)
            case _ =>
              Block(List(transformedTerm), transformedRest)

        case other :: _ =>
          report.errorAndAbort(s"DurablePreprocessor: unexpected statement type: ${other.getClass.getName}", other.pos)

    /**
     * Wrap the rest of the block in WithSessionResource for an ephemeral resource val.
     * Uses the user's expression for acquisition, takes release from DurableEphemeral.
     *
     * Generates:
     *   await(Durable.withResourceSync(
     *     acquire = userExpression,
     *     release = r => summon[DurableEphemeral[R]].release(r)
     *   ){ r => restOfBlock })
     */
    def wrapWithEphemeralResource(
        vd: ValDef,
        rhs: Term,
        restStats: List[Statement],
        expr: Term,
        valType: TypeRepr,
        ephemeralInstance: Term,
        isReturnPosition: Boolean
    ): Term =
      val originalSymbol = vd.symbol

      // Build the rest of the block as a term (will be the lambda body)
      // We need to transform it AND substitute the original symbol with the lambda param
      val restBlock = transformBlockStatements(restStats, expr, isReturnPosition = true)

      // Get the result type from the rest of the block
      val resultType = widenAll(restBlock.tpe)

      // Create lambda for use: (r: R) => restBlock (with symbol substitution)
      val useLambdaMethodType = MethodType(List(vd.name))(_ => List(valType), _ => resultType)

      val useLambda = Lambda(
        Symbol.spliceOwner,
        useLambdaMethodType,
        (meth: Symbol, params: List[Tree]) =>
          val paramSymbol = params.head.asInstanceOf[ValDef].symbol
          val substitutor = new SymbolSubstitutor(originalSymbol, paramSymbol)
          substitutor.transformTerm(restBlock)(meth)
      )

      // Create lambda for release: (r: R) => ephemeral.release(r)
      val releaseLambdaMethodType = MethodType(List("r"))(_ => List(valType), _ => TypeRepr.of[Unit])
      val releaseLambda = Lambda(
        Symbol.spliceOwner,
        releaseLambdaMethodType,
        (meth: Symbol, params: List[Tree]) =>
          val paramRef = Ref(params.head.asInstanceOf[ValDef].symbol)
          // Call ephemeralInstance.release(r)
          val releaseMethod = ephemeralInstance.tpe.widen.typeSymbol.methodMember("release").head
          Apply(Select(ephemeralInstance, releaseMethod), List(paramRef))
      )

      // Build: Durable.withResourceSync[R, A](acquire = rhs, release = releaseLambda)(useLambda)
      // dotty-cps-async will transform to withResourceSync_async if body contains awaits
      val durableModule = Ref(Symbol.requiredModule("durable.Durable"))
      val withResourceMethod = durableModule.symbol.methodMember("withResourceSync").head

      val withResourceCall = Apply(
        Apply(
          TypeApply(
            Select(durableModule, withResourceMethod),
            List(
              TypeTree.of(using valType.asType),
              TypeTree.of(using resultType.asType)
            )
          ),
          List(rhs, releaseLambda)  // acquire = user's expression, release = from typeclass
        ),
        List(useLambda)
      )

      // Wrap with await[Durable, A, Durable](withResourceCall)(using ctx, identityConversion)
      val awaitRef = Ref(Symbol.requiredMethod("cps.await"))
      val awaitWithTypes = TypeApply(
        awaitRef,
        List(
          TypeTree.of[Durable],
          TypeTree.of(using resultType.asType),
          TypeTree.of[Durable]
        )
      )
      val awaitApply1 = Apply(awaitWithTypes, List(withResourceCall))
      val identityConversionRef = Ref(Symbol.requiredMethod("cps.CpsMonadConversion.identityConversion"))
      val identityConversionTyped = TypeApply(identityConversionRef, List(TypeTree.of[Durable]))
      Apply(awaitApply1, List(ctx.asTerm, identityConversionTyped))

    /**
     * Transform a statement at top level.
     * Only wraps val definitions - doesn't descend into expressions.
     * Note: For blocks, use transformBlockStatements for proper resource handling.
     */
    def transformStatement(stat: Statement): Statement =
      stat match
        // Val definition - transform RHS (which will wrap as activity if needed)
        case vd @ ValDef(name, tpt, Some(rhs)) =>
          val transformedRhs = transformTopLevel(rhs, isReturnPosition = false)
          ValDef.copy(vd)(name, tpt, Some(transformedRhs))

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
      val termStr = term.show.take(100).replace("\n", " ")
      if termStr.contains("map") || termStr.contains("doubled") then
        println(s"[PREPROC] transformTopLevel: $termStr, isReturnPosition=$isReturnPosition")
      term match
        // Lambda - if body contains await, transform the body recursively
        // Lambdas appear as Block(List(defDef), closure) in the AST
        // Must come before general Block case
        case block @ Block(List(defDef: DefDef), closure: Closure) =>
          val hasAwait = containsAwait(defDef.rhs.getOrElse(term))
          println(s"[PREPROC] Lambda case: defDef.name=${defDef.name}, hasRhs=${defDef.rhs.isDefined}, containsAwait=$hasAwait")
          if hasAwait then
            // Transform the def body, then reconstruct the block
            val transformedRhs = defDef.rhs.map(rhs => transformTopLevel(rhs, isReturnPosition = true))
            val newDefDef = DefDef.copy(defDef)(defDef.name, defDef.paramss, defDef.returnTpt, transformedRhs)
            Block(List(newDefDef), closure)
          else
            term  // No await, leave as-is

        // Block - transform using transformBlockStatements for proper resource handling
        case block @ Block(stats, expr) =>
          println(s"[PREPROC] Block case matched: stats.size=${stats.size}, expr=${expr.show.take(50)}")
          transformBlockStatements(stats, expr, isReturnPosition)

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

        // Already an await call, don't double-wrap
        case app @ Apply(Apply(TypeApply(fn, _), _), _) if fn.symbol == awaitSymbol =>
          term

        // Apply - check if arguments contain await, process those recursively
        case app @ Apply(fn, args) =>
          val anyArgHasAwait = args.exists(containsAwait)
          val fnHasAwait = containsAwait(fn)
          println(s"[PREPROC] Apply case: fn=${fn.show.take(50)}, args.size=${args.size}, anyArgHasAwait=$anyArgHasAwait, fnHasAwait=$fnHasAwait")
          if anyArgHasAwait || fnHasAwait then
            // Process arguments that contain await
            val processedArgs = args.map { arg =>
              if containsAwait(arg) then transformTopLevel(arg, isReturnPosition = false)
              else arg
            }
            // Process fn if it contains await
            val processedFn = if fnHasAwait then transformTopLevel(fn, isReturnPosition = false) else fn
            val processedApp = Apply.copy(app)(processedFn, processedArgs)
            if isReturnPosition then processedApp
            else wrapWithActivity(processedApp)
          else if isReturnPosition then term
          else wrapWithActivity(term)

        // Select - check if qualifier contains await
        case sel @ Select(qualifier, name) =>
          if containsAwait(qualifier) then
            val processedQualifier = transformTopLevel(qualifier, isReturnPosition = false)
            val processedSel = Select.copy(sel)(processedQualifier, name)
            if isReturnPosition then processedSel
            else wrapWithActivity(processedSel)
          else if isReturnPosition then term
          else wrapWithActivity(term)

        // TypeApply - check if fn contains await
        case typeApp @ TypeApply(fn, typeArgs) =>
          if containsAwait(fn) then
            val processedFn = transformTopLevel(fn, isReturnPosition = false)
            val processedTypeApp = TypeApply.copy(typeApp)(processedFn, typeArgs)
            if isReturnPosition then processedTypeApp
            else wrapWithActivity(processedTypeApp)
          else if isReturnPosition then term
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

    val transformed = transformTopLevel(bodyAfterFutureTransform, isReturnPosition = true)
    transformed.asExprOf[A]
