import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGES } from '../../main/resources/web/js/signin.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import {
  fillForm, happyRoutes, mountPage, response, submitEvent, submitHandler,
} from './signin-page-fixtures.mjs';
import {
  CHALLENGE_TEXT, CODE_TEXT, SEALED, deferred, sessionText,
} from './signin-fixtures.mjs';

const SIGNED_IN = 'Signed in. Returning to the app.';
const LOCKED_90 = 'The account is locked after too many wrong tries, so try again in 90 seconds.';
const ROUTES = ['/v1/auth/salt', '/v1/auth/login', '/v1/auth/handoff/start', '/v1/auth/logout'];

// Mounts the page with the real network and the real flow, runs one submit, and returns the mounted page.
async function run(overrides = {}, log = []) {
  const m = mountPage({ log, answers: happyRoutes(overrides) });
  fillForm(m);
  await submitHandler(m)(submitEvent());
  return m;
}

test('success calls the four routes in order and then the redirect', async () => {
  const log = [];
  const m = await run({}, log);
  assert.deepEqual(log, [
    'fetch /v1/auth/salt',
    'derive',
    'fetch /v1/auth/login',
    'box',
    'seal',
    'fetch /v1/auth/handoff/start',
    'fetch /v1/auth/logout',
    'replace',
  ]);
  assert.deepEqual(m.calls.map((call) => call.url), ROUTES);
  assert.deepEqual(m.loc.replaced, ['breaker://handoff?code=' + CODE_TEXT]);
  assert.equal(m.els['signin-status'].textContent, SIGNED_IN);
});

test('every request omits credentials and sends no store cache', async () => {
  const m = await run();
  assert.equal(m.calls.length, 4);
  for (const call of m.calls) {
    assert.equal(call.init.credentials, 'omit');
    assert.equal(call.init.cache, 'no-store');
  }
});

test('the salt request carries the username exactly as typed', async () => {
  const m = await run();
  assert.deepEqual(m.calls[0].body, { username: 'account one' });
});

test('the login request carries the username and the verifier text only', async () => {
  const m = await run();
  assert.deepEqual(Object.keys(m.calls[1].body).sort(), ['auth_verifier', 'username']);
  assert.equal(m.calls[1].body.username, 'account one');
  assert.equal(typeof m.calls[1].body.auth_verifier, 'string');
});

test('the start request carries the challenge the sealed package and the bearer token', async () => {
  const m = await run();
  const start = m.calls[2];
  assert.deepEqual(Object.keys(start.body).sort(), ['challenge', 'sealed_package']);
  assert.equal(start.body.challenge, CHALLENGE_TEXT);
  assert.equal(start.body.sealed_package, encodeBase64Padded(SEALED));
  assert.equal(start.init.headers.Authorization, 'Bearer ' + sessionText);
});

test('the logout request carries the bearer token keepalive true and no body', async () => {
  const m = await run();
  const out = m.calls[3];
  assert.equal(out.init.keepalive, true);
  assert.equal(out.init.headers.Authorization, 'Bearer ' + sessionText);
  assert.equal(out.body, null);
});

test('the logout answer is never read', async () => {
  const answer = response(204);
  const m = await run({ '/v1/auth/logout': async () => answer });
  assert.equal(m.calls.length, 4);
  assert.equal(answer.reads, 0);
});

test('a 401 at login gives the generic message and no logout', async () => {
  const m = await run({ '/v1/auth/login': async () => response(401, { error: 'refused' }) });
  assert.equal(m.els['signin-status'].textContent, MESSAGES.bad_credentials);
  assert.deepEqual(m.calls.map((call) => call.url), ['/v1/auth/salt', '/v1/auth/login']);
  assert.deepEqual(m.loc.replaced, []);
});

test('a 423 with Retry-After text 90 shows 90 seconds', async () => {
  const m = await run({ '/v1/auth/login': async () => response(423, null, '90') });
  assert.equal(m.els['signin-status'].textContent, LOCKED_90);
  assert.deepEqual(m.calls.map((call) => call.url), ['/v1/auth/salt', '/v1/auth/login']);
});

test('a rejected salt request gives the one network message', async () => {
  const m = await run({ '/v1/auth/salt': async () => { throw new Error('offline'); } });
  assert.equal(m.els['signin-status'].textContent, MESSAGES.network);
  assert.deepEqual(m.calls.map((call) => call.url), ['/v1/auth/salt']);
  assert.deepEqual(m.loc.replaced, []);
});

test('a failing handoff start after login still ends with one logout call', async () => {
  const m = await run({ '/v1/auth/handoff/start': async () => response(500, { error: 'down' }) });
  assert.equal(m.els['signin-status'].textContent, MESSAGES.start_refused);
  assert.equal(m.calls.filter((call) => call.url === '/v1/auth/logout').length, 1);
  assert.deepEqual(m.loc.replaced, []);
});

test('a stuck logout request does not hold up the redirect', async () => {
  const hold = deferred();
  const m = await run({ '/v1/auth/logout': () => hold.promise });
  assert.equal(m.els['signin-status'].textContent, SIGNED_IN);
  assert.deepEqual(m.loc.replaced, ['breaker://handoff?code=' + CODE_TEXT]);
});
