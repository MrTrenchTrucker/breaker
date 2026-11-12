import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { x25519 } from '../../main/resources/web/js/vendor/noble-curves/ed25519.js';
import { hsalsa, xsalsa20poly1305 } from '../../main/resources/web/js/vendor/noble-ciphers/salsa.js';
import { blake2b } from '../../main/resources/web/js/vendor/noble-hashes/blake2.js';
import { seal } from '../../main/resources/web/js/signin-seal.js';
import * as facade from '../../main/resources/web/js/signin-crypto.js';
import { SEAL_VECTORS, SEAL_REFUSALS } from './signin-vectors.mjs';

// Test-only opener: the recipient side of the sealed box, written here and checked against the shared boxes.
// The vendored hsalsa works on little-endian 32-bit words, so bytes go in and come out through these two helpers.
const fromHex = (text) => new Uint8Array(Buffer.from(text, 'hex'));
const hex = (bytes) => Buffer.from(bytes).toString('hex');
const wordsOf = (bytes) => {
  const words = new Uint32Array(bytes.length / 4);
  for (let i = 0; i < words.length; i += 1) {
    words[i] = (bytes[4 * i] | (bytes[4 * i + 1] << 8) | (bytes[4 * i + 2] << 16) | (bytes[4 * i + 3] << 24)) >>> 0;
  }
  return words;
};
const bytesOf = (words) => {
  const bytes = new Uint8Array(words.length * 4);
  for (let i = 0; i < words.length; i += 1) {
    for (let j = 0; j < 4; j += 1) bytes[4 * i + j] = (words[i] >>> (8 * j)) & 255;
  }
  return bytes;
};
const concat = (a, b) => {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
};
const SIGMA = wordsOf(new TextEncoder().encode('expand 32-byte k'));
const boxKey = (shared) => {
  const out = new Uint32Array(8);
  hsalsa(SIGMA, wordsOf(shared), wordsOf(new Uint8Array(16)), out);
  return bytesOf(out);
};
const openBox = (box, recipientPrivate) => {
  const recipientPublic = x25519.getPublicKey(recipientPrivate);
  const epk = box.subarray(0, 32);
  const key = boxKey(x25519.getSharedSecret(recipientPrivate, epk));
  const nonce = blake2b(concat(epk, recipientPublic), { dkLen: 24 });
  return xsalsa20poly1305(key, nonce).decrypt(box.subarray(32));
};

const PACKAGE = SEAL_VECTORS.find((row) => row.name === 'signin-package-65');
const RECIPIENT_PUBLIC = fromHex(PACKAGE.recipientPublicHex);
const RECIPIENT_PRIVATE = fromHex(PACKAGE.recipientPrivateHex);
const EPHEMERAL_PRIVATE = fromHex(PACKAGE.ephemeralPrivateHex);
const PLAIN = fromHex(PACKAGE.plainHex);

for (const row of SEAL_VECTORS) {
  test('seal of ' + row.name + ' with its ephemeral private key gives its shared box', () => {
    const out = seal(fromHex(row.plainHex), fromHex(row.recipientPublicHex), fromHex(row.ephemeralPrivateHex));
    assert.equal(hex(out), row.boxHex);
  });
}

for (const row of SEAL_VECTORS) {
  test('the test-only opener opens ' + row.name + ' to its plain bytes', () => {
    assert.equal(hex(openBox(fromHex(row.boxHex), fromHex(row.recipientPrivateHex))), row.plainHex);
  });
}

test('our own seal opens back to the same bytes for plaintext lengths 1, 65 and 1000', () => {
  for (const length of [1, 65, 1000]) {
    const plain = Uint8Array.from({ length }, (_, i) => i & 255);
    const box = seal(plain, RECIPIENT_PUBLIC);
    assert.equal(box.length, length + 48, 'length ' + length);
    assert.equal(hex(openBox(box, RECIPIENT_PRIVATE)), hex(plain), 'length ' + length);
  }
});

for (const row of SEAL_REFUSALS) {
  test('the test-only opener refuses ' + row.name, () => {
    assert.throws(() => openBox(fromHex(row.boxHex), fromHex(row.recipientPrivateHex)), Error, row.name);
  });
}

test('two seals of the 65 byte package with no ephemeral key differ and both open', () => {
  const first = seal(PLAIN, RECIPIENT_PUBLIC);
  const second = seal(PLAIN, RECIPIENT_PUBLIC);
  assert.equal(first.length, 113);
  assert.equal(second.length, 113);
  assert.notEqual(hex(first), hex(second));
  assert.equal(hex(openBox(first, RECIPIENT_PRIVATE)), PACKAGE.plainHex);
  assert.equal(hex(openBox(second, RECIPIENT_PRIVATE)), PACKAGE.plainHex);
});

test('a supplied ephemeral key, the plaintext and the recipient key are left unchanged', () => {
  const before = [hex(PLAIN), hex(RECIPIENT_PUBLIC), hex(EPHEMERAL_PRIVATE)];
  seal(PLAIN, RECIPIENT_PUBLIC, EPHEMERAL_PRIVATE);
  assert.deepEqual([hex(PLAIN), hex(RECIPIENT_PUBLIC), hex(EPHEMERAL_PRIVATE)], before);
});

test('seal refuses a bad plaintext with null and no throw', () => {
  const cases = [
    ['an empty plaintext', new Uint8Array(0)],
    ['a plain Array', [1, 2, 3]],
    ['a string', 'abc'],
    ['null', null],
    ['undefined', undefined],
  ];
  for (const [name, value] of cases) assert.strictEqual(seal(value, RECIPIENT_PUBLIC), null, name);
});

test('seal refuses a bad recipient key with null and no throw', () => {
  const cases = [
    ['a recipient key of 31 bytes', RECIPIENT_PUBLIC.subarray(0, 31)],
    ['a recipient key of 33 bytes', new Uint8Array(33)],
    ['a recipient key given as a string', PACKAGE.recipientPublicHex],
  ];
  for (const [name, value] of cases) assert.strictEqual(seal(PLAIN, value), null, name);
});

test('seal refuses a bad ephemeral key with null and no throw', () => {
  const cases = [
    ['an ephemeral key of 31 bytes', EPHEMERAL_PRIVATE.subarray(0, 31)],
    ['an ephemeral key of 33 bytes', new Uint8Array(33)],
    ['an ephemeral key given as a string', PACKAGE.ephemeralPrivateHex],
  ];
  for (const [name, value] of cases) assert.strictEqual(seal(PLAIN, RECIPIENT_PUBLIC, value), null, name);
});

test('an all-zero recipient key gives null and no throw', () => {
  assert.strictEqual(seal(PLAIN, new Uint8Array(32)), null);
});

test('the low-order point with u equal to one gives null and no throw', () => {
  const one = new Uint8Array(32);
  one[0] = 1;
  assert.strictEqual(seal(PLAIN, one), null);
});

test('the facade seal gives the shared 65 byte box for the vector keys', () => {
  const out = facade.seal(PLAIN, RECIPIENT_PUBLIC, EPHEMERAL_PRIVATE);
  assert.equal(hex(out), PACKAGE.boxHex);
});

test('the facade still exports exactly four names and available is exactly false', () => {
  assert.deepEqual(Object.keys(facade).sort(), ['available', 'boxPublicKey', 'derive', 'seal']);
  assert.strictEqual(facade.available, false);
});
