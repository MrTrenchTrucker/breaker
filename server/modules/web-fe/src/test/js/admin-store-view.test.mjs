import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mountAdminStore } from '../../main/resources/web/js/admin-store-view.js';
import { ADMIN_STORE_TEXTS } from '../../main/resources/web/js/admin-store.js';

const HOSTILE = '<img src=x onerror=alert(1)>';
const USERNAME = 'alice@example.com';

// The page this view mounts into installs a minimal document. The view builds its
// own elements with document.createElement and never looks anything up by id and
// never touches window.
globalThis.document = {
  createElement(tag) {
    return fakeElement(tag);
  },
};

// A plain element: textContent records every write, the markup setters throw, so a
// view that writes markup fails the test that drives it.
function fakeElement(tag) {
  const element = {
    tag,
    children: [],
    listeners: [],
    writes: [],
    text: '',
    value: '',
    disabled: false,
    hidden: false,
  };
  Object.defineProperty(element, 'textContent', {
    enumerable: true,
    get() { return element.text; },
    set(next) {
      element.writes.push(next);
      element.text = next;
    },
  });
  Object.defineProperty(element, 'innerHTML', {
    get: () => '',
    set: () => { throw new Error('innerHTML was set'); },
  });
  Object.defineProperty(element, 'outerHTML', {
    get: () => '',
    set: () => { throw new Error('outerHTML was set'); },
  });
  element.insertAdjacentHTML = () => { throw new Error('insertAdjacentHTML was called'); };
  element.appendChild = (child) => { element.children.push(child); return child; };
  Object.defineProperty(element, 'firstChild', {
    get() { return element.children[0] ?? null; },
  });
  element.removeChild = (child) => {
    const index = element.children.indexOf(child);
    if (index >= 0) element.children.splice(index, 1);
    return child;
  };
  element.addEventListener = (type, fn) => { element.listeners.push({ type, fn }); };
  element.click = () => {
    for (const listener of element.listeners) {
      if (listener.type === 'click') listener.fn();
    }
  };
  return element;
}

// A fake store-calls object that records every call it is given.
function netOf(answer = { status: 200, body: { cleared: 0 } }) {
  const calls = [];
  const net = {
    calls,
    clearStore: async (token, options) => {
      calls.push({ token, options });
      return answer;
    },
  };
  return net;
}

async function flush() {
  for (let i = 0; i < 30; i += 1) await Promise.resolve();
}

// root.children[0] form, [1] all button, [2] message, [3] confirm control
function formOf(root) { return root.children[0]; }
function nameInputOf(root) { return formOf(root).children[1]; }
function oneButtonOf(root) { return formOf(root).children[2]; }
function allButtonOf(root) { return root.children[1]; }
function messageOf(root) { return root.children[2]; }
function confirmOf(root) { return root.children[3]; }
function confirmWordsOf(root) { return confirmOf(root).children[0]; }
function confirmButtonOf(root) { return confirmOf(root).children[1]; }
function cancelButtonOf(root) { return confirmOf(root).children[2]; }

function mount(options = {}) {
  const net = options.net ?? netOf();
  const signedOut = [];
  const root = fakeElement('root');
  mountAdminStore(root, {
    getToken: options.getToken ?? (() => 'tok-abc'),
    onSignedOut: () => { signedOut.push(undefined); },
    net,
  });
  return { root, net, signedOut };
}

test('clear one user: the confirm names the target and nothing is sent before it', async () => {
  const { root, net } = mount({ net: netOf({ status: 200, body: { cleared: 4 } }) });
  nameInputOf(root).value = USERNAME;
  oneButtonOf(root).click();
  await flush();
  assert.equal(net.calls.length, 0, 'nothing is sent before the confirm control is used');
  assert.equal(confirmOf(root).hidden, false);
  assert.equal(confirmWordsOf(root).textContent, 'Clear the store for ' + USERNAME + '?');
  confirmButtonOf(root).click();
  await flush();
  assert.equal(net.calls.length, 1, 'exactly one call after the confirm control is used');
  assert.deepEqual(net.calls[0].options, { username: USERNAME, confirm: true });
});

test('clear all users: the confirm names all users and nothing is sent before it', async () => {
  const { root, net } = mount({ net: netOf({ status: 200, body: { cleared: 11 } }) });
  allButtonOf(root).click();
  await flush();
  assert.equal(net.calls.length, 0, 'nothing is sent before the confirm control is used');
  assert.equal(confirmOf(root).hidden, false);
  assert.equal(confirmWordsOf(root).textContent, ADMIN_STORE_TEXTS.confirm_all);
  assert.equal(confirmWordsOf(root).textContent.includes('all users'), true);
  confirmButtonOf(root).click();
  await flush();
  assert.equal(net.calls.length, 1, 'exactly one call after the confirm control is used');
  assert.deepEqual(net.calls[0].options, { username: null, confirm: true });
});

test('clicking cancel hides the confirm control and sends nothing', async () => {
  const { root, net } = mount();
  allButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, false);
  cancelButtonOf(root).click();
  await flush();
  assert.equal(confirmOf(root).hidden, true);
  assert.equal(net.calls.length, 0, 'cancel sends no call');
});

test('a double click on the confirm button sends one call', async () => {
  let resolveClear = null;
  const calls = [];
  const net = {
    calls,
    clearStore: async (token, options) => {
      calls.push({ token, options });
      return new Promise((resolve) => { resolveClear = resolve; });
    },
  };
  const { root } = mount({ net });
  allButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  confirmButtonOf(root).click();
  await flush();
  assert.equal(calls.length, 1, 'the second click while the first is pending must not send');
  resolveClear({ status: 200, body: { cleared: 1 } });
  await flush();
  assert.equal(confirmButtonOf(root).disabled, false, 'the button is enabled again once the flow answers');
});

test('a hostile user name renders as literal text and the markup setters are never called', async () => {
  const { root, net } = mount({ net: netOf({ status: 200, body: { cleared: 1 } }) });
  nameInputOf(root).value = HOSTILE;
  oneButtonOf(root).click();
  await flush();
  assert.equal(confirmWordsOf(root).textContent, 'Clear the store for ' + HOSTILE + '?');
  confirmButtonOf(root).click();
  await flush();
  assert.equal(net.calls.length, 1);
  assert.equal(net.calls[0].options.username, HOSTILE);
});

test('a user name with replacement-pattern characters appears verbatim in the confirm words', async () => {
  const { root } = mount();
  // $&, $' and $$ are special in a replacement STRING; a plain string replacer
  // would rewrite them, so the confirm would no longer name the exact target.
  nameInputOf(root).value = 'a$&b$$c';
  oneButtonOf(root).click();
  await flush();
  assert.equal(confirmWordsOf(root).textContent, 'Clear the store for a$&b$$c?');
});

test('after a clear the message shows the cleared count the server answered', async () => {
  const { root } = mount({ net: netOf({ status: 200, body: { cleared: 7 } }) });
  allButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  await flush();
  assert.equal(messageOf(root).textContent, 'The store was cleared: 7 rows.');
});

test('401 shows the unauthorized sentence', async () => {
  const { root } = mount({ net: netOf({ status: 401, body: null }) });
  allButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  await flush();
  assert.equal(messageOf(root).textContent, ADMIN_STORE_TEXTS.unauthorized);
});

test('a rejected fetch shows the network sentence', async () => {
  const net = netOf();
  net.clearStore = async () => { throw new Error('down'); };
  const { root } = mount({ net });
  allButtonOf(root).click();
  await flush();
  confirmButtonOf(root).click();
  await flush();
  assert.equal(messageOf(root).textContent, ADMIN_STORE_TEXTS.network);
});

test('an empty user name shows the needs name sentence and sends nothing', async () => {
  const { root, net } = mount();
  nameInputOf(root).value = '   ';
  oneButtonOf(root).click();
  await flush();
  assert.equal(messageOf(root).textContent, ADMIN_STORE_TEXTS.needs_name);
  assert.equal(confirmOf(root).hidden, true);
  assert.equal(net.calls.length, 0);
});

test('the confirm control is hidden at mount', async () => {
  const { root, net } = mount();
  assert.equal(confirmOf(root).hidden, true);
  assert.equal(net.calls.length, 0);
});

test('the three store source files contain no DOM or storage tokens', () => {
  const files = [
    new URL('../../main/resources/web/js/admin-store.js', import.meta.url),
    new URL('../../main/resources/web/js/admin-store-net.js', import.meta.url),
    new URL('../../main/resources/web/js/admin-store-view.js', import.meta.url),
  ];
  const tokens = ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm'];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const token of tokens) {
      assert.equal(source.includes(token), false, file.pathname + ' contains forbidden token: ' + token);
    }
  }
});
