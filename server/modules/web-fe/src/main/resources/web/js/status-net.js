// The one call the admin status banner makes to the server's own route: GET
// /v1/status with the bearer token, no cookies, no cache, no body. The fetch
// function is passed in and called as a plain function. A network error is not
// swallowed here: the call rejects and the future page code is what catches it.
// The module never retries, so a failure is the caller's to handle.
export function createStatusNet({ baseUrl, fetchFn }) {
  async function getStatus(token) {
    const response = await fetchFn(`${baseUrl}/v1/status`, {
      method: 'GET',
      headers: { Authorization: 'Bearer ' + token },
      credentials: 'omit',
      cache: 'no-store',
    });
    return { status: response.status, body: await readBody(response) };
  }

  // The parsed answer, or null when the text is not JSON. A non-200 answer is
  // passed through unchanged; the banner's parse is what marks it unknown.
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  return { getStatus };
}
