// Pending-sealed conversion for the web FE: list GET /v1/sync/pending-sealed,
// unseal each row with the box private key, re-encrypt under the DEK, upload as
// a new sync record with client_id = the pending record's id. Everything
// injected: unseal, encrypt, upload, fetch. No DOM, no network, no storage.

export const MESSAGES = Object.freeze({
  signed_out: 'You are signed out. Sign in again to see your transcriptions.',
  retry: 'The server is unavailable. Try again in a moment.',
  list_failed: 'The pending-sealed list could not be read. Try again in a moment.',
});

function isObject(value) {
  return value !== null && typeof value === 'object';
}

function isString(value) {
  return typeof value === 'string';
}

// Strict padded standard base64: decode and re-encode, must match exactly.
function decodeBase64Strict(text) {
  if (typeof text !== 'string' || text.length === 0 || text.length % 4 !== 0) return null;
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let pad = 0;
  while (text[text.length - 1 - pad] === '=') pad += 1;
  if (pad > 2) return null;
  const data = text.substring(0, text.length - pad);
  const out = new Uint8Array(Math.floor(data.length * 6 / 8));
  let acc = 0;
  let accBits = 0;
  let at = 0;
  for (let i = 0; i < data.length; i += 1) {
    const v = alphabet.indexOf(data.charAt(i));
    if (v < 0) return null;
    acc = (acc << 6) | v;
    accBits += 6;
    if (accBits >= 8) {
      accBits -= 8;
      out[at] = (acc >> accBits) & 255;
      at += 1;
      acc &= (1 << accBits) - 1;
    }
  }
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let encoded = '';
  for (let i = 0; i < out.length; i += 3) {
    const b0 = out[i];
    const b1 = i + 1 < out.length ? out[i + 1] : 0;
    const b2 = i + 2 < out.length ? out[i + 2] : 0;
    const triple = (b0 << 16) | (b1 << 8) | b2;
    encoded += chars.charAt((triple >> 18) & 63);
    encoded += chars.charAt((triple >> 12) & 63);
    encoded += i + 1 < out.length ? chars.charAt((triple >> 6) & 63) : '=';
    encoded += i + 2 < out.length ? chars.charAt(triple & 63) : '=';
  }
  return encoded === text ? out : null;
}

export function createPendingSealed({ unseal, encrypt, upload, fetch: fetchFn }) {
  async function run(token, boxPrivateKey, keyVersion, render) {
    let answer;
    try {
      answer = await fetchFn(token);
    } catch {
      render.showMessage(MESSAGES.list_failed);
      return { status: 'list_failed', converted: 0, skipped: 0, failed: 1 };
    }
    const status = answer.status;
    if (status === 401 || status === 403) {
      render.showSignedOut();
      return { status: 'signed_out', converted: 0, skipped: 0, failed: 0 };
    }
    if (status !== 200) {
      render.showMessage(MESSAGES.list_failed);
      return { status: 'list_failed', converted: 0, skipped: 0, failed: 1 };
    }
    const body = answer.body;
    if (!Array.isArray(body)) {
      render.showMessage(MESSAGES.list_failed);
      return { status: 'list_failed', converted: 0, skipped: 0, failed: 1 };
    }
    let converted = 0;
    let skipped = 0;
    let failed = 0;
    for (const row of body) {
      if (!isObject(row)) {
        skipped += 1;
        continue;
      }
      const id = row.id;
      const sealedBoxText = row.sealed_box;
      const createdAt = row.created_at;
      if (!isString(id) || !isString(sealedBoxText) || !isString(createdAt) || createdAt === '') {
        skipped += 1;
        continue;
      }
      const sealedBytes = decodeBase64Strict(sealedBoxText);
      if (sealedBytes === null) {
        skipped += 1;
        continue;
      }
      let plaintext;
      try {
        plaintext = await unseal(sealedBytes, boxPrivateKey);
      } catch {
        skipped += 1;
        continue;
      }
      if (!(plaintext instanceof Uint8Array)) {
        skipped += 1;
        continue;
      }
      // The plaintext is encrypted first and wiped as soon as encrypt has answered, whatever it answered.
      let sealed;
      try {
        sealed = await encrypt(plaintext, id, keyVersion);
      } catch {
        skipped += 1;
        continue;
      } finally {
        plaintext.fill(0);
      }
      if (sealed === null || sealed === undefined) {
        skipped += 1;
        continue;
      }
      let outcome;
      try {
        outcome = await upload(id, keyVersion, sealed);
      } catch {
        outcome = 'FAILED';
      }
      if (outcome === 'CONFIRMED') {
        converted += 1;
      } else {
        failed += 1;
      }
    }
    return { status: 'ok', converted, skipped, failed };
  }
  return { run };
}
