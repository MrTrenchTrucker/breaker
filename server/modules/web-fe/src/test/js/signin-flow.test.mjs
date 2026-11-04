import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGES } from '../../main/resources/web/js/signin.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import {
  BOX_PUB, CODE_TEXT, DEK, GOOD_KDF, KEK, flipped, isZero, loginAnswer, makeInput,
  patterned, readySignin, saltAnswer, wrapped,
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

async function lockedWith(retryAfter) {
  const { signin } = readySignin({ login: async () => ({ status: 423, retryAfter, body: null }) });
  return signin.run(makeInput());
}

test('salt route 404 gives bad credentials with the same message as a 401 login', async () => {
  const salt = await refused({ getSalt: async () => ({ status: 404, body: null }) }, 'bad_credentials');
  const login = await refused({ login: async () => ({ status: 401, retryAfter: null, body: null }) }, 'bad_credentials');
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

test('423 with retry after 90 shows 90 seconds', async () => {
  const result = await lockedWith('90');
  assert.equal(result.code, 'locked');
  assert.equal(result.message, 'The account is locked after too many wrong tries, so try again in 90 seconds.');
});

test('423 with retry after 1 shows 1 second', async () => {
  const result = await lockedWith('1');
  assert.equal(result.message, 'The account is locked after too many wrong tries, so try again in 1 second.');
});

for (const [name, retryAfter] of [['zero', '0'], ['text', 'soon'], ['a fraction', '1.5'], ['no header', null]]) {
  test('423 with retry after ' + name + ' shows the locked message without a number', async () => {
    const result = await lockedWith(retryAfter);
    assert.equal(result.code, 'locked');
    assert.equal(result.message, MESSAGES.locked);
    assert.equal(/[0-9]/.test(result.message), false);
  });
}

test('login 401 gives bad credentials and the derive has run but nothing after it', async () => {
  const { rec } = await refused({ login: async () => ({ status: 401, retryAfter: null, body: null }) }, 'bad_credentials');
  assert.equal(rec.derive.length, 1);
  assert.equal(rec.box.length, 0);
  assert.equal(rec.seal.length, 0);
});

test('login status 500 gives network', async () => {
  await refused({ login: async () => ({ status: 500, retryAfter: null, body: null }) }, 'network');
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

for (const [name, body] of [
  ['both wrapped keys null', { wrapped_dek: null, wrapped_box_privkey: null }],
  ['only the wrapped DEK null', { wrapped_dek: null }],
  ['only the wrapped box key null', { wrapped_box_privkey: null }],
]) {
  test('keyless account with ' + name + ' gives keyless', async () => {
    const { rec } = await refused({ login: async () => loginAnswer(body) }, 'keyless');
    assert.equal(rec.derive.length, 1);
    assert.equal(rec.box.length, 0);
  });
}

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

test('423 with retry after given as a number shows the locked message without a number', async () => {
  const result = await lockedWith(90);
  assert.equal(result.message, MESSAGES.locked);
});

test('423 with retry after in hex text shows the locked message without a number', async () => {
  const result = await lockedWith('0x1A');
  assert.equal(result.message, MESSAGES.locked);
});

test('423 with a retry after value too large to count shows no number', async () => {
  const result = await lockedWith('9'.repeat(20));
  assert.equal(result.message, MESSAGES.locked);
});

test('salt answer with status 200 and no body gives bad credentials', async () => {
  await refused({ getSalt: async () => ({ status: 200, body: null }) }, 'bad_credentials');
});
