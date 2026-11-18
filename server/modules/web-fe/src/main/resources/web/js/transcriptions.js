// Transcription dashboard flow: list the account's transcriptions, decrypt each row
// with the data key and the associated data the Android side builds, search and copy
// client-side, delete one row. Everything is injected: getToken() -> token string or
// null; net is the transcriptions-calls object ({ listTranscriptions, listOlder,
// deleteTranscription }); dek is the 32-byte data-encryption key; copy(text) answers
// 'COPIED' or 'FAILED'; format(iso) turns a stored_at stamp into display text; render
// is { showRows(rows), showMessage(text), showSignedOut() }. No DOM, no network and
// no storage of its own: the view owns the DOM, the net module owns the HTTP.

import { createCipher } from './aesgcm.js';
import { isStrictBody } from './conversion-encrypt.js';

// The one fixed string a row that cannot be decrypted is shown as. Never blank.
export const UNREADABLE = 'cannot be read';

export const MESSAGES = Object.freeze({
  signed_out: 'You are signed out. Sign in again to see your transcriptions.',
  retry: 'The server is unavailable. Try again in a moment.',
  list_failed: 'The list could not be loaded. Try again in a moment.',
  empty: 'No transcriptions to show.',
  copied: 'Copied to the clipboard.',
  copy_failed: 'The text could not be copied.',
});

// A page is at most 100 rows. This cap only stops a server that answers more = true
// for ever from looping for ever; a well-behaved server ends after a handful of pages.
const MAX_PAGES = 1000;

const STANDARD_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

const UTF8 = new TextDecoder('utf-8', { fatal: true });

function isObject(value) {
  return value !== null && typeof value === 'object';
}

function isString(value) {
  return typeof value === 'string';
}

// Strict padded standard base64: decode and re-encode, and the two must match exactly.
// A frame that is truncated, unpadded, non-canonical or carries a foreign character is
// refused rather than silently decrypted into something else.
function decodeBase64Strict(text) {
  if (!isString(text) || text.length === 0 || text.length % 4 !== 0) return null;
  let pad = 0;
  while (text[text.length - 1 - pad] === '=') pad += 1;
  if (pad > 2) return null;
  const data = text.substring(0, text.length - pad);
  const out = new Uint8Array(Math.floor(data.length * 6 / 8));
  let acc = 0;
  let accBits = 0;
  let at = 0;
  for (let i = 0; i < data.length; i += 1) {
    const value = STANDARD_CHARS.indexOf(data.charAt(i));
    if (value < 0) return null;
    acc = (acc << 6) | value;
    accBits += 6;
    if (accBits >= 8) {
      accBits -= 8;
      out[at] = (acc >> accBits) & 255;
      at += 1;
      acc &= (1 << accBits) - 1;
    }
  }
  let encoded = '';
  for (let i = 0; i < out.length; i += 3) {
    const b0 = out[i];
    const b1 = i + 1 < out.length ? out[i + 1] : 0;
    const b2 = i + 2 < out.length ? out[i + 2] : 0;
    const triple = (b0 << 16) | (b1 << 8) | b2;
    encoded += STANDARD_CHARS.charAt((triple >> 18) & 63);
    encoded += STANDARD_CHARS.charAt((triple >> 12) & 63);
    encoded += i + 1 < out.length ? STANDARD_CHARS.charAt((triple >> 6) & 63) : '=';
    encoded += i + 2 < out.length ? STANDARD_CHARS.charAt(triple & 63) : '=';
  }
  return encoded === text ? out : null;
}

// The associated data of a transcription row: the UTF-8 bytes of the client id, one LF
// byte, then the decimal key version. This is the builder the Android side uses
// (shared/modules/api-contracts/src/main/kotlin/dev/breaker/shared/api/sync/RecordFrame.kt,
// RecordFrame.aad, lines 75-80); the row's own key_version is part of it, so a body
// cannot be moved to another record or another key version.
export function transcriptionAad(clientId, keyVersion) {
  return new TextEncoder().encode(clientId + '\n' + String(keyVersion));
}

// The three parts of a record frame: the nonce, the ciphertext and the tag.
function decodeFrame(text) {
  const bytes = decodeBase64Strict(text);
  if (bytes === null || bytes.length < 28) return null;
  return {
    nonce: bytes.subarray(0, 12),
    ciphertext: bytes.subarray(12, bytes.length - 16),
    tag: bytes.subarray(bytes.length - 16),
  };
}

// The decrypted text of a row, or null when it cannot be read: a delete record, a row
// with no usable key_version, a frame that is not strict base64, a tag that does not
// verify, or a plaintext that is not the R1 body.
export function decryptRowText(row, cipher) {
  if (!isObject(row)) return null;
  if (row.deleted === true) return null;
  const version = row.key_version;
  if (!Number.isInteger(version) || version < 1) return null;
  const clientId = row.client_id;
  if (!isString(clientId) || clientId === '') return null;
  const frame = decodeFrame(isString(row.encrypted) ? row.encrypted : '');
  if (frame === null) return null;
  let plain;
  try {
    plain = cipher.open(frame, transcriptionAad(clientId, version));
  } catch {
    return null;
  }
  // The writer's decodeBody level, shared: the plaintext must be a valid R1 body
  // (six members, types and ranges, source local or server, any key order). A
  // plaintext that is not -- for example {"text":"x"} -- is refused here, so the
  // row keeps its place and shows the fixed unreadable text.
  if (!isStrictBody(plain)) return null;
  let body;
  try {
    body = JSON.parse(UTF8.decode(plain));
  } catch {
    return null;
  }
  return body.text;
}

// One view row per live record. A row that cannot be read keeps its place and carries
// the fixed unreadable text, so it never disappears and never renders blank.
function rowsFrom(items, cipher, format) {
  const out = [];
  for (const item of items) {
    if (!isObject(item)) continue;
    if (item.deleted === true) continue;
    if (!isString(item.client_id) || item.client_id === '') continue;
    const text = decryptRowText(item, cipher);
    const storedAt = isString(item.stored_at) ? item.stored_at : '';
    out.push({
      id: item.client_id,
      text: text === null ? UNREADABLE : text,
      unreadable: text === null,
      stored_at: storedAt,
      stored_at_text: format(storedAt),
    });
  }
  return out;
}

export function createTranscriptions({ getToken, net, dek, copy, format, render }) {
  let rows = [];

  async function tokenOrSignedOut() {
    const token = getToken();
    if (token === null || token === '') {
      render.showSignedOut();
      return null;
    }
    return token;
  }

  // A thrown fetch error is the retry path. The net methods are called as plain
  // functions, never as methods of this flow.
  async function call(fn, ...args) {
    try {
      return await fn(...args);
    } catch {
      return { status: 0, body: null };
    }
  }

  // A data key that is not a 32-byte Uint8Array is refused by the AES-GCM layer. The
  // flow then shows every row as unreadable instead of throwing.
  function makeCipher() {
    try {
      return createCipher(dek);
    } catch {
      return null;
    }
  }

  // Pulls one paged route into a flat list of records. Returns 'ok' with the records,
  // or the status the caller must show.
  async function pull(token, pageFn, firstArg) {
    const items = [];
    let arg = firstArg;
    let more = true;
    let pages = 0;
    while (more) {
      if (pages >= MAX_PAGES) {
        render.showMessage(MESSAGES.list_failed);
        return { status: 'list_failed', items: null };
      }
      pages += 1;
      const answer = await call(pageFn, token, arg);
      const status = answer.status;
      if (status === 200) {
        const body = answer.body;
        if (!isObject(body) || !Array.isArray(body.items)) {
          render.showMessage(MESSAGES.list_failed);
          return { status: 'list_failed', items: null };
        }
        for (const item of body.items) items.push(item);
        more = body.more === true && isString(body.next) && body.next !== '';
        arg = more ? body.next : null;
        continue;
      }
      if (status === 401 || status === 403) {
        render.showSignedOut();
        return { status: 'signed_out', items: null };
      }
      render.showMessage(MESSAGES.retry);
      return { status: 'retry', items: null };
    }
    return { status: 'ok', items };
  }

  // Loads the newest page of transcriptions, following since = next while more is
  // true. A 200 that is not a TranscriptionPage is a list failure, not an empty list.
  async function load() {
    const token = await tokenOrSignedOut();
    if (token === null) return 'signed_out';
    const pulled = await pull(token, net.listTranscriptions, null);
    if (pulled.status !== 'ok') return pulled.status;
    rows = rowsFrom(pulled.items, makeCipher(), format);
    render.showRows(rows);
    return 'ok';
  }

  // Loads one batch older than the given bound ("<ms>" or "<ms>-<change>") and appends
  // it to the rows already on screen. The bound is the caller's stored_at.
  async function loadOlder(olderThan) {
    const token = await tokenOrSignedOut();
    if (token === null) return 'signed_out';
    const pulled = await pull(token, net.listOlder, olderThan);
    if (pulled.status !== 'ok') return pulled.status;
    rows = rows.concat(rowsFrom(pulled.items, makeCipher(), format));
    render.showRows(rows);
    return 'ok';
  }

  // Client-side only: filters the rows already decrypted and on screen. No server call.
  function search(term) {
    const needle = isString(term) ? term.toLowerCase() : '';
    const shown = needle === ''
      ? rows
      : rows.filter((row) => row.text.toLowerCase().includes(needle));
    render.showRows(shown);
    return shown;
  }

  // Copies the text of one row through the injected clipboard. No server call.
  async function copyText(text) {
    if (!isString(text) || text === '') {
      render.showMessage(MESSAGES.copy_failed);
      return 'copy_failed';
    }
    let answer;
    try {
      answer = await copy(text);
    } catch {
      answer = 'FAILED';
    }
    if (answer === 'COPIED') {
      render.showMessage(MESSAGES.copied);
      return 'copied';
    }
    render.showMessage(MESSAGES.copy_failed);
    return 'copy_failed';
  }

  // Deletes one row by client id. A 204 carries no body; the list is then reloaded so
  // the row disappears. There is no delete-all.
  async function deleteOne(clientId) {
    const token = await tokenOrSignedOut();
    if (token === null) return 'signed_out';
    const answer = await call(net.deleteTranscription, token, clientId);
    const status = answer.status;
    if (status === 204) {
      await load();
      return 'ok';
    }
    if (status === 401 || status === 403) {
      render.showSignedOut();
      return 'signed_out';
    }
    render.showMessage(MESSAGES.retry);
    return 'retry';
  }

  return { load, loadOlder, search, copyText, deleteOne };
}
