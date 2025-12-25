#!/usr/bin/env node
/**
 * Process A: Starts workflow, runs until suspension, saves state.
 *
 * Usage: node processA.mjs <compiledJsPath> <storageDir> <workflowId> <input>
 *
 * This script is spawned as a separate Node.js process by CrossProcessTestJS.
 * It loads the compiled ScalaJS test code and calls the runProcessA entry point.
 */

const args = process.argv.slice(2);

if (args.length < 4) {
  console.error('Usage: node processA.mjs <compiledJsPath> <storageDir> <workflowId> <input>');
  process.exit(1);
}

const [compiledJsPath, storageDir, workflowId, input] = args;

// Mock scalajsCom to prevent test bridge initialization error
// The test bridge uses this for sbt communication, but we don't need it
globalThis.scalajsCom = {
  init: (onMessage) => {},
  send: (msg) => {}
};

// Dynamic import of the compiled ScalaJS module
async function main() {
  try {
    // Import the compiled ScalaJS module
    const module = await import(compiledJsPath);

    // Call the exported runProcessA function
    if (typeof module.runProcessA !== 'function') {
      console.error('[ProcessA] runProcessA function not found in module');
      console.error('[ProcessA] Available exports:', Object.keys(module));
      process.exit(1);
    }

    const result = await module.runProcessA(storageDir, workflowId, input);

    if (result.success) {
      console.log(`[ProcessA] Success: ${result.message}`);
      if (result.activityIndex !== undefined) {
        console.log(`[ProcessA] Activity index: ${result.activityIndex}`);
      }
      process.exit(0);
    } else {
      console.error(`[ProcessA] Failed: ${result.message}`);
      process.exit(1);
    }
  } catch (error) {
    console.error('[ProcessA] Error:', error.message);
    console.error(error.stack);
    process.exit(1);
  }
}

main();
