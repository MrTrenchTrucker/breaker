import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createAdminSettingsNet } from '../../main/resources/web/js/admin-settings-net.js';

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

test('getWrongPwLimit sends the exact GET with bearer token and no body', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"enabled":true}' }));
  const answer = await createAdminSettingsNet({ baseUrl: BASE, fetchFn }).getWrongPwLimit(TOKEN);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/admin/wrong-pw-limit');
  assert.equal(calls[0].init.method, 'GET');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal('body' in calls[0].init, false);
  assert.equal('Content-Type' in calls[0].init.headers, false);
  assert.deepEqual(answer, { status: 200, body: { enabled: true } });
});

test('setWrongPwLimit sends the exact PUT with a JSON body and content type', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"enabled":false}' }));
  const answer = await createAdminSettingsNet({ baseUrl: BASE, fetchFn }).setWrongPwLimit(TOKEN, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].self, undefined);
  assert.equal(calls[0].url, BASE + '/v1/admin/wrong-pw-limit');
  assert.equal(calls[0].init.method, 'PUT');
  assert.deepEqual(calls[0].init.headers, { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json' });
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.cache, 'no-store');
  assert.equal(calls[0].init.body, JSON.stringify({ enabled: true }));
  assert.deepEqual(answer, { status: 200, body: { enabled: false } });
});

test('getRateLimits and setRateLimits use the rate-limits path', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"enabled":true}' }));
  const net = createAdminSettingsNet({ baseUrl: BASE, fetchFn });
  await net.getRateLimits(TOKEN);
  await net.setRateLimits(TOKEN, false);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].url, BASE + '/v1/admin/rate-limits');
  assert.equal(calls[0].init.method, 'GET');
  assert.equal('body' in calls[0].init, false);
  assert.equal(calls[1].url, BASE + '/v1/admin/rate-limits');
  assert.equal(calls[1].init.method, 'PUT');
  assert.equal(calls[1].init.body, JSON.stringify({ enabled: false }));
  assert.equal(calls[1].init.headers['Content-Type'], 'application/json');
});

test('getRegistrationOpen and setRegistrationOpen use the registration-open path', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse({ status: 200, text: '{"enabled":false}' }));
  const net = createAdminSettingsNet({ baseUrl: BASE, fetchFn });
  await net.getRegistrationOpen(TOKEN);
  await net.setRegistrationOpen(TOKEN, true);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].url, BASE + '/v1/admin/registration-open');
  assert.equal(calls[0].init.method, 'GET');
  assert.equal('body' in calls[0].init, false);
  assert.equal(calls[1].url, BASE + '/v1/admin/registration-open');
  assert.equal(calls[1].init.method, 'PUT');
  assert.equal(calls[1].init.body, JSON.stringify({ enabled: true }));
  assert.equal(calls[1].init.headers['Content-Type'], 'application/json');
});

test('a GET answer that is not JSON gives a null body', async () => {
  const { fetchFn } = recordingFetch(fakeResponse({ status: 500, text: 'plain words' }));
  assert.deepEqual(
    await createAdminSettingsNet({ baseUrl: BASE, fetchFn }).getWrongPwLimit(TOKEN),
    { status: 500, body: null },
  );
});

test('the fetch function is called as a plain function with no this', async () => {
  const { fetchFn, calls } = recordingFetch(fakeResponse());
  await createAdminSettingsNet({ baseUrl: BASE, fetchFn }).setRegistrationOpen(TOKEN, true);
  assert.equal(calls[0].self, undefined);
});

test('a rejecting fetch rejects all six calls', async () => {
  const net = createAdminSettingsNet({ baseUrl: BASE, fetchFn: () => Promise.reject(new Error('offline')) });
  await assert.rejects(net.getWrongPwLimit(TOKEN), /offline/);
  await assert.rejects(net.setWrongPwLimit(TOKEN, true), /offline/);
  await assert.rejects(net.getRateLimits(TOKEN), /offline/);
  await assert.rejects(net.setRateLimits(TOKEN, true), /offline/);
  await assert.rejects(net.getRegistrationOpen(TOKEN), /offline/);
  await assert.rejects(net.setRegistrationOpen(TOKEN, true), /offline/);
});

test('admin-settings-net.js contains no forbidden tokens', () => {
  const source = readFileSync(new URL('../../main/resources/web/js/admin-settings-net.js', import.meta.url), 'utf8');
  for (const token of ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm', 'console.']) {
    assert.equal(source.includes(token), false, 'forbidden token present: ' + token);
  }
});
