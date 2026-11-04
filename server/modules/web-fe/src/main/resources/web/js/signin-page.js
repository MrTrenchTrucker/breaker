// Sign-in page: the DOM work for the sign-in page. Only this file touches document and window. The flow comes
// from signin.js, the four network calls from signin-net.js and the three key functions from signin-crypto.js.
// Every message is written with textContent, and the password field is emptied after every run.
import * as cryptoModule from './signin-crypto.js';
import { createSignin } from './signin.js';
import { createNet } from './signin-net.js';

export const PAGE_MESSAGES = Object.freeze({
  not_available: 'Signing in through the website is not available yet, so this page cannot hand your sign-in to the app.',
  bad_setup: 'This page could not be set up safely, so nothing was sent.',
  signed_in: 'Signed in. Returning to the app.',
});

// Wires the five page elements to the sign-in flow. Every outcome is written to the status line.
export function initSigninPage({ document, window, location, history, fetch, crypto, cleanUrl }) {
  const form = document.getElementById('signin-form');
  const username = document.getElementById('signin-username');
  const password = document.getElementById('signin-password');
  const submit = document.getElementById('signin-submit');
  const status = document.getElementById('signin-status');
  let signin = null;
  let ready = false;
  let running = false;

  function say(message) {
    status.textContent = message;
  }

  function disableAll() { username.disabled = true; password.disabled = true; submit.disabled = true; }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!ready || running) return;
    running = true;
    submit.disabled = true;
    let typed = password.value;
    let ok = false;
    try {
      const result = await signin.run({ username: username.value, typedText: typed });
      ok = result.ok === true;
      say(ok ? PAGE_MESSAGES.signed_in : result.message);
    } finally {
      typed = '';
      password.value = '';
      running = false;
      if (!ok) submit.disabled = false;
    }
  });

  if (typeof cleanUrl !== 'string' || cleanUrl === '') {
    say(PAGE_MESSAGES.bad_setup);
    form.hidden = true; // setup refused
    disableAll(); // setup refused
    return;
  }

  const net = createNet(fetch);
  signin = createSignin({
    cleanUrl,
    history,
    location,
    getSalt: net.getSalt,
    derive: crypto.derive,
    login: net.login,
    boxPublicKey: crypto.boxPublicKey,
    seal: crypto.seal,
    startHandoff: net.startHandoff,
    logout: net.logout,
  });
  window.addEventListener('pagehide', () => {
    signin.wipeAll();
    password.value = '';
  });

  const linked = signin.readLink(location.hash);
  if (!linked.ok) {
    say(linked.message);
    form.hidden = true; // link refused
    disableAll(); // link refused
    return;
  }
  if (crypto.available !== true) {
    disableAll(); // crypto unavailable
    say(PAGE_MESSAGES.not_available);
    return;
  }
  username.disabled = false;
  password.disabled = false;
  submit.disabled = false;
  ready = true;
}

// The page runs itself only in a browser, where document is an object. Under node nothing runs.
if (typeof document === 'object' && document !== null) {
  initSigninPage({
    document,
    window,
    location,
    history,
    fetch,
    crypto: cryptoModule,
    cleanUrl: location.pathname + location.search,
  });
}
