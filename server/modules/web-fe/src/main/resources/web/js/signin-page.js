// Sign-in page: the DOM work for the sign-in page. Only this file touches document and window. The flow comes
// from signin.js, the four calls from signin-net.js, the first-login keys upload from bootstrap-net.js, the
// pending-sealed conversion from pending-sealed.js, and the three key functions from signin-crypto.js. Every
// message is written with textContent, and the password field is emptied after every run.
import * as cryptoModule from './signin-crypto.js';
import { createSignin } from './signin.js';
import { createNet } from './signin-net.js';
import { createBootstrapCrypto } from './bootstrap-crypto.js';
import { createBootstrapNet } from './bootstrap-net.js';
import { createCipher } from './aesgcm.js';
import { unseal } from './unseal.js';
import { createRecordEncrypt } from './conversion-encrypt.js';
import { createRecordUpload } from './conversion-upload.js';
import { createPendingSealed } from './pending-sealed.js';
import { createPendingSealedNet } from './pending-sealed-net.js';

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
      say(result.ok ? PAGE_MESSAGES.signed_in + (result.summary ? ' ' + result.summary : '') : result.message);
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
  // The first-login keys upload is bootstrap-net.js's one net call, built with the page's own base URL (the same
  // root the four calls use) and the page's fetch. Page code passes neither the random nor the wipe hook.
  const bootstrapNet = createBootstrapNet({ baseUrl: '', fetchFn: fetch });
  // The pending-sealed conversion runs between the tamper check and the seal. Its own render writes
  // nothing to the page; the flow reads only the counts and the page shows one summary line.
  const pendingNet = createPendingSealedNet({ baseUrl: '', fetchFn: fetch });
  const silentRender = { showMessage() {}, showSignedOut() {} };
  const convert = async (cur, keys) => {
    const encrypt = createRecordEncrypt({ cipher: createCipher(keys.dek) });
    const upload = createRecordUpload({ baseUrl: '', fetchFn: fetch, getToken: () => cur.token });
    const conversion = createPendingSealed({
      unseal,
      encrypt,
      upload,
      fetch: pendingNet.fetchPendingSealed,
    });
    return conversion.run(cur.token, keys.boxKey, keys.keyVersion, silentRender);
  };
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
    bootstrap: createBootstrapCrypto().bootstrap,
    postKeys: bootstrapNet.postKeys,
    convert,
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
