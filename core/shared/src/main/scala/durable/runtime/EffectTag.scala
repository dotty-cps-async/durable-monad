package durable.runtime

import scala.concurrent.Future
import cps.*
import cps.monads.{CpsIdentity, given}

/**
 * CpsIdentity -> Future conversion.
 * Lifts a pure value into a successful Future.
 * Note: Future.successful is synchronous and doesn't need ExecutionContext.
 */
given cpsIdentityToFutureConversion: CpsMonadConversion[CpsIdentity, Future] with
  def apply[T](ft: T): Future[T] = Future.successful(ft)

/**
 * Effect tag for G[_] that can compute conversions from source effects.
 * Target effect knows what sources it can accept.
 *
 * Key insight: The target effect defines what sources it can accept. This enables clean extension:
 * - Adding IO only requires defining IOEffectTag - no modifications to idTag or futureTag
 * - Each effect module is self-contained
 *
 * Uses method instead of Map to support effect systems (Kyo, Eff, etc.)
 * where effect sets are dynamic/compound.
 */
trait EffectTag[G[_]]:
  /**
   * Get conversion from source effect F to this effect G.
   * Returns None if conversion not possible.
   *
   * Implementation pattern matches on source tag to determine F and provide conversion.
   */
  def conversionFrom[F[_]](source: EffectTag[F]): Option[CpsMonadConversion[F, G]]

  /**
   * Can this effect accept values from source?
   * Typed version - preserves source type parameter.
   */
  def canAccept[F[_]](source: EffectTag[F]): Boolean =
    conversionFrom[F](source).isDefined

  /**
   * Can this effect accept values from source (existential version)?
   * Used when source type is erased (e.g., from Set[EffectTag[?]]).
   *
   * Implementation delegates to conversionFrom which does pattern matching
   * on the concrete tag type, so the type parameter erasure is safe.
   */
  def canAcceptErased(source: EffectTag[?]): Boolean =
    source match
      case tag: EffectTag[f] => conversionFrom[f](tag).isDefined

  /**
   * Can this effect (as target) handle all the given source effects?
   *
   * For simple effects (Id, Future, IO): checks each source individually.
   * For effect systems (Kyo, Eff): can override to compute union of effect sets.
   *
   * @param sources Set of source effect tags from activities in a workflow
   * @return true if this effect can be a target for all sources
   */
  def canAcceptAll(sources: Set[EffectTag[?]]): Boolean =
    sources.forall(canAcceptErased)


object EffectTag:

  /**
   * EffectTag for CpsIdentity (synchronous, pure values).
   * CpsIdentity can only accept CpsIdentity (cannot convert Future -> CpsIdentity).
   */
  given cpsIdentityTag: EffectTag[CpsIdentity] with
    def conversionFrom[F[_]](source: EffectTag[F]): Option[CpsMonadConversion[F, CpsIdentity]] =
      source match
        case _: cpsIdentityTag.type =>
          // CpsIdentity -> CpsIdentity: identity conversion
          Some(CpsMonadConversion.identityConversion[CpsIdentity].asInstanceOf[CpsMonadConversion[F, CpsIdentity]])
        case _ =>
          // Cannot accept Future or other effects
          None

  /**
   * EffectTag for Future (standard Scala async).
   * Future accepts CpsIdentity and Future.
   *
   * Note: Future cannot accept IO (would require unsafe conversion).
   * IO -> Future conversion is handled by IORunner, not FutureRunner.
   *
   * No ExecutionContext needed here - Future.successful is synchronous,
   * and Future -> Future is identity.
   */
  given futureTag: EffectTag[Future] with
    def conversionFrom[F[_]](source: EffectTag[F]): Option[CpsMonadConversion[F, Future]] =
      source match
        case _: cpsIdentityTag.type =>
          // CpsIdentity -> Future: lift pure value into Future (no EC needed)
          Some(cpsIdentityToFutureConversion.asInstanceOf[CpsMonadConversion[F, Future]])
        case _: futureTag.type =>
          // Future -> Future: identity conversion
          Some(CpsMonadConversion.identityConversion[Future].asInstanceOf[CpsMonadConversion[F, Future]])
        case _ =>
          // Cannot accept IO or other effects
          None

  /**
   * Find a target effect tag that can handle all source tags.
   *
   * Searches through candidate tags to find one where canAcceptAll(sources) is true.
   * For standard effects, the hierarchy is: IO > Future > Id
   *
   * @param sources Set of effect tags collected from workflow activities
   * @param candidates Available target effect tags to consider (ordered by preference)
   * @return Some(targetTag) if found, None if no compatible target exists
   */
  def findTarget(
    sources: Set[EffectTag[?]],
    candidates: Seq[EffectTag[?]]
  ): Option[EffectTag[?]] =
    candidates.find(_.canAcceptAll(sources))
