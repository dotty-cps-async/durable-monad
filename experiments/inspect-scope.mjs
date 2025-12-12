// Experiment: Capture local variables via V8 Inspector Protocol
import inspector from 'node:inspector/promises';

const session = new inspector.Session();
session.connect();

await session.post('Debugger.enable');
await session.post('Runtime.enable');

// Store captured scopes
let capturedScopes = null;

// Listen for pause events - SYNCHRONOUS to avoid timing issues
session.on('Debugger.paused', (event) => {
  console.log('\n=== Debugger Paused ===');

  const callFrames = event.params.callFrames;
  capturedScopes = [];

  // Use synchronous session (not promises) to avoid race
  const syncSession = new inspector.Session();
  syncSession.connect();

  for (const frame of callFrames) {
    console.log(`\nFunction: ${frame.functionName || '(anonymous)'}`);

    const frameScopes = [];

    for (const scope of frame.scopeChain) {
      if (scope.type === 'local' || scope.type === 'closure') {
        console.log(`  Scope type: ${scope.type}`);

        // Synchronous call
        syncSession.post('Runtime.getProperties', {
          objectId: scope.object.objectId,
          ownProperties: true
        }, (err, props) => {
          if (err) return;
          const scopeVars = {};
          for (const prop of props.result) {
            if (prop.value) {
              scopeVars[prop.name] = {
                type: prop.value.type,
                value: prop.value.value ?? prop.value.description
              };
              console.log(`    ${prop.name}: ${prop.value.type} = ${JSON.stringify(prop.value.value) ?? prop.value.description}`);
            }
          }
          frameScopes.push({ type: scope.type, variables: scopeVars });
        });
      }
    }
    capturedScopes.push({ function: frame.functionName, scopes: frameScopes });
  }

  syncSession.disconnect();

  // Resume execution synchronously
  session.post('Debugger.resume');
});

// Test function with local variables
function testWorkflow() {
  const step1Result = 42;
  const items = [1, 2, 3];

  function innerComputation() {
    const innerVar = step1Result * 2;
    const message = "hello";

    // Programmatically trigger a pause
    debugger;  // <-- This pauses execution

    return innerVar;
  }

  return innerComputation();
}

console.log('Running test workflow...');
const result = testWorkflow();

// Small delay for async logging
await new Promise(r => setTimeout(r, 100));

console.log(`\nResult: ${result}`);
console.log('\n=== Captured Scopes (JSON) ===');
console.log(JSON.stringify(capturedScopes, null, 2));

session.disconnect();
