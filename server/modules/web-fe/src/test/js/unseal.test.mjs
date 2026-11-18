// The opening function js/unseal.js, tested RED-first.
//
// Covers the step scope: open a libsodium crypto_box_seal box with the account's
// box private key, built from the vendored noble files, never throwing (a bad box
// is null), intermediates wiped. Three RED-first families:
//   1. the existing signin-seal.js sealer round-trips through unseal.js;
//   2. the fixed shared vectors open to their published plaintext;
//   3. the vendored libsodium (the oracle, imported by the TEST only, never served)
//      is checked in BOTH directions.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Buffer } from 'node:buffer';
import sodium from './libsodium-sumo.mjs';
import { unseal } from '../../main/resources/web/js/unseal.js';
import { seal } from '../../main/resources/web/js/signin-seal.js';
import { SEAL_VECTORS, SEAL_REFUSALS } from './signin-vectors.mjs';

const fromHex = (text) => new Uint8Array(Buffer.from(text, 'hex'));
const hex = (bytes) => Buffer.from(bytes).toString('hex');
const PACKAGE = SEAL_VECTORS.find((row) => row.name === 'signin-package-65');
const RECIPIENT_PRIVATE = fromHex(PACKAGE.recipientPrivateHex);
const RECIPIENT_PUBLIC = fromHex(PACKAGE.recipientPublicHex);
const WRONG_PRIVATE = fromHex(SEAL_REFUSALS[0].recipientPrivateHex);

// The vendored libsodium core, initialised once. This is the independent oracle:
// it is never imported by the shipped module (see the import-shape test below).
const sodiumReady = sodium({
  getRandomValue: () => globalThis.crypto.getRandomValues(new Uint32Array(1))[0] >>> 0,
});

// Seal with the vendored libsodium (a fresh random ephemeral key each call).
const oracleSeal = async (plain, recipientPublic) => {
  const s = await sodiumReady;
  const pk = s._malloc(32);
  const message = s._malloc(plain.length);
  const out = s._malloc(plain.length + 48);
  s.HEAPU8.set(recipientPublic, pk);
  s.HEAPU8.set(plain, message);
  const rc = s._crypto_box_seal(out, message, plain.length, 0, pk);
  const sealed = rc === 0 ? new Uint8Array(s.HEAPU8.subarray(out, out + plain.length + 48)) : null;
  s._free(pk);
  s._free(message);
  s._free(out);
  return sealed;
};

// Open with the vendored libsodium.
const oracleOpen = async (box, recipientPublic, recipientPrivate) => {
  const s = await sodiumReady;
  const pk = s._malloc(32);
  const sk = s._malloc(32);
  const cipher = s._malloc(box.length);
  const out = s._malloc(box.length);
  s.HEAPU8.set(recipientPublic, pk);
  s.HEAPU8.set(recipientPrivate, sk);
  s.HEAPU8.set(box, cipher);
  const rc = s._crypto_box_seal_open(out, cipher, box.length, 0, pk, sk);
  const plain = rc === 0 ? new Uint8Array(s.HEAPU8.subarray(out, out + box.length - 48)) : null;
  s._free(pk);
  s._free(sk);
  s._free(cipher);
  s._free(out);
  return plain;
};

// --- 1. the existing signin-seal.js sealer round-trips through unseal.js ---
for (const row of SEAL_VECTORS) {
  test('the signin-seal.js sealer round-trips ' + row.name + ' through unseal.js', () => {
    const box = seal(fromHex(row.plainHex), fromHex(row.recipientPublicHex), fromHex(row.ephemeralPrivateHex));
    assert.equal(hex(box), row.boxHex, 'the sealer still makes the published box');
    assert.equal(hex(unseal(box, fromHex(row.recipientPrivateHex))), row.plainHex);
  });
}

test('a fresh seal with no ephemeral key opens back to the same bytes', () => {
  for (const length of [1, 65, 1000]) {
    const plain = Uint8Array.from({ length }, (_, i) => i & 255);
    const box = seal(plain, RECIPIENT_PUBLIC);
    assert.equal(hex(unseal(box, RECIPIENT_PRIVATE)), hex(plain), 'length ' + length);
  }
});

// --- 2. the fixed vectors open to their published plaintext ---
for (const row of SEAL_VECTORS) {
  test('the fixed libsodium vector ' + row.name + ' opens to its published plaintext', () => {
    assert.equal(hex(unseal(fromHex(row.boxHex), fromHex(row.recipientPrivateHex))), row.plainHex);
  });
}

// --- 3. the vendored libsodium oracle, BOTH directions ---
test('the vendored libsodium opens the same box unseal.js opens, to the same bytes', async () => {
  for (const row of SEAL_VECTORS) {
    const box = fromHex(row.boxHex);
    const ours = unseal(box, fromHex(row.recipientPrivateHex));
    const theirs = await oracleOpen(box, fromHex(row.recipientPublicHex), fromHex(row.recipientPrivateHex));
    assert.equal(hex(ours), row.plainHex);
    assert.equal(hex(theirs), hex(ours));
  }
});

test('unseal.js opens a box the vendored libsodium sealed', async () => {
  for (const row of SEAL_VECTORS) {
    const plain = fromHex(row.plainHex);
    const box = await oracleSeal(plain, fromHex(row.recipientPublicHex));
    assert.equal(hex(unseal(box, fromHex(row.recipientPrivateHex))), row.plainHex);
  }
});

test('unseal.js opens the vendored libsodium output for plaintext lengths 1, 65 and 1000', async () => {
  for (const length of [1, 65, 1000]) {
    const plain = Uint8Array.from({ length }, (_, i) => (i * 7) & 255);
    const box = await oracleSeal(plain, RECIPIENT_PUBLIC);
    assert.equal(hex(unseal(box, RECIPIENT_PRIVATE)), hex(plain), 'length ' + length);
  }
});

// --- refusals: null, never a throw ---
test('a short box is null and never a throw', () => {
  for (const box of [new Uint8Array(0), new Uint8Array(32), new Uint8Array(47)]) {
    assert.strictEqual(unseal(box, RECIPIENT_PRIVATE), null);
  }
});

test('a bad ephemeral key is null and never a throw', () => {
  const zeros = new Uint8Array(PACKAGE.boxHex.length / 2);
  assert.strictEqual(unseal(zeros, RECIPIENT_PRIVATE), null, 'all-zero ephemeral key');
  const one = new Uint8Array(48);
  one[0] = 1;
  assert.strictEqual(unseal(one, RECIPIENT_PRIVATE), null, 'u=1 ephemeral key');
});

test('a truncated ciphertext is null and never a throw', () => {
  const box = fromHex(PACKAGE.boxHex);
  assert.strictEqual(unseal(box.subarray(0, box.length - 1), RECIPIENT_PRIVATE), null, 'one byte short');
  assert.strictEqual(unseal(box.subarray(0, 48), RECIPIENT_PRIVATE), null, 'tag only, no ciphertext');
});

test('a wrong recipient key is null and never a throw', () => {
  assert.strictEqual(unseal(fromHex(PACKAGE.boxHex), WRONG_PRIVATE), null);
});

test('bad inputs are null and never a throw', () => {
  const box = fromHex(PACKAGE.boxHex);
  const cases = [
    ['a null box', null, RECIPIENT_PRIVATE],
    ['an undefined box', undefined, RECIPIENT_PRIVATE],
    ['a plain Array box', [1, 2, 3], RECIPIENT_PRIVATE],
    ['a string box', PACKAGE.boxHex, RECIPIENT_PRIVATE],
    ['a null private key', box, null],
    ['an undefined private key', box, undefined],
    ['a 31-byte private key', box, RECIPIENT_PRIVATE.subarray(0, 31)],
    ['a 33-byte private key', box, new Uint8Array(33)],
    ['a string private key', box, PACKAGE.recipientPrivateHex],
  ];
  for (const [name, badBox, badKey] of cases) {
    assert.strictEqual(unseal(badBox, badKey), null, name);
  }
});

// --- the caller's key is not wiped; the module's own intermediates are ---
test('the recipient private key is left unchanged', () => {
  const before = hex(RECIPIENT_PRIVATE);
  unseal(fromHex(PACKAGE.boxHex), RECIPIENT_PRIVATE);
  assert.equal(hex(RECIPIENT_PRIVATE), before);
});

test('the module wipes every intermediate in its finally block', () => {
  const source = readFileSync(new URL('../../main/resources/web/js/unseal.js', import.meta.url), 'utf8');
  const block = /finally \{([\s\S]*?)\n  \}/.exec(source);
  assert.ok(block, 'no finally block found');
  for (const name of ['recipientPublic', 'shared', 'sharedWords', 'key', 'keyWords', 'nonce']) {
    const line = 'if (' + name + ' !== null) ' + name + '.fill(0);';
    assert.ok(block[1].includes(line), 'missing wipe of ' + name);
  }
});

// --- the shipped module stays on the vendored noble files only ---
test('the shipped module imports only the three vendored noble files', () => {
  const source = readFileSync(new URL('../../main/resources/web/js/unseal.js', import.meta.url), 'utf8');
  const paths = [...source.matchAll(/from '([^']+)'/g)].map((match) => match[1]);
  assert.deepEqual(paths, [
    './vendor/noble-curves/ed25519.js',
    './vendor/noble-ciphers/salsa.js',
    './vendor/noble-hashes/blake2.js',
  ]);
  assert.ok(!paths.some((path) => path.includes('libsodium')), 'the shipped module imports libsodium');
});

test('the module guards the box length, the private key length and the all-zero shared secret', () => {
  const source = readFileSync(new URL('../../main/resources/web/js/unseal.js', import.meta.url), 'utf8');
  assert.ok(source.includes('const BOX_OVERHEAD = 48;'), 'box overhead constant');
  assert.ok(source.includes('if (!(box instanceof Uint8Array) || box.length < BOX_OVERHEAD) return null;'), 'box length guard');
  assert.ok(source.includes('if (!(recipientPrivate instanceof Uint8Array) || recipientPrivate.length !== 32) return null;'), 'private key length guard');
  assert.ok(source.includes('if (shared.every((byte) => byte === 0)) return null;'), 'all-zero shared secret guard');
});
