import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGES } from '../../main/resources/web/js/signin.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import {
  BOOTSTRAP_BOX_KEY, BOOTSTRAP_BOX_PUB, BOOTSTRAP_DEK, BOX_PUB, CODE_TEXT, DEK, GOOD_KDF, KEK, bootstrapAnswer,
  bootstrapDeps, flipped, isZero, loginAnswer, makeInput, patterned, readySignin, saltAnswer, sessionText, wrapped,
} from './signin-fixtures.mjs';

// Runs the flow with the given overrides and checks the refusal code and its fixed message.
async function refused(overrides, code, input = makeInput()) {
  const { rec, signin } = readySignin(overrides);
  const result = await signin.run(input);
  assert.equal(result.ok, false);
  assert.equal(result.code, code);
  assert.equal(result.message, MESSAGES[code]);
  return { result, rec, input };
}

test('salt route 404 gives bad credentials with the same message as a 401 login', async () => {
  const salt = await refused({ getSalt: async () => ({ status: 404, body: null }) }, 'bad_credentials');
  const login = await refused({ login: async () => ({ status: 401, body: null }) }, 'bad_credentials');
  assert.equal(salt.result.message, login.result.message);
  assert.equal(salt.rec.derive.length, 0);
  assert.equal(login.rec.seal.length, 0);
});

test('salt route 404 with a complete salt body still gives bad credentials before derive', async () => {
  const { rec } = await refused({ getSalt: async () => ({ status: 404, body: saltAnswer().body }) }, 'bad_credentials');
  assert.equal(rec.derive.length, 0);
});

test('salt answer without kdf params gives bad credentials before derive', async () => {
  const body = saltAnswer().body;
  delete body.kdf_params;
  const { rec } = await refused({ getSalt: async () => ({ status: 200, body }) }, 'bad_credentials');
  assert.equal(rec.derive.length, 0);
});

for (const size of [15, 17]) {
  test('salt of ' + size + ' bytes gives bad salt before derive', async () => {
    const salt = encodeBase64Padded(patterned(0x20, size));
    const { rec } = await refused({ getSalt: async () => saltAnswer({ salt }) }, 'bad_salt');
    assert.equal(rec.derive.length, 0);
  });
}

const KDF_CASES = [
  ['memory one below the floor', { ...GOOD_KDF, memory_kib: 65535 }, 1],
  ['memory one above the ceiling', { ...GOOD_KDF, memory_kib: 262145 }, 1],
  ['iterations one below the floor', { ...GOOD_KDF, iterations: 2 }, 1],
  ['iterations one above the ceiling', { ...GOOD_KDF, iterations: 11 }, 1],
  ['parallelism below the floor', { ...GOOD_KDF, parallelism: 0 }, 1],
  ['parallelism above the ceiling', { ...GOOD_KDF, parallelism: 5 }, 1],
  ['memory that is not an integer', { ...GOOD_KDF, memory_kib: 65536.5 }, 1],
  ['iterations given as text', { ...GOOD_KDF, iterations: '3' }, 1],
  ['kdf version two', GOOD_KDF, 2],
  ['kdf version given as text', GOOD_KDF, '1'],
];

for (const [name, kdf, version] of KDF_CASES) {
  test(name + ' gives kdf refused before derive and before the password is read', async () => {
    const input = makeInput();
    const overrides = { getSalt: async () => saltAnswer({ kdf_params: kdf, kdf_version: version }) };
    const { rec } = await refused(overrides, 'kdf_refused', input);
    assert.equal(rec.derive.length, 0);
    assert.equal(input.reads, 0);
    assert.equal(rec.login.length, 0);
  });
}

test('derive output of 31 bytes gives derive refused and zeroes both arrays', async () => {
  const kek = patterned(0x10, 31);
  const verifier = patterned(0x30, 32);
  const { rec } = await refused({ derive: async () => ({ kek, verifier }) }, 'derive_refused');
  assert.equal(isZero(kek), true);
  assert.equal(isZero(verifier), true);
  assert.equal(rec.login.length, 0);
});

test('derive output of 33 bytes gives derive refused and zeroes both arrays', async () => {
  const kek = patterned(0x10, 32);
  const verifier = patterned(0x30, 33);
  await refused({ derive: async () => ({ kek, verifier }) }, 'derive_refused');
  assert.equal(isZero(kek), true);
  assert.equal(isZero(verifier), true);
});

test('derive answer with a missing verifier gives derive refused and zeroes the kek', async () => {
  const kek = new Uint8Array(KEK);
  const { rec } = await refused({ derive: async () => ({ kek }) }, 'derive_refused');
  assert.equal(isZero(kek), true);
  assert.equal(rec.login.length, 0);
});

test('login 401 gives bad credentials and the derive has run but nothing after it', async () => {
  const { rec } = await refused({ login: async () => ({ status: 401, body: null }) }, 'bad_credentials');
  assert.equal(rec.derive.length, 1);
  assert.equal(rec.box.length, 0);
  assert.equal(rec.seal.length, 0);
});

test('login status 500 gives network', async () => {
  await refused({ login: async () => ({ status: 500, body: null }) }, 'network');
});

test('login that throws gives network', async () => {
  await refused({ login: async () => { throw new Error('down'); } }, 'network');
});

test('getSalt that rejects gives network', async () => {
  await refused({ getSalt: () => Promise.reject(new Error('down')) }, 'network');
});

test('location replace that throws gives network', async () => {
  await refused({ location: { replace() { throw new Error('closed'); } } }, 'network');
});

test('empty session token gives network', async () => {
  await refused({ login: async () => loginAnswer({ token: '' }) }, 'network');
});

test('session token given as a number gives network', async () => {
  await refused({ login: async () => loginAnswer({ token: 5 }) }, 'network');
});

test('a keyless account with both wrapped keys null bootstraps, uploads and hands off with the new keys', async () => {
  const answer = bootstrapAnswer();
  const uploaded = [];
  const { rec, signin } = readySignin({
    ...bootstrapDeps(answer),
    // copied at call time: the flow wipes those arrays before the run ends
    postKeys: async (token, members) => {
      const taken = [members.wrappedDek, members.wrappedBoxPrivkey, members.boxPubkey];
      uploaded.push({ token, bytes: taken.map((m) => new Uint8Array(m)) });
      return { ok: true, keyVersion: 1 };
    },
  });
  assert.deepEqual(await signin.run(makeInput()), { ok: true, summary: 'Pending transcriptions: 0 converted, 0 skipped, 0 failed.' });
  assert.deepEqual(rec.log, ['salt', 'derive', 'login', 'box', 'seal', 'start', 'logout', 'replace']);
  const pkg = rec.seal[0].pkg;
  assert.equal(pkg[0], 1);
  assert.deepEqual(pkg.subarray(1, 33), BOOTSTRAP_DEK);
  assert.deepEqual(pkg.subarray(33, 65), BOOTSTRAP_BOX_KEY);
  assert.deepEqual(uploaded.map((call) => call.token), [sessionText]);
  assert.deepEqual(uploaded[0].bytes, [answer.wrappedDek, answer.wrappedBoxPrivkey, BOOTSTRAP_BOX_PUB]);
});

for (const [name, body] of [
  ['only the wrapped DEK null', { wrapped_dek: null }], ['only the wrapped box key null', { wrapped_box_privkey: null }],
  ['a wrapped DEK present but not decodable', { wrapped_dek: 'not base64 at all' }], ['a wrapped box key present but not decodable', { wrapped_box_privkey: 'not base64 at all' }],
]) {
  test('a wrapped-key answer with ' + name + ' stops with keys refused and uploads nothing', async () => {
    let uploaded = 0;
    const { rec, signin } = readySignin({
      ...bootstrapDeps(),
      loginBody: body,
      postKeys: async () => { uploaded += 1; return { ok: true, keyVersion: 1 }; },
    });
    assert.deepEqual(await signin.run(makeInput()), { ok: false, code: 'keys_refused', message: MESSAGES.keys_refused });
    assert.equal(uploaded, 0);
    assert.deepEqual(rec.log, ['salt', 'derive', 'login', 'logout']);
  });
}

test('a 409 or a failed keys upload stops with one fixed sentence and nothing reaches the app', async () => {
  for (const upload of [
    { ok: false, code: 'conflict', message: 'This account already has keys, so nothing was stored.' },
    { ok: false, code: 'network', message: MESSAGES.network },
  ]) {
    const { rec, signin } = readySignin({ ...bootstrapDeps(), postKeys: async () => upload });
    assert.deepEqual(await signin.run(makeInput()), { ok: false, code: 'upload_refused', message: MESSAGES.upload_refused });
    assert.deepEqual(rec.log, ['salt', 'derive', 'login', 'logout']);
  }
});

test('a bootstrap that throws or answers a bad shape stops with keys refused and uploads nothing', async () => {
  for (const bootstrap of [
    async () => { throw new Error('no entropy'); },
    async () => bootstrapAnswer({ dek: patterned(0xb0, 31) }),
  ]) {
    let uploaded = 0;
    const { rec, signin } = readySignin({
      ...bootstrapDeps(),
      bootstrap,
      postKeys: async () => { uploaded += 1; return { ok: true, keyVersion: 1 }; },
    });
    assert.deepEqual(await signin.run(makeInput()), { ok: false, code: 'keys_refused', message: MESSAGES.keys_refused });
    assert.equal(uploaded, 0);
    assert.equal(rec.seal.length + rec.replaced.length, 0);
  }
});

test('wrapped DEK of 59 bytes gives keys refused', async () => {
  const plain = wrapped(KEK, DEK).subarray(0, 59);
  await refused({ login: async () => loginAnswer({ wrapped_dek: encodeBase64Padded(plain) }) }, 'keys_refused');
});

test('wrapped DEK of 61 bytes gives keys refused', async () => {
  const longer = new Uint8Array(61);
  longer.set(wrapped(KEK, DEK));
  await refused({ login: async () => loginAnswer({ wrapped_dek: encodeBase64Padded(longer) }) }, 'keys_refused');
});

test('a wrapped DEK under another KEK gives keys refused with the same message as a box key under the KEK', async () => {
  const other = patterned(0x11, 32);
  const dekUnderOther = { wrapped_dek: encodeBase64Padded(wrapped(other, DEK)) };
  const boxUnderKek = { wrapped_box_privkey: encodeBase64Padded(wrapped(KEK, patterned(0xc0, 32))) };
  const a = await refused({ login: async () => loginAnswer(dekUnderOther) }, 'keys_refused');
  const b = await refused({ login: async () => loginAnswer(boxUnderKek) }, 'keys_refused');
  assert.equal(a.result.message, b.result.message);
  assert.equal(b.rec.seal.length, 0);
});

test('box_pubkey null gives keys refused and boxPublicKey is never called', async () => {
  const { rec } = await refused({ login: async () => loginAnswer({ box_pubkey: null }) }, 'keys_refused');
  assert.equal(rec.box.length, 0);
  assert.equal(rec.seal.length, 0);
});

for (const size of [31, 33]) {
  test('box_pubkey of ' + size + ' bytes gives keys refused and boxPublicKey is never called', async () => {
    const box_pubkey = encodeBase64Padded(patterned(0xe0, size));
    const { rec } = await refused({ login: async () => loginAnswer({ box_pubkey }) }, 'keys_refused');
    assert.equal(rec.box.length, 0);
  });
}

for (const size of [31, 33]) {
  test('boxPublicKey returning ' + size + ' bytes gives keys refused and zeroes the box key', async () => {
    let seen = null;
    await refused({
      boxPublicKey: async (key) => { seen = key; return patterned(0xe0, size); },
    }, 'keys_refused');
    assert.equal(isZero(seen), true);
  });
}

test('one flipped bit in box_pubkey gives box key mismatch and seal is never called', async () => {
  const box_pubkey = encodeBase64Padded(flipped(BOX_PUB));
  const { rec } = await refused({ login: async () => loginAnswer({ box_pubkey }) }, 'box_key_mismatch');
  assert.equal(rec.seal.length, 0);
  assert.equal(rec.start.length, 0);
  assert.equal(rec.replaced.length, 0);
  assert.equal(isZero(rec.kekRefs[0]), true);
  assert.equal(isZero(rec.boxRefs[0]), true);
});

for (const [name, value] of [
  ['112 bytes', new Uint8Array(112)],
  ['114 bytes', new Uint8Array(114)],
  ['a non array', 'text'],
]) {
  test('seal returning ' + name + ' gives seal refused and no return link', async () => {
    let pkg = null;
    const { rec } = await refused({ seal: async (p) => { pkg = p; return value; } }, 'seal_refused');
    assert.equal(isZero(pkg), true);
    assert.equal(rec.start.length, 0);
    assert.equal(rec.replaced.length, 0);
  });
}

for (const status of [400, 401, 403, 413, 503]) {
  test('start status ' + status + ' gives start refused and no return link', async () => {
    const { rec } = await refused({ startHandoff: async () => ({ status, body: null }) }, 'start_refused');
    assert.equal(rec.replaced.length, 0);
  });
}

test('a code of 42 characters gives bad code and no return link', async () => {
  const { rec } = await refused({ startHandoff: async () => ({ status: 201, body: { code: CODE_TEXT.substring(0, 42) } }) }, 'bad_code');
  assert.equal(rec.replaced.length, 0);
});

test('a code with a bad character gives bad code', async () => {
  const code = CODE_TEXT.substring(0, 10) + '+' + CODE_TEXT.substring(11);
  await refused({ startHandoff: async () => ({ status: 201, body: { code } }) }, 'bad_code');
});

test('a 201 answer with no body gives bad code', async () => {
  await refused({ startHandoff: async () => ({ status: 201, body: null }) }, 'bad_code');
});

test('startHandoff that throws gives network', async () => {
  await refused({ startHandoff: async () => { throw new Error('down'); } }, 'network');
});

test('a refusal at the handoff start wipes every key array', async () => {
  const { rec } = await refused({ startHandoff: async () => ({ status: 401, body: null }) }, 'start_refused');
  assert.equal(rec.boxRefs.length, 1);
  assert.equal(rec.sealRefs.length, 1);
  for (const ref of [...rec.kekRefs, ...rec.verifierRefs, ...rec.boxRefs, ...rec.sealRefs]) {
    assert.equal(isZero(ref), true);
  }
});

test('salt answer with status 200 and no body gives bad credentials', async () => {
  await refused({ getSalt: async () => ({ status: 200, body: null }) }, 'bad_credentials');
});
