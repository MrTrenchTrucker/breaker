// The three calls the admin agent-token view makes to the server's own routes:
// list the account's agent tokens, create one, revoke one. Every call carries
// the bearer token, no cookies, no cache. The fetch function is passed in and
// is called as a plain function. A network error is not swallowed here: the
// call rejects and the flow is what catches it. The module never retries.
export function createAdminTokensNet({ baseUrl, fetchFn }) {
  // The parsed answer, or null when the text is not JSON.
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  async function listTokens(token) {
    const response = await fetchFn(`${baseUrl}/v1/admin/agent-tokens`, {
      method: 'GET',
      headers: { Authorization: 'Bearer ' + token },
      credentials: 'omit',
      cache: 'no-store',
    });
    return { status: response.status, body: await readBody(response) };
  }

  async function createToken(token, request) {
    const response = await fetchFn(`${baseUrl}/v1/admin/agent-tokens`, {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
      credentials: 'omit',
      cache: 'no-store',
      body: JSON.stringify(request),
    });
    return { status: response.status, body: await readBody(response) };
  }

  async function revokeToken(token, id) {
    const response = await fetchFn(
      `${baseUrl}/v1/admin/agent-tokens/` + encodeURIComponent(String(id)),
      {
        method: 'DELETE',
        headers: { Authorization: 'Bearer ' + token },
        credentials: 'omit',
        cache: 'no-store',
      },
    );
    return { status: response.status, body: await readBody(response) };
  }

  return { listTokens, createToken, revokeToken };
}
