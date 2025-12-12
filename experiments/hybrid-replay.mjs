// Hybrid Replay: Skip computation, restore locals from saved state
import inspector from 'node:inspector/promises';

class HybridDurableContext {
  constructor(savedState = null) {
    this.awaitIndex = 0;
    this.snapshots = savedState?.snapshots || [];
    this.resumeFromAwait = savedState?.awaitIndex || 0;
    this.isShutdown = false;

    // Inspector for capturing locals
    this.session = new inspector.Session();
    this.session.connect();
  }

  async init() {
    await this.session.post('Debugger.enable');
    await this.session.post('Runtime.enable');
  }

  // Called at each await point
  async checkpoint(computeFn) {
    const currentAwait = this.awaitIndex++;

    if (currentAwait < this.resumeFromAwait) {
      // HYBRID: Skip computation, restore from snapshot
      const snapshot = this.snapshots[currentAwait];
      console.log(`  [await #${currentAwait}] SKIP - restoring locals:`, snapshot.locals);

      // In real implementation, we'd inject these locals into scope
      // For now, just return the cached result
      return snapshot.result;
    } else {
      // Actually execute
      console.log(`  [await #${currentAwait}] EXECUTE`);
      const result = await computeFn();

      // Capture locals (simplified - in real impl use V8 inspector)
      // For demo, computeFn returns {result, locals}
      const snapshot = {
        await: currentAwait,
        result: result.value,
        locals: result.locals
      };
      this.snapshots.push(snapshot);

      return result.value;
    }
  }

  shutdown() {
    this.isShutdown = true;
  }

  getState() {
    return {
      awaitIndex: this.awaitIndex,
      snapshots: this.snapshots
    };
  }

  close() {
    this.session.disconnect();
  }
}

// Simulated workflow - in real impl, this would be generated code
async function workflow(ctx) {
  console.log('\n--- Workflow Start ---');

  // Await #0
  const a = await ctx.checkpoint(async () => {
    const computed = 10 * 2;
    return { value: computed, locals: { a: computed } };
  });
  console.log(`  After await #0: a = ${a}`);

  // Await #1
  const b = await ctx.checkpoint(async () => {
    const computed = a + 5;
    return { value: computed, locals: { a, b: computed } };
  });
  console.log(`  After await #1: a = ${a}, b = ${b}`);

  // Simulate shutdown after await #1 on first run
  if (ctx.resumeFromAwait === 0 && ctx.awaitIndex === 2) {
    console.log('  [Simulating shutdown]');
    ctx.shutdown();
    return { status: 'shutdown', partial: { a, b } };
  }

  // Await #2
  const c = await ctx.checkpoint(async () => {
    const computed = a + b + 100;
    return { value: computed, locals: { a, b, c: computed } };
  });
  console.log(`  After await #2: a = ${a}, b = ${b}, c = ${c}`);

  return { status: 'completed', result: c };
}

// === First Run ===
console.log('========== FIRST RUN ==========');
let ctx = new HybridDurableContext();
await ctx.init();

let result = await workflow(ctx);
console.log('\nResult:', result);

let savedState = null;
if (ctx.isShutdown) {
  savedState = ctx.getState();
  console.log('\nSaved state:', JSON.stringify(savedState, null, 2));
}
ctx.close();

// === Resume Run ===
if (savedState) {
  console.log('\n========== RESUME RUN ==========');
  ctx = new HybridDurableContext(savedState);
  await ctx.init();

  result = await workflow(ctx);
  console.log('\nResult:', result);
  console.log('Final state:', JSON.stringify(ctx.getState(), null, 2));
  ctx.close();
}
