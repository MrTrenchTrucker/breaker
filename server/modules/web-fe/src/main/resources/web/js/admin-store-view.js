// Admin store view: the DOM part of the store panel. Everything is built inside
// the root it is given: no document lookups by id, no window, no browser storage.
// Every piece of text goes in with textContent, so a hostile user name ends up as
// literal text and never as markup. Both destructive controls sit behind an
// in-view confirm that names its target; nothing is sent before the confirm
// control is used.
import { createAdminStore, ADMIN_STORE_TEXTS } from './admin-store.js';

const NAME_LABEL = 'User name';
const ONE_BUTTON = "Clear this user's store";
const ALL_BUTTON = "Clear all users' stores";
const CONFIRM_BUTTON = 'Clear';
const CANCEL_BUTTON = 'Cancel';

export function mountAdminStore(root, { getToken, onSignedOut, net }) {
  const form = document.createElement('div');
  const nameLabel = document.createElement('span');
  nameLabel.textContent = NAME_LABEL;
  const nameInput = document.createElement('input');
  nameInput.type = 'text';
  nameInput.value = '';
  const oneButton = document.createElement('button');
  oneButton.type = 'button';
  oneButton.textContent = ONE_BUTTON;

  const allButton = document.createElement('button');
  allButton.type = 'button';
  allButton.textContent = ALL_BUTTON;

  const message = document.createElement('div');

  // The confirm control: shown only after a click on one of the two clear
  // buttons. Nothing is sent before the confirm button is clicked.
  const confirm = document.createElement('div');
  confirm.hidden = true;
  const confirmWords = document.createElement('span');
  const confirmButton = document.createElement('button');
  confirmButton.type = 'button';
  confirmButton.textContent = CONFIRM_BUTTON;
  const cancelButton = document.createElement('button');
  cancelButton.type = 'button';
  cancelButton.textContent = CANCEL_BUTTON;
  confirm.appendChild(confirmWords);
  confirm.appendChild(confirmButton);
  confirm.appendChild(cancelButton);

  form.appendChild(nameLabel);
  form.appendChild(nameInput);
  form.appendChild(oneButton);
  root.appendChild(form);
  root.appendChild(allButton);
  root.appendChild(message);
  root.appendChild(confirm);

  function showMessage(text) {
    message.textContent = text;
  }

  function showSignedOut() {
    message.textContent = '';
    onSignedOut();
  }

  const render = { showMessage, showSignedOut };
  const flow = createAdminStore({ getToken, net, render });

  let pending = null; // { kind: 'one', username } or { kind: 'all' }
  let busy = false;

  oneButton.addEventListener('click', () => {
    const username = String(nameInput.value).trim();
    if (username === '') {
      showMessage(ADMIN_STORE_TEXTS.needs_name);
      return;
    }
    pending = { kind: 'one', username };
    confirmWords.textContent = ADMIN_STORE_TEXTS.confirm_one.replace('{username}', () => username);
    confirm.hidden = false;
  });

  allButton.addEventListener('click', () => {
    pending = { kind: 'all' };
    confirmWords.textContent = ADMIN_STORE_TEXTS.confirm_all;
    confirm.hidden = false;
  });

  confirmButton.addEventListener('click', async () => {
    confirm.hidden = true;
    if (busy) return;
    busy = true;
    confirmButton.disabled = true;
    const target = pending;
    pending = null;
    try {
      if (target === null) return;
      // No token means there is nothing to send: the flow shows the signed-out
      // view without a call. The token is read here and used for this click only.
      const token = getToken();
      if (token === null || token === '') {
        showSignedOut();
        return;
      }
      if (target.kind === 'one') {
        await flow.clearOne(target.username);
      } else {
        await flow.clearAll();
      }
    } finally {
      busy = false;
      confirmButton.disabled = false;
    }
  });

  cancelButton.addEventListener('click', () => {
    confirm.hidden = true;
    pending = null;
  });

  return { render, flow };
}
