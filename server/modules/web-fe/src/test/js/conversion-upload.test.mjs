// The record upload adapter.
//
// POST {baseUrl}/v1/sync with the bearer token, Content-Type application/json,
// no cookies and no cache; the body is exactly {client_id, key_version,
// encrypted} and never resave. A 204 with no body answers CONFIRMED; every other
// status, a body that is not JSON, or a thrown fetch answers FAILED. No token
// answers FAILED and sends nothing.

import test from 'node:test';
import assert from 'node:assert/strict';
import { createRecordUpload } from '../../main/resources/web/js/conversion-upload.js';
import { CLIENT_ID, KEY_VERSION } from './conversion-fixtures.mjs';

const BASE_URL = 'https://example.test';
const TOKEN = 'tok-abc';
const ENCRYPTED = 'c29tZS1mcmFtZQ==';

function harness({ baseUrl = BASE_URL, token = TOKEN, response } = {}) {
  const calls = [];
  const tokenThises = [];
  // A plain function: called without a receiver, its `this` is undefined.
  const fetchFn = function (url, options) {
    calls.push({ url, options, thisValue: this });
    return Promise.resolve(response);
  };
  const getToken = function () {
    tokenThises.push(this);
    return token;
  };
  const upload = createRecordUpload({ baseUrl, fetchFn, getToken });
  return { upload, calls, tokenThises };
}

function noContent() {
  return { status: 204, text: async () => '' };
}

test('a 204 with no body answers CONFIRMED', async () => {
  const { upload } = harness({ response: noContent() });
  assert.equal(await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED), 'CONFIRMED');
});

test('the call is POST {baseUrl}/v1/sync with the bearer token, json content type, no cookies and no cache', async () => {
  const { upload, calls } = harness({ response: noContent() });
  await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED);
  assert.equal(calls.length, 1);
  const { url, options } = calls[0];
  assert.equal(url, `${BASE_URL}/v1/sync`);
  assert.equal(options.method, 'POST');
  assert.deepEqual(options.headers, {
    'Content-Type': 'application/json',
    Authorization: 'Bearer ' + TOKEN,
  });
  assert.equal(options.credentials, 'omit');
  assert.equal(options.cache, 'no-store');
});

test('the body is exactly client_id, key_version and encrypted, with no resave', async () => {
  const { upload, calls } = harness({ response: noContent() });
  await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED);
  const body = JSON.parse(calls[0].options.body);
  assert.deepEqual(Object.keys(body), ['client_id', 'key_version', 'encrypted']);
  assert.deepEqual(body, {
    client_id: CLIENT_ID,
    key_version: KEY_VERSION,
    encrypted: ENCRYPTED,
  });
});

test('the encrypted string is passed through exactly', async () => {
  const { upload, calls } = harness({ response: noContent() });
  await upload(CLIENT_ID, 7, 'AAA=.BBB');
  assert.equal(JSON.parse(calls[0].options.body).encrypted, 'AAA=.BBB');
});

test('every status other than 204 answers FAILED', async () => {
  for (const status of [200, 201, 400, 403, 409, 500]) {
    const { upload } = harness({ response: { status, text: async () => '{"ok":true}' } });
    assert.equal(await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED), 'FAILED', String(status));
  }
});

test('a body that is not JSON answers FAILED even at 204', async () => {
  const { upload } = harness({ response: { status: 204, text: async () => '<html>error</html>' } });
  assert.equal(await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED), 'FAILED');
});

test('a thrown fetch answers FAILED', async () => {
  const fetchFn = () => Promise.reject(new Error('offline'));
  const getToken = () => TOKEN;
  const upload = createRecordUpload({ baseUrl: BASE_URL, fetchFn, getToken });
  let answer;
  assert.doesNotThrow(() => { answer = upload(CLIENT_ID, KEY_VERSION, ENCRYPTED); });
  assert.equal(await answer, 'FAILED');
});

test('a null token answers FAILED and sends nothing', async () => {
  const { upload, calls } = harness({ token: null, response: noContent() });
  assert.equal(await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED), 'FAILED');
  assert.equal(calls.length, 0);
});

test('an empty token answers FAILED and sends nothing', async () => {
  const { upload, calls } = harness({ token: '', response: noContent() });
  assert.equal(await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED), 'FAILED');
  assert.equal(calls.length, 0);
});

test('getToken is called as a plain function, not a method', async () => {
  const { upload, tokenThises } = harness({ response: noContent() });
  await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED);
  assert.equal(tokenThises.length, 1);
  assert.equal(tokenThises[0], undefined);
});

test('fetchFn is called as a plain function, not a method', async () => {
  const { upload, calls } = harness({ response: noContent() });
  await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED);
  assert.equal(calls[0].thisValue, undefined);
});

test('a 204 carrying a JSON body still answers CONFIRMED', async () => {
  const { upload } = harness({ response: { status: 204, text: async () => '{}' } });
  assert.equal(await upload(CLIENT_ID, KEY_VERSION, ENCRYPTED), 'CONFIRMED');
});
