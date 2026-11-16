import test from 'node:test';
import assert from 'node:assert/strict';
import { createAdminUsersNet } from '../../main/resources/web/js/admin-users-net.js';

const BASE = 'https://breaker.example';
const TOKEN = 'session-token-7';

// A fake response: a status, a headers.get that answers nothing, and a json
// that parses the text or throws when the text is not JSON.
function fakeResponse({ status = 200, text = '{}' } = {}) {
  return {
    status,
    headers: { get() { return null; } },
    json: async () => JSON.parse(text),
  };
}

// A fake fetch that records every call with the value of this inside the call.
function recordingFetch(response) {
  const calls = [];
  const fetchFn = function (url, init) {
    calls.push({ url, init, self: this });
    return Promise.resolve(response);
  };
  return { fetchFn, calls };
}

test('setRole POSTs the roles route with a JSON body, the bearer token and no cookies', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"username":"alice","role":"admin"}' }));
  const answer = await createAdminUsersNet({ baseUrl: BASE, fetchFn }).setRole(TOKEN, 'alice', true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/admin/roles');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json' });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.deepEqual(JSON.parse(calls[0].init.body), { username: 'alice', admin: true });
  assert.deepEqual(answer, { status: 200, body: { username: 'alice', role: 'admin' } });
});

test('resetAccount POSTs the reset route with the user name in the JSON body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"reset_code":"CODE"}' }));
  const answer = await createAdminUsersNet({ baseUrl: BASE, fetchFn }).resetAccount(TOKEN, 'alice');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/admin/reset-account');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json' });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.deepEqual(JSON.parse(calls[0].init.body), { username: 'alice' });
  assert.deepEqual(answer, { status: 200, body: { reset_code: 'CODE' } });
});

test('a user name with a space and a slash travels in the body as the raw text', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"username":"a b/c","role":"user"}' }));
  await createAdminUsersNet({ baseUrl: BASE, fetchFn }).setRole(TOKEN, 'a b/c', false);
  assert.deepEqual(JSON.parse(calls[0].init.body), { username: 'a b/c', admin: false });
});

test('an answer whose text is not JSON gives a null body', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 500, text: 'plain words' }));
  assert.deepEqual(await createAdminUsersNet({ baseUrl: BASE, fetchFn }).resetAccount(TOKEN, 'alice'), { status: 500, body: null });
});

test('the fetch function is called as a plain function with no this', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse());
  await createAdminUsersNet({ baseUrl: BASE, fetchFn }).setRole(TOKEN, 'alice', true);
  assert.equal(calls[0].self, undefined);
});

test('a rejecting fetch rejects both calls and is not retried', async () => {
  const calls = [];
  const fetchFn = (url, init) => { calls.push({ url, init }); return Promise.reject(new Error('offline')); };
  const net = createAdminUsersNet({ baseUrl: BASE, fetchFn });
  await assert.rejects(net.setRole(TOKEN, 'alice', true), /offline/);
  await assert.rejects(net.resetAccount(TOKEN, 'alice'), /offline/);
  assert.equal(calls.length, 2, 'each call is tried once and never retried');
});
