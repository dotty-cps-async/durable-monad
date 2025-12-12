// Test: Full capture/restore cycle with shutdown and resume
import { LocalsCapture } from './capture-restore.mjs';

console.log('=== Testing Capture/Restore Module ===\n');

// --- FIRST RUN: Execute and capture, then shutdown ---

let savedState = null;

{
  console.log('--- FIRST RUN ---');
  const capture = new LocalsCapture();
  capture.enable();

  const snapshots = [];
  let awaitIndex = 0;
  let shutdownRequested = false;

  capture.onPause((event) => {
    const frame = event.params.callFrames[0];
    const currentAwait = awaitIndex++;

    console.log(`\n  [await #${currentAwait}] Paused at ${frame.functionName}`);

    capture.captureLocals(frame, (err, locals) => {
      if (err) {
        console.log('    Error:', err);
        capture.resume();
        return;
      }

      // Save snapshot
      snapshots.push({
        await: currentAwait,
        locals: { ...locals }
      });

      console.log('    Captured locals:', Object.keys(locals).map(k => `${k}=${JSON.stringify(locals[k].value)}`).join(', '));

      // Simulate shutdown after await #1
      if (currentAwait === 1) {
        console.log('    >>> SHUTDOWN requested <<<');
        shutdownRequested = true;
      }

      capture.resume();
    });
  });

  // Workflow function
  function workflow() {
    let a = 10;
    let b = 20;

    debugger; // await #0

    let c = a + b;  // c = 30

    debugger; // await #1 - shutdown here

    if (shutdownRequested) {
      // Early exit on shutdown
      return { status: 'shutdown', partial: { a, b, c } };
    }

    let d = c * 2;  // d = 60

    debugger; // await #2

    return { status: 'complete', result: d };
  }

  const result = workflow();
  console.log('\n  First run result:', result);

  if (result.status === 'shutdown') {
    savedState = {
      awaitIndex: 2, // Resume from await #2
      snapshots: snapshots
    };
    console.log('\n  Saved state:', JSON.stringify(savedState, null, 2));
  }

  capture.disconnect();
}

// --- RESUME RUN: Restore locals and continue ---

if (savedState) {
  console.log('\n--- RESUME RUN ---');
  const capture = new LocalsCapture();
  capture.enable();

  let awaitIndex = 0;

  capture.onPause((event) => {
    const frame = event.params.callFrames[0];
    const currentAwait = awaitIndex++;

    console.log(`\n  [await #${currentAwait}] Paused at ${frame.functionName}`);

    if (currentAwait < savedState.awaitIndex) {
      // RESTORE: Inject saved locals
      const snapshot = savedState.snapshots[currentAwait];
      console.log('    RESTORE mode - injecting:', Object.keys(snapshot.locals).join(', '));

      // Convert to simple values for restoration
      const toRestore = {};
      for (const [name, info] of Object.entries(snapshot.locals)) {
        toRestore[name] = info;
      }

      capture.restoreLocals(frame, toRestore, (err) => {
        if (err) console.log('    Restore errors:', err);
        else console.log('    Locals restored successfully');
        capture.resume();
      });
    } else {
      // Normal capture
      capture.captureLocals(frame, (err, locals) => {
        console.log('    CAPTURE mode - locals:', Object.keys(locals).map(k => `${k}=${JSON.stringify(locals[k].value)}`).join(', '));
        capture.resume();
      });
    }
  });

  // Same workflow - but locals will be restored at early debugger points
  function workflow() {
    let a = 10;
    let b = 20;

    debugger; // await #0 - will restore a, b from saved state

    let c = a + b;  // If a,b restored correctly, c should still be 30

    debugger; // await #1 - will restore

    // No shutdown check on resume - continue execution
    let d = c * 2;  // d = 60

    debugger; // await #2 - normal capture

    return { status: 'complete', result: d };
  }

  const result = workflow();
  console.log('\n  Resume run result:', result);
  console.log(`\n=== Test ${result.status === 'complete' && result.result === 60 ? 'PASSED ✓' : 'FAILED ✗'} ===`);

  capture.disconnect();
}
