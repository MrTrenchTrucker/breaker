import test from 'node:test';
import assert from 'node:assert/strict';
import { BootstrapRefused, createBootstrapCrypto } from '../../main/resources/web/js/bootstrap-crypto.js';
import { createCipher, AesGcmRefused } from '../../main/resources/web/js/aesgcm.js';
import { x25519 } from '../../main/resources/web/js/vendor/noble-curves/ed25519.js';
import { WRAP_VECTORS, X25519_BOX } from './signin-vectors.mjs';
import { wipeBytes } from '../../main/resources/web/js/signin-link.js';

const fromHex = (text) => Uint8Array.from(text.match(/../g) ?? [], (pair) => parseInt(pair, 16));
const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
const patterned = (base, size) => Uint8Array.from({ length: size }, (_, i) => (base + i) & 255);
const byName = (rows, name) => rows.find((row) => row.name === name);
const KEK = patterned(0x10, 32);
const BOX_ROW = byName(WRAP_VECTORS, 'box-private-key-under-dek');
const DEK_ROW = byName(WRAP_VECTORS, 'dek-under-kek');
const PREV_ROW = byName(WRAP_VECTORS, 'previous-dek-under-new-dek');
const BOX_AAD = fromHex(BOX_ROW.aadHex);
const PREV_AAD = fromHex(PREV_ROW.aadHex);
const ALICE = byName(X25519_BOX, 'rfc7748-6.1-alice');

// A scripted random source: every call takes the next queued array and records the size asked for.
function scriptedSource(queue) {
  const asks = [];
  let at = 0;
  const source = (size) => {
    asks.push(size);
    const next = queue[at];
    at += 1;
    if (next === undefined) throw new Error('the scripted source ran out');
    return next;
  };
  return { source, asks };
}

// One bootstrap with a fully scripted source: data key, box private key, then the two wrap nonces.
function scriptedBootstrap(queue, kek = KEK) {
  const { source, asks } = scriptedSource(queue);
  const out = createBootstrapCrypto({ random: source }).bootstrap(kek);
  return { out, asks };
}

const refused = (err) => err instanceof BootstrapRefused
  && err.name === 'BootstrapRefused'
  && err.message === 'key bootstrap refused';

// An unwrap refusal comes from the AES-GCM layer, so it carries that layer's error.
const aesRefused = (err) => err instanceof AesGcmRefused
  && err.name === 'AesGcmRefused'
  && err.message === 'AES-GCM refused';

test('an injected random source gives the exact data key and the exact box key pair', () => {
  const dek = patterned(0xa0, 32);
  const boxPrivate = patterned(0xc0, 32);
  // The expected values are captured before the call: draw() zeroes the buffers the source handed
  // out, so the queued arrays are zero by the time bootstrap returns.
  const dekHex = hex(dek);
  const boxPrivateHex = hex(boxPrivate);
  const boxPublicHex = hex(x25519.getPublicKey(boxPrivate));
  const { out, asks } = scriptedBootstrap([dek, boxPrivate, patterned(0x50, 12), patterned(0x60, 12)]);
  assert.deepEqual(asks, [32, 32, 12, 12], 'the source is asked for two keys then two nonces');
  assert.equal(hex(out.dek), dekHex);
  assert.equal(hex(out.boxPrivkey), boxPrivateHex);
  assert.equal(hex(out.boxPubkey), boxPublicHex);
  assert.equal(out.wrappedDek.length, 60);
  assert.equal(out.wrappedBoxPrivkey.length, 60);
  assert.equal(out.boxPubkey.length, 32);
});

test('the box public key is the published RFC 7748 public key of the generated private key', () => {
  const { out } = scriptedBootstrap([patterned(0xa0, 32), fromHex(ALICE.privateHex), patterned(0x50, 12), patterned(0x60, 12)]);
  assert.equal(hex(out.boxPubkey), ALICE.publicHex);
});

test('the wrapped data key opens under the KEK with empty associated data', () => {
  const { out } = scriptedBootstrap([patterned(0xa0, 32), patterned(0xc0, 32), patterned(0x50, 12), patterned(0x60, 12)]);
  const opened = createCipher(KEK).unwrapDek(out.wrappedDek);
  assert.equal(hex(opened), hex(out.dek));
  assert.throws(() => createCipher(KEK).unwrapDek(out.wrappedDek, BOX_AAD), aesRefused, 'box purpose AAD');
});

test('the wrapped box private key opens under the data key with the box purpose associated data', () => {
  const { out } = scriptedBootstrap([patterned(0xa0, 32), patterned(0xc0, 32), patterned(0x50, 12), patterned(0x60, 12)]);
  const opened = createCipher(out.dek).unwrapDek(out.wrappedBoxPrivkey, BOX_AAD);
  assert.equal(hex(opened), hex(out.boxPrivkey));
});

test('the wrapped box private key is refused under no associated data and under an empty one', () => {
  const { out } = scriptedBootstrap([patterned(0xa0, 32), patterned(0xc0, 32), patterned(0x50, 12), patterned(0x60, 12)]);
  const cipher = createCipher(out.dek);
  assert.throws(() => cipher.unwrapDek(out.wrappedBoxPrivkey), aesRefused, 'no associated data');
  assert.throws(() => cipher.unwrapDek(out.wrappedBoxPrivkey, new Uint8Array(0)), aesRefused, 'empty associated data');
});

test('the wrapped box private key is refused under the previous data key purpose', () => {
  const { out } = scriptedBootstrap([patterned(0xa0, 32), patterned(0xc0, 32), patterned(0x50, 12), patterned(0x60, 12)]);
  assert.throws(() => createCipher(out.dek).unwrapDek(out.wrappedBoxPrivkey, PREV_AAD), aesRefused);
});

test('the shared dek-under-kek blob is reproduced with the injected nonce', () => {
  const plain = fromHex(DEK_ROW.plainHex);
  const { out } = scriptedBootstrap([plain, patterned(0xc0, 32), fromHex(DEK_ROW.nonceHex), patterned(0x60, 12)], fromHex(DEK_ROW.wrapperKeyHex));
  assert.equal(hex(out.wrappedDek), DEK_ROW.wrappedHex);
});

test('the shared box-private-key-under-dek blob is reproduced with the injected nonce', () => {
  const dek = fromHex(BOX_ROW.wrapperKeyHex);
  const boxPrivate = fromHex(BOX_ROW.plainHex);
  const { out } = scriptedBootstrap([dek, boxPrivate, patterned(0x50, 12), fromHex(BOX_ROW.nonceHex)]);
  assert.equal(hex(out.wrappedBoxPrivkey), BOX_ROW.wrappedHex);
  assert.equal(hex(out.dek), BOX_ROW.wrapperKeyHex);
  assert.equal(hex(out.boxPrivkey), BOX_ROW.plainHex);
});

for (const [name, kek] of [['31 bytes', patterned(0x10, 31)], ['33 bytes', patterned(0x10, 33)], ['not bytes', 'text']]) {
  test('a key encryption key of ' + name + ' is refused and the source is never called', () => {
    const { source, asks } = scriptedSource([patterned(0xa0, 32)]);
    assert.throws(() => createBootstrapCrypto({ random: source }).bootstrap(kek), refused);
    assert.deepEqual(asks, []);
  });
}

test('a source that throws is refused', () => {
  assert.throws(() => createBootstrapCrypto({ random: () => { throw new Error('no entropy'); } }).bootstrap(KEK), refused);
});

for (const [name, value] of [['31 bytes', new Uint8Array(31)], ['33 bytes', new Uint8Array(33)], ['the number 32', 32]]) {
  test('a source answering the key size with ' + name + ' is refused', () => {
    assert.throws(() => createBootstrapCrypto({ random: () => value }).bootstrap(KEK), refused);
  });
}

test('a source that keeps and mutates one buffer cannot change the data key or the box key', () => {
  const held = patterned(0xa0, 32);
  const nonces = [patterned(0x50, 12), patterned(0x60, 12)];
  let nonceAt = 0;
  const source = (size) => {
    if (size === 32) return held;
    const nonce = nonces[nonceAt];
    nonceAt += 1;
    return nonce;
  };
  const out = createBootstrapCrypto({ random: source }).bootstrap(KEK);
  const dekBefore = hex(out.dek);
  const boxKeyBefore = hex(out.boxPrivkey);
  held.fill(0);
  assert.equal(hex(out.dek), dekBefore, 'the returned data key is a copy the source cannot change');
  assert.equal(hex(out.boxPrivkey), boxKeyBefore, 'the returned box key is a copy the source cannot change');
});

test('a successful bootstrap zeroes the raw key buffers the source handed out', () => {
  const dek = patterned(0xa0, 32);
  const boxPrivate = patterned(0xc0, 32);
  const nonce1 = patterned(0x50, 12);
  const nonce2 = patterned(0x60, 12);
  const queue = [dek, boxPrivate, nonce1, nonce2];
  const expectedDek = hex(dek);
  const expectedBoxPrivate = hex(boxPrivate);
  const { out, asks } = scriptedBootstrap(queue);
  assert.deepEqual(asks, [32, 32, 12, 12], 'the source is asked for two keys then two nonces');
  assert.equal(hex(out.dek), expectedDek, 'the returned data key is the drawn key');
  assert.equal(hex(out.boxPrivkey), expectedBoxPrivate, 'the returned box key is the drawn key');
  assert.ok(dek.every((byte) => byte === 0), 'the raw data-key buffer is zero after the copy');
  assert.ok(boxPrivate.every((byte) => byte === 0), 'the raw box-key buffer is zero after the copy');
});

test('a wrong-length raw key buffer is zero on the shape-refusal throw path', () => {
  const wrong = patterned(0xa0, 31);
  assert.throws(() => createBootstrapCrypto({ random: () => wrong }).bootstrap(KEK), refused);
  assert.ok(wrong.every((byte) => byte === 0), 'the refused raw buffer is zero after the throw');
});

test('a refused later step leaves both internal key copies zero through the wipe hook', () => {
  const queue = [patterned(0xa0, 32), patterned(0xc0, 32)];
  const expected = [hex(queue[0]), hex(queue[1])];
  let at = 0;
  const source = () => {
    if (at < queue.length) {
      const next = queue[at];
      at += 1;
      return next;
    }
    throw new Error('no entropy for the wrap nonce');
  };
  const seen = [];
  const wiped = [];
  // The stand-in wipe records what it was handed, then does the real zero-fill the default hook does.
  const wipe = (bytes) => {
    seen.push(hex(bytes));
    wiped.push(bytes);
    wipeBytes(bytes);
  };
  assert.throws(() => createBootstrapCrypto({ random: source, wipe }).bootstrap(KEK), refused);
  assert.equal(wiped.length, 2, 'both internal key copies reach the wipe hook');
  assert.ok(wiped.every((bytes) => bytes instanceof Uint8Array && bytes.length === 32),
    'each copy handed over is 32 bytes');
  assert.deepEqual(seen, expected, 'the hook receives the two drawn keys themselves');
  assert.ok(wiped.every((bytes) => bytes.every((byte) => byte === 0)), 'both copies are zero');
});

test('exports verified', async () => {
  const m = await import('../../main/resources/web/js/bootstrap-crypto.js');
  assert.equal(typeof m.createBootstrapCrypto, 'function');
  assert.equal(typeof m.BootstrapRefused, 'function');
});
