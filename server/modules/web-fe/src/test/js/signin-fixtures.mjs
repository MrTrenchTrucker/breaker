// Shared fixtures for the sign-in tests. Every base64 text is built in code from patterned bytes, so no
// base64 literal is written here. The real aesgcm.js builds the wrapped keys, and the fakes record calls.
import { createCipher } from '../../main/resources/web/js/aesgcm.js';
import { encodeBase64Padded } from '../../main/resources/web/js/signin-link.js';
import { createSignin } from '../../main/resources/web/js/signin.js';

export const patterned = (base, size) => Uint8Array.from({ length: size }, (_, i) => (base + i) & 255);

// Unpadded URL-safe text of 32 bytes: the challenge, the app key and the code all have this shape.
export function toUrlSafe43(bytes) {
  return encodeBase64Padded(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export const KEK = patterned(0x10, 32);
export const VERIFIER = patterned(0x30, 32);
export const DEK = patterned(0xa0, 32);
export const BOX_KEY = patterned(0xc0, 32);
export const BOX_PUB = patterned(0xe0, 32);
export const SALT = patterned(0x20, 16);
export const SEALED = patterned(0x70, 113);
export const APP_KEY = patterned(0x40, 32);
export const CHALLENGE_BYTES = patterned(0x60, 32);
export const CODE_BYTES = patterned(0x90, 32);

export const CHALLENGE_TEXT = toUrlSafe43(CHALLENGE_BYTES);
export const APP_TEXT = toUrlSafe43(APP_KEY);
export const CODE_TEXT = toUrlSafe43(CODE_BYTES);
export const VALID_HASH = '#challenge=' + CHALLENGE_TEXT + '&app_key=' + APP_TEXT;
export const typedText = 'typed sample text';
export const sessionText = 'session sample text';

export const EXPECTED_PACKAGE = new Uint8Array(65);
EXPECTED_PACKAGE[0] = 1;
EXPECTED_PACKAGE.set(DEK, 1);
EXPECTED_PACKAGE.set(BOX_KEY, 33);

export const GOOD_KDF = { memory_kib: 65536, iterations: 3, parallelism: 1 };

export function wrapped(key, plain) {
  return createCipher(key, { random: () => patterned(0x50, 12) }).wrapDek(plain);
}

export function saltAnswer(body = {}) {
  return {
    status: 200,
    body: { salt: encodeBase64Padded(SALT), kdf_params: { ...GOOD_KDF }, kdf_version: 1, ...body },
  };
}

export function loginAnswer(body = {}) {
  return {
    status: 200,
    retryAfter: null,
    body: {
      token: sessionText,
      wrapped_dek: encodeBase64Padded(wrapped(KEK, DEK)),
      wrapped_box_privkey: encodeBase64Padded(wrapped(DEK, BOX_KEY)),
      box_pubkey: encodeBase64Padded(BOX_PUB),
      ...body,
    },
  };
}

// Fake dependencies. Each fake records what it was given (copies at call time, and references where the
// test checks zero-fill) and appends its name to the call-order log.
// Count and zero state of every secret array the fakes were handed, read at the moment of the call.
function secretRefsState(rec) {
  const refs = [...rec.kekRefs, ...rec.verifierRefs, ...rec.boxRefs, ...rec.boxResultRefs, ...rec.sealRefs, ...rec.passwordRefs, ...rec.appRefs];
  return { count: refs.length, allZero: refs.every((bytes) => isZero(bytes)) };
}

export function makeDeps(overrides = {}) {
  const rec = {
    log: [], salt: [], derive: [], login: [], box: [], seal: [], start: [], replaced: [], historyCalls: [],
    kekRefs: [], verifierRefs: [], boxRefs: [], boxResultRefs: [], sealRefs: [], passwordRefs: [], appRefs: [],
    logout: [], logoutZero: [], logoutMode: 'ok', logoutHold: deferred(),
  };
  const deps = {
    cleanUrl: '/signin.html',
    history: { replaceState(state, unused, url) { rec.historyCalls.push([state, unused, url]); } },
    location: { replace(link) { rec.replaced.push(link); rec.log.push('replace'); } },
    getSalt: async (username) => { rec.salt.push(username); rec.log.push('salt'); return saltAnswer(); },
    derive: async (passwordBytes, saltBytes, kdfParams) => {
      rec.derive.push({ password: new Uint8Array(passwordBytes), salt: new Uint8Array(saltBytes), kdfParams });
      rec.passwordRefs.push(passwordBytes);
      rec.log.push('derive');
      const kek = new Uint8Array(KEK);
      const verifier = new Uint8Array(VERIFIER);
      rec.kekRefs.push(kek);
      rec.verifierRefs.push(verifier);
      return { kek, verifier };
    },
    login: async (username, verifierBase64) => {
      rec.login.push({ username, verifierBase64 });
      rec.log.push('login');
      return loginAnswer();
    },
    boxPublicKey: async (boxPrivateKey) => {
      rec.box.push(new Uint8Array(boxPrivateKey));
      rec.boxRefs.push(boxPrivateKey);
      rec.log.push('box');
      const computed = new Uint8Array(BOX_PUB);
      rec.boxResultRefs.push(computed);
      return computed;
    },
    seal: async (packageBytes, appKeyBytes) => {
      rec.seal.push({ pkg: new Uint8Array(packageBytes), app: new Uint8Array(appKeyBytes) });
      rec.sealRefs.push(packageBytes);
      rec.appRefs.push(appKeyBytes);
      rec.log.push('seal');
      return new Uint8Array(SEALED);
    },
    startHandoff: async (challenge, sealedBase64, token) => {
      rec.start.push({ challenge, sealed: sealedBase64, token });
      rec.log.push('start');
      return { status: 201, body: { code: CODE_TEXT } };
    },
    logout: (token) => {
      rec.logout.push(token);
      rec.log.push('logout');
      rec.logoutZero.push(secretRefsState(rec));
      if (rec.logoutMode === 'throw') throw new Error('logout threw');
      if (rec.logoutMode === 'reject') return Promise.reject(new Error('logout rejected'));
      if (rec.logoutMode === 'hold') return rec.logoutHold.promise;
      return undefined;
    },
    ...overrides,
  };
  return { deps, rec };
}

export function readySignin(overrides = {}, hash = VALID_HASH) {
  const { deps, rec } = makeDeps(overrides);
  const signin = createSignin(deps);
  const linked = signin.readLink(hash);
  return { deps, rec, signin, linked };
}

// The typed text is read through a getter, so a test can see whether the password was ever read.
export function makeInput(text = typedText, username = 'account one') {
  const input = { username, reads: 0 };
  Object.defineProperty(input, 'typedText', {
    enumerable: true,
    get() { input.reads += 1; return text; },
  });
  return input;
}

// A storage-like object whose every access throws, so a test fails if the code touches it.
export function poisoned(name) {
  const fail = () => { throw new Error(name + ' was touched'); };
  return new Proxy({}, {
    get: fail, set: fail, has: fail, ownKeys: fail, getOwnPropertyDescriptor: fail,
    defineProperty: fail, deleteProperty: fail, getPrototypeOf: fail,
  });
}

export const isZero = (bytes) => bytes.every((b) => b === 0);

export function flipped(bytes) {
  const out = new Uint8Array(bytes);
  out[31] ^= 1;
  return out;
}

export async function settle() {
  for (let i = 0; i < 25; i += 1) await Promise.resolve();
}

export function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}
