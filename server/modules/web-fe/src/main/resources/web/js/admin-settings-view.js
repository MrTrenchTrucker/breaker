// Admin settings view: the DOM part of the settings module. Everything is built
// inside the root it is given: no document lookups by id, no window, no browser
// storage. Every piece of text goes in with textContent, so a hostile string ends
// up as literal text and never as markup. A settings toggle needs no confirm: the
// change control sends the new value straight away, and the state it draws is the
// one the server answered, not the one that was clicked.
import { createAdminSettings, ADMIN_SETTINGS_TEXTS } from './admin-settings.js';

// The three rows, in draw order. Each names the state key, the label sentence and
// the flow method its control calls.
const ROWS = [
  { key: 'wrongPwLimit', label: 'wrong_pw_limit_label', set: 'setWrongPwLimit' },
  { key: 'rateLimits', label: 'rate_limits_label', set: 'setRateLimits' },
  { key: 'registrationOpen', label: 'registration_open_label', set: 'setRegistrationOpen' },
];

export function mountAdminSettings(root, { getToken, net }) {
  const message = document.createElement('div');
  const rows = {};

  for (const row of ROWS) {
    const box = document.createElement('div');
    const label = document.createElement('span');
    label.textContent = ADMIN_SETTINGS_TEXTS[row.label];
    const state = document.createElement('span');
    state.textContent = ADMIN_SETTINGS_TEXTS.unknown_label;
    const control = document.createElement('input');
    control.type = 'checkbox';
    control.checked = false;
    let busy = false;
    control.addEventListener('change', async () => {
      if (busy) return;
      busy = true;
      control.disabled = true;
      try {
        const token = getToken();
        if (token === null || token === '') {
          render.showMessage(ADMIN_SETTINGS_TEXTS.no_token);
          return;
        }
        await settings[row.set](control.checked);
      } finally {
        busy = false;
        control.disabled = false;
      }
    });
    box.appendChild(label);
    box.appendChild(state);
    box.appendChild(control);
    root.appendChild(box);
    rows[row.key] = { box, state, control };
  }
  root.appendChild(message);

  // A state this load did not get (null or absent) leaves the row as it was.
  function showStates(states) {
    for (const row of ROWS) {
      const value = states[row.key];
      if (value === null || value === undefined) continue;
      const cells = rows[row.key];
      cells.control.checked = value === true;
      cells.state.textContent = value === true ? ADMIN_SETTINGS_TEXTS.on_label : ADMIN_SETTINGS_TEXTS.off_label;
    }
  }

  function showMessage(text) {
    message.textContent = text;
  }

  const render = { showStates, showMessage };
  const settings = createAdminSettings({ getToken, net, render });

  settings.load();

  return { render, settings, rows };
}
