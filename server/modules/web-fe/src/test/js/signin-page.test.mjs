import test from 'node:test';
import assert from 'node:assert/strict';
import { MESSAGES } from '../../main/resources/web/js/signin.js';
import { PAGE_MESSAGES } from '../../main/resources/web/js/signin-page.js';
import {
  CLEAN_URL, fakeCrypto, fillForm, happyRoutes, mountPage, pagehideHandler, response, submitEvent,
  submitHandler, unavailableCrypto, until,
} from './signin-page-fixtures.mjs';
import { KEK, VERIFIER, deferred, isZero, saltAnswer, typedText } from './signin-fixtures.mjs';

const NOT_AVAILABLE = 'Signing in through the website is not available yet, so this page cannot hand your sign-in to the app.';
const BAD_SETUP = 'This page could not be set up safely, so nothing was sent.';
const SIGNED_IN = 'Signed in. Returning to the app.';

test('crypto unavailable disables the inputs and the submit', () => {
  const m = mountPage({ crypto: unavailableCrypto([]) });
  assert.equal(m.els['signin-username'].disabled, true);
  assert.equal(m.els['signin-password'].disabled, true);
  assert.equal(m.els['signin-submit'].disabled, true);
  assert.equal(m.els['signin-form'].hidden, false);
});

test('crypto unavailable shows the exact not available message once', () => {
  const m = mountPage({ crypto: unavailableCrypto([]) });
  assert.equal(PAGE_MESSAGES.not_available, NOT_AVAILABLE);
  assert.deepEqual(m.els['signin-status'].writes, [NOT_AVAILABLE]);
});

test('a truthy crypto available that is not true stays disabled', () => {
  const m = mountPage({ crypto: fakeCrypto([], { available: 1 }) });
  assert.equal(m.els['signin-submit'].disabled, true);
  assert.deepEqual(m.els['signin-status'].writes, [NOT_AVAILABLE]);
});

test('a refused link shows its message and hides the form', () => {
  const m = mountPage({ hash: '' });
  assert.equal(m.els['signin-status'].textContent, MESSAGES.bad_link);
  assert.equal(m.els['signin-form'].hidden, true);
});

test('a refused link disables every control and calls no network', async () => {
  const m = mountPage({ hash: '' });
  assert.equal(m.els['signin-username'].disabled, true);
  assert.equal(m.els['signin-password'].disabled, true);
  assert.equal(m.els['signin-submit'].disabled, true);
  await submitHandler(m)(submitEvent());
  assert.deepEqual(m.calls, []);
});

test('a refused link writes the status exactly once', () => {
  const m = mountPage({ hash: '' });
  assert.deepEqual(m.els['signin-status'].writes, [MESSAGES.bad_link]);
});

test('the link is read first and the fragment is cleared once with the clean url', () => {
  const m = mountPage();
  assert.deepEqual(m.hist.calls, [[null, '', CLEAN_URL]]);
  assert.equal(m.els['signin-submit'].disabled, false);
});

test('the link is read first also when the crypto is unavailable', () => {
  const m = mountPage({ crypto: unavailableCrypto([]) });
  assert.deepEqual(m.hist.calls, [[null, '', CLEAN_URL]]);
});

const BAD_CLEAN_URLS = [
  ['a missing clean url is refused', undefined],
  ['an empty clean url is refused', ''],
  ['a non string clean url is refused', 5],
];

for (const [name, cleanUrl] of BAD_CLEAN_URLS) {
  test(name, () => {
    const m = mountPage({ cleanUrl });
    assert.equal(m.els['signin-status'].textContent, BAD_SETUP);
    assert.equal(m.els['signin-form'].hidden, true);
    assert.equal(m.els['signin-username'].disabled, true);
    assert.equal(m.els['signin-password'].disabled, true);
    assert.equal(m.els['signin-submit'].disabled, true);
    assert.deepEqual(m.hist.calls, []);
    assert.deepEqual(m.win.listeners, []);
    assert.deepEqual(m.calls, []);
  });
}

test('every submit calls preventDefault once', async () => {
  const setups = [
    mountPage(),
    mountPage({ hash: '' }),
    mountPage({ crypto: unavailableCrypto([]) }),
    mountPage({ cleanUrl: '' }),
  ];
  for (const m of setups) {
    const event = submitEvent();
    await submitHandler(m)(event);
    assert.equal(event.count, 1);
  }
});

test('submit with unavailable crypto runs nothing', async () => {
  const log = [];
  const m = mountPage({ crypto: unavailableCrypto(log), log });
  fillForm(m);
  const writesBefore = m.els['signin-status'].writes.length;
  await submitHandler(m)(submitEvent());
  assert.deepEqual(m.calls, []);
  assert.deepEqual(log, []);
  assert.equal(m.els['signin-status'].writes.length, writesBefore);
});

test('the username is sent exactly as typed', async () => {
  const m = mountPage();
  m.els['signin-username'].value = '  account one  ';
  m.els['signin-password'].value = typedText;
  await submitHandler(m)(submitEvent());
  assert.deepEqual(m.calls[0].body, { username: '  account one  ' });
});

test('the typed text reaches the key derivation', async () => {
  const seen = [];
  const log = [];
  const crypto = fakeCrypto(log, {
    derive: async (password) => {
      seen.push(new Uint8Array(password));
      return { kek: new Uint8Array(KEK), verifier: new Uint8Array(VERIFIER) };
    },
  });
  const m = mountPage({ log, crypto, answers: happyRoutes({ '/v1/auth/login': async () => response(401) }) });
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(seen.length, 1);
  assert.deepEqual(seen[0], new TextEncoder().encode(typedText));
});

test('the password field is emptied after a failed run', async () => {
  const m = mountPage({ answers: happyRoutes({ '/v1/auth/login': async () => response(401) }) });
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-password'].value, '');
  assert.equal(m.els['signin-status'].textContent, MESSAGES.bad_credentials);
});

test('the password field is emptied after a successful run', async () => {
  const m = mountPage();
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-password'].value, '');
});

test('the password field is emptied after a thrown run', async () => {
  const m = mountPage();
  fillForm(m);
  m.els['signin-status'].throwOn = SIGNED_IN;
  await assert.rejects(submitHandler(m)(submitEvent()), /status write refused/);
  assert.equal(m.els['signin-password'].value, '');
});

test('every message is set with textContent and each status write is the message', async () => {
  const m = mountPage({ answers: happyRoutes({ '/v1/auth/login': async () => response(401) }) });
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-status'].textContent, MESSAGES.bad_credentials);
  assert.deepEqual(m.els['signin-status'].writes, [MESSAGES.bad_credentials]);
});

test('the status shows the signed in text on success', async () => {
  const m = mountPage();
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-status'].textContent, SIGNED_IN);
  assert.equal(m.loc.replaced.length, 1);
});

test('the submit is disabled while a run is held', async () => {
  const hold = deferred();
  const m = mountPage({
    answers: happyRoutes({ '/v1/auth/salt': () => hold.promise, '/v1/auth/login': async () => response(401) }),
  });
  fillForm(m);
  const pending = submitHandler(m)(submitEvent());
  await until(() => m.calls.length === 1);
  try {
    assert.equal(m.els['signin-submit'].disabled, true);
  } finally {
    hold.resolve(response(200, saltAnswer().body));
    await pending;
  }
  assert.equal(m.els['signin-submit'].disabled, false);
});

test('the submit stays disabled after a successful run', async () => {
  const m = mountPage();
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-submit'].disabled, true);
});

test('a second submit during a run starts no second run', async () => {
  const hold = deferred();
  const m = mountPage({
    answers: happyRoutes({ '/v1/auth/salt': () => hold.promise, '/v1/auth/login': async () => response(401) }),
  });
  fillForm(m);
  const pending = submitHandler(m)(submitEvent());
  await until(() => m.calls.length === 1);
  const second = submitHandler(m)(submitEvent());
  try {
    assert.equal(m.calls.length, 1);
  } finally {
    hold.resolve(response(200, saltAnswer().body));
    await pending;
    await second;
  }
  assert.deepEqual(m.calls.map((call) => call.url), ['/v1/auth/salt', '/v1/auth/login']);
});

test('pagehide stops a held run and zeroes its password array before the release', async () => {
  const hold = deferred();
  const seen = [];
  const log = [];
  const crypto = fakeCrypto(log, {
    derive: (password) => { seen.push(password); return hold.promise; },
  });
  const m = mountPage({ log, crypto });
  fillForm(m);
  const pending = submitHandler(m)(submitEvent());
  await until(() => seen.length === 1);
  assert.deepEqual(seen[0], new TextEncoder().encode(typedText));
  assert.equal(isZero(seen[0]), false);
  try {
    pagehideHandler(m)();
    assert.equal(isZero(seen[0]), true);
    assert.equal(m.els['signin-password'].value, '');
  } finally {
    hold.resolve({ kek: new Uint8Array(KEK), verifier: new Uint8Array(VERIFIER) });
    await pending;
  }
  assert.equal(m.els['signin-status'].textContent, MESSAGES.network);
  assert.deepEqual(m.calls.map((call) => call.url), ['/v1/auth/salt']);
});

test('pagehide empties the password field', () => {
  const m = mountPage();
  m.els['signin-password'].value = typedText;
  pagehideHandler(m)();
  assert.equal(m.els['signin-password'].value, '');
});

test('pagehide is registered on the window once', () => {
  const m = mountPage();
  assert.equal(m.win.listeners.filter((entry) => entry.type === 'pagehide').length, 1);
});

test('the three page messages are the contract texts', () => {
  assert.deepEqual(Object.keys(PAGE_MESSAGES), ['not_available', 'bad_setup', 'signed_in']);
  assert.equal(PAGE_MESSAGES.not_available, NOT_AVAILABLE);
  assert.equal(PAGE_MESSAGES.bad_setup, BAD_SETUP);
  assert.equal(PAGE_MESSAGES.signed_in, SIGNED_IN);
  assert.equal(Object.isFrozen(PAGE_MESSAGES), true);
});

test('a second submit after a failed run starts a second run', async () => {
  const m = mountPage({ answers: happyRoutes({ '/v1/auth/login': async () => response(401) }) });
  fillForm(m);
  await submitHandler(m)(submitEvent());
  assert.equal(m.els['signin-password'].value, '');
  const changedTyped = typedText.toUpperCase();
  m.els['signin-password'].value = changedTyped;
  await submitHandler(m)(submitEvent());
  assert.deepEqual(m.calls.map((call) => call.url), ['/v1/auth/salt', '/v1/auth/login', '/v1/auth/salt', '/v1/auth/login']);
  assert.equal(m.els['signin-password'].value, '');
  assert.equal(m.els['signin-status'].textContent, MESSAGES.bad_credentials);
});

test('importing the page with no document registers nothing', () => {
  assert.equal(typeof globalThis.document, 'undefined');
  assert.equal(typeof globalThis.window, 'undefined');
});
