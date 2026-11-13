// The one call pending-sealed conversion makes to the server's own route: GET
// /v1/sync/pending-sealed with the bearer token, no cookies, no cache, no body.
// The fetch function is passed in and is called as a plain function.
export function createPendingSealedNet({ baseUrl, fetchFn }) {
  async function fetchPendingSealed(token) {
    const response = await fetchFn(`${baseUrl}/v1/sync/pending-sealed`, {
      method: 'GET',
      headers: { Authorization: 'Bearer ' + token },
      credentials: 'omit',
      cache: 'no-store',
    });
    return { status: response.status, body: await readBody(response) };
  }

  // The parsed answer, or null when the text is not JSON.
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  return { fetchPendingSealed };
}
