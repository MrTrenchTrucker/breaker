// Record conversion, encrypt side. The pending-sealed row's
// plaintext is an R1 body whose source is server; before it is encrypted it must
// be validated as a strict server R1 body (ADR-006 P5, ADR-018), and the SAME
// bytes are then encrypted verbatim -- never re-serialised, reordered or
// rewritten. cipher is the injected aesgcm cipher object ({ seal, open, ... }),
// the same shape transcriptions.js injects. The adapter answers standard padded
// base64 of nonce(12) | ciphertext | tag(16), or null for every refusal. It
// throws nothing. No DOM, no network and no storage: the flow owns those.

import { encodeBase64Padded } from './signin-link.js';

// AAD, pinned by RecordFrame.kt aad lines 75-80 and ADR-006 P2 ("AAD is the UTF-8
// bytes of client_id, a newline, and the decimal key_version"): the UTF-8 bytes of
// clientId, one LF (0x0A) byte, then the DECIMAL keyVersion.
export function recordAad(clientId, keyVersion) {
  return new TextEncoder().encode(clientId + '\n' + String(keyVersion));
}

// The six members of an R1 body in the exact order decodeStrictServerBody requires.
// Cite SyncBodyCodec.decodeStrictServerBody: "accepts only a body that decodeBody
// accepts, whose six keys are in the exact order ..., and whose source is exactly
// server". A refusal is null, which pending-sealed.js counts as skipped.
const BODY_KEYS = ['v', 'text', 'source', 'model', 'duration_ms', 'created_at'];
const DURATION_RE = /^(?:0|[1-9][0-9]{0,15})$/;
const MAX_DURATION_MS = 9007199254740991;
const CLIENT_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const CREATED_AT_RE = /^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})\.([0-9]{3})Z$/;
const UTF8 = new TextDecoder('utf-8', { fatal: true });

function isObject(value) {
  return value !== null && typeof value === 'object';
}

function memberKind(members, key) {
  const member = members.get(key);
  return member === undefined ? '' : member.kind;
}

// Strict UTF-8, and no BOM: openapi.yaml /v1/sync refuses a leading BOM and
// invalid UTF-8 bytes. Null when either is present.
function decodeUtf8Strict(bytes) {
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) return null;
  try {
    return UTF8.decode(bytes);
  } catch {
    return null;
  }
}

// A real calendar date/time: 1970-01-01T00:00:00.000Z .. 9999-12-31T23:59:59.999Z,
// the same rule as SyncBodyTime.parseCreatedAtMs.
function isCreatedStamp(value) {
  const match = CREATED_AT_RE.exec(value);
  if (match === null) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  if (year < 1970 || month < 1 || month > 12) return false;
  if (day < 1 || day > daysInMonth(year, month)) return false;
  return hour <= 23 && minute <= 59 && second <= 59;
}

function daysInMonth(year, month) {
  if (month === 2) return isLeapYear(year) ? 29 : 28;
  if (month === 4 || month === 6 || month === 9 || month === 11) return 30;
  return 31;
}

function isLeapYear(year) {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

// A strict JSON reader over the decoded text, mirroring SyncBodyScanner: JSON
// whitespace (space, tab, LF, CR), every standard escape, duplicate keys refused,
// nesting depth 1, nothing after the object. It answers { ok, keys, members } or
// { ok: false }; it never throws out of scanBody.
function scanBody(text) {
  const state = { text, at: 0 };

  function fail() {
    throw new Error('r1-refused');
  }

  function atEnd() {
    return state.at >= text.length;
  }

  function peek() {
    if (atEnd()) fail();
    return text[state.at];
  }

  function take() {
    const character = peek();
    state.at += 1;
    return character;
  }

  function skipWhitespace() {
    while (!atEnd()) {
      const character = text[state.at];
      if (character === ' ' || character === '\t' || character === '\n' || character === '\r') {
        state.at += 1;
      } else {
        break;
      }
    }
  }

  function readHex4() {
    let hex = '';
    for (let i = 0; i < 4; i += 1) {
      const character = take();
      if (!/[0-9a-fA-F]/.test(character)) fail();
      hex += character;
    }
    return hex;
  }

  // The caller has consumed the opening quote.
  function parseString() {
    let out = '';
    for (;;) {
      const character = take();
      if (character === '"') return out;
      if (character === '\\') {
        out += parseEscape();
        continue;
      }
      if (character.charCodeAt(0) < 0x20) fail();
      out += character;
    }
  }

  function parseEscape() {
    const character = take();
    if (character === '"') return '"';
    if (character === '\\') return '\\';
    if (character === '/') return '/';
    if (character === 'b') return '\b';
    if (character === 'f') return '\f';
    if (character === 'n') return '\n';
    if (character === 'r') return '\r';
    if (character === 't') return '\t';
    if (character !== 'u') fail();
    const code = parseInt(readHex4(), 16);
    if (code >= 0xdc00 && code <= 0xdfff) fail();
    if (code < 0xd800 || code > 0xdbff) return String.fromCharCode(code);
    // A high surrogate must be followed by a \u low surrogate; a lone surrogate
    // escape is refused.
    if (take() !== '\\') fail();
    if (take() !== 'u') fail();
    const low = parseInt(readHex4(), 16);
    if (low < 0xdc00 || low > 0xdfff) fail();
    return String.fromCharCode(code, low);
  }

  function parseNumberToken() {
    const start = state.at;
    while (!atEnd()) {
      const character = text[state.at];
      const numeric = (character >= '0' && character <= '9')
        || character === '+' || character === '-'
        || character === '.' || character === 'e' || character === 'E';
      if (!numeric) break;
      state.at += 1;
    }
    return text.slice(start, state.at);
  }

  function parseLiteral(word) {
    for (let i = 0; i < word.length; i += 1) {
      if (take() !== word[i]) fail();
    }
    return { kind: 'literal', value: word };
  }

  function parseValue(depth) {
    skipWhitespace();
    const character = peek();
    if (character === '"') {
      state.at += 1;
      return { kind: 'string', value: parseString() };
    }
    if (character === '{' || character === '[') {
      // Nesting depth 1: a member value may not be an object or an array.
      fail();
    }
    if (character === 't') return parseLiteral('true');
    if (character === 'f') return parseLiteral('false');
    if (character === 'n') return parseLiteral('null');
    if (character === '-' || (character >= '0' && character <= '9')) {
      return { kind: 'number', token: parseNumberToken() };
    }
    fail();
    return undefined;
  }

  function parseObject() {
    const members = new Map();
    skipWhitespace();
    if (!atEnd() && text[state.at] === '}') {
      state.at += 1;
      return members;
    }
    for (;;) {
      skipWhitespace();
      if (peek() !== '"') fail();
      state.at += 1;
      const key = parseString();
      if (members.has(key)) fail();
      skipWhitespace();
      if (take() !== ':') fail();
      members.set(key, parseValue(1));
      skipWhitespace();
      const separator = take();
      if (separator === ',') continue;
      if (separator === '}') return members;
      fail();
    }
  }

  try {
    skipWhitespace();
    if (peek() !== '{') return { ok: false };
    state.at += 1;
    const members = parseObject();
    skipWhitespace();
    if (!atEnd()) return { ok: false };
    return { ok: true, keys: Array.from(members.keys()), members };
  } catch {
    return { ok: false };
  }
}

// Everything decodeBody checks, then the exact key order, then source === 'server'.
function isStrictServerBody(bytes) {
  const text = decodeUtf8Strict(bytes);
  if (text === null) return false;
  const scanned = scanBody(text);
  if (!scanned.ok) return false;
  const { keys, members } = scanned;

  // Unknown key refused.
  for (const key of keys) {
    if (!BODY_KEYS.includes(key)) return false;
  }

  // The field rules, in decodeBody's order.
  const version = members.get('v');
  if (memberKind(members, 'v') !== 'number' || version.token !== '1') return false;
  if (memberKind(members, 'text') !== 'string') return false;
  const source = members.get('source');
  if (memberKind(members, 'source') !== 'string') return false;
  if (source.value !== 'local' && source.value !== 'server') return false;
  if (memberKind(members, 'model') !== 'string') return false;
  const duration = members.get('duration_ms');
  if (memberKind(members, 'duration_ms') !== 'number' || !DURATION_RE.test(duration.token)) return false;
  const durationMs = Number(duration.token);
  if (!(durationMs >= 0 && durationMs <= MAX_DURATION_MS)) return false;
  if (memberKind(members, 'created_at') !== 'string' || !isCreatedStamp(members.get('created_at').value)) {
    return false;
  }

  // The six keys in the exact order.
  if (keys.length !== BODY_KEYS.length) return false;
  for (let i = 0; i < BODY_KEYS.length; i += 1) {
    if (keys[i] !== BODY_KEYS[i]) return false;
  }

  return source.value === 'server';
}

export function createRecordEncrypt({ cipher }) {
  return function encryptRecord(plaintext, clientId, keyVersion) {
    if (!(plaintext instanceof Uint8Array)) return null;
    if (typeof clientId !== 'string' || !CLIENT_ID_RE.test(clientId)) return null;
    if (!Number.isInteger(keyVersion) || keyVersion < 1) return null;
    if (!isStrictServerBody(plaintext)) return null;

    let sealed;
    try {
      sealed = cipher.seal(plaintext, recordAad(clientId, keyVersion));
    } catch {
      return null;
    }
    if (!isObject(sealed)) return null;
    const nonce = sealed.nonce;
    const ciphertext = sealed.ciphertext;
    const tag = sealed.tag;
    if (!(nonce instanceof Uint8Array) || nonce.length !== 12) return null;
    if (!(ciphertext instanceof Uint8Array)) return null;
    if (!(tag instanceof Uint8Array) || tag.length !== 16) return null;

    // The frame shape transcriptions.js publishes: nonce | ciphertext | tag.
    const frame = new Uint8Array(12 + ciphertext.length + 16);
    frame.set(nonce, 0);
    frame.set(ciphertext, 12);
    frame.set(tag, 12 + ciphertext.length);
    try {
      return encodeBase64Padded(frame);
    } catch {
      return null;
    }
  };
}
