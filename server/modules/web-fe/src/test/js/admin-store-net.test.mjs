import test from 'node:test';
import assert from 'node:assert/strict';
import { createAdminStoreNet } from '../../main/resources/web/js/admin-store-net.js';

const BASE = 'https://breaker.example';
const TOKEN = 'session-token-7';

// A fake response: a status, a headers.get that answers nothing, and a json that
// parses the text or throws when the text is not JSON.
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

test('clearStore sends the exact POST with bearer token, JSON body and the status and body back', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"cleared":3}' }));
  const answer = await createAdminStoreNet({ baseUrl: BASE, fetchFn }).clearStore(TOKEN, {
    username: 'alice@example.com',
    confirm: true,
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/admin/clear-store');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, {
    Authorization: 'Bearer ' + TOKEN,
    'Content-Type': 'application/json',
  });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal(calls[0].init.body, JSON.stringify({ username: 'alice@example.com', confirm: true }));
  assert.deepEqual(answer, { status: 200, body: { cleared: 3 } });
});

test('clearStore sends the username raw in the body, never encoded', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"cleared":1}' }));
  const name = 'alice/ two@example.com';
  await createAdminStoreNet({ baseUrl: BASE, fetchFn }).clearStore(TOKEN, { username: name, confirm: true });
  const body = JSON.parse(calls[0].init.body);
  assert.equal(body.username, name, 'the name must travel exactly as it was typed');
  assert.equal(calls[0].url, BASE + '/v1/admin/clear-store', 'the name never travels in the path');
});

test('clearStore sends a null username for all users', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"cleared":9}' }));
  await createAdminStoreNet({ baseUrl: BASE, fetchFn }).clearStore(TOKEN, { username: null, confirm: true });
  assert.equal(calls[0].init.body, JSON.stringify({ username: null, confirm: true }));
});

test('clearStore sends confirm false when confirm is not the boolean true', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 400, text: '{"error":"bad_request"}' }));
  await createAdminStoreNet({ baseUrl: BASE, fetchFn }).clearStore(TOKEN, {
    username: 'alice@example.com',
    confirm: false,
  });
  assert.equal(calls[0].init.body, JSON.stringify({ username: 'alice@example.com', confirm: false }));
});

test('clearStore gives a null body when the answer is not JSON', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 500, text: 'plain words' }));
  assert.deepEqual(
    await createAdminStoreNet({ baseUrl: BASE, fetchFn }).clearStore(TOKEN, { username: null, confirm: true }),
    { status: 500, body: null },
  );
});

test('the fetch function is called as a plain function with no this', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse());
  await createAdminStoreNet({ baseUrl: BASE, fetchFn }).clearStore(TOKEN, { username: null, confirm: true });
  assert.equal(calls[0].self, undefined);
});

test('clearStore puts the token only in the Authorization header, never in the URL or the body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"cleared":0}' }));
  await createAdminStoreNet({ baseUrl: BASE, fetchFn }).clearStore(TOKEN, { username: null, confirm: true });
  assert.equal(calls[0].url.includes(TOKEN), false);
  assert.equal(calls[0].init.body.includes(TOKEN), false);
  assert.equal(calls[0].init.headers.Authorization, 'Bearer ' + TOKEN);
});

test('a rejecting fetch rejects the call', async () => {
  const net = createAdminStoreNet({ baseUrl: BASE, fetchFn: () => Promise.reject(new Error('offline')) });
  await assert.rejects(net.clearStore(TOKEN, { username: null, confirm: true }), /offline/);
});
