// Fakes for the sign-in page tests: a plain document, window, location, history, fetch and crypto, plus one
// helper that mounts the page on them. Every fake records what it was given. This file is a helper, not a test file.
import { initSigninPage } from '../../main/resources/web/js/signin-page.js';
import {
  BOX_PUB, CODE_TEXT, KEK, SEALED, VALID_HASH, VERIFIER, loginAnswer, saltAnswer, typedText,
} from './signin-fixtures.mjs';

export const CLEAN_URL = '/signin.html';

// A plain element. Its textContent setter records every write, and throwOn makes one write throw. The markup
// setters throw, so a page that writes markup fails the test that drives it.
export function fakeElement(id) {
  const element = { id, value: '', disabled: false, hidden: false, listeners: [], throwOn: null, writes: [] };
  let text = '';
  Object.defineProperty(element, 'textContent', {
    enumerable: true,
    get() { return text; },
    set(next) {
      element.writes.push(next);
      if (element.throwOn !== null && next === element.throwOn) throw new Error('status write refused');
      text = next;
    },
  });
  Object.defineProperty(element, 'innerHTML', { get: () => '', set: () => { throw new Error('innerHTML was set'); } });
  Object.defineProperty(element, 'outerHTML', { get: () => '', set: () => { throw new Error('outerHTML was set'); } });
  element.addEventListener = (type, fn) => { element.listeners.push({ type, fn }); };
  element.insertAdjacentHTML = () => { throw new Error('insertAdjacentHTML was called'); };
  return element;
}

export function fakeDocument() {
  const elements = {};
  for (const id of ['signin-form', 'signin-username', 'signin-password', 'signin-submit', 'signin-status']) {
    elements[id] = fakeElement(id);
  }
  return { elements, getElementById: (id) => (Object.hasOwn(elements, id) ? elements[id] : null) };
}

export function fakeWindow() {
  const win = { listeners: [] };
  win.addEventListener = (type, fn) => { win.listeners.push({ type, fn }); };
  return win;
}

export function fakeLocation(hash, log) {
  const loc = {
    hash,
    pathname: CLEAN_URL,
    search: '',
    replaced: [],
    replace(link) { loc.replaced.push(link); log.push('replace'); },
  };
  return loc;
}

export function fakeHistory() {
  const hist = { calls: [] };
  hist.replaceState = (state, unused, url) => { hist.calls.push([state, unused, url]); };
  return hist;
}

// Answers one request. Every call is recorded with its parsed body (null when the request has no body).
export function fakeFetch(answers, log) {
  const calls = [];
  const fn = (url, init) => {
    calls.push({ url, init, body: init.body === undefined ? null : JSON.parse(init.body) });
    log.push('fetch ' + url);
    const answer = answers[url];
    if (answer === undefined) return Promise.reject(new Error('no route for ' + url));
    return answer();
  };
  return { fn, calls };
}

// A fake response: a status, the Retry-After header text or null, and a json method that counts its reads.
export function response(status, body = null, retryAfter = null) {
  const answer = {
    status,
    reads: 0,
    headers: { get: (name) => (name === 'Retry-After' ? retryAfter : null) },
    json: async () => {
      answer.reads += 1;
      if (body === null) throw new SyntaxError('no body');
      return body;
    },
  };
  return answer;
}

export function happyRoutes(overrides = {}) {
  return {
    '/v1/auth/salt': async () => response(200, saltAnswer().body),
    '/v1/auth/login': async () => response(200, loginAnswer().body),
    '/v1/auth/handoff/start': async () => response(201, { code: CODE_TEXT }),
    '/v1/auth/logout': async () => response(204),
    ...overrides,
  };
}

// The crypto stand-in when it is available: each function logs its name and answers with fixed bytes.
export function fakeCrypto(log, overrides = {}) {
  return {
    available: true,
    derive: async () => {
      log.push('derive');
      return { kek: new Uint8Array(KEK), verifier: new Uint8Array(VERIFIER) };
    },
    boxPublicKey: async () => { log.push('box'); return new Uint8Array(BOX_PUB); },
    seal: async () => { log.push('seal'); return new Uint8Array(SEALED); },
    ...overrides,
  };
}

// The crypto stand-in before any library exists: not available, and every function throws when called.
export function unavailableCrypto(log) {
  const refuse = async () => { log.push('crypto'); throw new Error('not available'); };
  return { available: false, derive: refuse, boxPublicKey: refuse, seal: refuse };
}

// Mounts the page on fresh fakes. Options: hash, cleanUrl, crypto, answers and log.
export function mountPage(options = {}) {
  const log = options.log ?? [];
  const doc = fakeDocument();
  const win = fakeWindow();
  const loc = fakeLocation(options.hash ?? VALID_HASH, log);
  const hist = fakeHistory();
  const net = fakeFetch(options.answers ?? happyRoutes(), log);
  const crypto = options.crypto ?? fakeCrypto(log);
  const cleanUrl = 'cleanUrl' in options ? options.cleanUrl : CLEAN_URL;
  initSigninPage({ document: doc, window: win, location: loc, history: hist, fetch: net.fn, crypto, cleanUrl });
  return { els: doc.elements, win, loc, hist, calls: net.calls, log, crypto };
}

export function fillForm(mounted, username = 'account one', typed = typedText) {
  mounted.els['signin-username'].value = username;
  mounted.els['signin-password'].value = typed;
}

function onlyListener(list, type) {
  const found = list.filter((entry) => entry.type === type);
  return found.length === 1 ? found[0].fn : null;
}

export function submitHandler(mounted) {
  return onlyListener(mounted.els['signin-form'].listeners, 'submit');
}

export function pagehideHandler(mounted) {
  return onlyListener(mounted.win.listeners, 'pagehide');
}

export function submitEvent() {
  const event = { count: 0, preventDefault() { event.count += 1; } };
  return event;
}

// Waits, one microtask at a time, until the predicate holds. It fails after 200 turns, so a test cannot hang.
export async function until(predicate) {
  for (let i = 0; i < 200; i += 1) {
    if (predicate()) return;
    await Promise.resolve();
  }
  throw new Error('the awaited state was not reached');
}
