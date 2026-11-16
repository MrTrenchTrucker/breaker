import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mountAdminUsers } from '../../main/resources/web/js/admin-users-view.js';
import { ADMIN_USERS_TEXTS } from '../../main/resources/web/js/admin-users.js';

const HOSTILE = '<img src=x onerror=alert(1)>';
const CODE = 'Reset-Code-0123456789-abcdefghijklmnopqrstuvwxyz-ABCD';
const TOKEN = 'session-token-7';

// The page installs a minimal document; the view builds its own elements and
// never looks anything up by id or touches window.
globalThis.document = { createElement(tag) { return fakeElement(tag); } };

// A plain element: textContent records every write, the markup setters throw.
function fakeElement(tag) {
  const element = { tag, children: [], listeners: [], writes: [], text: '', value: '', disabled: false, hidden: false };
  Object.defineProperty(element, 'textContent', { enumerable: true, get() { return element.text; }, set(next) { element.writes.push(next); element.text = next; } });
  Object.defineProperty(element, 'innerHTML', { get: () => '', set: () => { throw new Error('innerHTML was set'); } });
  element.insertAdjacentHTML = () => { throw new Error('insertAdjacentHTML was called'); };
  element.appendChild = (child) => { element.children.push(child); return child; };
  element.addEventListener = (type, fn) => { element.listeners.push({ type, fn }); };
  element.click = () => { for (const listener of element.listeners) if (listener.type === 'click') listener.fn(); };
  return element;
}

// A fake admin-user calls object that records every call it is given.
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

async function flush() { for (let i = 0; i < 30; i += 1) await Promise.resolve(); }

// root.children: 0 name, 1 make admin, 2 remove admin, 3 reset, 4 confirm, 5 message, 6 role, 7 code box.
const inputOf = (root) => root.children[0], makeAdminOf = (root) => root.children[1];
const removeAdminOf = (root) => root.children[2], resetButtonOf = (root) => root.children[3];
const confirmOf = (root) => root.children[4];
const confirmWordsOf = (root) => confirmOf(root).children[0], confirmButtonOf = (root) => confirmOf(root).children[1];
const cancelButtonOf = (root) => confirmOf(root).children[2], messageOf = (root) => root.children[5];
const roleLineOf = (root) => root.children[6], codeBoxOf = (root) => root.children[7];
const codeTextOf = (root) => codeBoxOf(root).children[0], dismissButtonOf = (root) => codeBoxOf(root).children[1];

function mount(options = {}) {
  const net = options.net ?? netOf();
  const root = fakeElement('root');
  const held = mountAdminUsers(root, { getToken: options.getToken ?? (() => TOKEN), net });
  return { root, net, held };
}

function textsUnder(node, out = []) {
  if (typeof node.text === 'string') out.push(node.text);
  if (typeof node.value === 'string') out.push(node.value);
  for (const child of node.children ?? []) textsUnder(child, out);
  return out;
}

function holdsSecret(obj, secret) {
  for (const key of Object.keys(obj)) {
    const value = obj[key];
    if (typeof value === 'string' && value.includes(secret)) return true;
    if (value !== null && typeof value === 'object' && holdsSecret(value, secret)) return true;
  }
  return false;
}

test('R1: the reset code is shown once and after dismiss no node under the root holds it', async () => {
  const { root, held } = mount();
  inputOf(root).value = 'alice';
  resetButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  await flush();
  assert.equal(codeTextOf(root).textContent, CODE, 'the code is shown once');
  assert.equal(codeTextOf(root).writes.filter((write) => write === CODE).length, 1);
  dismissButtonOf(root).click();
  await flush();
  assert.equal(codeTextOf(root).textContent, '', 'the dismiss control clears the code element');
  for (const text of textsUnder(root)) {
    assert.equal(text.includes(CODE), false, 'a node under the root still holds the code: ' + text);
  }
  assert.equal(holdsSecret(held, CODE), false, 'the flow object has no way to read the code back');
});

test('R2: the reset confirm names the target and nothing is sent before it', async () => {
  const { root, net } = mount();
  inputOf(root).value = 'alice@example.com';
  resetButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, false);
  assert.equal(confirmWordsOf(root).textContent.includes('alice@example.com'), true, 'the confirm text names the target: ' + confirmWordsOf(root).textContent);
  assert.equal(net.calls.filter((call) => call.op === 'reset').length, 0, 'nothing is sent before the confirm control is used');
  confirmButtonOf(root).click();
  await flush();
  const resets = net.calls.filter((call) => call.op === 'reset');
  assert.equal(resets.length, 1, 'exactly one reset call after the confirm control is used');
  assert.equal(resets[0].username, 'alice@example.com');
});

test('R2: the confirm sends the name it named, even after the box changes', async () => {
  const { root, net } = mount();
  inputOf(root).value = 'alice';
  resetButtonOf(root).click();
  await flush();
  assert.equal(confirmWordsOf(root).textContent, 'Reset the account alice?');
  inputOf(root).value = 'bob';
  confirmButtonOf(root).click();
  await flush();
  const resets = net.calls.filter((call) => call.op === 'reset');
  assert.equal(resets.length, 1);
  assert.equal(resets[0].username, 'alice', 'the confirm sends the name it named, not the edited box');
});

test('an empty name shows the fixed sentence, sends nothing and opens no confirm', async () => {
  const { root, net } = mount();
  inputOf(root).value = '';
  resetButtonOf(root).click();
  await flush();
  assert.equal(messageOf(root).textContent, ADMIN_USERS_TEXTS.name_required);
  assert.equal(confirmOf(root).hidden, true, 'no confirm opens for an empty name');
  assert.equal(net.calls.filter((call) => call.op === 'reset').length, 0);
});

test('a blank name shows the fixed sentence and sends no role change', async () => {
  for (const button of ['make', 'remove']) {
    const { root, net } = mount();
    inputOf(root).value = '   ';
    (button === 'make' ? makeAdminOf(root) : removeAdminOf(root)).click();
    await flush();
    assert.equal(messageOf(root).textContent, ADMIN_USERS_TEXTS.name_required, button);
    assert.equal(net.calls.filter((call) => call.op === 'role').length, 0, button);
  }
});

test('R3: a role change shows the role the server answered, not the value that was requested', async () => {
  const { root, net } = mount({ net: netOf({ role: { status: 200, body: { username: 'alice', role: 'user' } } }) });
  inputOf(root).value = 'alice';
  makeAdminOf(root).click();
  await flush();
  assert.equal(roleLineOf(root).textContent, 'user', 'the view shows the server answer');
  assert.equal(messageOf(root).textContent, ADMIN_USERS_TEXTS.role_ok);
  assert.equal(net.calls.filter((call) => call.op === 'role').length, 1);
});

test('the remove-admin control sends admin false and shows the server answer', async () => {
  const { root, net } = mount({ net: netOf({ role: { status: 200, body: { username: 'alice', role: 'user' } } }) });
  inputOf(root).value = 'alice';
  removeAdminOf(root).click();
  await flush();
  assert.equal(net.calls[0].admin, false);
  assert.equal(roleLineOf(root).textContent, 'user');
});

test('cancel hides the confirm control, clears the pending name and sends nothing', async () => {
  const { root, net } = mount();
  inputOf(root).value = 'alice';
  resetButtonOf(root).click();
  await flush();
  cancelButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, true);
  assert.equal(net.calls.filter((call) => call.op === 'reset').length, 0);
  inputOf(root).value = 'bob';
  confirmButtonOf(root).click();
  await flush();
  assert.equal(net.calls.filter((call) => call.op === 'reset').length, 0, 'a cancel clears the pending name');
});

test('two quick clicks on the confirm button send one reset', async () => {
  let resolveReset = null;
  const calls = [];
  const net = {
    setRole: async () => ({ status: 200, body: { username: 'alice', role: 'admin' } }),
    resetAccount: async () => {
      calls.push('reset');
      return new Promise((resolve) => { resolveReset = resolve; });
    },
  };
  const { root } = mount({ net });
  inputOf(root).value = 'alice';
  resetButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  confirmButtonOf(root).click();
  await flush();
  assert.equal(calls.length, 1, 'the second click while the first is pending must not send');
  resolveReset({ status: 200, body: { reset_code: CODE } });
  await flush();
  assert.equal(codeTextOf(root).textContent, CODE);
});

test('the reset code is written with textContent and the markup setters are never called', async () => {
  const { root } = mount({ net: netOf({ reset: { status: 200, body: { reset_code: HOSTILE } } }) });
  inputOf(root).value = 'alice';
  resetButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  await flush();
  assert.equal(codeTextOf(root).textContent, HOSTILE, 'the code is literal text, never markup');
});

test('a hostile user name in the confirm text is literal text', async () => {
  const { root } = mount();
  inputOf(root).value = HOSTILE;
  resetButtonOf(root).click();
  await flush();
  assert.equal(confirmWordsOf(root).textContent, 'Reset the account ' + HOSTILE + '?');
});

test('the status sentences are the fixed table sentences', async () => {
  const cases = [
    ['role', 400, ADMIN_USERS_TEXTS.role_bad_request],
    ['role', 404, ADMIN_USERS_TEXTS.role_not_found],
    ['role', 409, ADMIN_USERS_TEXTS.role_conflict],
    ['reset', 400, ADMIN_USERS_TEXTS.reset_bad_request],
    ['reset', 404, ADMIN_USERS_TEXTS.reset_not_found],
    ['reset', 503, ADMIN_USERS_TEXTS.reset_unavailable],
  ];
  for (const [op, status, sentence] of cases) {
    const net = netOf(op === 'role' ? { role: { status, body: null } } : { reset: { status, body: null } });
    const { root } = mount({ net });
    inputOf(root).value = 'alice';
    if (op === 'role') {
      makeAdminOf(root).click();
    } else {
      resetButtonOf(root).click();
      await flush();
      confirmButtonOf(root).click();
    }
    await flush();
    assert.equal(messageOf(root).textContent, sentence, op + ' ' + status);
  }
});

test('a rejecting net shows the network sentence and the view does not throw', async () => {
  const net = {
    setRole: async () => { throw new Error('offline'); },
    resetAccount: async () => { throw new Error('offline'); },
  };
  const { root } = mount({ net });
  inputOf(root).value = 'alice';
  makeAdminOf(root).click();
  await flush();
  assert.equal(messageOf(root).textContent, ADMIN_USERS_TEXTS.network_failed);
  resetButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  await flush();
  assert.equal(messageOf(root).textContent, ADMIN_USERS_TEXTS.network_failed);
});

test('an unknown status shows the unknown sentence', async () => {
  const { root } = mount({ net: netOf({ role: { status: 418, body: null } }) });
  inputOf(root).value = 'alice';
  makeAdminOf(root).click();
  await flush();
  assert.equal(messageOf(root).textContent, ADMIN_USERS_TEXTS.unknown_status);
});

test('no message contains the token or the reset code', async () => {
  const { root } = mount();
  inputOf(root).value = 'alice';
  makeAdminOf(root).click();
  await flush();
  resetButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  await flush();
  for (const write of messageOf(root).writes) {
    assert.equal(write.includes(TOKEN), false, 'a message held the token: ' + write);
    assert.equal(write.includes(CODE), false, 'a message held the code: ' + write);
  }
});

test('the three users source files contain no DOM or storage tokens', () => {
  const files = [
    new URL('../../main/resources/web/js/admin-users.js', import.meta.url),
    new URL('../../main/resources/web/js/admin-users-net.js', import.meta.url),
    new URL('../../main/resources/web/js/admin-users-view.js', import.meta.url),
  ];
  const tokens = ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm', 'console.'];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const token of tokens) {
      assert.equal(source.includes(token), false, file.pathname + ' contains forbidden token: ' + token);
    }
  }
});
