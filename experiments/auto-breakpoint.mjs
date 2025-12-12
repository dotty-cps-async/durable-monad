// Experiment: Set breakpoints programmatically using debugger statement
import inspector from 'node:inspector/promises';

const session = new inspector.Session();
session.connect();

await session.post('Debugger.enable');
await session.post('Runtime.enable');

// Capture state at each pause
const captures = [];

session.on('Debugger.paused', async (event) => {
  const frame = event.params.callFrames[0];
  console.log(`\n>>> Paused at: ${frame.functionName}, line ${frame.location.lineNumber}`);

  const capture = { function: frame.functionName, locals: {} };

  // Get local variables
  for (const scope of frame.scopeChain) {
    if (scope.type === 'local') {
      const props = await session.post('Runtime.getProperties', {
        objectId: scope.object.objectId,
        ownProperties: true
      });
      for (const prop of props.result) {
        if (prop.value && prop.value.type !== 'function') {
          capture.locals[prop.name] = prop.value.value ?? prop.value.description;
          console.log(`  ${prop.name} = ${JSON.stringify(prop.value.value)}`);
        }
      }
    }
  }

  captures.push(capture);
  await session.post('Debugger.resume');
});

// Function with manual debugger statements at "await points"
function workflow() {
  let counter = 0;

  // Step 1
  counter += 10;
  debugger;  // Checkpoint 1

  // Step 2
  counter += 20;
  debugger;  // Checkpoint 2

  // Step 3
  counter += 30;
  debugger;  // Checkpoint 3

  return counter;
}

console.log('--- Running workflow with debugger checkpoints ---');
const result = workflow();

await new Promise(r => setTimeout(r, 50)); // Let async complete

console.log(`\nFinal result: ${result}`);
console.log('\n=== All captured states ===');
console.log(JSON.stringify(captures, null, 2));

session.disconnect();
