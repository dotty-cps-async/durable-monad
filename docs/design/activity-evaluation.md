

Activity handling should have next algorithm

1. run and catch exception or Failur of returning Future.  
2. if we have failure or exception and its recoverable exception (we should have something like configurable policy) and numner of retries is not max -- log it retry exception with randomized (expotential or fibonacchy-growing) delay.
  (Naybe in DurableSnaphot we shpudl add fields for currently rerunning exception and or reuse Suspension or create new Suspension type)
  If exception is not recoverable or max retrues is reached - fail workflow.
3. Store the result. Is storing failed -- fail workflow.
4. Should think about how to configure logger and policy in runner.

Maybe create a file ActivityRunner, if we think that amount of code will be big.

