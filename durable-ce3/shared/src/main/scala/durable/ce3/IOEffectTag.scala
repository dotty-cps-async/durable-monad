package durable.ce3

import scala.concurrent.Future

import cats.effect.IO
import cps.*
import cps.monads.CpsIdentity

import durable.runtime.EffectTag

/**
 * CpsMonadConversion from CpsIdentity to IO.
 * Lifts pure values into IO.pure.
 */
given cpsIdentityToIOConversion: CpsMonadConversion[CpsIdentity, IO] with
  def apply[T](ft: T): IO[T] = IO.pure(ft)

/**
 * CpsMonadConversion from Future to IO.
 * Uses IO.fromFuture to lift Future into IO.
 */
given futureToIOConversion: CpsMonadConversion[Future, IO] with
  def apply[T](ft: Future[T]): IO[T] = IO.fromFuture(IO.pure(ft))


/**
 * EffectTag for cats-effect IO.
 *
 * IO is the "biggest" effect in the hierarchy:
 * - Can accept CpsIdentity (via IO.pure)
 * - Can accept Future (via IO.fromFuture)
 * - Can accept itself (identity)
 *
 * This enables dynamic runner switching:
 * - FutureRunner encounters Activity[IO] → switches to IORunner
 * - IORunner can handle all activities (IO, Future, CpsIdentity)
 */
object IOEffectTag:

  given ioTag: EffectTag[IO] with
    def conversionFrom[F[_]](source: EffectTag[F]): Option[CpsMonadConversion[F, IO]] =
      source match
        case _: EffectTag.cpsIdentityTag.type =>
          Some(cpsIdentityToIOConversion.asInstanceOf[CpsMonadConversion[F, IO]])
        case _: EffectTag.futureTag.type =>
          Some(futureToIOConversion.asInstanceOf[CpsMonadConversion[F, IO]])
        case _: ioTag.type =>
          Some(CpsMonadConversion.identityConversion[IO].asInstanceOf[CpsMonadConversion[F, IO]])
        case _ =>
          None
