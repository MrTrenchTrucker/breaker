// Sign-in page logic: from the sign-in link to the return link. The salt, the derivation, the login, the
// box public key, the seal and the handoff start are injected; the two key unwraps use the real aesgcm.js.
// Keys live only in local variables and in arrays this module zero-fills on every path.
import { createCipher } from './aesgcm.js';
import {
  parseFragment, passwordToBytes, decodeBase64Padded, encodeBase64Padded, isCode, wipeBytes,
} from './signin-link.js';

// One plain sentence per result code. A locked answer with a usable wait uses lockedMessage instead.
export const MESSAGES = Object.freeze({
  bad_link: 'This sign-in link is not valid, so start the sign-in again from the app.',
  bad_credentials: 'The username or the password is not right.',
  locked: 'The account is locked after too many wrong tries, so try again later.',
  keyless: 'This account has no keys yet, so it cannot sign in to the app.',
  bad_salt: 'The account salt could not be read, so nothing was sent.',
  kdf_refused: 'The account key settings are outside the allowed range, so nothing was sent.',
  derive_refused: 'The password could not be checked, so nothing was sent.',
  keys_refused: 'The account keys could not be opened, so nothing was sent.',
  box_key_mismatch: 'The stored account key does not match, so sign-in stopped and nothing was sent.',
  seal_refused: 'The keys could not be sealed for the app, so nothing was sent.',
  bad_code: 'The sign-in code was not valid, so nothing was sent.',
  start_refused: 'The sign-in could not be started, so nothing was sent.',
  network: 'The connection failed, so try again later.',
});

const KDF_RANGES = {
  memory_kib: [65536, 262144],
  iterations: [3, 10],
  parallelism: [1, 4],
};
const OUTPUT_BYTES = 32;
const PACKAGE_BYTES = 65;
const SEALED_BYTES = 113;

// A refusal carries its code and its message out of the flow. Any other thrown error is the network result.
class Stop {
  constructor(code, message = MESSAGES[code]) {
    this.code = code;
    this.message = message;
  }
}

function stop(code, message) {
  throw new Stop(code, message);
}

function failure(code, message = MESSAGES[code]) {
  return { ok: false, code, message };
}

function outcome(error) {
  return error instanceof Stop ? failure(error.code, error.message) : failure('network');
}

function objectOrNull(value) {
  return value !== null && typeof value === 'object' ? value : null;
}

// Reads a field of an answer, or undefined when the answer is not an object.
function field(source, name) {
  return objectOrNull(source) ? source[name] : undefined;
}

const missing = (value) => value === null || value === undefined;

function isBytes(value, length) {
  return value instanceof Uint8Array && value.length === length;
}

function inRange(value, [low, high]) {
  return Number.isInteger(value) && value >= low && value <= high;
}

// Records a secret array in its run so the run's finally block zero-fills it. Anything else passes through.
function keep(cur, value) {
  if (value instanceof Uint8Array) cur.secrets.push(value);
  return value;
}

// A plain byte by byte comparison with no early exit.
function sameBytes(a, b) {
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a[i] ^ b[i];
  return diff === 0;
}

// Shows the wait only when Retry-After is text of digits, a whole number and at least one second.
function lockedMessage(retryAfter) {
  if (typeof retryAfter !== 'string' || !/^[0-9]+$/.test(retryAfter)) return MESSAGES.locked;
  const seconds = Number(retryAfter);
  if (!Number.isSafeInteger(seconds) || seconds < 1) return MESSAGES.locked;
  const unit = seconds === 1 ? 'second' : 'seconds';
  return 'The account is locked after too many wrong tries, so try again in ' + seconds + ' ' + unit + '.';
}

function unwrapKeys(cur, kek, wrappedDek, wrappedBox) {
  try {
    const dek = keep(cur, createCipher(kek).unwrapDek(wrappedDek));
    const boxKey = keep(cur, createCipher(dek).unwrapDek(wrappedBox));
    return { dek, boxKey };
  } catch {
    return stop('keys_refused');
  }
}

export function createSignin(deps) {
  let challenge = null;
  let appKey = null;
  let gen = 0;
  const active = new Set();

  // Called after every await: a wipeAll during the wait stops the run before anything else is called.
  function checkOpen(cur) {
    if (cur.gen !== gen) stop('network');
  }

  function readLink(hash) {
    const parsed = parseFragment(hash);
    deps.history.replaceState(null, '', deps.cleanUrl);
    wipeBytes(appKey);
    challenge = null;
    appKey = null;
    if (!parsed.ok) return failure('bad_link');
    challenge = parsed.challenge;
    appKey = parsed.appKey;
    return { ok: true };
  }

  async function readSalt(cur, username) {
    const answer = await deps.getSalt(username);
    checkOpen(cur);
    const body = field(answer, 'body');
    const kdf = objectOrNull(field(body, 'kdf_params'));
    if (field(answer, 'status') !== 200 || kdf === null) stop('bad_credentials');
    const salt = decodeBase64Padded(body.salt, 16);
    if (salt === null) stop('bad_salt');
    if (body.kdf_version !== 1) stop('kdf_refused');
    if (!inRange(kdf.memory_kib, KDF_RANGES.memory_kib) || !inRange(kdf.iterations, KDF_RANGES.iterations)
      || !inRange(kdf.parallelism, KDF_RANGES.parallelism)) stop('kdf_refused');
    const params = { memoryKib: kdf.memory_kib, iterations: kdf.iterations, parallelism: kdf.parallelism };
    return { salt, kdf: { ...params, outputLength: OUTPUT_BYTES } };
  }

  async function deriveKeys(cur, input, salt, kdf) {
    const password = keep(cur, passwordToBytes(input.typedText));
    const out = await deps.derive(password, salt, kdf);
    const kek = keep(cur, field(out, 'kek'));
    const verifier = keep(cur, field(out, 'verifier'));
    checkOpen(cur);
    if (!isBytes(kek, OUTPUT_BYTES) || !isBytes(verifier, OUTPUT_BYTES)) stop('derive_refused');
    return { kek, verifier };
  }

  async function login(cur, username, verifier) {
    const answer = await deps.login(username, encodeBase64Padded(verifier));
    checkOpen(cur);
    const status = field(answer, 'status');
    if (status === 401) stop('bad_credentials');
    if (status === 423) stop('locked', lockedMessage(field(answer, 'retryAfter')));
    const body = field(answer, 'body') || {};
    if (status !== 200 || typeof body.token !== 'string' || body.token === '') stop('network');
    if (missing(body.wrapped_dek) || missing(body.wrapped_box_privkey)) stop('keyless');
    return {
      token: body.token,
      wrappedDek: keep(cur, decodeBase64Padded(body.wrapped_dek, 60)),
      wrappedBox: keep(cur, decodeBase64Padded(body.wrapped_box_privkey, 60)),
      boxPubText: body.box_pubkey,
    };
  }

  async function checkBoxKey(cur, boxKey, boxPubText) {
    const boxPub = keep(cur, decodeBase64Padded(boxPubText, 32));
    if (boxPub === null) stop('keys_refused');
    const computed = keep(cur, await deps.boxPublicKey(boxKey));
    checkOpen(cur);
    if (!isBytes(computed, 32)) stop('keys_refused');
    if (!sameBytes(boxPub, computed)) stop('box_key_mismatch');
  }

  async function sealPackage(cur, dek, boxKey) {
    const pkg = keep(cur, new Uint8Array(PACKAGE_BYTES));
    pkg[0] = 1;
    pkg.set(dek, 1);
    pkg.set(boxKey, 33);
    const sealed = await deps.seal(pkg, cur.appCopy);
    checkOpen(cur);
    if (!isBytes(sealed, SEALED_BYTES)) stop('seal_refused');
    return encodeBase64Padded(sealed);
  }

  async function startHandoff(cur, token, sealedBase64) {
    const answer = await deps.startHandoff(cur.challenge, sealedBase64, token);
    checkOpen(cur);
    if (field(answer, 'status') !== 201) stop('start_refused');
    const code = field(field(answer, 'body'), 'code');
    if (!isCode(code)) stop('bad_code');
    return code;
  }

  async function flow(cur, input) {
    const { salt, kdf } = await readSalt(cur, input.username);
    const { kek, verifier } = await deriveKeys(cur, input, salt, kdf);
    const session = await login(cur, input.username, verifier);
    const { dek, boxKey } = unwrapKeys(cur, kek, session.wrappedDek, session.wrappedBox);
    await checkBoxKey(cur, boxKey, session.boxPubText);
    const sealed = await sealPackage(cur, dek, boxKey);
    const code = await startHandoff(cur, session.token, sealed);
    deps.location.replace('breaker://handoff?code=' + code);
    return { ok: true };
  }

  async function run(input) {
    if (challenge === null) return failure('bad_link');
    const cur = { gen, challenge, appCopy: null, secrets: [] };
    active.add(cur);
    try {
      cur.appCopy = keep(cur, new Uint8Array(appKey));
      return await flow(cur, input);
    } catch (error) {
      return outcome(error);
    } finally {
      for (const bytes of cur.secrets) wipeBytes(bytes);
      active.delete(cur);
    }
  }

  // For the page-hide hook: zero every secret of every run in flight and the stored app key.
  function wipeAll() {
    gen += 1;
    for (const cur of active) {
      for (const bytes of cur.secrets) wipeBytes(bytes);
    }
    wipeBytes(appKey);
    challenge = null;
    appKey = null;
  }

  return { readLink, run, wipeAll };
}
