// The record encryption adapter.
//
// The adapter refuses (null) unless the plaintext is a Uint8Array, the client id
// is a lowercase canonical UUID and the key version is an integer >= 1; it then
// validates the plaintext as a strict server R1 body (every decodeBody rule, the
// six keys in the exact order, source exactly "server") and only then encrypts
// the SAME bytes verbatim, answering standard padded base64 of
// nonce(12) | ciphertext | tag(16).
//
// Each real-cipher case builds a FRESH cipher: aesgcm refuses to reuse a nonce,
// so one cipher object serves exactly one seal.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createCipher } from '../../main/resources/web/js/aesgcm.js';
import { decodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import { recordAad, createRecordEncrypt } from '../../main/resources/web/js/conversion-encrypt.js';
import {
  DEK,
  CLIENT_ID,
  OTHER_CLIENT_ID,
  KEY_VERSION,
  aadFor,
  bodyBytes,
  bodyWith,
  goodEntries,
  rawBytes,
  cipherWithFixedNonce,
} from './conversion-fixtures.mjs';

const MODULE_DIR = '../../main/resources/web/js';
const ENCRYPT_SOURCE = readFileSync(new URL(`${MODULE_DIR}/conversion-encrypt.js`, import.meta.url), 'utf8');
const UPLOAD_SOURCE = readFileSync(new URL(`${MODULE_DIR}/conversion-upload.js`, import.meta.url), 'utf8');

// One seal on a fresh fixed-nonce cipher.
function sealOnce(plaintext, clientId = CLIENT_ID, keyVersion = KEY_VERSION) {
  return createRecordEncrypt({ cipher: cipherWithFixedNonce() })(plaintext, clientId, keyVersion);
}

// Decode the answer strictly and hand back its three parts.
function frameParts(answer, ciphertextLength) {
  const bytes = decodeBase64Padded(answer, 12 + ciphertextLength + 16);
  return {
    nonce: bytes.slice(0, 12),
    ciphertext: bytes.slice(12, 12 + ciphertextLength),
    tag: bytes.slice(12 + ciphertextLength),
  };
}

// Open a frame with the real cipher and the given AAD; null when the tag fails.
function open(frame, aad) {
  try {
    return createCipher(DEK).open(frame, aad);
  } catch {
    return null;
  }
}

test('recordAad is the client id UTF-8, one LF byte, then the decimal key version', () => {
  const bytes = recordAad(CLIENT_ID, KEY_VERSION);
  assert.ok(bytes instanceof Uint8Array);
  const expected = new TextEncoder().encode(CLIENT_ID + '\n' + String(KEY_VERSION));
  assert.deepEqual(Array.from(bytes), Array.from(expected));
  // The LF sits right after the client id and before the decimal version.
  assert.equal(bytes[CLIENT_ID.length], 0x0a);
});

test('recordAad writes the decimal key version, so 10 is two bytes', () => {
  const bytes = recordAad(CLIENT_ID, 10);
  assert.deepEqual(
    Array.from(bytes.slice(CLIENT_ID.length + 1)),
    Array.from(new TextEncoder().encode('10')),
  );
});

test('the adapter answers standard padded base64 of nonce | ciphertext | tag', () => {
  const plaintext = bodyBytes();
  const cipher = cipherWithFixedNonce(0x11);
  const answer = createRecordEncrypt({ cipher })(plaintext, CLIENT_ID, KEY_VERSION);
  assert.equal(typeof answer, 'string');
  assert.match(answer, /^[A-Za-z0-9+/]+={0,2}$/);
  assert.equal(answer.length % 4, 0);
  const parts = frameParts(answer, plaintext.length);
  assert.deepEqual(Array.from(parts.nonce), new Array(12).fill(0x11));
  // The same bytes come back under the same AAD.
  assert.deepEqual(open(parts, aadFor(CLIENT_ID, KEY_VERSION)), plaintext);
});

test('the adapter encrypts the bytes verbatim, not a re-serialisation', () => {
  // JSON whitespace the reader accepts; a re-serialiser would drop it.
  const plaintext = rawBytes(
    '{ "v" : 1 , "text" : "a b" , "source" : "server" , "model" : "m" ,'
      + ' "duration_ms" : 1200 , "created_at" : "2026-10-11T12:00:00.000Z" }',
  );
  const answer = sealOnce(plaintext);
  const parts = frameParts(answer, plaintext.length);
  const plain = open(parts, aadFor(CLIENT_ID, KEY_VERSION));
  assert.notEqual(plain, null);
  assert.deepEqual(plain, plaintext);
});

test('the AAD binds the client id and the key version', () => {
  const plaintext = bodyBytes();
  const answer = sealOnce(plaintext);
  const parts = frameParts(answer, plaintext.length);
  assert.equal(open(parts, aadFor(OTHER_CLIENT_ID, KEY_VERSION)), null);
  assert.equal(open(parts, aadFor(CLIENT_ID, KEY_VERSION + 1)), null);
  assert.deepEqual(open(parts, aadFor(CLIENT_ID, KEY_VERSION)), plaintext);
});

test('the adapter hands the plaintext bytes and the recordAad to cipher.seal', () => {
  const seen = [];
  const fake = {
    seal: (plaintext, aad) => {
      seen.push({ plaintext, aad });
      return { nonce: new Uint8Array(12), ciphertext: new Uint8Array(0), tag: new Uint8Array(16) };
    },
  };
  const plaintext = bodyBytes();
  const answer = createRecordEncrypt({ cipher: fake })(plaintext, CLIENT_ID, KEY_VERSION);
  assert.equal(typeof answer, 'string');
  assert.equal(seen.length, 1);
  assert.equal(seen[0].plaintext, plaintext);
  assert.deepEqual(seen[0].aad, aadFor(CLIENT_ID, KEY_VERSION));
});

test('the adapter refuses a plaintext that is not a Uint8Array', () => {
  for (const bad of [null, undefined, 'text', {}, [], new ArrayBuffer(8)]) {
    assert.equal(sealOnce(bad), null);
  }
});

test('the adapter refuses a client id that is not a lowercase canonical UUID', () => {
  const badIds = [
    CLIENT_ID.toUpperCase(),
    CLIENT_ID.replace(/-/g, ''),
    CLIENT_ID.slice(0, -1),
    CLIENT_ID.replace(/[0-9a-f]/, 'z'),
    '',
    null,
  ];
  for (const clientId of badIds) {
    assert.equal(sealOnce(bodyBytes(), clientId), null);
  }
});

test('the adapter refuses a key version below 1 or not an integer', () => {
  // No default parameters here: an explicit undefined must reach the adapter.
  const encrypt = (version) =>
    createRecordEncrypt({ cipher: cipherWithFixedNonce() })(bodyBytes(), CLIENT_ID, version);
  for (const version of [0, -1, 1.5, Number.NaN, '1', null, undefined]) {
    assert.equal(encrypt(version), null);
  }
});

test('a body with source local is refused', () => {
  assert.equal(sealOnce(bodyBytes({ source: 'local' })), null);
});

test('a body whose source is not exactly lower case is refused', () => {
  assert.equal(sealOnce(bodyBytes({ source: 'Server' })), null);
  assert.equal(sealOnce(bodyBytes({ source: '' })), null);
});

test('a body whose six keys are out of order is refused', () => {
  const reordered = [
    ['text', '"the words"'],
    ['v', '1'],
    ['source', '"server"'],
    ['model', '"test-model"'],
    ['duration_ms', '1200'],
    ['created_at', '"2026-10-11T12:00:00.000Z"'],
  ];
  assert.equal(sealOnce(bodyWith(reordered)), null);
});

test('a body with a duplicated key is refused', () => {
  assert.equal(sealOnce(bodyWith(goodEntries().concat([['v', '1']]))), null);
});

test('a body with an unknown key is refused', () => {
  assert.equal(sealOnce(bodyWith(goodEntries().concat([['resave', 'true']]))), null);
});

test('a body with a missing key is refused', () => {
  const missing = goodEntries().filter(([key]) => key !== 'model');
  assert.equal(sealOnce(bodyWith(missing)), null);
});

test('a body with a nested object member is refused', () => {
  const nested = goodEntries().map(([key, value]) =>
    (key === 'model' ? ['model', '{"a":1}'] : [key, value]));
  assert.equal(sealOnce(bodyWith(nested)), null);
});

test('a body with a non-string member where a string belongs is refused', () => {
  const numbers = goodEntries().map(([key, value]) =>
    (key === 'text' ? ['text', '5'] : [key, value]));
  assert.equal(sealOnce(bodyWith(numbers)), null);
});

test('v must be the integer token 1', () => {
  for (const bad of ['"1"', '1.0', '01', '2', '1e0', '-1', 'true', 'null']) {
    const entries = goodEntries().map(([key, value]) => (key === 'v' ? ['v', bad] : [key, value]));
    assert.equal(sealOnce(bodyWith(entries)), null, bad);
  }
  assert.notEqual(sealOnce(bodyBytes()), null);
});

test('duration_ms must be an integer token in range', () => {
  for (const bad of ['-1', '1.5', '1e3', '01', '9007199254740992', 'true']) {
    assert.equal(sealOnce(bodyBytes({ duration_ms: bad })), null, bad);
  }
  for (const good of ['0', '9007199254740991']) {
    assert.notEqual(sealOnce(bodyBytes({ duration_ms: good })), null, good);
  }
});

test('created_at must be the exact form and a real calendar date', () => {
  const badStamps = [
    '2026-10-11T12:00:00Z',
    '2026-10-11T12:00:00.000+00:00',
    '2026-10-11t12:00:00.000Z',
    '2026-10-11T12:00:00.000z',
    '2026-10-11T12:00:60.000Z',
    '2026-10-11T24:00:00.000Z',
    '2026-02-30T12:00:00.000Z',
    '2023-02-29T12:00:00.000Z',
    '1969-12-31T23:59:59.999Z',
    '2026-10-11 12:00:00.000Z',
  ];
  for (const created_at of badStamps) {
    assert.equal(sealOnce(bodyBytes({ created_at })), null, created_at);
  }
  const goodStamps = [
    '1970-01-01T00:00:00.000Z',
    '9999-12-31T23:59:59.999Z',
    '2024-02-29T00:00:00.000Z',
    '2026-10-11T12:00:00.001Z',
  ];
  for (const created_at of goodStamps) {
    assert.notEqual(sealOnce(bodyBytes({ created_at })), null, created_at);
  }
});

test('the leap-year century rule: 2000 is a leap year, 2100 is not', () => {
  assert.notEqual(sealOnce(bodyBytes({ created_at: '2000-02-29T00:00:00.000Z' })), null,
    '2000 is divisible by 400, so 2000-02-29 is a real date');
  assert.equal(sealOnce(bodyBytes({ created_at: '2100-02-29T00:00:00.000Z' })), null,
    '2100 is divisible by 100 but not by 400, so 2100-02-29 does not exist');
});

test('a leading BOM is refused', () => {
  assert.equal(sealOnce(Uint8Array.of(0xef, 0xbb, 0xbf, ...bodyBytes())), null);
});

test('invalid UTF-8 bytes are refused', () => {
  assert.equal(sealOnce(Uint8Array.of(0x7b, 0xff, 0x7d)), null);
  // A truncated three-byte sequence at the end of an otherwise valid body.
  assert.equal(sealOnce(Uint8Array.of(...bodyBytes(), 0xe2, 0x82)), null);
});

test('bytes after the object are refused', () => {
  const body = bodyBytes();
  const trailing = new Uint8Array(body.length + 2);
  trailing.set(body, 0);
  trailing.set(rawBytes(' x'), body.length);
  assert.equal(sealOnce(trailing), null);
});

test('a lone surrogate escape is refused', () => {
  assert.equal(sealOnce(bodyBytes({ text: '\ud800' })), null);
  assert.equal(sealOnce(bodyBytes({ text: '\udc00' })), null);
});

test('a valid surrogate pair escape is accepted and kept verbatim', () => {
  const plaintext = bodyBytes({ text: '\ud83d\ude00' });
  const answer = sealOnce(plaintext);
  const parts = frameParts(answer, plaintext.length);
  assert.deepEqual(open(parts, aadFor(CLIENT_ID, KEY_VERSION)), plaintext);
});

test('JSON whitespace and the standard escapes are accepted', () => {
  const text = 'quote " backslash \\ newline \n tab \t cr \r bs \b ff \f slash /';
  assert.notEqual(sealOnce(bodyBytes({ text })), null);
  const escapedSlash = rawBytes(
    '{"v":1,"text":"a\\/b","source":"server","model":"m","duration_ms":1,'
      + '"created_at":"2026-10-11T12:00:00.000Z"}',
  );
  assert.notEqual(sealOnce(escapedSlash), null);
});

test('a raw control character inside a string is refused', () => {
  const entries = goodEntries().map(([key, value]) =>
    (key === 'text' ? ['text', '"a\u0001b"'] : [key, value]));
  assert.equal(sealOnce(bodyWith(entries)), null);
});

test('the adapter refuses when the cipher throws', () => {
  const broken = { seal: () => { throw new Error('no cipher'); } };
  assert.equal(createRecordEncrypt({ cipher: broken })(bodyBytes(), CLIENT_ID, KEY_VERSION), null);
});

test('the adapter refuses a frame that is not nonce | ciphertext | tag', () => {
  const badFrames = [
    { nonce: new Uint8Array(11), ciphertext: new Uint8Array(1), tag: new Uint8Array(16) },
    { nonce: new Uint8Array(12), ciphertext: new Uint8Array(1), tag: new Uint8Array(15) },
    { nonce: new Uint8Array(12), ciphertext: 'nope', tag: new Uint8Array(16) },
    {},
  ];
  for (const sealed of badFrames) {
    const fake = { seal: () => sealed };
    assert.equal(createRecordEncrypt({ cipher: fake })(bodyBytes(), CLIENT_ID, KEY_VERSION), null);
  }
});

test('the adapter throws nothing: every refusal is a null', () => {
  const cases = [
    [null, CLIENT_ID, KEY_VERSION],
    [bodyBytes(), 'not-a-uuid', KEY_VERSION],
    [bodyBytes(), CLIENT_ID, 0],
    [rawBytes('{}'), CLIENT_ID, KEY_VERSION],
    [bodyBytes({ source: 'local' }), CLIENT_ID, KEY_VERSION],
  ];
  for (const [plaintext, clientId, version] of cases) {
    let answer;
    assert.doesNotThrow(() => {
      answer = createRecordEncrypt({ cipher: cipherWithFixedNonce() })(plaintext, clientId, version);
    });
    assert.equal(answer, null);
  }
});

test('the conversion modules hold no DOM, storage or network code of their own', () => {
  const forbidden = ['document.', 'localStorage', 'sessionStorage', 'fetch(', 'XMLHttpRequest', 'setTimeout', 'window.'];
  for (const source of [ENCRYPT_SOURCE, UPLOAD_SOURCE]) {
    for (const token of forbidden) {
      assert.equal(source.includes(token), false, token);
    }
  }
});
