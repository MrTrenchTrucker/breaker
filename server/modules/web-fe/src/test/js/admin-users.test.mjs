import test from 'node:test';
import assert from 'node:assert/strict';
import { createAdminUsers, ADMIN_USERS_TEXTS } from '../../main/resources/web/js/admin-users.js';

const TOKEN = 'session-token-7';
const CODE = 'Reset-Code-0123456789-abcdefghijklmnopqrstuvwxyz-ABCD';

function renderOf() {
  const calls = { messages: [], roles: [], codes: [] };
  return {
    calls,
    showMessage: (text) => { calls.messages.push(text); },
    showRole: (role) => { calls.roles.push(role); },
    showResetCode: (code) => { calls.codes.push(code); },
  };
}

function netOf(answers = {}) {
  const calls = [];
  return {
    calls,
    setRole: async (token, username, admin) => {
      calls.push({ op: 'role', token, username, admin });
      return answers.role ?? { status: 200, body: { username, role: 'admin' } };
    },
    resetAccount: async (token, username) => {
      calls.push({ op: 'reset', token, username });
      return answers.reset ?? { status: 200, body: { reset_code: CODE } };
    },
  };
}

function holdsSecret(obj, secret) {
  for (const key of Object.keys(obj)) {
    const value = obj[key];
    if (typeof value === 'string' && value.includes(secret)) return true;
    if (value !== null && typeof value === 'object' && holdsSecret(value, secret)) return true;
  }
  return false;
}

test('the text table is frozen and holds the eighteen sentences', () => {
  assert.equal(Object.isFrozen(ADMIN_USERS_TEXTS), true);
  assert.equal(Object.keys(ADMIN_USERS_TEXTS).length, 18);
});

test('R3: setRole shows the role the server answered, not the value that was requested', async () => {
  const render = renderOf();
  const net = netOf({ role: { status: 200, body: { username: 'alice', role: 'user' } } });
  const users = createAdminUsers({ getToken: () => TOKEN, net, render });
  await users.setRole('alice', true);
  assert.deepEqual(render.calls.roles, ['user']);
  assert.deepEqual(render.calls.messages, [ADMIN_USERS_TEXTS.role_ok]);
});

test('a 200 role answer with no string role shows the unknown sentence', async () => {
  const render = renderOf();
  const net = netOf({ role: { status: 200, body: { username: 'alice' } } });
  const users = createAdminUsers({ getToken: () => TOKEN, net, render });
  await users.setRole('alice', true);
  assert.deepEqual(render.calls.messages, [ADMIN_USERS_TEXTS.unknown_status]);
  assert.deepEqual(render.calls.roles, [], 'no role is shown for a 200 without a string role');
});

test('setRole with no token shows the unauthorized sentence and sends nothing', async () => {
  const render = renderOf();
  const net = netOf();
  const users = createAdminUsers({ getToken: () => null, net, render });
  await users.setRole('alice', true);
  assert.deepEqual(render.calls.messages, [ADMIN_USERS_TEXTS.role_unauthorized]);
  assert.equal(net.calls.length, 0);
});

test('setRole answers one fixed sentence for each roles status', async () => {
  const table = ADMIN_USERS_TEXTS;
  const cases = [
    [400, table.role_bad_request],
    [401, table.role_unauthorized],
    [403, table.role_forbidden],
    [404, table.role_not_found],
    [409, table.role_conflict],
    [413, table.role_too_large],
    [503, table.role_unavailable],
  ];
  for (const [status, sentence] of cases) {
    const render = renderOf();
    const net = netOf({ role: { status, body: null } });
    const users = createAdminUsers({ getToken: () => TOKEN, net, render });
    await users.setRole('alice', true);
    assert.deepEqual(render.calls.messages, [sentence], 'status ' + status);
  }
});

test('setRole with an unknown status shows the unknown sentence', async () => {
  const render = renderOf();
  const net = netOf({ role: { status: 418, body: null } });
  const users = createAdminUsers({ getToken: () => TOKEN, net, render });
  await users.setRole('alice', true);
  assert.deepEqual(render.calls.messages, [ADMIN_USERS_TEXTS.unknown_status]);
});

test('setRole with a rejecting net shows the network sentence and does not throw', async () => {
  const render = renderOf();
  const net = {
    setRole: async () => { throw new Error('offline'); },
    resetAccount: async () => ({ status: 200, body: { reset_code: CODE } }),
  };
  const users = createAdminUsers({ getToken: () => TOKEN, net, render });
  await users.setRole('alice', true);
  assert.deepEqual(render.calls.messages, [ADMIN_USERS_TEXTS.network_failed]);
});

test('R1: resetAccount hands the code to the view once and keeps no reference to it', async () => {
  const render = renderOf();
  const net = netOf();
  const users = createAdminUsers({ getToken: () => TOKEN, net, render });
  await users.resetAccount('alice');
  assert.deepEqual(render.calls.codes, [CODE]);
  assert.equal(holdsSecret(users, CODE), false, 'the flow object must not hold the code');
});

test('a 200 reset answer with no string reset_code shows the unknown sentence', async () => {
  const render = renderOf();
  const net = netOf({ reset: { status: 200, body: { username: 'alice' } } });
  const users = createAdminUsers({ getToken: () => TOKEN, net, render });
  await users.resetAccount('alice');
  assert.deepEqual(render.calls.messages, [ADMIN_USERS_TEXTS.unknown_status]);
  assert.deepEqual(render.calls.codes, [], 'no code is shown for a 200 without a string code');
});

test('resetAccount answers one fixed sentence for each reset status', async () => {
  const table = ADMIN_USERS_TEXTS;
  const cases = [
    [400, table.reset_bad_request],
    [401, table.reset_unauthorized],
    [403, table.reset_forbidden],
    [404, table.reset_not_found],
    [413, table.reset_too_large],
    [503, table.reset_unavailable],
  ];
  for (const [status, sentence] of cases) {
    const render = renderOf();
    const net = netOf({ reset: { status, body: null } });
    const users = createAdminUsers({ getToken: () => TOKEN, net, render });
    await users.resetAccount('alice');
    assert.deepEqual(render.calls.messages, [sentence], 'status ' + status);
    assert.deepEqual(render.calls.codes, [], 'status ' + status + ' shows no code');
  }
});

test('resetAccount with an unknown status shows the unknown sentence', async () => {
  const render = renderOf();
  const net = netOf({ reset: { status: 418, body: null } });
  const users = createAdminUsers({ getToken: () => TOKEN, net, render });
  await users.resetAccount('alice');
  assert.deepEqual(render.calls.messages, [ADMIN_USERS_TEXTS.unknown_status]);
});

test('resetAccount with a rejecting net shows the network sentence and does not throw', async () => {
  const render = renderOf();
  const net = {
    setRole: async () => ({ status: 200, body: { username: 'alice', role: 'admin' } }),
    resetAccount: async () => { throw new Error('offline'); },
  };
  const users = createAdminUsers({ getToken: () => TOKEN, net, render });
  await users.resetAccount('alice');
  assert.deepEqual(render.calls.messages, [ADMIN_USERS_TEXTS.network_failed]);
});

test('no message or role text contains the token or the reset code', async () => {
  const render = renderOf();
  const net = netOf({ role: { status: 200, body: { username: 'alice', role: 'admin' } } });
  const users = createAdminUsers({ getToken: () => TOKEN, net, render });
  await users.setRole('alice', true);
  await users.resetAccount('alice');
  for (const text of [...render.calls.messages, ...render.calls.roles]) {
    assert.equal(text.includes(TOKEN), false, 'a message held the token: ' + text);
    assert.equal(text.includes(CODE), false, 'a message held the code: ' + text);
  }
});
