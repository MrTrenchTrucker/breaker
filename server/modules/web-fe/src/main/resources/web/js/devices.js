// Device list flow: list the signed-in user's devices, sign one out, sign every device out.
// Everything is injected: getToken() -> token string or null; net is the device-calls
// object ({ listDevices, signOutDevice, signOutAllDevices }); format(iso) turns a
// last_seen stamp into display text; render is { showDevices(rows), showMessage(text),
// showSignedOut() }. No DOM access, no browser storage. The token is never kept beyond
// the one call it is used for.

export const MESSAGES = Object.freeze({
  signed_out: 'You are signed out. Sign in again to see your devices.',
  gone: 'That device is already signed out. The list is up to date.',
  retry: 'The server is unavailable. Try again in a moment.',
  empty: 'No devices to show.',
  list_failed: 'The list could not be loaded. Try again in a moment.',
});

function isObject(value) {
  return value !== null && typeof value === 'object';
}

function rowOf(device, format) {
  return {
    id: device.id,
    name: device.name,
    last_seen: device.last_seen,
    current: device.current === true,
    last_seen_text: format(device.last_seen),
  };
}

export function createDevices({ getToken, net, format, render }) {
  let rows = []; // last rows rendered: only to tell whether a signed-out row was current

  async function tokenOrSignedOut() {
    const token = getToken();
    if (token === null || token === '') {
      render.showSignedOut();
      return null;
    }
    return token;
  }

  // A thrown fetch error becomes the retry path; net methods are called as plain functions.
  async function call(fn, ...args) {
    try {
      return await fn(...args);
    } catch {
      return { status: 0, body: null };
    }
  }

  // load() answers with what it did, so a caller can tell a successful reload from a
  // sign-out or a failure: 'ok' | 'signed_out' | 'list_failed' | 'retry'.
  async function load() {
    const token = await tokenOrSignedOut();
    if (token === null) return 'signed_out';
    const answer = await call(net.listDevices, token);
    const status = answer.status;
    if (status === 200) {
      // The server's answer is a DeviceList object: { devices: [ ... ] }. A 200 whose body is not
      // that shape is a failure, not an empty account, so it takes the list_failed message.
      const list = isObject(answer.body) && Array.isArray(answer.body.devices) ? answer.body.devices : null;
      if (list === null) {
        render.showMessage(MESSAGES.list_failed);
        return 'list_failed';
      }
      rows = list.map((device) => rowOf(device, format));
      render.showDevices(rows);
      return 'ok';
    }
    if (status === 401 || status === 403) {
      render.showSignedOut();
      return 'signed_out';
    }
    render.showMessage(MESSAGES.retry);
    return 'retry';
  }

  async function signOutOne(id) {
    const token = await tokenOrSignedOut();
    if (token === null) return;
    const answer = await call(net.signOutDevice, token, id);
    const status = answer.status;
    if (status === 204) {
      const wasCurrent = rows.some((row) => row.id === id && row.current === true);
      if (wasCurrent) {
        render.showSignedOut();
        return;
      }
      await load();
      return;
    }
    if (status === 401 || status === 403) {
      render.showSignedOut();
      return;
    }
    if (status === 404) {
      // The device is already gone. Reload first and show the gone sentence after it,
      // because a view clears the message when it renders a non-empty list: showing the
      // sentence first means the reload wipes it and the user never reads it. The
      // sentence is only shown when the reload actually succeeded, so a reload that
      // signs the user out or fails keeps its own message.
      const outcome = await load();
      if (outcome === 'ok') render.showMessage(MESSAGES.gone);
      return;
    }
    render.showMessage(MESSAGES.retry);
  }

  async function signOutAll() {
    const token = await tokenOrSignedOut();
    if (token === null) return;
    const answer = await call(net.signOutAllDevices, token);
    const status = answer.status;
    if (status === 204 || status === 401 || status === 403) {
      render.showSignedOut();
      return;
    }
    render.showMessage(MESSAGES.retry);
  }

  return { load, signOutOne, signOutAll };
}
