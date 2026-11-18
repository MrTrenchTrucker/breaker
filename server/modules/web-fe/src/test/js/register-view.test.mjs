import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mountRegister, VIEW_TEXTS } from '../../main/resources/web/js/register-view.js';

const HOSTILE = '<img src=x onerror=alert(1)>';

// The page this view mounts into installs a minimal document. The view builds its own
// elements with document.createElement and never looks anything up by id.
globalThis.document = {
  createElement(tag) {
    return fakeElement(tag);
  },
};

// A plain element: textContent records every write, the markup setters throw, a field
// carries a value and an attribute bag, and a submit button fires its form's submit.
function fakeElement(tag) {
  const element = {
    tag,
    children: [],
    listeners: [],
    writes: [],
    labels: {},
    text: '',
    value: '',
    disabled: false,
    hidden: false,
    required: true,
    type: '',
    parent: null,
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
  element.appendChild = (child) => { child.parent = element; element.children.push(child); return child; };
  Object.defineProperty(element, 'firstChild', {
    get() { return element.children[0] ?? null; },
  });
  element.removeChild = (child) => {
    const index = element.children.indexOf(child);
    if (index >= 0) element.children.splice(index, 1);
    return child;
  };
  element.setAttribute = (name, value) => { element.labels[name] = value; };
  element.addEventListener = (type, fn) => { element.listeners.push({ type, fn }); };
  // A submit button submits its form, the way a browser does it.
  element.click = () => {
    for (const listener of element.listeners) {
      if (listener.type === 'click') listener.fn();
    }
    if (element.tag === 'button' && element.type === 'submit' && element.parent !== null) {
      for (const listener of element.parent.listeners) {
        if (listener.type === 'submit') listener.fn({ preventDefault: () => {} });
      }
    }
  };
  // Lets a test drive a form the way the keyboard does.
  element.fire = (type, event) => {
    for (const listener of element.listeners) {
      if (listener.type === type) listener.fn(event);
    }
  };
  return element;
}

async function flush() {
  for (let i = 0; i < 30; i += 1) await Promise.resolve();
}

// A fake register flow that records the input it was given and answers with `answer`.
function fakeRegister(answer) {
  const calls = [];
  return {
    calls,
    run: async (input) => {
      calls.push(input);
      return typeof answer === 'function' ? answer(input) : answer;
    },
  };
}

function mount(options = {}) {
  const root = fakeElement('root');
  const registered = [];
  const control = mountRegister(root, {
    register: options.register ?? fakeRegister({ ok: true, username: 'account-one', role: 'admin', keyVersion: 1 }),
    onRegistered: options.onRegistered ?? ((result) => registered.push(result)),
    installCodeField: options.installCodeField,
  });
  return { root, registered, ...control };
}

test('the view texts are fixed sentences', () => {
  assert.equal(VIEW_TEXTS.install_code_label, 'Install code (optional)');
  assert.equal(Object.isFrozen(VIEW_TEXTS), true);
});

test('the view builds a form and a live status line inside the given root, in order', () => {
  const { root, form, username, password, installCode, submit, message } = mount();
  assert.deepEqual(root.children, [form, message]);
  assert.deepEqual(form.children, [username, password, installCode, submit]);
  assert.equal(form.tag, 'form');
  assert.equal(username.tag, 'input');
  assert.equal(password.tag, 'input');
  assert.equal(installCode.tag, 'input');
  assert.equal(submit.tag, 'button');
  assert.equal(submit.type, 'submit');
  assert.equal(message.tag, 'div');
  assert.equal(message.labels['role'], 'status');
  assert.equal(message.labels['aria-live'], 'polite');
});

test('every field carries its label, an autocomplete hint and the install code field is not required', () => {
  const { username, password, installCode } = mount();
  assert.equal(username.labels['aria-label'], VIEW_TEXTS.username_label);
  assert.equal(password.labels['aria-label'], VIEW_TEXTS.password_label);
  assert.equal(installCode.labels['aria-label'], VIEW_TEXTS.install_code_label);
  assert.equal(installCode.required, false);
  assert.equal(username.labels['autocomplete'], 'username');
  assert.equal(password.labels['autocomplete'], 'new-password');
  assert.equal(installCode.labels['autocomplete'], 'off');
  assert.equal(submitLabel(), VIEW_TEXTS.submit);
});

function submitLabel() {
  const { submit } = mount();
  return submit.text;
}

test('the install code field can be left out of the view altogether', () => {
  const { root, form, installCode } = mount({ installCodeField: false });
  assert.equal(root.children.length, 2);
  assert.equal(form.children.length, 3);
  assert.equal(installCode, null);
});

test('a click sends the three entered values to the flow', async () => {
  const register = fakeRegister({ ok: true, username: 'account-one', role: 'admin' });
  const view = mount({ register });
  view.username.value = 'account-one';
  view.password.value = 'typed sample text long enough';
  view.installCode.value = 'install code sample text that is short';
  view.submit.click();
  await flush();
  assert.deepEqual(register.calls, [{
    username: 'account-one',
    typedText: 'typed sample text long enough',
    installCode: 'install code sample text that is short',
  }]);
});

test('a submit event on the form runs the flow, so the Enter key submits', async () => {
  const register = fakeRegister({ ok: true, username: 'account-one', role: 'admin' });
  const view = mount({ register });
  view.username.value = 'account-one';
  view.password.value = 'typed sample text long enough';
  view.form.fire('submit', { preventDefault: () => {} });
  await flush();
  assert.deepEqual(register.calls, [{
    username: 'account-one',
    typedText: 'typed sample text long enough',
    installCode: undefined,
  }]);
});

test('a blank install code is sent as undefined, not as empty text', async () => {
  const register = fakeRegister({ ok: true, username: 'account-one', role: 'admin' });
  const view = mount({ register });
  view.username.value = 'account-one';
  view.password.value = 'typed sample text long enough';
  view.submit.click();
  await flush();
  assert.equal(register.calls[0].installCode, undefined);
});

test('a refused registration writes its sentence as literal text', async () => {
  const view = mount({ register: fakeRegister({ ok: false, code: 'username_taken', message: HOSTILE }) });
  view.submit.click();
  await flush();
  assert.equal(view.message.textContent, HOSTILE);
  assert.deepEqual(view.message.writes, [HOSTILE]);
});

test('a successful registration writes the created sentence and reports once', async () => {
  const result = { ok: true, username: 'account-one', role: 'admin', keyVersion: 1 };
  const view = mount({ register: fakeRegister(result) });
  view.submit.click();
  await flush();
  assert.equal(view.message.textContent, VIEW_TEXTS.created);
  assert.deepEqual(view.registered, [result]);
});

test('two clicks while the first run is held send exactly one run', async () => {
  let release;
  const pending = new Promise((resolve) => { release = resolve; });
  const register = { calls: [], run: async (input) => { register.calls.push(input); return pending; } };
  const view = mount({ register });
  view.submit.click();
  view.submit.click();
  await flush();
  assert.equal(register.calls.length, 1);
  release({ ok: true, username: 'account-one', role: 'admin' });
  await flush();
  assert.equal(register.calls.length, 1);
});

test('a failed run clears the password and keeps the install code so a typo can be fixed', async () => {
  const view = mount({ register: fakeRegister({ ok: false, code: 'network', message: 'The connection failed, so try again later.' }) });
  view.password.value = 'typed sample text long enough';
  view.installCode.value = 'install code sample text that is short';
  view.submit.click();
  await flush();
  assert.equal(view.password.value, '');
  assert.equal(view.installCode.value, 'install code sample text that is short');
  assert.equal(view.submit.disabled, false);
});

test('a successful run clears both the password and the install code', async () => {
  const view = mount({ register: fakeRegister({ ok: true, username: 'account-one', role: 'admin', keyVersion: 1 }) });
  view.password.value = 'typed sample text long enough';
  view.installCode.value = 'install code sample text that is short';
  view.submit.click();
  await flush();
  assert.equal(view.password.value, '');
  assert.equal(view.installCode.value, '');
});

test('the four register source files contain no markup or storage tokens', () => {
  const files = [
    new URL('../../main/resources/web/js/register-policy.js', import.meta.url),
    new URL('../../main/resources/web/js/register-net.js', import.meta.url),
    new URL('../../main/resources/web/js/register.js', import.meta.url),
    new URL('../../main/resources/web/js/register-view.js', import.meta.url),
  ];
  const tokens = ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'document.write', 'localStorage', 'sessionStorage', 'indexedDB', 'document.cookie'];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const token of tokens) {
      assert.equal(source.includes(token), false, file.pathname + ' contains forbidden token: ' + token);
    }
  }
});

test('the flow and policy modules touch no document, no window and no fetch', () => {
  const files = [
    new URL('../../main/resources/web/js/register-policy.js', import.meta.url),
    new URL('../../main/resources/web/js/register-net.js', import.meta.url),
    new URL('../../main/resources/web/js/register.js', import.meta.url),
  ];
  const tokens = ['document.', 'window.', 'fetch(', 'XMLHttpRequest'];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const token of tokens) {
      assert.equal(source.includes(token), false, file.pathname + ' contains forbidden token: ' + token);
    }
  }
});
