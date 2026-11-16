import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createAdminSettings, ADMIN_SETTINGS_TEXTS } from '../../main/resources/web/js/admin-settings.js';

// A fake settings-calls object that records every call it is given. The net
// methods are plain functions: they are called as (token, enabled) with no this.
function netOf(answers = {}) {
  const calls = [];
  const make = (op) => async (token, enabled) => {
    calls.push({ op, token, enabled });
    return answers[op] ?? { status: 200, body: { enabled: true } };
  };
  return {
    calls,
    getWrongPwLimit: make('getWrongPwLimit'),
    setWrongPwLimit: make('setWrongPwLimit'),
    getRateLimits: make('getRateLimits'),
    setRateLimits: make('setRateLimits'),
    getRegistrationOpen: make('getRegistrationOpen'),
    setRegistrationOpen: make('setRegistrationOpen'),
  };
}

function renderOf() {
  const states = [];
  const messages = [];
  const render = {
    showStates: (value) => { states.push(value); },
    showMessage: (text) => { messages.push(text); },
  };
  return { render, states, messages };
}

function flowOf(net, render, getToken = () => 'tok-abc') {
  return createAdminSettings({ getToken, net, render });
}

test('load shows the three states the server answered', async () => {
  const net = netOf({
    getWrongPwLimit: { status: 200, body: { enabled: true } },
    getRateLimits: { status: 200, body: { enabled: false } },
    getRegistrationOpen: { status: 200, body: { enabled: true } },
  });
  const { render, states, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, 'ok');
  assert.equal(net.calls.length, 3);
  assert.deepEqual(net.calls.map((call) => call.op), ['getWrongPwLimit', 'getRateLimits', 'getRegistrationOpen']);
  assert.deepEqual(states[states.length - 1], { wrongPwLimit: true, rateLimits: false, registrationOpen: true });
  assert.equal(messages.length, 0);
});

test('load passes the token to every call', async () => {
  const net = netOf();
  const { render } = renderOf();
  await flowOf(net, render, () => 'tok-xyz').load();
  assert.deepEqual(net.calls.map((call) => call.token), ['tok-xyz', 'tok-xyz', 'tok-xyz']);
});

test('load 401 shows the unauthorized sentence for that setting', async () => {
  const net = netOf({ getWrongPwLimit: { status: 401, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, '401');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.wrong_pw_limit_unauthorized);
});

test('load 403 shows the forbidden sentence for that setting', async () => {
  const net = netOf({ getRateLimits: { status: 403, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, '403');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.rate_limits_forbidden);
});

test('load 503 shows the unavailable sentence for that setting', async () => {
  const net = netOf({ getRegistrationOpen: { status: 503, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, '503');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.registration_open_unavailable);
});

test('a GET answer with a status this module does not know shows the unknown sentence', async () => {
  const net = netOf({ getWrongPwLimit: { status: 418, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, '418');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.unknown_status);
});

test('a GET 400 is not a listed GET status and shows the unknown sentence', async () => {
  const net = netOf({ getWrongPwLimit: { status: 400, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, '400');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.unknown_status);
});

test('a 200 whose body is not the one-member state object shows the unknown sentence', async () => {
  const net = netOf({ getWrongPwLimit: { status: 200, body: { enabled: 'yes' } } });
  const { render, messages, states } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, 'unknown');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.unknown_status);
  assert.equal(states[states.length - 1].wrongPwLimit, null);
});

test('a rejected fetch on load shows the network sentence', async () => {
  const net = netOf();
  net.getRateLimits = async () => { throw new Error('down'); };
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).load();
  assert.equal(outcome, 'network');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.network_failed);
});

test('setWrongPwLimit 200 shows the server answer when it differs from the request', async () => {
  const net = netOf({ setWrongPwLimit: { status: 200, body: { enabled: false } } });
  const { render, states, messages } = renderOf();
  const outcome = await flowOf(net, render).setWrongPwLimit(true);
  assert.equal(outcome, 'ok');
  assert.equal(net.calls.length, 1);
  assert.equal(net.calls[0].enabled, true, 'the request asked for true');
  assert.deepEqual(states[states.length - 1], { wrongPwLimit: false }, 'the view is shown the server answer false, not the request true');
  assert.equal(messages[messages.length - 1], ADMIN_SETTINGS_TEXTS.wrong_pw_limit_set_ok);
});

test('setRateLimits 200 shows the server answer when it differs from the request', async () => {
  const net = netOf({ setRateLimits: { status: 200, body: { enabled: true } } });
  const { render, states } = renderOf();
  const outcome = await flowOf(net, render).setRateLimits(false);
  assert.equal(outcome, 'ok');
  assert.equal(net.calls[0].enabled, false, 'the request asked for false');
  assert.deepEqual(states[states.length - 1], { rateLimits: true }, 'the view is shown the server answer true');
});

test('setRegistrationOpen 200 shows the server answer when it differs from the request', async () => {
  const net = netOf({ setRegistrationOpen: { status: 200, body: { enabled: false } } });
  const { render, states } = renderOf();
  const outcome = await flowOf(net, render).setRegistrationOpen(true);
  assert.equal(outcome, 'ok');
  assert.equal(net.calls[0].enabled, true, 'the request asked for true');
  assert.deepEqual(states[states.length - 1], { registrationOpen: false }, 'the view is shown the server answer false');
});

test('setWrongPwLimit 400 shows the bad request sentence', async () => {
  const net = netOf({ setWrongPwLimit: { status: 400, body: null } });
  const { render, messages, states } = renderOf();
  const outcome = await flowOf(net, render).setWrongPwLimit(true);
  assert.equal(outcome, '400');
  assert.equal(messages.length, 1);
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.wrong_pw_limit_bad_request);
  assert.equal(states.length, 0, 'a failed set draws no state');
});

test('setWrongPwLimit 401 shows the unauthorized sentence', async () => {
  const net = netOf({ setWrongPwLimit: { status: 401, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).setWrongPwLimit(true);
  assert.equal(outcome, '401');
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.wrong_pw_limit_unauthorized);
});

test('setWrongPwLimit 403 shows the forbidden sentence', async () => {
  const net = netOf({ setWrongPwLimit: { status: 403, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).setWrongPwLimit(true);
  assert.equal(outcome, '403');
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.wrong_pw_limit_forbidden);
});

test('setWrongPwLimit 503 shows the unavailable sentence', async () => {
  const net = netOf({ setWrongPwLimit: { status: 503, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).setWrongPwLimit(true);
  assert.equal(outcome, '503');
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.wrong_pw_limit_unavailable);
});

test('a set answer with a status this module does not know shows the unknown sentence', async () => {
  const net = netOf({ setWrongPwLimit: { status: 418, body: null } });
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).setWrongPwLimit(true);
  assert.equal(outcome, '418');
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.unknown_status);
});

test('a rejected fetch on set shows the network sentence', async () => {
  const net = netOf();
  net.setWrongPwLimit = async () => { throw new Error('down'); };
  const { render, messages } = renderOf();
  const outcome = await flowOf(net, render).setWrongPwLimit(true);
  assert.equal(outcome, 'network');
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.network_failed);
});

test('no token sends nothing and shows the no-token sentence', async () => {
  const net = netOf();
  const { render, messages } = renderOf();
  const flow = flowOf(net, render, () => null);
  const first = await flow.load();
  const second = await flow.setWrongPwLimit(true);
  assert.equal(first, 'no_token');
  assert.equal(second, 'no_token');
  assert.equal(net.calls.length, 0, 'no call may be made without a token');
  assert.equal(messages.length, 2);
  assert.equal(messages[0], ADMIN_SETTINGS_TEXTS.no_token);
  assert.equal(messages[1], ADMIN_SETTINGS_TEXTS.no_token);
});

test('an empty token is treated as no token and sends nothing', async () => {
  const net = netOf();
  const { render, messages } = renderOf();
  const flow = flowOf(net, render, () => '');
  await flow.load();
  await flow.setRateLimits(true);
  assert.equal(net.calls.length, 0);
  assert.equal(messages.length, 2);
});

test('ADMIN_SETTINGS_TEXTS holds the fixed sentences and is frozen', () => {
  assert.equal(Object.isFrozen(ADMIN_SETTINGS_TEXTS), true);
  assert.equal(ADMIN_SETTINGS_TEXTS.wrong_pw_limit_read_ok, 'The wrong-password limit setting was read.');
  assert.equal(ADMIN_SETTINGS_TEXTS.wrong_pw_limit_set_ok, 'The wrong-password limit setting was saved.');
  assert.equal(ADMIN_SETTINGS_TEXTS.wrong_pw_limit_bad_request, 'The wrong-password limit value was not accepted. The setting was not changed.');
  assert.equal(ADMIN_SETTINGS_TEXTS.rate_limits_forbidden, 'An admin role is required to change the rate limits.');
  assert.equal(ADMIN_SETTINGS_TEXTS.registration_open_unavailable, 'The server is unavailable. Registration open was not changed.');
  assert.equal(ADMIN_SETTINGS_TEXTS.unknown_status, 'The server answered with a status this panel does not understand.');
  assert.equal(ADMIN_SETTINGS_TEXTS.network_failed, 'The server could not be reached. Try again in a moment.');
});

test('no message contains the token, for every status', async () => {
  const token = 'tok-99-c068-secret-value';
  for (const status of [400, 401, 403, 418, 503]) {
    const net = netOf({ setWrongPwLimit: { status, body: null } });
    const { render, messages } = renderOf();
    await flowOf(net, render, () => token).setWrongPwLimit(true);
    assert.equal(messages.length, 1);
    assert.equal(messages[0].includes(token), false, 'status ' + status + ' wrote the token: ' + messages[0]);
    assert.equal(messages[0].includes('99-c068'), false, 'status ' + status + ' wrote part of the token: ' + messages[0]);
  }
});

test('admin-settings.js contains no DOM or storage tokens', () => {
  const source = readFileSync(new URL('../../main/resources/web/js/admin-settings.js', import.meta.url), 'utf8');
  for (const token of ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm', 'console.']) {
    assert.equal(source.includes(token), false, 'forbidden token present: ' + token);
  }
});
