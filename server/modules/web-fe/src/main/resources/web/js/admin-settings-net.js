// The six calls the admin settings view makes to the server's own routes: read and
// set the three on/off settings. Every call carries the bearer token, no cookies,
// no cache. A GET has no body; a PUT carries one JSON member, enabled, with the
// JSON content type. The fetch function is passed in and called as a plain
// function. The module never retries, so a failure is the caller's to handle.
export function createAdminSettingsNet({ baseUrl, fetchFn }) {
  function call(method, path, token, body) {
    const init = {
      method,
      headers: { Authorization: 'Bearer ' + token },
      credentials: 'omit',
      cache: 'no-store',
    };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    return fetchFn(`${baseUrl}${path}`, init);
  }

  // The parsed answer, or null when the text is not JSON.
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  async function getWrongPwLimit(token) {
    const response = await call('GET', '/v1/admin/wrong-pw-limit', token);
    return { status: response.status, body: await readBody(response) };
  }

  async function setWrongPwLimit(token, enabled) {
    const response = await call('PUT', '/v1/admin/wrong-pw-limit', token, { enabled });
    return { status: response.status, body: await readBody(response) };
  }

  async function getRateLimits(token) {
    const response = await call('GET', '/v1/admin/rate-limits', token);
    return { status: response.status, body: await readBody(response) };
  }

  async function setRateLimits(token, enabled) {
    const response = await call('PUT', '/v1/admin/rate-limits', token, { enabled });
    return { status: response.status, body: await readBody(response) };
  }

  async function getRegistrationOpen(token) {
    const response = await call('GET', '/v1/admin/registration-open', token);
    return { status: response.status, body: await readBody(response) };
  }

  async function setRegistrationOpen(token, enabled) {
    const response = await call('PUT', '/v1/admin/registration-open', token, { enabled });
    return { status: response.status, body: await readBody(response) };
  }

  return {
    getWrongPwLimit,
    setWrongPwLimit,
    getRateLimits,
    setRateLimits,
    getRegistrationOpen,
    setRegistrationOpen,
  };
}
