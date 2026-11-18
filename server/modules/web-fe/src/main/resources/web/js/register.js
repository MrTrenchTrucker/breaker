// Registration flow: policy check, a fresh sixteen byte salt from an injected random source, the
// existing sign-in stretch, the register call, then the first login and, because a brand new account is
// keyless, the same first-login bootstrap wiring signin.js uses (deps.bootstrap and deps.postKeys).
// Every dependency is injected: random(size), derive(passwordBytes, salt, params), register(members),
// login(username, verifierBase64), bootstrap(kek), postKeys(token, members). No DOM, no network of its
// own, no storage. Secret arrays are zero-filled on every path.
import { encodeBase64Padded, isUrlSafe43, passwordToBytes, wipeBytes } from './signin-link.js';
import { checkPassword, MESSAGES as POLICY_MESSAGES } from './register-policy.js';

// The cost the Android app uses and the floor signin-kdf.js accepts, so a registration derives at the
// same strength as a sign-in.
export const DEFAULT_KDF_PARAMS = Object.freeze({ memoryKib: 65536, iterations: 3, parallelism: 1 });

// The contract's own username pattern, repeated here so a bad name is refused before the stretch runs.
const USERNAME_RE = /^[A-Za-z0-9._-]{1,64}$/;

// One plain sentence per result. A refusal carries its code and its message out of the flow.
export const MESSAGES = Object.freeze({
  password_too_short: POLICY_MESSAGES.too_short,
  bad_username: 'That username is not allowed, so nothing was sent.',
  bad_install_code: 'That install code is not a code this server accepts, so nothing was sent.',
  bad_credentials: 'The username or the password is not right.',
  locked: 'The account is locked after too many wrong tries, so try again later.',
  unavailable: 'The service is unavailable, try again later.',
  random_refused: 'A random value could not be drawn, so nothing was sent.',
  derive_refused: 'The password could not be checked, so nothing was sent.',
  keys_refused: 'The account keys could not be made, so nothing was stored.',
  upload_refused: 'The account keys could not be stored, so sign-in stopped and nothing was sent.',
  unknown: 'The server answered in a way this page does not understand.',
  network: 'The connection failed, so try again later.',
});

const SALT_BYTES = 16;
const OUTPUT_BYTES = 32;
const PUBKEY_BYTES = 32;
const WRAPPED_BYTES = 60;
const KDF_VERSION = 1;

function failure(code, message = MESSAGES[code]) {
  return { ok: false, code, message };
}

function isBytes(value, length) {
  return value instanceof Uint8Array && value.length === length;
}

function isObject(value) {
  return value !== null && typeof value === 'object';
}

function field(source, name) {
  return isObject(source) ? source[name] : undefined;
}

const missing = (value) => value === null || value === undefined;

// Draws one salt and copies it, so a source that keeps and mutates its buffer cannot change the
// bytes that were registered.
function drawSalt(random) {
  if (typeof random !== 'function') throw new Error('no random source');
  const raw = random(SALT_BYTES);
  if (!(raw instanceof Uint8Array) || raw.length !== SALT_BYTES) throw new Error('bad salt length');
  return new Uint8Array(raw);
}

// Reads a login answer: a 200 with a session token is the only answer that lets the flow continue.
// The keyless answer is the one signin.js carries: both wrapped keys missing together is a brand new
// account that gets bootstrapped, exactly one missing is an inconsistent account that is refused, and
// any other answer is an account that already has its keys.
function readSession(session) {
  const status = field(session, 'status');
  const body = field(session, 'body');
  if (status === 401) return { code: 'bad_credentials' };
  if (status === 423) return { code: 'locked' };
  // The contract's own 503 meaning, the same sentence the register route uses: a store failure is not a
  // connection failure, and the person should come back later rather than check their network.
  if (status === 503) return { code: 'unavailable' };
  if (status !== 200 || !isObject(body) || typeof body.token !== 'string' || body.token === '') {
    return { code: 'network' };
  }
  const dekMissing = missing(field(body, 'wrapped_dek'));
  const boxMissing = missing(field(body, 'wrapped_box_privkey'));
  if (dekMissing && boxMissing) return { token: body.token, keyless: true };
  if (dekMissing || boxMissing) return { code: 'keys_refused' };
  return { token: body.token, keyless: false };
}

export function createRegister(deps) {
  // The bootstrap members are the three byte arrays signin.js hands to its postKeys; the net module
  // encodes them for the wire.
  async function bootstrapKeys(kek, token, secrets) {
    let made;
    try {
      made = await deps.bootstrap(kek);
    } catch {
      return failure('keys_refused');
    }
    const dek = field(made, 'dek');
    const boxPrivkey = field(made, 'boxPrivkey');
    // Track both secrets the moment bootstrap answers, so the finally block in run() zeroes them on
    // every later path: a refused shape, a refused upload, a throw or an early return.
    if (dek instanceof Uint8Array) secrets.push(dek);
    if (boxPrivkey instanceof Uint8Array) secrets.push(boxPrivkey);
    const boxPubkey = field(made, 'boxPubkey');
    const wrappedDek = field(made, 'wrappedDek');
    const wrappedBox = field(made, 'wrappedBoxPrivkey');
    if (!isBytes(dek, OUTPUT_BYTES) || !isBytes(boxPrivkey, OUTPUT_BYTES) || !isBytes(boxPubkey, PUBKEY_BYTES)
      || !isBytes(wrappedDek, WRAPPED_BYTES) || !isBytes(wrappedBox, WRAPPED_BYTES)) return failure('keys_refused');

    let uploaded;
    try {
      uploaded = await deps.postKeys(token, {
        wrappedDek,
        wrappedBoxPrivkey: wrappedBox,
        boxPubkey: boxPubkey,
      });
    } catch {
      return failure('upload_refused');
    }
    if (!isObject(uploaded) || uploaded.ok !== true) return failure('upload_refused');
    return { ok: true, keyVersion: field(uploaded, 'keyVersion') };
  }

  async function flow(input, secrets) {
    const typedText = field(input, 'typedText');
    if (!checkPassword(typedText).ok) return failure('password_too_short');

    // The cheap shapes before the expensive one. The server is the authority, but a name or a code it
    // would refuse costs a 64 MiB stretch first, so the flow refuses here with one fixed sentence each
    // and sends nothing. A blank install code is absent, not malformed: the field is optional.
    const typedName = field(input, 'username');
    if (typeof typedName !== 'string' || !USERNAME_RE.test(typedName)) return failure('bad_username');
    const typedCode = field(input, 'installCode');
    const installCode = typeof typedCode === 'string' && typedCode !== '' ? typedCode : undefined;
    if (installCode !== undefined && !isUrlSafe43(installCode)) return failure('bad_install_code');

    const keep = (value) => {
      if (value instanceof Uint8Array) secrets.push(value);
      return value;
    };

    let salt;
    try {
      salt = keep(drawSalt(deps.random));
    } catch {
      return failure('random_refused');
    }

    const password = keep(passwordToBytes(typedText));
    let out;
    try {
      out = await deps.derive(password, salt, { ...DEFAULT_KDF_PARAMS, outputLength: OUTPUT_BYTES });
    } catch {
      return failure('derive_refused');
    }
    const kek = field(out, 'kek');
    const verifier = field(out, 'verifier');
    // Track before validating: a valid kek must be wiped even when its partner is malformed,
    // otherwise this refusal path leaks it.
    keep(kek);
    keep(verifier);
    if (!isBytes(kek, OUTPUT_BYTES) || !isBytes(verifier, OUTPUT_BYTES)) return failure('derive_refused');

    let answer;
    try {
      answer = await deps.register({
        username: field(input, 'username'),
        salt,
        kdfParams: {
          memory_kib: DEFAULT_KDF_PARAMS.memoryKib,
          iterations: DEFAULT_KDF_PARAMS.iterations,
          parallelism: DEFAULT_KDF_PARAMS.parallelism,
        },
        kdfVersion: KDF_VERSION,
        verifier,
        installCode,
      });
    } catch {
      return failure('network');
    }
    if (!isObject(answer) || answer.ok !== true) {
      const code = field(answer, 'code');
      const message = field(answer, 'message');
      return failure(typeof code === 'string' ? code : 'unknown',
        typeof message === 'string' ? message : MESSAGES.unknown);
    }

    let session;
    try {
      session = await deps.login(field(input, 'username'), encodeBase64Padded(verifier));
    } catch {
      return failure('network');
    }
    const read = readSession(session);
    if (read.code !== undefined) return failure(read.code);

    // A brand new account is keyless, so the first login runs the same bootstrap signin.js runs. An
    // account that already has both keys is left alone; exactly one missing key never reaches here.
    if (!read.keyless) {
      return { ok: true, username: field(answer, 'username'), role: field(answer, 'role') };
    }
    const bootstrapped = await bootstrapKeys(kek, read.token, secrets);
    if (!bootstrapped.ok) return bootstrapped;
    return {
      ok: true,
      username: field(answer, 'username'),
      role: field(answer, 'role'),
      keyVersion: bootstrapped.keyVersion,
    };
  }

  async function run(input) {
    const secrets = [];
    try {
      return await flow(input, secrets);
    } catch {
      return failure('network');
    } finally {
      for (const bytes of secrets) wipeBytes(bytes);
    }
  }

  return { run };
}
