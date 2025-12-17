

Now we have many race conditions in WorkflowServiceImp in JVM

Thread 1
  - enterSendEvent  (event e)

Thread2
  - enter handleSuspended  (workflow w, start waiting for event e)


Thread1
  - collectWaitingWorkflows (w is absent there)

Thread2
  - call checkPendingEvents (return None)

Thread1
  - call storage.savePendingEvent(name, eventId, event, timestamp) 

Thread2
  - update state

---------

 So, we have event e in pending events, w not read it.

The probem:
  - we need a systematical way to prevent such type of errors. 




