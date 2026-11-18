import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { createRegister, DEFAULT_KDF_PARAMS, MESSAGES } from '../../main/resources/web/js/register.js';
import { derive as realDerive } from '../../main/resources/web/js/signin-crypto.js';
import { encodeBase64Padded, passwordToBytes } from '../../main/resources/web/js/signin-link.js';
import { KDF_ANDROID } from './signin-vectors.mjs';

const [MAIN] = KDF_ANDROID;
const patterned = (base, size) => Uint8Array.from({ length: size }, (_, i) => (base + i) & 255);
const SALT = patterned(0x20, 16);
const KEK = patterned(0x30, 32);
const VERIFIER = patterned(0x40, 32);
const DEK = patterned(0xa0, 32);
const BOX_KEY = patterned(0xc0, 32);
const BOX_PUB = patterned(0xe0, 32);
const WRAPPED_DEK = patterned(0x50, 60);
const WRAPPED_BOX = patterned(0x60, 60);
const TOKEN = 'session sample text';
const USERNAME = 'account-one';
const TYPED = 'typed sample text long enough';
// A real install code: forty three URL-safe characters, the shape the contract and isUrlSafe43 accept.
const CODE = encodeBase64Padded(patterned(0x40, 32)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const fromHex = (text) => new Uint8Array(Buffer.from(text, 'hex'));
const isZero = (bytes) => bytes instanceof Uint8Array && bytes.every((b) => b === 0);

function bootstrapAnswer(overrides = {}) {
  return {
    dek: new Uint8Array(DEK),
    boxPrivkey: new Uint8Array(BOX_KEY),
    boxPubkey: new Uint8Array(BOX_PUB),
    wrappedDek: new Uint8Array(WRAPPED_DEK),
    wrappedBoxPrivkey: new Uint8Array(WRAPPED_BOX),
    ...overrides,
  };
}

// Fake dependencies. Each records what it was given and appends its name to the log.
function makeDeps(overrides = {}) {
  const rec = {
    log: [], random: [], derive: [], register: [], login: [], bootstrap: [], postKeys: [],
    refs: [],
  };
  const deps = {
    random: (size) => { rec.random.push(size); rec.log.push('random'); return new Uint8Array(SALT); },
    derive: async (password, salt, params) => {
      rec.derive.push({ password: new Uint8Array(password), salt: new Uint8Array(salt), params: { ...params } });
      rec.refs.push(['password', password], ['salt', salt]);
      rec.log.push('derive');
      const kek = new Uint8Array(KEK);
      const verifier = new Uint8Array(VERIFIER);
      rec.refs.push(['kek', kek], ['verifier', verifier]);
      return { kek, verifier };
    },
    register: async (members) => {
      rec.register.push({
        username: members.username, salt: new Uint8Array(members.salt), kdfParams: { ...members.kdfParams },
        kdfVersion: members.kdfVersion, verifier: new Uint8Array(members.verifier),
        installCode: members.installCode,
      });
      rec.log.push('register');
      return { ok: true, username: members.username, role: 'admin' };
    },
    login: async (username, verifierText) => {
      rec.login.push({ username, verifierText });
      rec.log.push('login');
      return { status: 200, body: { token: TOKEN, wrapped_dek: null, wrapped_box_privkey: null, box_pubkey: null } };
    },
    bootstrap: async (kek) => {
      rec.bootstrap.push(new Uint8Array(kek));
      rec.log.push('bootstrap');
      return bootstrapAnswer();
    },
    postKeys: async (token, members) => {
      rec.postKeys.push({ token, members: { ...members } });
      rec.log.push('postKeys');
      return { ok: true, keyVersion: 1 };
    },
    ...overrides,
  };
  return { deps, rec };
}

function ready(overrides = {}, input = {}) {
  const { deps, rec } = makeDeps(overrides);
  const register = createRegister(deps);
  return { register, rec, input: { username: USERNAME, typedText: TYPED, ...input } };
}

test('the default derivation parameters are the Android floor and the version is one', () => {
  assert.deepEqual(DEFAULT_KDF_PARAMS, { memoryKib: 65536, iterations: 3, parallelism: 1 });
});

test('a happy keyless registration runs every step in order and reports ok', async () => {
  const { register, rec, input } = ready();
  const result = await register.run(input);
  assert.deepEqual(result, { ok: true, username: USERNAME, role: 'admin', keyVersion: 1 });
  assert.deepEqual(rec.log, ['random', 'derive', 'register', 'login', 'bootstrap', 'postKeys']);
});

test('the salt is exactly sixteen bytes drawn from the injected source, once', async () => {
  const { register, rec, input } = ready();
  await register.run(input);
  assert.deepEqual(rec.random, [16]);
  assert.equal(rec.register.length, 1);
  assert.deepEqual(rec.register[0].salt, new Uint8Array(SALT));
});

test('a source that keeps and mutates one buffer cannot change the salt that was registered', async () => {
  let raw = new Uint8Array(SALT);
  const { register, rec, input } = ready({ random: (size) => { rec.random.push(size); const copy = new Uint8Array(raw); raw = new Uint8Array(size); return copy; } });
  await register.run(input);
  assert.deepEqual(rec.register[0].salt, new Uint8Array(SALT));
});

test('the derivation gets the NFC password bytes, the drawn salt and the floor parameters', async () => {
  const { register, rec, input = {} } = ready({}, { typedText: 'cafe\u0301-password-long' });
  await register.run(input);
  assert.deepEqual(rec.derive.length, 1);
  assert.deepEqual(Array.from(rec.derive[0].password), Array.from(passwordToBytes('caf\u00e9-password-long')));
  assert.deepEqual(Array.from(rec.derive[0].salt), Array.from(SALT));
  assert.deepEqual(rec.derive[0].params, { memoryKib: 65536, iterations: 3, parallelism: 1, outputLength: 32 });
});

test('the register call carries the verifier, the floor params as wire names and version one', async () => {
  const { register, rec, input } = ready({}, { installCode: CODE });
  await register.run(input);
  assert.deepEqual(rec.register[0], {
    username: USERNAME,
    salt: new Uint8Array(SALT),
    kdfParams: { memory_kib: 65536, iterations: 3, parallelism: 1 },
    kdfVersion: 1,
    verifier: new Uint8Array(VERIFIER),
    installCode: CODE,
  });
});

test('no install code is sent as undefined when the field was left blank', async () => {
  const { register, rec, input } = ready({}, { installCode: '' });
  await register.run(input);
  assert.equal(rec.register[0].installCode, undefined);
});

test('the first login sends the username and the padded base64 verifier', async () => {
  const { register, rec, input } = ready();
  await register.run(input);
  assert.deepEqual(rec.login, [{ username: USERNAME, verifierText: encodeBase64Padded(VERIFIER) }]);
});

test('the bootstrap gets the kek and the upload gets the three byte members', async () => {
  const { register, rec, input } = ready();
  await register.run(input);
  assert.deepEqual(rec.bootstrap, [new Uint8Array(KEK)]);
  assert.deepEqual(rec.postKeys, [{
    token: TOKEN,
    members: {
      wrappedDek: WRAPPED_DEK,
      wrappedBoxPrivkey: WRAPPED_BOX,
      boxPubkey: BOX_PUB,
    },
  }]);
});

test('an account that already has both keys skips the bootstrap and still reports ok', async () => {
  const wrapped = encodeBase64Padded(patterned(0x70, 60));
  const seen = [];
  const { register, rec, input } = ready({
    login: async () => { seen.push('login'); return { status: 200, body: { token: TOKEN, wrapped_dek: wrapped, wrapped_box_privkey: wrapped, box_pubkey: encodeBase64Padded(BOX_PUB) } }; },
  });
  const result = await register.run(input);
  assert.equal(result.ok, true);
  assert.deepEqual(rec.log, ['random', 'derive', 'register']);
  assert.deepEqual(seen, ['login']);
  assert.equal(rec.bootstrap.length, 0);
  assert.equal(rec.postKeys.length, 0);
});

// --- refusals: each stops the flow and calls nothing after it ---

test('a password under twelve characters stops before any work', async () => {
  const { register, rec } = ready();
  const result = await register.run({ username: USERNAME, typedText: 'short' });
  assert.deepEqual(result, { ok: false, code: 'password_too_short', message: MESSAGES.password_too_short });
  assert.deepEqual(rec.log, []);
});

test('a username outside the contract pattern stops before any work', async () => {
  const { register, rec } = ready();
  const result = await register.run({ username: 'bad name with spaces', typedText: TYPED });
  assert.deepEqual(result, { ok: false, code: 'bad_username', message: MESSAGES.bad_username });
  assert.deepEqual(rec.log, []);
});

test('a username of sixty five characters stops before any work', async () => {
  const { register, rec } = ready();
  const result = await register.run({ username: 'a'.repeat(65), typedText: TYPED });
  assert.deepEqual(result, { ok: false, code: 'bad_username', message: MESSAGES.bad_username });
  assert.deepEqual(rec.log, []);
});

test('a present install code that is not forty three URL-safe characters stops before any work', async () => {
  const { register, rec } = ready();
  const result = await register.run({ username: USERNAME, typedText: TYPED, installCode: 'too-short' });
  assert.deepEqual(result, { ok: false, code: 'bad_install_code', message: MESSAGES.bad_install_code });
  assert.deepEqual(rec.log, []);
});

test('a blank install code is absent, not malformed, and the run proceeds', async () => {
  const { register, rec, input } = ready({}, { installCode: '' });
  const result = await register.run(input);
  assert.equal(result.ok, true);
  assert.deepEqual(rec.register[0].installCode, undefined);
});

test('a random source that throws gives random_refused and registers nothing', async () => {
  const seen = [];
  const { register, input } = ready({ random: () => { seen.push('random'); throw new Error('offline'); } });
  const result = await register.run(input);
  assert.deepEqual(result, { ok: false, code: 'random_refused', message: MESSAGES.random_refused });
  assert.deepEqual(seen, ['random']);
  assert.deepEqual(result.message.includes('offline'), false);
});

test('a random source answering fifteen bytes gives random_refused', async () => {
  const { register, input } = ready({ random: () => new Uint8Array(15) });
  assert.deepEqual(await register.run(input), { ok: false, code: 'random_refused', message: MESSAGES.random_refused });
});

test('a derive answer of null gives derive_refused', async () => {
  const seen = [];
  const { register, rec, input } = ready({ derive: async () => { seen.push('derive'); return null; } });
  const result = await register.run(input);
  assert.deepEqual(result, { ok: false, code: 'derive_refused', message: MESSAGES.derive_refused });
  assert.deepEqual(seen, ['derive']);
  assert.deepEqual(rec.log, ['random']);
});

test('a derive answer with a thirty one byte kek gives derive_refused', async () => {
  const { register, input } = ready({ derive: async () => ({ kek: new Uint8Array(31), verifier: new Uint8Array(VERIFIER) }) });
  assert.deepEqual(await register.run(input), { ok: false, code: 'derive_refused', message: MESSAGES.derive_refused });
});

test('a throwing derive gives derive_refused, not network', async () => {
  const { register, input } = ready({ derive: async () => { throw new Error('held'); } });
  assert.deepEqual(await register.run(input), { ok: false, code: 'derive_refused', message: MESSAGES.derive_refused });
});

test('a refused registration passes its code and sentence through untouched', async () => {
  const failure = { ok: false, code: 'username_taken', message: 'That username is already registered, so nothing was created.' };
  const seen = [];
  const { register, rec, input } = ready({ register: async () => { seen.push('register'); return failure; } });
  const result = await register.run(input);
  assert.deepEqual(result, failure);
  assert.deepEqual(seen, ['register']);
  assert.deepEqual(rec.log, ['random', 'derive']);
});

test('a throwing register call gives network', async () => {
  const { register, input } = ready({ register: async () => { throw new Error('held'); } });
  assert.deepEqual(await register.run(input), { ok: false, code: 'network', message: MESSAGES.network });
});

test('a login 401 gives bad credentials and stops before the bootstrap', async () => {
  const seen = [];
  const { register, rec, input } = ready({ login: async () => { seen.push('login'); return { status: 401, body: null }; } });
  const result = await register.run(input);
  assert.deepEqual(result, { ok: false, code: 'bad_credentials', message: MESSAGES.bad_credentials });
  assert.deepEqual(seen, ['login']);
  assert.deepEqual(rec.log, ['random', 'derive', 'register']);
  assert.equal(rec.bootstrap.length, 0);
});

test('a login 423 gives the plain locked sentence', async () => {
  const { register, input } = ready({ login: async () => ({ status: 423, body: null }) });
  assert.deepEqual(await register.run(input), { ok: false, code: 'locked', message: MESSAGES.locked });
});

test('a login 503 gives the unavailable sentence, not the network one', async () => {
  const { register, input } = ready({ login: async () => ({ status: 503, body: null }) });
  assert.deepEqual(await register.run(input), { ok: false, code: 'unavailable', message: MESSAGES.unavailable });
  assert.equal(MESSAGES.unavailable, 'The service is unavailable, try again later.');
  assert.notEqual(MESSAGES.unavailable, MESSAGES.network);
});

test('a login answer this module does not know gives network', async () => {
  const { register, input } = ready({ login: async () => ({ status: 500, body: null }) });
  assert.deepEqual(await register.run(input), { ok: false, code: 'network', message: MESSAGES.network });
});

test('a login with an empty token gives network', async () => {
  const { register, input } = ready({ login: async () => ({ status: 200, body: { token: '', wrapped_dek: null, wrapped_box_privkey: null } }) });
  assert.deepEqual(await register.run(input), { ok: false, code: 'network', message: MESSAGES.network });
});

test('an account with only the wrapped DEK null is inconsistent and refuses the bootstrap', async () => {
  const { register, rec, input } = ready({
    login: async () => ({ status: 200, body: { token: TOKEN, wrapped_dek: null, wrapped_box_privkey: 'x', box_pubkey: null } }),
  });
  const result = await register.run(input);
  assert.deepEqual(result, { ok: false, code: 'keys_refused', message: MESSAGES.keys_refused });
  assert.equal(rec.bootstrap.length, 0);
  assert.equal(rec.postKeys.length, 0);
});

test('a throwing bootstrap gives keys_refused and uploads nothing', async () => {
  const { register, rec, input } = ready({ bootstrap: async () => { throw new Error('held'); } });
  const result = await register.run(input);
  assert.deepEqual(result, { ok: false, code: 'keys_refused', message: MESSAGES.keys_refused });
  assert.equal(rec.postKeys.length, 0);
});

test('a bootstrap answer with a fifty nine byte wrapped blob gives keys_refused', async () => {
  const { register, input } = ready({ bootstrap: async () => bootstrapAnswer({ wrappedDek: new Uint8Array(59) }) });
  assert.deepEqual(await register.run(input), { ok: false, code: 'keys_refused', message: MESSAGES.keys_refused });
});

test('a refused keys upload gives upload_refused', async () => {
  const { register, input } = ready({ postKeys: async () => ({ ok: false, code: 'conflict', message: 'This account already has keys, so nothing was stored.' }) });
  assert.deepEqual(await register.run(input), { ok: false, code: 'upload_refused', message: MESSAGES.upload_refused });
});

// --- secret handling ---

test('every secret array the fakes were handed is zeroed after a finished run', async () => {
  const { register, rec, input } = ready();
  await register.run(input);
  assert.ok(rec.refs.length >= 4);
  for (const [name, bytes] of rec.refs) {
    assert.equal(isZero(bytes), true, name + ' was not zeroed');
  }
});

test('the bootstrap dek and box private key are zeroed after a finished run', async () => {
  let held;
  const { register, input } = ready({
    bootstrap: async () => { held = bootstrapAnswer(); return held; },
  });
  const result = await register.run(input);
  assert.equal(result.ok, true);
  assert.equal(isZero(held.dek), true, 'the bootstrap dek must be zeroed after a finished run');
  assert.equal(isZero(held.boxPrivkey), true, 'the bootstrap box private key must be zeroed after a finished run');
});

test('the bootstrap dek and box private key are zeroed when the upload throws', async () => {
  let held;
  const { register, input } = ready({
    bootstrap: async () => { held = bootstrapAnswer(); return held; },
    postKeys: async () => { throw new Error('held'); },
  });
  const result = await register.run(input);
  assert.deepEqual(result, { ok: false, code: 'upload_refused', message: MESSAGES.upload_refused });
  assert.equal(isZero(held.dek), true, 'the bootstrap dek must be zeroed when the upload throws');
  assert.equal(isZero(held.boxPrivkey), true, 'the bootstrap box private key must be zeroed when the upload throws');
});

test('the bootstrap dek and box private key are zeroed when the answer shape is refused', async () => {
  let held;
  const { register, input } = ready({
    bootstrap: async () => { held = bootstrapAnswer({ wrappedDek: new Uint8Array(59) }); return held; },
  });
  const result = await register.run(input);
  assert.deepEqual(result, { ok: false, code: 'keys_refused', message: MESSAGES.keys_refused });
  assert.equal(isZero(held.dek), true, 'the bootstrap dek must be zeroed on the early return');
  assert.equal(isZero(held.boxPrivkey), true, 'the bootstrap box private key must be zeroed on the early return');
});

test('a valid kek is zeroed even when the verifier from derive is malformed', async () => {
  const { deps, rec } = makeDeps();
  deps.derive = async (password, salt, params) => {
    const kek = new Uint8Array(KEK);
    const verifier = new Uint8Array(VERIFIER).subarray(0, 31);
    rec.refs.push(['kek', kek], ['verifier', verifier]);
    return { kek, verifier };
  };
  const register = createRegister(deps);
  const result = await register.run({ username: USERNAME, typedText: TYPED });
  assert.equal(result.ok, false);
  assert.equal(result.code, 'derive_refused');
  const kekRef = rec.refs.find(([name]) => name === 'kek')[1];
  assert.equal(isZero(kekRef), true, 'the kek handed to derive must be zeroed on this path');
});

test('the password array is zeroed even when the registration is refused', async () => {
  const { register, rec, input } = ready({ register: async () => ({ ok: false, code: 'username_taken', message: 'x' }) });
  await register.run(input);
  const passwordRef = rec.refs.find(([name]) => name === 'password');
  assert.equal(isZero(passwordRef[1]), true);
});

test('no result carries the session token or a secret byte', async () => {
  const { register, input } = ready();
  const result = await register.run(input);
  assert.equal('token' in result, false);
  assert.equal(JSON.stringify(result).includes(TOKEN), false);
});

test('the real sign-in stretch is reached and the Android vector verifier is registered', async () => {
  const seen = [];
  const { register, rec, input } = ready({
    random: () => new Uint8Array(fromHex(MAIN.saltHex)),
    derive: async (password, salt, params) => {
      seen.push({ password: Array.from(password), salt: Array.from(salt), params: { ...params } });
      return realDerive(password, salt, params);
    },
  }, { typedText: MAIN.password });
  const result = await register.run(input);
  assert.equal(result.ok, true);
  assert.deepEqual(seen[0].password, Array.from(passwordToBytes(MAIN.password)));
  assert.deepEqual(seen[0].salt, Array.from(fromHex(MAIN.saltHex)));
  assert.deepEqual(seen[0].params, { memoryKib: MAIN.memoryKib, iterations: MAIN.iterations, parallelism: MAIN.parallelism, outputLength: 32 });
  assert.deepEqual(Array.from(rec.register[0].verifier), Array.from(fromHex(MAIN.verifier)));
  assert.equal(rec.login[0].verifierText, encodeBase64Padded(fromHex(MAIN.verifier)));
});
