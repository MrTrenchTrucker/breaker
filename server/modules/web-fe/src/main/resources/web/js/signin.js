// Sign-in page logic: from the sign-in link to the return link. The salt, the derivation, the login, the
// box public key, the seal, the handoff start and the pending-sealed conversion are injected; the two key
// unwraps use the real aesgcm.js. Keys live only in local variables and zero-filled arrays.
import { createCipher } from './aesgcm.js';
import {
  parseFragment, passwordToBytes, decodeBase64Padded, encodeBase64Padded, isCode, wipeBytes,
} from './signin-link.js';

// The purpose of the box private key blob, as ASCII bytes. It is built from parts, so the whole text is in no one literal.
const BOX_PURPOSE_PARTS = Object.freeze(['breaker', 'box', 'privkey', 'v1']);
const BOX_KEY_PURPOSE = new TextEncoder().encode(BOX_PURPOSE_PARTS.join('-'));

// One plain sentence per result code. Every result code has one plain sentence, and a locked answer always uses MESSAGES.locked.
export const MESSAGES = Object.freeze({
  bad_link: 'This sign-in link is not valid, so start the sign-in again from the app.',
  bad_credentials: 'The username or the password is not right.',
  locked: 'The account is locked after too many wrong tries, so try again later.',
  bad_salt: 'The account salt could not be read, so nothing was sent.',
  kdf_refused: 'The account key settings are outside the allowed range, so nothing was sent.',
  derive_refused: 'The password could not be checked, so nothing was sent.',
  keys_refused: 'The account keys could not be opened, so nothing was sent.',
  upload_refused: 'The account keys could not be stored, so sign-in stopped and nothing was sent.',
  box_key_mismatch: 'The stored account key does not match, so sign-in stopped and nothing was sent.',
  seal_refused: 'The keys could not be sealed for the app, so nothing was sent.',
  bad_code: 'The sign-in code was not valid, so nothing was sent.',
  start_refused: 'The sign-in could not be started, so nothing was sent.',
  network: 'The connection failed, so try again later.',
});

// The one summary sentence the hand-off adds after a conversion; the flow reads only the counts.
export function conversionSummaryText({ converted, skipped, failed }) {
  return 'Pending transcriptions: ' + converted + ' converted, ' + skipped + ' skipped, ' + failed + ' failed.';
}

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
  constructor(code, message = MESSAGES[code]) { this.code = code; this.message = message; }
}
function stop(code, message) { throw new Stop(code, message); }
function failure(code, message = MESSAGES[code]) { return { ok: false, code, message }; }
function outcome(error) { return error instanceof Stop ? failure(error.code, error.message) : failure('network'); }
function objectOrNull(value) { return value !== null && typeof value === 'object' ? value : null; }
// Reads a field of an answer, or undefined when the answer is not an object.
function field(source, name) { return objectOrNull(source) ? source[name] : undefined; }
const missing = (value) => value === null || value === undefined;
function isBytes(value, length) { return value instanceof Uint8Array && value.length === length; }
function inRange(value, [low, high]) { return Number.isInteger(value) && value >= low && value <= high; }

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

function unwrapKeys(cur, kek, wrappedDek, wrappedBox) {
  try {
    const dek = keep(cur, createCipher(kek).unwrapDek(wrappedDek));
    const boxKey = keep(cur, createCipher(dek).unwrapDek(wrappedBox, BOX_KEY_PURPOSE));
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

  // One logout per run, and only once a session token exists. Best effort: a throw or a rejection is ignored,
  // and the token leaves the run record as it is sent.
  async function logoutOnce(cur) {
    if (cur.token === null || cur.loggedOut) return;
    const token = cur.token;
    cur.token = null;
    cur.loggedOut = true;
    try {
      await deps.logout(token);
    } catch {
      // Ignored: a failed logout never changes the result.
    }
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
    if (status === 423) stop('locked');
    const body = field(answer, 'body') || {};
    if (status !== 200 || typeof body.token !== 'string' || body.token === '') stop('network');
    cur.token = body.token;
    // The account's key_version travels with the login answer; a keyless account answers 0 here.
    const keyVersion = body.key_version;
    if (!Number.isInteger(keyVersion) || keyVersion < 0) stop('keys_refused');
    // A keyless account carries both wrapped keys as null together, and the flow bootstraps it. Any
    // other answer is an account that HAS keys, so it is refused rather than re-bootstrapped over:
    // exactly one null is an inconsistent account, and a wrapped key that is present but does not
    // decode is refused further down by unwrapKeys.
    if (missing(body.wrapped_dek) && missing(body.wrapped_box_privkey)) {
      return { token: body.token, keyless: true, boxPubText: body.box_pubkey, keyVersion };
    }
    // An account that HAS keys must be at version 1 or later; only a keyless account may answer 0.
    if (keyVersion < 1) stop('keys_refused');
    if (missing(body.wrapped_dek) || missing(body.wrapped_box_privkey)) stop('keys_refused');
    return {
      token: body.token,
      keyless: false,
      wrappedDek: keep(cur, decodeBase64Padded(body.wrapped_dek, 60)),
      wrappedBox: keep(cur, decodeBase64Padded(body.wrapped_box_privkey, 60)),
      boxPubText: body.box_pubkey,
      keyVersion,
    };
  }

  // The stored path: open the two wrapped keys, and carry the stored box public text to the check.
  async function storedKeys(cur, kek, session) {
    const { dek, boxKey } = unwrapKeys(cur, kek, session.wrappedDek, session.wrappedBox);
    return { dek, boxKey, boxPubText: session.boxPubText, keyVersion: session.keyVersion };
  }

  // The keyless path: the injected bootstrap makes a new data key and box key pair under the KEK, the
  // three members are uploaded, and the flow continues with the NEW keys. A throw, a bad shape or a
  // refused upload stops here, so nothing is sealed and nothing is started.
  async function bootstrapKeys(cur, kek) {
    let made;
    try {
      made = await deps.bootstrap(kek);
    } catch {
      return stop('keys_refused');
    }
    checkOpen(cur);
    const answer = made === null || typeof made !== 'object' ? {} : made;
    const dek = keep(cur, field(answer, 'dek'));
    const boxKey = keep(cur, field(answer, 'boxPrivkey'));
    const boxPub = keep(cur, field(answer, 'boxPubkey'));
    const { wrappedDek, wrappedBoxPrivkey: wrappedBox } = answer;
    if (!isBytes(dek, OUTPUT_BYTES) || !isBytes(boxKey, OUTPUT_BYTES) || !isBytes(boxPub, OUTPUT_BYTES)
      || !isBytes(wrappedDek, 60) || !isBytes(wrappedBox, 60)) stop('keys_refused');
    const uploaded = await deps.postKeys(cur.token, {
      wrappedDek,
      wrappedBoxPrivkey: wrappedBox,
      boxPubkey: boxPub,
    });
    checkOpen(cur);
    if (field(uploaded, 'ok') !== true) stop('upload_refused');
    return { dek, boxKey, boxPubText: encodeBase64Padded(boxPub), keyVersion: field(uploaded, 'keyVersion') };
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

  // The pending-sealed conversion runs between the tamper check and the seal; it never blocks and never
  // alters the hand-off. A throw is one counted failure; a 401/403 counts like any other, never a sign-out.
  async function convertPending(cur, keys) {
    let outcome;
    try {
      outcome = await deps.convert(cur, keys);
    } catch {
      outcome = { status: 'list_failed', converted: 0, skipped: 0, failed: 1 };
    }
    const counts = field(outcome, 'status') === 'signed_out' ? { converted: 0, skipped: 0, failed: 1 } : outcome;
    return conversionSummaryText({
      converted: field(counts, 'converted') || 0, skipped: field(counts, 'skipped') || 0, failed: field(counts, 'failed') || 0,
    });
  }

  async function flow(cur, input) {
    const { salt, kdf } = await readSalt(cur, input.username);
    const { kek, verifier } = await deriveKeys(cur, input, salt, kdf);
    const session = await login(cur, input.username, verifier);
    const keys = session.keyless === true ? await bootstrapKeys(cur, kek) : await storedKeys(cur, kek, session);
    await checkBoxKey(cur, keys.boxKey, keys.boxPubText);
    keys.summary = await convertPending(cur, keys);
    const sealed = await sealPackage(cur, keys.dek, keys.boxKey);
    const code = await startHandoff(cur, session.token, sealed);
    await logoutOnce(cur);
    checkOpen(cur);
    deps.location.replace('breaker://handoff?code=' + code);
    return { ok: true, summary: keys.summary };
  }

  async function run(input) {
    if (challenge === null) return failure('bad_link');
    const cur = { gen, challenge, appCopy: null, secrets: [], token: null, loggedOut: false };
    active.add(cur);
    try {
      cur.appCopy = keep(cur, new Uint8Array(appKey));
      return await flow(cur, input);
    } catch (error) {
      return outcome(error);
    } finally {
      for (const bytes of cur.secrets) wipeBytes(bytes);
      logoutOnce(cur);
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
