// Thin wrapper around derivation.js for use in a Web Worker.
// Only posts messages; never calls argon2id directly.
// Handles {password, salt, params} -> {kek, verifier} or {error}.
// Unknown message types are answered with an error.

import { deriveKeys } from './derivation.js';

self.onmessage = async function (event) {
  const data = event.data;
  if (!data || typeof data !== 'object' || data.type !== 'derive') {
    self.postMessage({ error: 'unknown message type' });
    return;
  }
  const { password, salt, params } = data;
  try {
    const { kek, verifier } = await deriveKeys(password, salt, params);
    self.postMessage({ kek, verifier });
  } catch (err) {
    self.postMessage({ error: err.message || String(err) });
  }
};
