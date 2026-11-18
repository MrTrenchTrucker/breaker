// The one call the first-login key bootstrap makes to the server's own route: POST /v1/auth/keys with
// exactly the three members, each standard padded base64 of 60, 60 and 32 bytes, the bearer token, no
// cookies and no cache. The fetch function and the base URL are passed in, and the fetch function is
// called as a plain function. Every answer, named or not, is one fixed message from MESSAGES; a thrown or
// rejecting fetch is the network one. This module touches no page and keeps no state.
import { encodeBase64Padded } from './signin-link.js';

export const MESSAGES = Object.freeze({
  bad_request: 'The keys were not stored, because the upload was not accepted.',
  unauthorized: 'You are signed out, so the keys were not stored.',
  forbidden: 'This sign-in is not allowed to store keys, so nothing was stored.',
  conflict: 'This account already has keys, so nothing was stored.',
  reset_pending: 'This account is waiting for a reset to be completed, so finish that first.',
  too_large: 'The upload was too large, so nothing was stored.',
  unknown: 'The server answered in a way this page does not understand.',
  network: 'The connection failed, so the keys were not stored.',
});

// The server's own reset-pending text: the one 409 body that means a reset is waiting rather than the
// account simply having keys already.
const RESET_PENDING_TEXT = 'The account is waiting for a reset to be completed';

const WRAPPED_BYTES = 60;
const PUBKEY_BYTES = 32;
const JSON_TYPE = 'application/json';

function failure(code) {
  return { ok: false, code, message: MESSAGES[code] };
}

function isBytes(value, length) {
  return value instanceof Uint8Array && value.length === length;
}

function isObject(value) {
  return value !== null && typeof value === 'object';
}

// Only a non-empty session token is sent. Anything else is the signed-out answer, and no call is made.
function tokenText(token) {
  return typeof token === 'string' && token !== '' ? token : null;
}

export function createBootstrapNet({ baseUrl, fetchFn }) {
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  function resetIsPending(body) {
    return isObject(body) && body.message === RESET_PENDING_TEXT;
  }

  async function postKeys(token, members) {
    const bearer = tokenText(token);
    if (bearer === null) return failure('unauthorized');
    if (!isObject(members)
      || !isBytes(members.wrappedDek, WRAPPED_BYTES)
      || !isBytes(members.wrappedBoxPrivkey, WRAPPED_BYTES)
      || !isBytes(members.boxPubkey, PUBKEY_BYTES)) return failure('bad_request');

    let response;
    try {
      response = await fetchFn(baseUrl + '/v1/auth/keys', {
        method: 'POST',
        headers: { 'Content-Type': JSON_TYPE, Authorization: 'Bearer ' + bearer },
        credentials: 'omit',
        cache: 'no-store',
        body: JSON.stringify({
          wrapped_dek: encodeBase64Padded(members.wrappedDek),
          wrapped_box_privkey: encodeBase64Padded(members.wrappedBoxPrivkey),
          box_pubkey: encodeBase64Padded(members.boxPubkey),
        }),
      });
    } catch {
      return failure('network');
    }
    if (!isObject(response)) return failure('unknown');

    const status = response.status;
    const body = await readBody(response);
    if (status === 400) return failure('bad_request');
    if (status === 401) return failure('unauthorized');
    if (status === 403) return failure('forbidden');
    if (status === 409) return resetIsPending(body) ? failure('reset_pending') : failure('conflict');
    if (status === 413) return failure('too_large');
    if (status === 200) {
      const version = isObject(body) ? body.key_version : undefined;
      if (Number.isInteger(version) && version >= 1) return { ok: true, keyVersion: version };
    }
    return failure('unknown');
  }

  return { postKeys };
}
