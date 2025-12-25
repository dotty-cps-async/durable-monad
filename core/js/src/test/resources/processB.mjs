#!/usr/bin/env node
/**
 * Process B: Restores workflow from storage and continues after providing event.
 *
 * Usage: node processB.mjs <compiledJsPath> <storageDir> <workflowId> <eventValue>
 *
 * This script is spawned as a separate Node.js process by CrossProcessTestJS.
 * It loads the compiled ScalaJS test code and calls the runProcessB entry point.
 */

const args = process.argv.slice(2);

if (args.length < 4) {
  console.error('Usage: node processB.mjs <compiledJsPath> <storageDir> <workflowId> <eventValue>');
  process.exit(1);
}

const [compiledJsPath, storageDir, workflowId, eventValue] = args;

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

    // Call the exported runProcessB function
    if (typeof module.runProcessB !== 'function') {
      console.error('[ProcessB] runProcessB function not found in module');
      console.error('[ProcessB] Available exports:', Object.keys(module));
      process.exit(1);
    }

    const result = await module.runProcessB(storageDir, workflowId, eventValue);

    if (result.success) {
      console.log(`[ProcessB] Success: ${result.message}`);
      process.exit(0);
    } else {
      console.error(`[ProcessB] Failed: ${result.message}`);
      process.exit(1);
    }
  } catch (error) {
    console.error('[ProcessB] Error:', error.message);
    console.error(error.stack);
    process.exit(1);
  }
}

main();
