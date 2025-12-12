// Test: Verify setVariableValue can modify locals and the change persists after resume
import inspector from 'node:inspector';

const session = new inspector.Session();
session.connect();

// Use callback-based API to ensure synchronous handling
session.post('Debugger.enable');
session.post('Runtime.enable');

let modificationResult = null;
let capturedLocals = null;

session.on('Debugger.paused', (event) => {
  const frame = event.params.callFrames[0];
  console.log(`\n>>> Paused in: ${frame.functionName}`);
  console.log(`    Location: line ${frame.location.lineNumber}`);

  // Get current local values
  const localScope = frame.scopeChain.find(s => s.type === 'local');
  if (localScope) {
    session.post('Runtime.getProperties', {
      objectId: localScope.object.objectId,
      ownProperties: true
    }, (err, props) => {
      if (err) {
        console.log('Error getting properties:', err);
        session.post('Debugger.resume');
        return;
      }

      console.log('\n    Current locals:');
      capturedLocals = {};
      for (const prop of props.result) {
        if (prop.value && prop.value.type !== 'function') {
          console.log(`      ${prop.name} = ${JSON.stringify(prop.value.value)}`);
          capturedLocals[prop.name] = prop.value.value;
        }
      }

      // Try to modify 'a' to 999
      console.log('\n    Attempting to set a = 999...');
      session.post('Debugger.setVariableValue', {
        scopeNumber: 0,  // local scope
        variableName: 'a',
        newValue: { value: 999 },
        callFrameId: frame.callFrameId
      }, (err2, result) => {
        if (err2) {
          console.log(`    setVariableValue failed: ${err2.message}`);
          modificationResult = false;
        } else {
          console.log('    setVariableValue succeeded!');
          modificationResult = true;
        }

        // Resume execution
        session.post('Debugger.resume');
      });
    });
  } else {
    session.post('Debugger.resume');
  }
});

// Test function
function testWorkflow() {
  let a = 10;
  let b = 20;

  console.log(`Before debugger: a=${a}, b=${b}`);

  debugger;  // Pause here, modify 'a' to 999

  console.log(`After debugger: a=${a}, b=${b}`);

  // This should show a=999 if setVariableValue worked
  return a + b;
}

console.log('=== Testing setVariableValue ===\n');

const result = testWorkflow();

console.log(`\nFunction returned: ${result}`);
console.log(`Expected: 1019 (999 + 20) if setVariableValue worked`);
console.log(`\n=== Test ${result === 1019 ? 'PASSED ✓' : 'FAILED ✗'} ===`);

session.disconnect();
