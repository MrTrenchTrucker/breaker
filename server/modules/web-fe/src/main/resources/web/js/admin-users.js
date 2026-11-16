// Admin users flow: set an account's admin role and reset an account. Everything
// is injected: getToken() -> token string or null; net is the admin-user calls
// object ({ setRole, resetAccount }); render is { showMessage(text),
// showRole(role), showResetCode(code) }. No DOM access, no browser storage. The
// token is never kept beyond the one call it is used for, and the reset code is
// handed to the view once and never kept here.

export const ADMIN_USERS_TEXTS = Object.freeze({
  role_ok: 'The admin role was updated.',
  role_bad_request: 'The role change was not accepted. Check the name and try again.',
  role_unauthorized: 'You are signed out. Sign in again to manage users.',
  role_forbidden: 'You need the admin role to change another account.',
  role_not_found: 'That account was not found.',
  role_conflict: 'The last admin cannot be demoted.',
  role_too_large: 'The request was too large. Try a shorter name.',
  role_unavailable: 'The server is unavailable. Try again in a moment.',
  reset_ok: 'The account was reset. The one-time code is shown once.',
  reset_bad_request: 'The reset was not accepted. Check the name and try again.',
  reset_unauthorized: 'You are signed out. Sign in again to manage users.',
  reset_forbidden: 'You need the admin role to reset an account.',
  reset_not_found: 'That account was not found.',
  reset_too_large: 'The request was too large. Try a shorter name.',
  reset_unavailable: 'The server is unavailable. Try again in a moment.',
  name_required: 'Type a user name first.',
  unknown_status: 'The server answered with a status this panel does not know.',
  network_failed: 'The network failed. Check your connection and try again.',
});

function isObject(value) {
  return value !== null && typeof value === 'object';
}

export function createAdminUsers({ getToken, net, render }) {
  // A thrown fetch error becomes the network path; net methods are called as
  // plain functions, so a failure never throws into the view.
  async function call(fn, ...args) {
    try {
      return await fn(...args);
    } catch {
      return { status: 0, body: null };
    }
  }

  function tokenOrMessage(sentence) {
    const token = getToken();
    if (token === null || token === '') {
      render.showMessage(sentence);
      return null;
    }
    return token;
  }

  async function setRole(username, admin) {
    const token = tokenOrMessage(ADMIN_USERS_TEXTS.role_unauthorized);
    if (token === null) return;
    const answer = await call(net.setRole, token, username, admin);
    const status = answer.status;
    if (status === 200) {
      // The server's answer is truth: the role it answered is what the view
      // shows, never the value the caller asked for. A 200 without a string
      // role is not an answer this panel understands.
      const body = answer.body;
      if (!isObject(body) || typeof body.role !== 'string') {
        render.showMessage(ADMIN_USERS_TEXTS.unknown_status);
        return;
      }
      render.showRole(body.role);
      render.showMessage(ADMIN_USERS_TEXTS.role_ok);
      return;
    }
    if (status === 400) { render.showMessage(ADMIN_USERS_TEXTS.role_bad_request); return; }
    if (status === 401) { render.showMessage(ADMIN_USERS_TEXTS.role_unauthorized); return; }
    if (status === 403) { render.showMessage(ADMIN_USERS_TEXTS.role_forbidden); return; }
    if (status === 404) { render.showMessage(ADMIN_USERS_TEXTS.role_not_found); return; }
    if (status === 409) { render.showMessage(ADMIN_USERS_TEXTS.role_conflict); return; }
    if (status === 413) { render.showMessage(ADMIN_USERS_TEXTS.role_too_large); return; }
    if (status === 503) { render.showMessage(ADMIN_USERS_TEXTS.role_unavailable); return; }
    if (status === 0) { render.showMessage(ADMIN_USERS_TEXTS.network_failed); return; }
    render.showMessage(ADMIN_USERS_TEXTS.unknown_status);
  }

  async function resetAccount(username) {
    const token = tokenOrMessage(ADMIN_USERS_TEXTS.reset_unauthorized);
    if (token === null) return;
    const answer = await call(net.resetAccount, token, username);
    const status = answer.status;
    if (status === 200) {
      // The code is handed to the view once and is not kept in this flow. A 200
      // without a string code must not promise a code that is not there.
      const body = answer.body;
      if (!isObject(body) || typeof body.reset_code !== 'string') {
        render.showMessage(ADMIN_USERS_TEXTS.unknown_status);
        return;
      }
      render.showResetCode(body.reset_code);
      render.showMessage(ADMIN_USERS_TEXTS.reset_ok);
      return;
    }
    if (status === 400) { render.showMessage(ADMIN_USERS_TEXTS.reset_bad_request); return; }
    if (status === 401) { render.showMessage(ADMIN_USERS_TEXTS.reset_unauthorized); return; }
    if (status === 403) { render.showMessage(ADMIN_USERS_TEXTS.reset_forbidden); return; }
    if (status === 404) { render.showMessage(ADMIN_USERS_TEXTS.reset_not_found); return; }
    if (status === 413) { render.showMessage(ADMIN_USERS_TEXTS.reset_too_large); return; }
    if (status === 503) { render.showMessage(ADMIN_USERS_TEXTS.reset_unavailable); return; }
    if (status === 0) { render.showMessage(ADMIN_USERS_TEXTS.network_failed); return; }
    render.showMessage(ADMIN_USERS_TEXTS.unknown_status);
  }

  return { setRole, resetAccount };
}
