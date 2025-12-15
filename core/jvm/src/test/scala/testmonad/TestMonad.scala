package testmonad

import cps.*

/**
 * Test monad defined in its own package.
 * Preprocessor given is in companion object.
 */
enum TestMonad[+A]:
  case Pure(value: A)
  case FlatMap[B, A](fa: TestMonad[B], f: B => TestMonad[A]) extends TestMonad[A]
  case Wrapped[A](value: A, marker: String) extends TestMonad[A]

  def map[B](f: A => B): TestMonad[B] = FlatMap(this, (a: A) => TestMonad.Pure(f(a)))
  def flatMap[B](f: A => TestMonad[B]): TestMonad[B] = FlatMap(this, f)

object TestMonad:
  def pure[A](a: A): TestMonad[A] = Pure(a)

  // Context for async/await
  class TestContext extends CpsTryMonadContext[[A] =>> TestMonad[A]]:
    def monad = testCpsTryMonad

    // Method that preprocessor will call - marks value as "preprocessed"
    def wrapValue[A](a: A): TestMonad[A] =
      TestMonad.Wrapped(a, "PREPROCESSED")

  // CpsTryMonad instance
  given testCpsTryMonad: CpsTryMonad[[A] =>> TestMonad[A]] with
    type Context = TestContext
    def pure[A](a: A): TestMonad[A] = TestMonad.pure(a)
    def map[A, B](fa: TestMonad[A])(f: A => B): TestMonad[B] = fa.map(f)
    def flatMap[A, B](fa: TestMonad[A])(f: A => TestMonad[B]): TestMonad[B] = fa.flatMap(f)
    def error[A](e: Throwable): TestMonad[A] = throw e
    def flatMapTry[A, B](fa: TestMonad[A])(f: scala.util.Try[A] => TestMonad[B]): TestMonad[B] =
      flatMap(fa)(a => f(scala.util.Success(a)))
    def apply[A](op: Context => TestMonad[A]): TestMonad[A] = op(new TestContext)

  /**
   * Preprocessor in companion object.
   * Transforms: val x = expr → val x = await(ctx.wrapValue(expr))
   */
  given testPreprocessor: CpsPreprocessor[TestMonad, TestContext] with
    transparent inline def preprocess[A](inline body: A, inline ctx: TestContext): A =
      ${ TestPreprocessorMacro.impl[A, TestContext]('body, 'ctx) }
