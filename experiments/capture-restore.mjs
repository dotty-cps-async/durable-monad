// Capture/Restore Module for V8 Inspector-based local variable management
import inspector from 'node:inspector';

export class LocalsCapture {
  constructor() {
    this.session = new inspector.Session();
    this.session.connect();
    this.enabled = false;
    this.pauseHandler = null;
  }

  async enable() {
    if (this.enabled) return;
    this.session.post('Debugger.enable');
    this.session.post('Runtime.enable');
    this.enabled = true;
  }

  // Capture all local variables from the paused frame
  captureLocals(frame, callback) {
    const localScope = frame.scopeChain.find(s => s.type === 'local');
    if (!localScope) {
      callback(null, {});
      return;
    }

    this.session.post('Runtime.getProperties', {
      objectId: localScope.object.objectId,
      ownProperties: true
    }, (err, props) => {
      if (err) {
        callback(err, null);
        return;
      }

      const locals = {};
      for (const prop of props.result) {
        if (prop.value && prop.value.type !== 'function') {
          // Store value and type info for proper restoration
          locals[prop.name] = {
            value: prop.value.value,
            type: prop.value.type,
            subtype: prop.value.subtype,
            description: prop.value.description
          };
        }
      }
      callback(null, locals);
    });
  }

  // Restore local variables to the paused frame
  restoreLocals(frame, savedLocals, callback) {
    const names = Object.keys(savedLocals);
    if (names.length === 0) {
      callback(null);
      return;
    }

    let completed = 0;
    let errors = [];

    for (const name of names) {
      const saved = savedLocals[name];

      // Convert saved value to Chrome DevTools Protocol format
      let newValue;
      if (saved.type === 'undefined') {
        newValue = { unserializableValue: 'undefined' };
      } else if (saved.value === null) {
        newValue = { value: null };
      } else if (saved.type === 'number' && !Number.isFinite(saved.value)) {
        newValue = { unserializableValue: String(saved.value) };
      } else {
        newValue = { value: saved.value };
      }

      this.session.post('Debugger.setVariableValue', {
        scopeNumber: 0,
        variableName: name,
        newValue: newValue,
        callFrameId: frame.callFrameId
      }, (err) => {
        if (err) {
          errors.push({ name, error: err.message });
        }
        completed++;
        if (completed === names.length) {
          callback(errors.length > 0 ? errors : null);
        }
      });
    }
  }

  // Set up a handler for pause events
  onPause(handler) {
    if (this.pauseHandler) {
      this.session.removeListener('Debugger.paused', this.pauseHandler);
    }
    this.pauseHandler = handler;
    this.session.on('Debugger.paused', handler);
  }

  // Resume execution
  resume() {
    this.session.post('Debugger.resume');
  }

  disconnect() {
    this.session.disconnect();
  }
}

// Simplified API for common use case
export function createDurableCapture() {
  const capture = new LocalsCapture();
  const snapshots = [];
  let awaitIndex = 0;
  let resumeFromIndex = 0;
  let isResuming = false;

  return {
    async init(savedState = null) {
      await capture.enable();
      if (savedState) {
        snapshots.push(...savedState.snapshots);
        resumeFromIndex = savedState.awaitIndex;
        isResuming = true;
      }
    },

    setupCheckpointHandler(getResult) {
      capture.onPause((event) => {
        const frame = event.params.callFrames[0];
        const currentAwait = awaitIndex++;

        if (isResuming && currentAwait < resumeFromIndex) {
          // Restore mode: inject saved locals
          const saved = snapshots[currentAwait];
          console.log(`  [await #${currentAwait}] RESTORE from snapshot`);

          capture.restoreLocals(frame, saved.locals, (err) => {
            if (err) console.log('    Restore errors:', err);
            capture.resume();
          });
        } else {
          // Capture mode: save current locals
          console.log(`  [await #${currentAwait}] CAPTURE`);

          capture.captureLocals(frame, (err, locals) => {
            if (err) {
              console.log('    Capture error:', err);
              capture.resume();
              return;
            }

            const result = getResult ? getResult() : undefined;
            snapshots.push({
              await: currentAwait,
              locals: locals,
              result: result
            });
            console.log('    Captured:', Object.keys(locals).join(', '));
            capture.resume();
          });
        }
      });
    },

    getState() {
      return {
        awaitIndex,
        snapshots
      };
    },

    close() {
      capture.disconnect();
    }
  };
}
