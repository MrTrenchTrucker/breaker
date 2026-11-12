import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import * as facade from '../../main/resources/web/js/signin-crypto.js';
import { MESSAGES } from '../../main/resources/web/js/signin.js';
import { encodeBase64Padded, passwordToBytes } from '../../main/resources/web/js/signin-link.js';
import { KDF_ANDROID } from './signin-vectors.mjs';
import { makeInput, readySignin, saltAnswer } from './signin-fixtures.mjs';

const [MAIN] = KDF_ANDROID;
const fromHex = (text) => new Uint8Array(Buffer.from(text, 'hex'));
const hex = (bytes) => Buffer.from(bytes).toString('hex');
const floor = {
  memoryKib: MAIN.memoryKib, iterations: MAIN.iterations, parallelism: MAIN.parallelism, outputLength: 32,
};
const salt16 = fromHex(MAIN.saltHex);

test('the facade reports available as exactly false', () => {
  assert.strictEqual(facade.available, false);
});

test('the facade exports exactly available, derive, boxPublicKey and seal', () => {
  assert.deepEqual(Object.keys(facade).sort(), ['available', 'boxPublicKey', 'derive', 'seal']);
});

test('boxPublicKey of no argument returns null', () => {
  assert.strictEqual(facade.boxPublicKey(), null);
});

test('seal of no arguments returns null', () => {
  assert.strictEqual(facade.seal(), null);
});

test('derive gives the main vector kek and verifier', async () => {
  const r = await facade.derive(passwordToBytes(MAIN.password), salt16, floor);
  assert.equal(hex(r.kek), MAIN.kek);
  assert.equal(hex(r.verifier), MAIN.verifier);
});

test('derive answers null for a refused parameter and does not throw', async () => {
  const refused = { ...floor, memoryKib: 65535 };
  assert.equal(await facade.derive(passwordToBytes(MAIN.password), salt16, refused), null);
});

test('derive answers null for a salt of 15 bytes and does not throw', async () => {
  assert.equal(await facade.derive(passwordToBytes(MAIN.password), salt16.subarray(0, 15), floor), null);
});

test('derive answers null for a string password and does not throw', async () => {
  assert.equal(await facade.derive(MAIN.password, salt16, floor), null);
});

test('the real derive reaches login with the verifier and the run ends with keys refused', async () => {
  const seen = [];
  const { signin, rec } = readySignin({
    getSalt: async () => saltAnswer({ salt: encodeBase64Padded(salt16) }),
    derive: async (password, salt, kdf) => {
      seen.push({ password: Array.from(password), salt: Array.from(salt), kdf: { ...kdf } });
      return facade.derive(password, salt, kdf);
    },
  });
  const result = await signin.run(makeInput(MAIN.password));
  assert.deepEqual(result, { ok: false, code: 'keys_refused', message: MESSAGES.keys_refused });
  assert.equal(seen.length, 1);
  assert.deepEqual(seen[0].password, Array.from(passwordToBytes(MAIN.password)));
  assert.deepEqual(seen[0].salt, Array.from(salt16));
  assert.deepEqual(seen[0].kdf, floor);
  assert.equal(rec.login.length, 1);
  assert.equal(rec.login[0].verifierBase64, encodeBase64Padded(fromHex(MAIN.verifier)));
  assert.equal(rec.box.length, 0);
  assert.equal(rec.seal.length, 0);
});
