// Admin settings flow: read the three on/off settings and set them. Everything is
// injected: getToken() -> token string or null; net is the settings-calls object
// ({ getWrongPwLimit, setWrongPwLimit, getRateLimits, setRateLimits,
// getRegistrationOpen, setRegistrationOpen }); render is { showStates(states),
// showMessage(text) }. No DOM access, no browser storage. The token is never kept
// beyond the one call it is used for, and a rejected fetch becomes the network
// sentence instead of a throw into the view.

export const ADMIN_SETTINGS_TEXTS = Object.freeze({
  // Shared sentences: a status this module does not know, a rejected fetch, and
  // the case where there is no token to send.
  unknown_status: 'The server answered with a status this panel does not understand.',
  network_failed: 'The server could not be reached. Try again in a moment.',
  no_token: 'You are not signed in. Sign in again to change settings.',

  // Wrong-password limit.
  wrong_pw_limit_read_ok: 'The wrong-password limit setting was read.',
  wrong_pw_limit_set_ok: 'The wrong-password limit setting was saved.',
  wrong_pw_limit_bad_request: 'The wrong-password limit value was not accepted. The setting was not changed.',
  wrong_pw_limit_unauthorized: 'A valid sign-in is required to change the wrong-password limit.',
  wrong_pw_limit_forbidden: 'An admin role is required to change the wrong-password limit.',
  wrong_pw_limit_unavailable: 'The server is unavailable. The wrong-password limit was not changed.',

  // Rate limits.
  rate_limits_read_ok: 'The rate limits setting was read.',
  rate_limits_set_ok: 'The rate limits setting was saved.',
  rate_limits_bad_request: 'The rate limits value was not accepted. The setting was not changed.',
  rate_limits_unauthorized: 'A valid sign-in is required to change the rate limits.',
  rate_limits_forbidden: 'An admin role is required to change the rate limits.',
  rate_limits_unavailable: 'The server is unavailable. The rate limits were not changed.',

  // Registration open.
  registration_open_read_ok: 'The registration open setting was read.',
  registration_open_set_ok: 'The registration open setting was saved.',
  registration_open_bad_request: 'The registration open value was not accepted. The setting was not changed.',
  registration_open_unauthorized: 'A valid sign-in is required to change registration open.',
  registration_open_forbidden: 'An admin role is required to change registration open.',
  registration_open_unavailable: 'The server is unavailable. Registration open was not changed.',

  // Fixed labels drawn by the view.
  wrong_pw_limit_label: 'Wrong-password limit',
  rate_limits_label: 'Rate limits',
  registration_open_label: 'Registration open',
  on_label: 'On',
  off_label: 'Off',
  unknown_label: 'Unknown',
});

// The three settings, in the order the view draws them. Each entry names the key
// the render table uses, the prefix of its sentences, and the two net methods.
const SETTINGS = [
  { key: 'wrongPwLimit', prefix: 'wrong_pw_limit', get: 'getWrongPwLimit', set: 'setWrongPwLimit' },
  { key: 'rateLimits', prefix: 'rate_limits', get: 'getRateLimits', set: 'setRateLimits' },
  { key: 'registrationOpen', prefix: 'registration_open', get: 'getRegistrationOpen', set: 'setRegistrationOpen' },
];

function isObject(value) {
  return value !== null && typeof value === 'object';
}

// The server's answer is a one-member object, { enabled: boolean }. Anything else
// is not a state this module can show.
function enabledOf(body) {
  return isObject(body) && typeof body.enabled === 'boolean' ? body.enabled : null;
}

export function createAdminSettings({ getToken, net, render }) {
  // A thrown fetch error becomes the network path; net methods are plain functions.
  async function call(fn, ...args) {
    try {
      return await fn(...args);
    } catch {
      return { status: 0, body: null };
    }
  }

  // One fixed sentence per status. wrote says whether the call was a PUT, because
  // a 400 is a PUT status only: a GET that answers 400 is unknown, not bad request.
  function textFor(prefix, status, wrote) {
    if (status === 200) {
      return ADMIN_SETTINGS_TEXTS[prefix + (wrote ? '_set_ok' : '_read_ok')];
    }
    if (wrote && status === 400) {
      return ADMIN_SETTINGS_TEXTS[prefix + '_bad_request'];
    }
    if (status === 401) return ADMIN_SETTINGS_TEXTS[prefix + '_unauthorized'];
    if (status === 403) return ADMIN_SETTINGS_TEXTS[prefix + '_forbidden'];
    if (status === 503) return ADMIN_SETTINGS_TEXTS[prefix + '_unavailable'];
    if (status === 0) return ADMIN_SETTINGS_TEXTS.network_failed;
    return ADMIN_SETTINGS_TEXTS.unknown_status;
  }

  async function load() {
    const token = getToken();
    if (token === null || token === '') {
      render.showMessage(ADMIN_SETTINGS_TEXTS.no_token);
      return 'no_token';
    }
    const states = { wrongPwLimit: null, rateLimits: null, registrationOpen: null };
    let outcome = 'ok';
    for (const setting of SETTINGS) {
      const answer = await call(net[setting.get], token);
      const status = answer.status;
      if (status === 200) {
        const value = enabledOf(answer.body);
        if (value === null) {
          render.showMessage(ADMIN_SETTINGS_TEXTS.unknown_status);
          outcome = 'unknown';
        } else {
          states[setting.key] = value;
        }
      } else {
        render.showMessage(textFor(setting.prefix, status, false));
        outcome = status === 0 ? 'network' : String(status);
      }
    }
    render.showStates(states);
    return outcome;
  }

  async function setOne(setting, enabled) {
    const token = getToken();
    if (token === null || token === '') {
      render.showMessage(ADMIN_SETTINGS_TEXTS.no_token);
      return 'no_token';
    }
    const answer = await call(net[setting.set], token, enabled);
    const status = answer.status;
    if (status === 200) {
      const value = enabledOf(answer.body);
      if (value === null) {
        render.showMessage(ADMIN_SETTINGS_TEXTS.unknown_status);
        return 'unknown';
      }
      // The server's answer is truth: the view shows what the server answered,
      // never the value that was requested.
      render.showStates({ [setting.key]: value });
      render.showMessage(ADMIN_SETTINGS_TEXTS[setting.prefix + '_set_ok']);
      return 'ok';
    }
    render.showMessage(textFor(setting.prefix, status, true));
    return status === 0 ? 'network' : String(status);
  }

  return {
    load,
    setWrongPwLimit: (enabled) => setOne(SETTINGS[0], enabled),
    setRateLimits: (enabled) => setOne(SETTINGS[1], enabled),
    setRegistrationOpen: (enabled) => setOne(SETTINGS[2], enabled),
  };
}
