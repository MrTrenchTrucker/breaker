// The three calls the devices view makes to the server's own routes: list the devices,
// sign out one device, sign out all devices. Every call carries the bearer token, no
// cookies, no cache, and no body. The fetch function is passed in and is called as a plain function.
export function createDevicesNet({ baseUrl, fetchFn }) {
  function deviceCall(method, path, token) {
    return fetchFn(`${baseUrl}${path}`, {
      method,
      headers: { Authorization: 'Bearer ' + token },
      credentials: 'omit',
      cache: 'no-store',
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

  async function listDevices(token) {
    const response = await deviceCall('GET', '/v1/auth/devices', token);
    return { status: response.status, body: await readBody(response) };
  }

  async function signOutDevice(token, id) {
    const response = await deviceCall('DELETE', '/v1/auth/devices/' + encodeURIComponent(String(id)), token);
    return { status: response.status, body: await readBody(response) };
  }

  async function signOutAllDevices(token) {
    const response = await deviceCall('POST', '/v1/auth/devices/sign-out-all', token);
    return { status: response.status, body: await readBody(response) };
  }

  return { listDevices, signOutDevice, signOutAllDevices };
}
