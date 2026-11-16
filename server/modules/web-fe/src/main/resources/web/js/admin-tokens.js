// Admin agent-token flow: list the account's agent tokens, create one, revoke
// one. Everything is injected: getToken() gives the bearer token or null; net is
// the token-calls object ({ listTokens, createToken, revokeToken }); format(iso)
// turns an expiry stamp into display text; render is { showTokens(rows),
// showMessage(text), showSecret(token), showSignedOut() }. No DOM, no storage.
// The token value the server answers is handed straight to render.showSecret and
// is never kept in a variable of this module, so the flow cannot read it back.

export const ADMIN_TOKENS_TEXTS = Object.freeze({
  // GET /v1/admin/agent-tokens
  list_200: 'No agent tokens to show.',
  list_401: 'You are signed out. Sign in again to manage agent tokens.',
  list_403: 'Admin role required.',
  list_503: 'The server is unavailable. Try again in a moment.',
  // POST /v1/admin/agent-tokens
  create_201: 'Agent token created. Copy it now; it is shown only once.',
  create_400: 'The request was not valid. Check the name, the owner and the expiry.',
  create_401: 'You are signed out. Sign in again to manage agent tokens.',
  create_403: 'Admin role required.',
  create_404: 'No such account.',
  create_409: 'That account is waiting for a reset to be completed.',
  create_413: 'The request was too large.',
  create_503: 'The server is unavailable. Try again in a moment.',
  // DELETE /v1/admin/agent-tokens/{id}
  revoke_204: 'The agent token was revoked.',
  revoke_401: 'You are signed out. Sign in again to manage agent tokens.',
  revoke_403: 'Admin role required.',
  revoke_404: 'That agent token is already revoked.',
  // no status, and no answer at all
  unknown: 'The server answered in a way this page does not understand.',
  network: 'The server could not be reached. Try again in a moment.',
});

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function rowOf(entry, format) {
  return {
    id: entry.id,
    name: entry.name,
    owner_username: entry.owner_username,
    scope: entry.scope,
    expires_at: entry.expires_at,
    expires_text: format(entry.expires_at),
  };
}

function tokenOf(body) {
  return isObject(body) && typeof body.token === 'string' ? body.token : null;
}

export function createAdminTokens({ getToken, net, format, render }) {
  // A thrown fetch error becomes the network answer; net methods are plain functions.
  async function call(fn, ...args) {
    try {
      return await fn(...args);
    } catch {
      return { status: 0, body: null };
    }
  }

  function tokenOrOut() {
    const token = getToken();
    if (token === null || token === '') return null;
    return token;
  }

  // load() answers with what it did: 'ok' | 'signed_out' | 'forbidden' |
  // 'unavailable' | 'unknown' | 'network'.
  async function load() {
    const token = tokenOrOut();
    if (token === null) {
      render.showSignedOut();
      return 'signed_out';
    }
    const answer = await call(net.listTokens, token);
    const status = answer.status;
    if (status === 200) {
      // The server's answer is an array of entries. A 200 whose body is not one,
      // or whose entries are not objects, is not a list this page understands.
      if (!Array.isArray(answer.body) || !answer.body.every(isObject)) {
        render.showMessage(ADMIN_TOKENS_TEXTS.unknown);
        return 'unknown';
      }
      render.showTokens(answer.body.map((entry) => rowOf(entry, format)));
      return 'ok';
    }
    if (status === 401) {
      render.showSignedOut();
      return 'signed_out';
    }
    if (status === 403) {
      render.showMessage(ADMIN_TOKENS_TEXTS.list_403);
      return 'forbidden';
    }
    if (status === 503) {
      render.showMessage(ADMIN_TOKENS_TEXTS.list_503);
      return 'unavailable';
    }
    if (status === 0) {
      render.showMessage(ADMIN_TOKENS_TEXTS.network);
      return 'network';
    }
    render.showMessage(ADMIN_TOKENS_TEXTS.unknown);
    return 'unknown';
  }

  // createToken(request) answers 'created' | 'signed_out' | 'forbidden' |
  // 'failed' | 'unavailable' | 'unknown' | 'network'. It never answers the token
  // value: that goes to render.showSecret and nowhere else.
  async function createToken(request) {
    const token = tokenOrOut();
    if (token === null) {
      render.showSignedOut();
      return 'signed_out';
    }
    // An empty or all-blank name is not a request at all: show the fixed sentence
    // and send nothing. The server is never asked to create an unnamed token.
    if (!isObject(request) || typeof request.name !== 'string' || request.name.trim() === '') {
      render.showMessage(ADMIN_TOKENS_TEXTS.create_400);
      return 'failed';
    }
    const answer = await call(net.createToken, token, request);
    const status = answer.status;
    if (status === 201) {
      const secret = tokenOf(answer.body);
      if (secret === null) {
        // A 201 that carries no token string is not the answer this page claims:
        // never promise a token that was not given.
        render.showMessage(ADMIN_TOKENS_TEXTS.unknown);
        return 'failed';
      }
      render.showSecret(secret);
      const outcome = await load();
      return outcome === 'ok' ? 'created' : outcome;
    }
    if (status === 401) {
      render.showSignedOut();
      return 'signed_out';
    }
    if (status === 403) {
      render.showMessage(ADMIN_TOKENS_TEXTS.create_403);
      return 'forbidden';
    }
    if (status === 400) {
      render.showMessage(ADMIN_TOKENS_TEXTS.create_400);
      return 'failed';
    }
    if (status === 404) {
      render.showMessage(ADMIN_TOKENS_TEXTS.create_404);
      return 'failed';
    }
    if (status === 409) {
      render.showMessage(ADMIN_TOKENS_TEXTS.create_409);
      return 'failed';
    }
    if (status === 413) {
      render.showMessage(ADMIN_TOKENS_TEXTS.create_413);
      return 'failed';
    }
    if (status === 503) {
      render.showMessage(ADMIN_TOKENS_TEXTS.create_503);
      return 'unavailable';
    }
    if (status === 0) {
      render.showMessage(ADMIN_TOKENS_TEXTS.network);
      return 'network';
    }
    render.showMessage(ADMIN_TOKENS_TEXTS.unknown);
    return 'unknown';
  }

  // revoke(id) answers 'revoked' | 'signed_out' | 'forbidden' | 'unknown' |
  // 'network'. No confirm step: revoking one token is like signing out one device.
  async function revoke(id) {
    const token = tokenOrOut();
    if (token === null) {
      render.showSignedOut();
      return 'signed_out';
    }
    const answer = await call(net.revokeToken, token, id);
    const status = answer.status;
    if (status === 204) {
      const outcome = await load();
      if (outcome === 'ok') render.showMessage(ADMIN_TOKENS_TEXTS.revoke_204);
      return outcome === 'ok' ? 'revoked' : outcome;
    }
    if (status === 401) {
      render.showSignedOut();
      return 'signed_out';
    }
    if (status === 403) {
      render.showMessage(ADMIN_TOKENS_TEXTS.revoke_403);
      return 'forbidden';
    }
    if (status === 404) {
      // The token is already gone. Reload first and show the sentence after it,
      // because rendering a non-empty list clears the message: showing the
      // sentence first means the reload wipes it and the user never reads it.
      const outcome = await load();
      if (outcome === 'ok') render.showMessage(ADMIN_TOKENS_TEXTS.revoke_404);
      return outcome === 'ok' ? 'revoked' : outcome;
    }
    if (status === 0) {
      render.showMessage(ADMIN_TOKENS_TEXTS.network);
      return 'network';
    }
    render.showMessage(ADMIN_TOKENS_TEXTS.unknown);
    return 'unknown';
  }

  return { load, createToken, revoke };
}
