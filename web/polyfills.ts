// These are used in the pinned runtime's session, inbox, token meter and tool paths.
// Import just the methods it calls, not the whole ECMAScript library.
import 'core-js/modules/es.array.at.js';
import 'core-js/modules/es.array.find-last.js';
import 'core-js/modules/es.array.to-reversed.js';
import 'core-js/modules/es.array.to-spliced.js';
import 'core-js/modules/es.array.with.js';
import 'core-js/modules/es.object.has-own.js';
import 'core-js/modules/es.aggregate-error.js';
import 'core-js/modules/es.promise.with-resolvers.js';

if (typeof globalThis.structuredClone !== 'function') {
  // Session snapshots must preserve identity, cycles, and undefined. JSON is not a clone.
  require('core-js/modules/web.structured-clone.js');
}

/** Shared resource-management symbols for WebViews predating ES2025. */
for (const key of ['dispose', 'asyncDispose']) {
  if (!(Symbol as any)[key])
    Object.defineProperty(Symbol, key, { value: Symbol.for(`Symbol.${key}`) });
}

function method(target: object, key: string, value: Function) {
  Object.defineProperty(target, key, { configurable: true, writable: true, value });
}

// IDs can cross the WebView/native boundary. Always use platform entropy.
const crypto = globalThis.crypto;
if (crypto?.getRandomValues && !crypto.randomUUID && globalThis.isSecureContext !== false) {
  method(crypto, 'randomUUID', () => {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, (n) => n.toString(16).padStart(2, '0')).join('');
    return [
      hex.slice(0, 8),
      hex.slice(8, 12),
      hex.slice(12, 16),
      hex.slice(16, 20),
      hex.slice(20),
    ].join('-');
  });
}

if (typeof AbortSignal !== 'undefined' && typeof AbortController !== 'undefined') {
  const prototype = AbortSignal.prototype;
  if (!('reason' in prototype)) {
    const reasons = new WeakMap<AbortSignal, unknown>();
    const defaultReason = () => new DOMException('The operation was aborted.', 'AbortError');
    const abort = AbortController.prototype.abort;
    method(AbortController.prototype, 'abort', function (this: AbortController, reason?: unknown) {
      if (!this.signal.aborted)
        reasons.set(this.signal, reason === undefined ? defaultReason() : reason);
      return abort.call(this);
    });
    Object.defineProperty(prototype, 'reason', {
      configurable: true,
      get(this: AbortSignal) {
        if (!this.aborted) return undefined;
        if (!reasons.has(this)) reasons.set(this, defaultReason());
        return reasons.get(this);
      },
    });
  }
  if (typeof prototype.throwIfAborted !== 'function') {
    method(prototype, 'throwIfAborted', function (this: AbortSignal) {
      if (this.aborted) throw this.reason;
    });
  }
  if (typeof AbortSignal.any !== 'function' && typeof WeakRef === 'function') {
    // One listener per source even over a long-lived session. Unused combined signals can be
    // collected without waiting for the owner session to end.
    const followers = new WeakMap<AbortSignal, Set<WeakRef<AbortController>>>();
    // Retain the upstream chain while the result is in use, including an unnamed
    // intermediate signal in any([any([owner])]).
    const owners = new WeakMap<
      AbortSignal,
      { controller: AbortController; sources: Set<AbortSignal> }
    >();
    function propagate(this: AbortSignal) {
      const pending = followers.get(this);
      followers.delete(this);
      for (const ref of pending ?? []) {
        const controller = ref.deref();
        if (!controller) continue;
        for (const source of owners.get(controller.signal)?.sources ?? []) {
          const siblings = followers.get(source);
          if (siblings?.delete(ref) && !siblings.size) {
            source.removeEventListener('abort', propagate);
            followers.delete(source);
          }
        }
        owners.delete(controller.signal);
        controller.abort(this.reason);
      }
    }
    method(AbortSignal, 'any', (signals: Iterable<AbortSignal>) => {
      const sources = new Set(signals);
      const controller = new AbortController();
      for (const source of sources) {
        if (!(source instanceof AbortSignal)) throw new TypeError('Expected an AbortSignal');
      }
      for (const source of sources) {
        if (source.aborted) {
          controller.abort(source.reason);
          return controller.signal;
        }
      }
      owners.set(controller.signal, { controller, sources });
      const ref = new WeakRef(controller);
      for (const source of sources) {
        let pending = followers.get(source);
        if (!pending) {
          followers.set(source, (pending = new Set()));
          source.addEventListener('abort', propagate, { once: true });
        }
        for (const old of pending) {
          const live = old.deref();
          if (!live || live.signal.aborted) pending.delete(old);
        }
        pending.add(ref);
      }
      return controller.signal;
    });
  }
}

export function assertKernelPlatform() {
  if (
    typeof WeakRef !== 'function' ||
    typeof AbortController !== 'function' ||
    typeof AbortSignal !== 'function' ||
    typeof crypto?.randomUUID !== 'function'
  ) {
    throw new Error(
      '此 WebView 缺少 Work 会话所需的系统能力。请更新 Android System WebView 后重试。',
    );
  }
}
