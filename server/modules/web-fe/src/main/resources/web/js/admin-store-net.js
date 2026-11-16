// The one call the admin store panel makes to the server's own route: POST
// /v1/admin/clear-store with the bearer token, no cookies, no cache, and a JSON
// body. The username travels in the body, never in the path. The fetch function
// is passed in and called as a plain function. A network error is not swallowed
// here: the call rejects and the flow catches it. The module never retries.
export function createAdminStoreNet({ baseUrl, fetchFn }) {
  // The parsed answer, or null when the text is not JSON.
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  // The body is exactly the contract's ClearStoreRequest: username (a string or
  // null) and confirm (a boolean). The username is sent raw, because the route
  // reads the name from the body and folds it on the server.
  async function clearStore(token, { username = null, confirm = false } = {}) {
    const response = await fetchFn(`${baseUrl}/v1/admin/clear-store`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json',
      },
      credentials: 'omit',
      cache: 'no-store',
      body: JSON.stringify({
        username: username === null || username === undefined ? null : String(username),
        confirm: confirm === true,
      }),
    });
    return { status: response.status, body: await readBody(response) };
  }

  return { clearStore };
}
