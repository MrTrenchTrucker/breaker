import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mountAdminSettings } from '../../main/resources/web/js/admin-settings-view.js';
import { ADMIN_SETTINGS_TEXTS } from '../../main/resources/web/js/admin-settings.js';

const HOSTILE = '<img src=x onerror=alert(1)>';

// The page this view mounts into installs a minimal document. The view builds
// its own elements with document.createElement and never looks anything up by
// id and never touches window.
globalThis.document = {
  createElement(tag) {
    return fakeElement(tag);
  },
};

// A plain element: textContent records every write, the markup setters throw,
// so a view that writes markup fails the test that drives it.
function fakeElement(tag) {
  const element = {
    tag,
    children: [],
    listeners: [],
    writes: [],
    text: '',
    disabled: false,
    hidden: false,
    checked: false,
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
  element.change = () => {
    for (const listener of element.listeners) {
      if (listener.type === 'change') listener.fn();
    }
  };
  return element;
}

// A fake settings-calls object that records every call it is given.
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

async function flush() {
  for (let i = 0; i < 30; i += 1) await Promise.resolve();
}

// root.children[0..2] are the three setting boxes, [3] is the message.
function messageOf(root) { return root.children[3]; }
function controlOf(root, key) { return root.children[key].children[2]; }
function stateOf(root, key) { return root.children[key].children[1]; }

function mount(options = {}) {
  const net = options.net ?? netOf();
  const root = fakeElement('root');
  const view = mountAdminSettings(root, {
    getToken: options.getToken ?? (() => 'tok-abc'),
    net,
  });
  return { root, net, view };
}

test('the view builds three rows inside the given root and never sets markup', async () => {
  const { root } = mount();
  await flush();
  assert.equal(root.children.length, 4, 'three setting boxes and one message');
  const labels = [0, 1, 2].map((index) => root.children[index].children[0].textContent);
  assert.deepEqual(labels, [
    ADMIN_SETTINGS_TEXTS.wrong_pw_limit_label,
    ADMIN_SETTINGS_TEXTS.rate_limits_label,
    ADMIN_SETTINGS_TEXTS.registration_open_label,
  ]);
  for (const box of root.children.slice(0, 3)) {
    assert.equal(box.children.length, 3);
    assert.equal(box.children[2].type, 'checkbox');
  }
});

test('a hostile message renders as literal text and the markup setters are never called', async () => {
  const { root, view } = mount();
  await flush();
  view.render.showMessage(HOSTILE);
  assert.equal(messageOf(root).textContent, HOSTILE);
  assert.deepEqual(messageOf(root).writes, [HOSTILE]);
});

test('load renders the three states into the controls', async () => {
  const { root, net } = mount({
    net: netOf({
      getWrongPwLimit: { status: 200, body: { enabled: true } },
      getRateLimits: { status: 200, body: { enabled: false } },
      getRegistrationOpen: { status: 200, body: { enabled: true } },
    }),
  });
  await flush();
  assert.equal(controlOf(root, 0).checked, true);
  assert.equal(stateOf(root, 0).textContent, ADMIN_SETTINGS_TEXTS.on_label);
  assert.equal(controlOf(root, 1).checked, false);
  assert.equal(stateOf(root, 1).textContent, ADMIN_SETTINGS_TEXTS.off_label);
  assert.equal(controlOf(root, 2).checked, true);
  assert.equal(net.calls.length, 3);
});

test('a settings toggle sends the new value with no confirm step', async () => {
  const { root, net } = mount();
  await flush();
  const control = controlOf(root, 0);
  control.checked = true;
  control.change();
  await flush();
  const sets = net.calls.filter((call) => call.op === 'setWrongPwLimit');
  assert.equal(sets.length, 1, 'one call, no confirm control in between');
  assert.equal(sets[0].enabled, true);
  assert.equal(sets[0].token, 'tok-abc');
});

test('the view shows the server answer when it differs from the request', async () => {
  const { root, net } = mount({
    net: netOf({ setWrongPwLimit: { status: 200, body: { enabled: false } } }),
  });
  await flush();
  const control = controlOf(root, 0);
  control.checked = true;
  control.change();
  await flush();
  assert.equal(net.calls.filter((call) => call.op === 'setWrongPwLimit').length, 1);
  assert.equal(control.checked, false, 'the control shows the server answer false, not the clicked true');
  assert.equal(stateOf(root, 0).textContent, ADMIN_SETTINGS_TEXTS.off_label);
});

test('the toggle is not sent twice while the first call is pending', async () => {
  let resolveSet = null;
  const calls = [];
  const net = netOf();
  net.setWrongPwLimit = async () => {
    calls.push('set');
    return new Promise((resolve) => { resolveSet = resolve; });
  };
  const { root } = mount({ net });
  await flush();
  const control = controlOf(root, 0);
  control.checked = true;
  control.change();
  control.change();
  await flush();
  assert.equal(calls.length, 1, 'the second change while the first is pending must not send');
  resolveSet({ status: 200, body: { enabled: true } });
  await flush();
  assert.equal(control.disabled, false, 'the control is enabled again once the flow answers');
});

test('the view shows the message the flow sends', async () => {
  const { root } = mount({
    net: netOf({ getWrongPwLimit: { status: 503, body: null } }),
  });
  await flush();
  assert.equal(messageOf(root).textContent, ADMIN_SETTINGS_TEXTS.wrong_pw_limit_unavailable);
});

test('a toggle with no token shows the no-token sentence and sends nothing', async () => {
  const { root, net } = mount({ getToken: () => '' });
  await flush();
  const control = controlOf(root, 0);
  control.checked = true;
  control.change();
  await flush();
  assert.equal(net.calls.filter((call) => call.op === 'setWrongPwLimit').length, 0);
  assert.equal(messageOf(root).textContent, ADMIN_SETTINGS_TEXTS.no_token);
});

test('the three view source files contain no forbidden tokens', () => {
  const files = [
    new URL('../../main/resources/web/js/admin-settings.js', import.meta.url),
    new URL('../../main/resources/web/js/admin-settings-net.js', import.meta.url),
    new URL('../../main/resources/web/js/admin-settings-view.js', import.meta.url),
  ];
  const tokens = ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'localStorage', 'sessionStorage', 'document.cookie', 'window.confirm', 'console.'];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const token of tokens) {
      assert.equal(source.includes(token), false, file.pathname + ' contains forbidden token: ' + token);
    }
  }
});
