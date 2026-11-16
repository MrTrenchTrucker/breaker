// Admin users view: the DOM part of the users module. Everything is built
// inside the root it is given: no document lookups by id, no window, no
// browser storage. Every piece of text goes in with textContent, so a hostile
// user name, role or reset code ends up as literal text and never as markup.
// The view uses the browser's document.createElement to build its own elements.
import { createAdminUsers, ADMIN_USERS_TEXTS } from './admin-users.js';

export function mountAdminUsers(root, { getToken, net }) {
  const username = document.createElement('input');
  username.type = 'text';

  const makeAdminButton = document.createElement('button');
  makeAdminButton.type = 'button';
  makeAdminButton.textContent = 'Make admin';

  const removeAdminButton = document.createElement('button');
  removeAdminButton.type = 'button';
  removeAdminButton.textContent = 'Remove admin';

  const resetButton = document.createElement('button');
  resetButton.type = 'button';
  resetButton.textContent = 'Reset account';

  // The confirm control: shown only after a click on "Reset account". Nothing
  // is sent before the confirm button is clicked, and the confirm text names
  // the account it will reset.
  const confirm = document.createElement('div');
  confirm.hidden = true;
  const confirmWords = document.createElement('span');
  const confirmButton = document.createElement('button');
  confirmButton.type = 'button';
  confirmButton.textContent = 'Reset this account';
  const cancelButton = document.createElement('button');
  cancelButton.type = 'button';
  cancelButton.textContent = 'Cancel';
  confirm.appendChild(confirmWords);
  confirm.appendChild(confirmButton);
  confirm.appendChild(cancelButton);

  const message = document.createElement('div');
  const roleLine = document.createElement('div');

  // The one-time code: written once with textContent into this one element.
  // The dismiss control clears the element, which is the only place the code
  // is held; no variable keeps it past the dismiss.
  const codeBox = document.createElement('div');
  codeBox.hidden = true;
  const codeText = document.createElement('span');
  const dismissButton = document.createElement('button');
  dismissButton.type = 'button';
  dismissButton.textContent = 'Dismiss the code';
  codeBox.appendChild(codeText);
  codeBox.appendChild(dismissButton);

  root.appendChild(username);
  root.appendChild(makeAdminButton);
  root.appendChild(removeAdminButton);
  root.appendChild(resetButton);
  root.appendChild(confirm);
  root.appendChild(message);
  root.appendChild(roleLine);
  root.appendChild(codeBox);

  function name() {
    return typeof username.value === 'string' ? username.value : '';
  }

  function isBlank(text) {
    return text.trim() === '';
  }

  function showMessage(text) {
    message.textContent = text;
  }

  function showRole(role) {
    roleLine.textContent = typeof role === 'string' ? role : '';
  }

  function showResetCode(code) {
    codeText.textContent = code;
    codeBox.hidden = false;
  }

  const render = { showMessage, showRole, showResetCode };
  const users = createAdminUsers({ getToken, net, render });

  // The account the confirm named, remembered at the first reset click so the
  // confirm always names the account that will be sent.
  let pending = null;

  function setRole(admin) {
    const target = name();
    if (isBlank(target)) {
      render.showMessage(ADMIN_USERS_TEXTS.name_required);
      return;
    }
    users.setRole(target, admin);
  }

  makeAdminButton.addEventListener('click', () => setRole(true));

  removeAdminButton.addEventListener('click', () => setRole(false));

  resetButton.addEventListener('click', () => {
    const target = name();
    if (isBlank(target)) {
      pending = null;
      confirm.hidden = true;
      render.showMessage(ADMIN_USERS_TEXTS.name_required);
      return;
    }
    pending = { username: target };
    confirmWords.textContent = 'Reset the account ' + target + '?';
    confirm.hidden = false;
  });

  let resetBusy = false;
  confirmButton.addEventListener('click', async () => {
    confirm.hidden = true;
    if (pending === null) return;
    const target = pending.username;
    pending = null;
    if (resetBusy) return;
    resetBusy = true;
    confirmButton.disabled = true;
    try {
      await users.resetAccount(target);
    } finally {
      resetBusy = false;
      confirmButton.disabled = false;
    }
  });

  cancelButton.addEventListener('click', () => {
    confirm.hidden = true;
    pending = null;
  });

  dismissButton.addEventListener('click', () => {
    codeText.textContent = '';
    codeBox.hidden = true;
  });

  return { render, users };
}
