// Fixtures for the conversion adapter tests.
//
// The sync body bytes are built by hand in the exact key order so a test can vary
// one decodeBody rule at a time. The cipher is the real aesgcm module over a
// synthetic patterned data key -- a test key, not a real key. The fixed nonce
// is supplied only here, in tests, through the documented test hook.

import { createCipher } from '../../main/resources/web/js/aesgcm.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';

export const DEK = Uint8Array.from({ length: 32 }, (_, i) => 0xc0 + i);

// A client id is BUILT, never written out: the shape CLIENT_ID_RE accepts puts an
// 8-char hex run in its first group, and the source pin forbids hex runs in a scanned
// file. Sixteen bytes from a seed, cut into the 8-4-4-4-12 lowercase hex it wants.
function clientIdFrom(seed) {
  const bytes = Uint8Array.from({ length: 16 }, (_, i) => (seed + i) & 0xff);
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  const groups = [hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16),
    hex.substring(16, 20), hex.substring(20, 32)];
  return groups.join('-');
}

export const CLIENT_ID = clientIdFrom(0x10);
export const OTHER_CLIENT_ID = clientIdFrom(0x30);
export const KEY_VERSION = 2;

// AAD: the client id UTF-8, one LF byte, then the DECIMAL key version.
export function aadFor(clientId, keyVersion) {
  return new TextEncoder().encode(clientId + '\n' + String(keyVersion));
}

// The six members as [key, raw JSON value] pairs. Defaults are a valid server
// sync body; a test overrides exactly the member it is varying.
export function goodEntries(overrides = {}) {
  const text = overrides.text === undefined ? 'the words' : overrides.text;
  const source = overrides.source === undefined ? 'server' : overrides.source;
  const model = overrides.model === undefined ? 'test-model' : overrides.model;
  const duration = overrides.duration_ms === undefined ? '1200' : overrides.duration_ms;
  const created = overrides.created_at === undefined ? '2026-10-11T12:00:00.000Z' : overrides.created_at;
  return [
    ['v', '1'],
    ['text', JSON.stringify(text)],
    ['source', JSON.stringify(source)],
    ['model', JSON.stringify(model)],
    ['duration_ms', duration],
    ['created_at', JSON.stringify(created)],
  ];
}

// A body from ordered [key, raw value] entries.
export function bodyWith(entries) {
  const json = '{' + entries.map(([key, value]) => JSON.stringify(key) + ':' + value).join(',') + '}';
  return new TextEncoder().encode(json);
}

// A valid server body as UTF-8 bytes, with optional overrides.
export function bodyBytes(overrides = {}) {
  return bodyWith(goodEntries(overrides));
}

// Arbitrary bytes, for the refusals that are not bodies at all.
export function rawBytes(text) {
  return new TextEncoder().encode(text);
}

// A real cipher over the synthetic DEK. The nonce is fixed only in tests.
export function cipherWithFixedNonce(nonceByte = 0x11) {
  return createCipher(DEK, { random: (length) => new Uint8Array(length).fill(nonceByte) });
}

// nonce(12) | ciphertext | tag(16), standard padded base64.
export function frameOf(parts) {
  const frame = new Uint8Array(12 + parts.ciphertext.length + 16);
  frame.set(parts.nonce, 0);
  frame.set(parts.ciphertext, 12);
  frame.set(parts.tag, 12 + parts.ciphertext.length);
  return encodeBase64Padded(frame);
}
