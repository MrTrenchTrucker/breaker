import test from 'node:test';
import assert from 'node:assert/strict';
import { createDevicesNet } from '../../main/resources/web/js/devices-net.js';

const BASE = 'https://breaker.example';
const TOKEN = 'session-token-7';

// A fake response: a status, a headers.get that answers nothing, and a json that parses the text
// or throws when the text is not JSON.
function fakeResponse({ status = 200, text = '{}' } = {}) {
  return {
    status,
    headers: { get() { return null; } },
    json: async () => JSON.parse(text),
  };
}

// A fake fetch that records every call with the value of this inside the call, and answers
// with the response.
function recordingFetch(response) {
  const calls = [];
  const fetchFn = function (url, init) {
    calls.push({ url, init, self: this });
    return Promise.resolve(response);
  };
  return { fetchFn, calls };
}

test('listDevices sends the exact GET with bearer token and returns status and body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"devices":[{"id":"1"}]}' }));
  const answer = await createDevicesNet({ baseUrl: BASE, fetchFn }).listDevices(TOKEN);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/auth/devices');
  assert.equal(calls[0].init.method, 'GET');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.equal('Content-Type' in calls[0].init.headers, false);
  assert.deepEqual(answer, { status: 200, body: { devices: [{ id: '1' }] } });
});

test('signOutDevice DELETEs the id encoded into the path with no body and no Content-Type', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 204, text: '' }));
  const answer = await createDevicesNet({ baseUrl: BASE, fetchFn }).signOutDevice(TOKEN, 'phone one/ two');
  assert.equal(calls[0].url, BASE + '/v1/auth/devices/' + encodeURIComponent('phone one/ two'));
  assert.equal(calls[0].init.method, 'DELETE');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.equal('Content-Type' in calls[0].init.headers, false);
  assert.deepEqual(answer, { status: 204, body: null });
});

test('signOutAllDevices POSTs sign-out-all with no body and no Content-Type', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 204, text: '' }));
  const answer = await createDevicesNet({ baseUrl: BASE, fetchFn }).signOutAllDevices(TOKEN);
  assert.equal(calls[0].url, BASE + '/v1/auth/devices/sign-out-all');
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.equal('Content-Type' in calls[0].init.headers, false);
  assert.deepEqual(answer, { status: 204, body: null });
});

test('listDevices gives a null body when the answer is not JSON', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 500, text: 'plain words' }));
  assert.deepEqual(await createDevicesNet({ baseUrl: BASE, fetchFn }).listDevices(TOKEN), { status: 500, body: null });
});

test('the fetch function is called as a plain function with no this', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse());
  await createDevicesNet({ baseUrl: BASE, fetchFn }).signOutAllDevices(TOKEN);
  assert.equal(calls[0].self, undefined);
});

test('a rejecting fetch rejects all three calls', async () => {
  const net = createDevicesNet({ baseUrl: BASE, fetchFn: () => Promise.reject(new Error('offline')) });
  await assert.rejects(net.listDevices(TOKEN), /offline/);
  await assert.rejects(net.signOutDevice(TOKEN, '1'), /offline/);
  await assert.rejects(net.signOutAllDevices(TOKEN), /offline/);
});
