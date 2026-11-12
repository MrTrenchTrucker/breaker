import test from 'node:test';
import assert from 'node:assert/strict';
import { createCipher, AesGcmRefused } from '../../main/resources/web/js/aesgcm.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import { WRAP_VECTORS, WRAP_REFUSALS } from './signin-vectors.mjs';
import { BOX_KEY, DEK, loginAnswer, makeInput, readySignin, wrapped } from './signin-fixtures.mjs';

const fromHex = (text) => Uint8Array.from(text.match(/../g) ?? [], (pair) => parseInt(pair, 16));
const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
const byName = (rows, name) => rows.find((row) => row.name === name);
const BOX = byName(WRAP_VECTORS, 'box-private-key-under-dek');
const PREV = byName(WRAP_VECTORS, 'previous-dek-under-new-dek');
const DEK_ROW = byName(WRAP_VECTORS, 'dek-under-kek');
const BOX_AAD = fromHex(BOX.aadHex);
const PREV_AAD = fromHex(PREV.aadHex);
const cipherFor = (entry) => createCipher(fromHex(entry.wrapperKeyHex));
const seamFor = (entry) => createCipher(fromHex(entry.wrapperKeyHex), { random: () => fromHex(entry.nonceHex) });
const refusedError = (err) => err instanceof AesGcmRefused && err.name === 'AesGcmRefused' && err.message === 'AES-GCM refused';
const SEAL_ORDER = ['salt', 'derive', 'login', 'box', 'seal', 'start', 'logout', 'replace'];

test('the shared box blob opens under its purpose AAD to its plain bytes', () => {
  assert.equal(hex(cipherFor(BOX).unwrapDek(fromHex(BOX.wrappedHex), BOX_AAD)), BOX.plainHex);
});

test('wrapDek with the box AAD and the shared nonce reproduces the shared box blob', () => {
  assert.equal(hex(seamFor(BOX).wrapDek(fromHex(BOX.plainHex), BOX_AAD)), BOX.wrappedHex);
});

test('wrapDek with the previous DEK AAD and the shared nonce reproduces the shared previous DEK blob', () => {
  assert.equal(hex(seamFor(PREV).wrapDek(fromHex(PREV.plainHex), PREV_AAD)), PREV.wrappedHex);
});

test('wrapDek with no AAD argument reproduces the DEK under KEK blob', () => {
  assert.equal(hex(seamFor(DEK_ROW).wrapDek(fromHex(DEK_ROW.plainHex))), DEK_ROW.wrappedHex);
});

test('every shared refusal is refused with the existing refusal error', () => {
  for (const entry of WRAP_REFUSALS) {
    assert.throws(() => cipherFor(entry).unwrapDek(fromHex(entry.wrappedHex), fromHex(entry.aadHex)), refusedError, entry.name);
  }
});

test('the box blob is refused under no AAD argument and under an empty AAD', () => {
  const cipher = cipherFor(BOX);
  const blob = fromHex(BOX.wrappedHex);
  assert.throws(() => cipher.unwrapDek(blob), refusedError, 'no AAD argument');
  assert.throws(() => cipher.unwrapDek(blob, new Uint8Array(0)), refusedError, 'empty AAD');
});

test('the DEK under KEK blob is refused under the box AAD and under the previous DEK AAD', () => {
  const cipher = cipherFor(DEK_ROW);
  const blob = fromHex(DEK_ROW.wrappedHex);
  assert.throws(() => cipher.unwrapDek(blob, BOX_AAD), refusedError, 'box AAD');
  assert.throws(() => cipher.unwrapDek(blob, PREV_AAD), refusedError, 'previous DEK AAD');
});

for (const [label, aad] of [['the purpose text', 'breaker-box-privkey-v1'], ['null', null]]) {
  test('unwrapDek refuses ' + label + ' given as the AAD', () => {
    assert.throws(() => cipherFor(BOX).unwrapDek(fromHex(BOX.wrappedHex), aad), refusedError, label);
  });
}

test('the happy login with the box blob under its AAD reaches the seal step', async () => {
  const { signin, rec } = readySignin();
  assert.deepEqual(await signin.run(makeInput()), { ok: true });
  assert.deepEqual(rec.log, SEAL_ORDER);
});

for (const [label, aad] of [['an empty AAD', new Uint8Array(0)], ['the previous DEK AAD', PREV_AAD]]) {
  test('a box blob under ' + label + ' gives keys refused and the seal step is never reached', async () => {
    const blob = wrapped(DEK, BOX_KEY, aad);
    const { signin, rec } = readySignin({ login: async () => loginAnswer({ wrapped_box_privkey: encodeBase64Padded(blob) }) });
    const result = await signin.run(makeInput());
    assert.equal(result.ok, false);
    assert.equal(result.code, 'keys_refused');
    assert.equal(rec.seal.length, 0);
  });
}
