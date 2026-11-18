import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGES, createRegisterNet } from '../../main/resources/web/js/register-net.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';

// The wire members are built in code from patterned bytes, so no base64 text and no
// install-code text is typed here. The fake fetch records every call.
const patterned = (base, size) => Uint8Array.from({ length: size }, (_, i) => (base + i) & 255);
const SALT = patterned(0x20, 16);
const VERIFIER = patterned(0x30, 32);
const CODE = encodeBase64Padded(patterned(0x40, 32)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const USERNAME = 'account-one';
const REGISTER_PATH = '/v1/auth/register';
const JSON_TYPE = 'application/json';

const MEMBERS = {
  username: USERNAME,
  salt: SALT,
  kdfParams: { memory_kib: 65536, iterations: 3, parallelism: 1 },
  kdfVersion: 1,
  verifier: VERIFIER,
};

function bodyText(members = MEMBERS) {
  const body = {
    username: members.username,
    salt: encodeBase64Padded(members.salt),
    kdf_params: members.kdfParams,
    kdf_version: members.kdfVersion,
    auth_verifier: encodeBase64Padded(members.verifier),
  };
  if (members.installCode !== undefined) body.install_code = members.installCode;
  return JSON.stringify(body);
}

// A fake response: a status, a headers.get that answers only Retry-After, and a json parser.
function fakeResponse({ status = 200, text = '{}', retryAfter } = {}) {
  return {
    status,
    headers: { get(name) { return name === 'Retry-After' ? (retryAfter ?? null) : null; } },
    json: async () => JSON.parse(text),
  };
}

function recordingFetch(response) {
  const calls = [];
  const fetchFn = function (url, init) {
    calls.push({ url, init, self: this });
    return Promise.resolve(response);
  };
  return { fetchFn, calls };
}

test('register sends the exact POST with the six members including the install code', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 201, text: '{"username":"alice","role":"admin"}' }));
  await createRegisterNet({ baseUrl: '', fetchFn }).register({ ...MEMBERS, installCode: CODE });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, REGISTER_PATH);
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, { 'Content-Type': JSON_TYPE });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal(calls[0].init.body, bodyText({ ...MEMBERS, installCode: CODE }));
});

test('the install code member is absent, not null, when none was given', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 201, text: '{"username":"alice","role":"user"}' }));
  await createRegisterNet({ baseUrl: '', fetchFn }).register(MEMBERS);
  const sent = JSON.parse(calls[0].init.body);
  assert.equal('install_code' in sent, false);
  assert.equal(calls[0].init.body, bodyText(MEMBERS));
});

test('a 201 answer gives ok with the server username and role', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 201, text: '{"username":"alice","role":"admin"}' }));
  assert.deepEqual(await createRegisterNet({ baseUrl: '', fetchFn }).register(MEMBERS),
    { ok: true, username: 'alice', role: 'admin' });
});

const STATUS_CODES = { 400: 'bad_request', 403: 'registration_closed', 409: 'username_taken', 413: 'too_large', 429: 'rate_limited' };

for (const [status, code] of Object.entries(STATUS_CODES)) {
  test('status ' + status + ' gives exactly one fixed sentence (' + code + ')', async () => {
    const { fetchFn } = recordingFetch(fakeResponse({ status: Number(status), text: '{"error":"x"}' }));
    const answer = await createRegisterNet({ baseUrl: '', fetchFn }).register(MEMBERS);
    assert.deepEqual(answer, { ok: false, code, message: MESSAGES[code] });
    assert.equal(typeof answer.message, 'string');
    assert.notEqual(answer.message, '');
  });
}

test('a 429 never surfaces the Retry-After value as a number or as text', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 429, text: '{"error":"rate_limited"}', retryAfter: '90' }));
  const answer = await createRegisterNet({ baseUrl: '', fetchFn }).register(MEMBERS);
  assert.deepEqual(answer, { ok: false, code: 'rate_limited', message: MESSAGES.rate_limited });
  assert.equal('retryAfter' in answer, false);
  assert.equal(JSON.stringify(answer).includes('90'), false);
  assert.equal(MESSAGES.rate_limited.match(/[0-9]/), null);
});

test('a 429 with no Retry-After header still gives the same fixed sentence', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 429, text: '{"error":"rate_limited"}' }));
  assert.deepEqual(await createRegisterNet({ baseUrl: '', fetchFn }).register(MEMBERS),
    { ok: false, code: 'rate_limited', message: MESSAGES.rate_limited });
});

test('status 503 gives the unavailable sentence', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 503, text: '{"error":"unavailable"}' }));
  assert.deepEqual(await createRegisterNet({ baseUrl: '', fetchFn }).register(MEMBERS),
    { ok: false, code: 'unavailable', message: MESSAGES.unavailable });
});

for (const status of [200, 204, 418, 500]) {
  test('status ' + status + ' outside the named set gives the unknown sentence', async () => {
    const { fetchFn } = recordingFetch(fakeResponse({ status, text: '{}' }));
    assert.deepEqual(await createRegisterNet({ baseUrl: '', fetchFn }).register(MEMBERS),
      { ok: false, code: 'unknown', message: MESSAGES.unknown });
  });
}

test('a 201 answer with no usable body gives the unknown sentence', async () => {
  for (const text of ['{}', '{"username":"alice"}', '{"username":"alice","role":3}', 'plain words']) {
    const { fetchFn } = recordingFetch(fakeResponse({ status: 201, text }));
    assert.deepEqual(await createRegisterNet({ baseUrl: '', fetchFn }).register(MEMBERS),
      { ok: false, code: 'unknown', message: MESSAGES.unknown }, 'body ' + text);
  }
});

test('a non-JSON answer still maps its status', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 400, text: 'plain words' }));
  assert.deepEqual(await createRegisterNet({ baseUrl: '', fetchFn }).register(MEMBERS),
    { ok: false, code: 'bad_request', message: MESSAGES.bad_request });
});

test('a rejecting fetch gives the network sentence', async () => {
  const answer = await createRegisterNet({ baseUrl: '', fetchFn: () => Promise.reject(new Error('offline')) }).register(MEMBERS);
  assert.deepEqual(answer, { ok: false, code: 'network', message: MESSAGES.network });
});

test('a throwing fetch gives the network sentence', async () => {
  const answer = await createRegisterNet({ baseUrl: '', fetchFn: () => { throw new Error('offline'); } }).register(MEMBERS);
  assert.deepEqual(answer, { ok: false, code: 'network', message: MESSAGES.network });
});

const REFUSED = [
  ['a username with a space', { username: 'account one' }],
  ['a username with a bad character', { username: 'account!' }],
  ['a username longer than sixty four characters', { username: 'a'.repeat(65) }],
  ['an empty username', { username: '' }],
  ['a username that is not text', { username: 7 }],
  ['a salt of fifteen bytes', { salt: SALT.subarray(0, 15) }],
  ['a salt of seventeen bytes', { salt: Uint8Array.from([...SALT, 0]) }],
  ['a salt that is an ordinary array', { salt: Array.from(SALT) }],
  ['memory below the floor', { kdfParams: { memory_kib: 65535, iterations: 3, parallelism: 1 } }],
  ['memory above the ceiling', { kdfParams: { memory_kib: 262145, iterations: 3, parallelism: 1 } }],
  ['iterations below the floor', { kdfParams: { memory_kib: 65536, iterations: 2, parallelism: 1 } }],
  ['iterations above the ceiling', { kdfParams: { memory_kib: 65536, iterations: 11, parallelism: 1 } }],
  ['parallelism below the floor', { kdfParams: { memory_kib: 65536, iterations: 3, parallelism: 0 } }],
  ['parallelism above the ceiling', { kdfParams: { memory_kib: 65536, iterations: 3, parallelism: 5 } }],
  ['kdf params that are missing a field', { kdfParams: { memory_kib: 65536, iterations: 3 } }],
  ['kdf version two', { kdfVersion: 2 }],
  ['kdf version given as text', { kdfVersion: '1' }],
  ['a verifier of thirty one bytes', { verifier: VERIFIER.subarray(0, 31) }],
  ['a verifier of thirty three bytes', { verifier: Uint8Array.from([...VERIFIER, 0]) }],
  ['a verifier that is not bytes', { verifier: 'verifier sample text' }],
  ['an install code of forty two characters', { installCode: CODE.substring(0, 42) }],
  ['an install code of forty four characters', { installCode: CODE + 'z' }],
  ['an install code with a standard base64 character', { installCode: CODE.substring(0, 42) + '+' }],
  ['an install code that is not text', { installCode: 43 }],
];

for (const [name, change] of REFUSED) {
  test('refuses before any call: ' + name, async () => {
    const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 201, text: '{"username":"alice","role":"admin"}' }));
    const answer = await createRegisterNet({ baseUrl: '', fetchFn }).register({ ...MEMBERS, ...change });
    assert.deepEqual(answer, { ok: false, code: 'bad_request', message: MESSAGES.bad_request });
    assert.equal(calls.length, 0);
  });
}

test('a member set that is not an object is refused before any call', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 201, text: '{"username":"alice","role":"admin"}' }));
  assert.deepEqual(await createRegisterNet({ baseUrl: '', fetchFn }).register(null),
    { ok: false, code: 'bad_request', message: MESSAGES.bad_request });
  assert.equal(calls.length, 0);
});

test('the net exports the register call and the frozen sentences', async () => {
  const m = await import('../../main/resources/web/js/register-net.js');
  assert.equal(typeof m.createRegisterNet, 'function');
  assert.equal(typeof m.MESSAGES, 'object');
  assert.equal(Object.isFrozen(m.MESSAGES), true);
  assert.equal(typeof m.createRegisterNet({ baseUrl: '', fetchFn: () => new Promise(() => {}) }).register, 'function');
});
