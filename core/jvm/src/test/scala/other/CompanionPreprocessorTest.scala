package other

import munit.FunSuite
import cps.*
import testmonad.{TestMonad, TestPreprocessorMacro}

/**
 * Test if preprocessor defined in TestMonad companion object
 * is found from external package (other).
 */
class CompanionPreprocessorTest extends FunSuite:

  test("preprocessor in companion object is found from external package") {
    // Reset flag
    TestPreprocessorMacro.preprocessorWasCalled = false

    // Import the monad's givens
    import TestMonad.given

    // This should find the preprocessor in TestMonad companion
    val result = async[TestMonad] {
      val x = 42
      x + 1
    }

    // Check if preprocessor was called
    assert(
      TestPreprocessorMacro.preprocessorWasCalled,
      "Preprocessor should have been called - companion object given should be found"
    )
  }
