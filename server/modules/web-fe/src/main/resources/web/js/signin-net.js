// The four calls the sign-in page makes to the server's own routes. Every call is a POST with a JSON body,
// no cookies, no cache, and no stored answer. The fetch function is passed in and is called as a plain function.
export function createNet(fetchFn) {
  function post(url, body, token) {
    const headers = { 'Content-Type': 'application/json' };
    if (token !== undefined) headers.Authorization = 'Bearer ' + token;
    return fetchFn(url, {
      method: 'POST',
      headers,
      credentials: 'omit',
      cache: 'no-store',
      body: JSON.stringify(body),
    });
  }

  // The parsed answer, or null when the text is not JSON.
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  async function getSalt(username) {
    const response = await post('/v1/auth/salt', { username });
    return { status: response.status, body: await readBody(response) };
  }

  async function login(username, verifierBase64) {
    const response = await post('/v1/auth/login', { username, auth_verifier: verifierBase64 });
    const retryAfter = response.headers.get('Retry-After') ?? null;
    return { status: response.status, body: await readBody(response), retryAfter };
  }

  async function startHandoff(challenge, sealedBase64, token) {
    const response = await post('/v1/auth/handoff/start', { challenge, sealed_package: sealedBase64 }, token);
    return { status: response.status, body: await readBody(response) };
  }

  // Fire and forget: the request starts, a throw or a rejection is swallowed, and the answer is never read.
  function logout(token) {
    try {
      const started = fetchFn('/v1/auth/logout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        credentials: 'omit',
        cache: 'no-store',
        keepalive: true,
      });
      Promise.resolve(started).catch(() => {});
    } catch {
      // Nothing to do: logout is best effort.
    }
    return Promise.resolve();
  }

  return { getSalt, login, startHandoff, logout };
}
