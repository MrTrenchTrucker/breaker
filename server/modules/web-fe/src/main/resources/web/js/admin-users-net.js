// The two calls the admin users view makes to the server's own routes: set an
// account's admin role and reset an account. Every call carries the bearer
// token, no cookies, no cache, and a JSON body with its content type. The
// fetch function is passed in and is called as a plain function. A network
// error is not swallowed here: the call rejects and the flow is what catches
// it. The module never retries, so a failure is the caller's to handle.
export function createAdminUsersNet({ baseUrl, fetchFn }) {
  async function post(path, token, body) {
    return fetchFn(`${baseUrl}${path}`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json',
      },
      credentials: 'omit',
      cache: 'no-store',
      body: JSON.stringify(body),
    });
  }

  // The parsed answer, or null when the text is not JSON. A non-200 answer is
  // passed through unchanged; the flow decides what it means.
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  async function setRole(token, username, admin) {
    const response = await post('/v1/admin/roles', token, { username: String(username), admin });
    return { status: response.status, body: await readBody(response) };
  }

  async function resetAccount(token, username) {
    const response = await post('/v1/admin/reset-account', token, { username: String(username) });
    return { status: response.status, body: await readBody(response) };
  }

  return { setRole, resetAccount };
}
