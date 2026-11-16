// Admin store flow: clear one user's transcription store or every user's store.
// Everything is injected: getToken() -> token string or null; net is the store-calls
// object ({ clearStore }); render is { showMessage(text), showSignedOut() }. No DOM
// access, no browser storage. The token is never kept beyond the one call it is used
// for. Every sentence the panel shows is a constant in ADMIN_STORE_TEXTS.

export const ADMIN_STORE_TEXTS = Object.freeze({
  cleared: 'The store was cleared: {count} rows.',
  bad_request: 'The server refused the request. Check the confirmation and try again.',
  unauthorized: 'You are not signed in. Sign in again to clear the store.',
  forbidden: 'Your account is not an admin. An admin role is required.',
  not_found: 'No such account. The name was not found.',
  unknown: 'The server answered with a status this panel does not understand.',
  network: 'The server is unavailable. Try again in a moment.',
  confirm_one: 'Clear the store for {username}?',
  confirm_all: 'Clear the store for all users?',
  needs_name: 'Type a user name first.',
});

function isObject(value) {
  return value !== null && typeof value === 'object';
}

// The cleared sentence with the server's own count in it.
function clearedSentence(count) {
  return ADMIN_STORE_TEXTS.cleared.replace('{count}', String(count));
}

export function createAdminStore({ getToken, net, render }) {
  async function tokenOrSignedOut() {
    const token = getToken();
    if (token === null || token === '') {
      render.showSignedOut();
      return null;
    }
    return token;
  }

  // A thrown fetch error becomes the network path; net.clearStore is called as a
  // plain function with no this.
  async function call(fn, ...args) {
    try {
      return await fn(...args);
    } catch {
      return { status: 0, body: null };
    }
  }

  // One fixed sentence per status. The 200 sentence carries the server's count,
  // never a value this side assumed.
  function answerFor(answer) {
    const status = answer.status;
    if (status === 200) {
      const count = isObject(answer.body) && Number.isInteger(answer.body.cleared) ? answer.body.cleared : null;
      if (count === null) {
        render.showMessage(ADMIN_STORE_TEXTS.unknown);
        return 'unknown';
      }
      render.showMessage(clearedSentence(count));
      return 'ok';
    }
    if (status === 400) {
      render.showMessage(ADMIN_STORE_TEXTS.bad_request);
      return 'bad_request';
    }
    if (status === 401) {
      render.showMessage(ADMIN_STORE_TEXTS.unauthorized);
      return 'unauthorized';
    }
    if (status === 403) {
      render.showMessage(ADMIN_STORE_TEXTS.forbidden);
      return 'forbidden';
    }
    if (status === 404) {
      render.showMessage(ADMIN_STORE_TEXTS.not_found);
      return 'not_found';
    }
    if (status === 0) {
      render.showMessage(ADMIN_STORE_TEXTS.network);
      return 'network';
    }
    render.showMessage(ADMIN_STORE_TEXTS.unknown);
    return 'unknown';
  }

  async function clearOne(username) {
    const token = await tokenOrSignedOut();
    if (token === null) return 'signed_out';
    const answer = await call(net.clearStore, token, { username, confirm: true });
    return answerFor(answer);
  }

  async function clearAll() {
    const token = await tokenOrSignedOut();
    if (token === null) return 'signed_out';
    const answer = await call(net.clearStore, token, { username: null, confirm: true });
    return answerFor(answer);
  }

  return { clearOne, clearAll };
}
