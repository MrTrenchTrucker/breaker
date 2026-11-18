// The one call the registration page makes to the server's own route: POST /v1/auth/register with the
// six contract members, no cookies, no cache and no stored answer. The fetch function and the base URL
// are passed in, and the fetch function is called as a plain function. Every answer, named or not, is
// one fixed message from MESSAGES; a thrown or rejecting fetch is the network one. The Retry-After
// header of a 429 is never read and never appears in a result, so no wait is ever shown as a number.
// This file touches no page and keeps no state.
import { encodeBase64Padded, isUrlSafe43 } from './signin-link.js';

export const MESSAGES = Object.freeze({
  bad_request: 'The registration was not accepted, so no account was created.',
  registration_closed: 'Registration is closed, so no account was created.',
  username_taken: 'That username is already registered, so nothing was created.',
  too_large: 'The registration was too large, so nothing was created.',
  rate_limited: 'Too many attempts, so nothing was created. Try again later.',
  unavailable: 'The service is unavailable, so nothing was created. Try again later.',
  unknown: 'The server answered in a way this page does not understand.',
  network: 'The connection failed, so nothing was created.',
});

const REGISTER_PATH = '/v1/auth/register';
const JSON_TYPE = 'application/json';
const USERNAME_RE = /^[A-Za-z0-9._-]{1,64}$/;
const SALT_BYTES = 16;
const VERIFIER_BYTES = 32;
const MEMORY_KIB = [65536, 262144];
const ITERATIONS = [3, 10];
const PARALLELISM = [1, 4];
const KDF_VERSION = 1;

function failure(code) {
  return { ok: false, code, message: MESSAGES[code] };
}

function isBytes(value, length) {
  return value instanceof Uint8Array && value.length === length;
}

function isObject(value) {
  return value !== null && typeof value === 'object';
}

function inRange(value, [low, high]) {
  return Number.isInteger(value) && value >= low && value <= high;
}

// Every check runs before any request. A member set the server would refuse is refused here first,
// with the same one sentence, and nothing is sent.
function validMembers(members) {
  if (!isObject(members)) return false;
  if (typeof members.username !== 'string' || !USERNAME_RE.test(members.username)) return false;
  if (!isBytes(members.salt, SALT_BYTES)) return false;
  if (!isObject(members.kdfParams)) return false;
  if (!inRange(members.kdfParams.memory_kib, MEMORY_KIB)
    || !inRange(members.kdfParams.iterations, ITERATIONS)
    || !inRange(members.kdfParams.parallelism, PARALLELISM)) return false;
  if (members.kdfVersion !== KDF_VERSION) return false;
  if (!isBytes(members.verifier, VERIFIER_BYTES)) return false;
  // Optional: absent is fine, present must be exactly 43 URL-safe characters, never trimmed or folded.
  if (members.installCode !== undefined && !isUrlSafe43(members.installCode)) return false;
  return true;
}

export function createRegisterNet({ baseUrl, fetchFn }) {
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  async function register(members) {
    if (!validMembers(members)) return failure('bad_request');

    // The install code travels only when it was given; the contract sets additionalProperties false,
    // so an absent member is omitted rather than sent as null.
    const body = {
      username: members.username,
      salt: encodeBase64Padded(members.salt),
      kdf_params: {
        memory_kib: members.kdfParams.memory_kib,
        iterations: members.kdfParams.iterations,
        parallelism: members.kdfParams.parallelism,
      },
      kdf_version: members.kdfVersion,
      auth_verifier: encodeBase64Padded(members.verifier),
    };
    if (members.installCode !== undefined) body.install_code = members.installCode;

    let response;
    try {
      response = await fetchFn(baseUrl + REGISTER_PATH, {
        method: 'POST',
        headers: { 'Content-Type': JSON_TYPE },
        credentials: 'omit',
        cache: 'no-store',
        body: JSON.stringify(body),
      });
    } catch {
      return failure('network');
    }
    if (!isObject(response)) return failure('unknown');

    const status = response.status;
    const answer = await readBody(response);
    if (status === 400) return failure('bad_request');
    if (status === 403) return failure('registration_closed');
    if (status === 409) return failure('username_taken');
    if (status === 413) return failure('too_large');
    if (status === 429) return failure('rate_limited');
    if (status === 503) return failure('unavailable');
    if (status === 201) {
      if (isObject(answer) && typeof answer.username === 'string' && answer.username !== ''
        && typeof answer.role === 'string' && answer.role !== '') {
        return { ok: true, username: answer.username, role: answer.role };
      }
    }
    return failure('unknown');
  }

  return { register };
}
