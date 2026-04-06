/**
 * bionic-bypass.js  — Android compatibility shim for OpenClaw
 *
 * Injected first via: NODE_OPTIONS=--require /path/to/bionic-bypass.js
 *
 * Fixes applied:
 *  1. os.networkInterfaces() crash on Bionic libc
 *  2. process.platform reported as 'android' → patched to 'linux'
 *  3. os.tmpdir() / os.homedir() respect TMPDIR / HOME env vars
 *  4. koffi native addon graceful fallback if glibc binary fails on Bionic
 *  5. child_process.spawn / exec shell path patched to /system/bin/sh
 */

'use strict';

// ── 1 & 2: Platform + networkInterfaces ─────────────────────────

const os = require('os');

const _realNetworkInterfaces = os.networkInterfaces;
os.networkInterfaces = function () {
  try {
    const ifaces = _realNetworkInterfaces.call(os);
    if (ifaces && Object.keys(ifaces).length > 0) return ifaces;
  } catch (e) { /* Bionic crash — fall through */ }
  return {
    lo: [{
      address: '127.0.0.1', netmask: '255.0.0.0',
      family: 'IPv4', mac: '00:00:00:00:00:00',
      internal: true, cidr: '127.0.0.1/8'
    }]
  };
};

if (process.platform === 'android') {
  Object.defineProperty(process, 'platform', { get: () => 'linux', configurable: true });
}

const _realPlatform = os.platform;
os.platform = () => { const p = _realPlatform.call(os); return p === 'android' ? 'linux' : p; };

// ── 3: tmpdir / homedir ──────────────────────────────────────────

const _realTmpdir  = os.tmpdir;
const _realHomedir = os.homedir;
os.tmpdir  = () => process.env.TMPDIR  || process.env.XDG_RUNTIME_DIR || _realTmpdir.call(os);
os.homedir = () => process.env.HOME    || _realHomedir.call(os);

// ── 4: koffi native addon graceful fallback ──────────────────────
// koffi ships linux_arm64 (glibc) binary. Android uses Bionic.
// On Android API 31+ many glibc symbols exist; attempt load but gracefully
// degrade if it fails so the rest of openclaw still works.
//
// If the load fails, we install a Proxy that throws a descriptive error
// instead of crashing the entire process.

const Module = require('module');
const _realLoad = Module._load;
Module._load = function (request, parent, isMain) {
  // Intercept require('koffi') and require('.../koffi.node')
  if (request === 'koffi' || request.endsWith('/koffi.node') || request.endsWith('\\koffi.node')) {
    try {
      return _realLoad.apply(this, arguments);
    } catch (e) {
      if (e.code === 'ERR_DLOPEN_FAILED' || e.message.includes('dlopen') ||
          e.message.includes('cannot open') || e.message.includes('ENOENT')) {
        console.warn('[bionic-bypass] koffi native load failed:', e.message);
        console.warn('[bionic-bypass] Returning stub — FFI device features disabled.');
        // Return a stub proxy that fails gracefully
        return new Proxy({}, {
          get(_, prop) {
            if (prop === '__koffi_stub__') return true;
            return (...args) => { throw new Error('[Android] koffi native addon unavailable. Compile koffi.node with NDK for full FFI support.'); };
          }
        });
      }
      throw e; // re-throw unexpected errors
    }
  }
  return _realLoad.apply(this, arguments);
};

// ── 5: child_process shell path ──────────────────────────────────
// Replace /bin/sh with /system/bin/sh in child_process defaults

const cp = require('child_process');
const _realSpawn = cp.spawn;
const _realExec  = cp.exec;
const _realExecSync = cp.execSync;
const ANDROID_SH = '/system/bin/sh';

cp.exec = function (command, options, callback) {
  if (typeof options === 'object' && options !== null) {
    options = { ...options, shell: ANDROID_SH };
  } else if (typeof options === 'function') {
    callback = options;
    options  = { shell: ANDROID_SH };
  } else {
    options = options || {};
    options.shell = ANDROID_SH;
  }
  return _realExec.call(cp, command, options, callback);
};

cp.execSync = function (command, options) {
  options = { ...(options || {}), shell: ANDROID_SH };
  return _realExecSync.call(cp, command, options);
};

// ── Suppress noisy Node.js warnings ─────────────────────────────
process.removeAllListeners('warning');
process.on('warning', (w) => {
  if (w.name === 'ExperimentalWarning' || w.name === 'DeprecationWarning') return;
  process.stderr.write(`[warn] ${w.name}: ${w.message}\n`);
});

console.log('[bionic-bypass] Android compatibility patches v2 applied ✓');
