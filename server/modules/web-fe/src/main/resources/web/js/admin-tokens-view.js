// Admin agent-token view: the DOM part of the token module. Everything is built
// inside the root it is given: no document lookups by id, no window, no storage.
// Every piece of text goes in with textContent, so a hostile token name or owner
// name ends up as literal text and never as markup. A created token value is
// written once into one element; the dismiss control clears that element.
import { createAdminTokens, ADMIN_TOKENS_TEXTS } from './admin-tokens.js';

const NAME_LABEL = 'Unnamed token';
const OWNER_LABEL = 'Unknown owner';

export function mountAdminTokens(root, { getToken, onSignedOut, net, format }) {
  const list = document.createElement('div');
  const message = document.createElement('div');

  // The one-time panel: a fixed label, the token value, and the dismiss control.
  const secretPanel = document.createElement('div');
  secretPanel.hidden = true;
  const secretLabel = document.createElement('span');
  const secretValue = document.createElement('span');
  const dismissButton = document.createElement('button');
  dismissButton.type = 'button';
  dismissButton.textContent = 'Dismiss';

  // The create form.
  const nameField = document.createElement('input');
  const ownerField = document.createElement('input');
  const scopeField = document.createElement('select');
  const transcribeOption = document.createElement('option');
  transcribeOption.value = 'transcribe';
  transcribeOption.textContent = 'transcribe';
  const adminOption = document.createElement('option');
  adminOption.value = 'admin';
  adminOption.textContent = 'admin';
  scopeField.appendChild(transcribeOption);
  scopeField.appendChild(adminOption);
  const expiresField = document.createElement('input');
  const createButton = document.createElement('button');
  createButton.type = 'button';
  createButton.textContent = 'Create agent token';

  secretPanel.appendChild(secretLabel);
  secretPanel.appendChild(secretValue);
  secretPanel.appendChild(dismissButton);

  root.appendChild(list);
  root.appendChild(message);
  root.appendChild(secretPanel);
  root.appendChild(nameField);
  root.appendChild(ownerField);
  root.appendChild(scopeField);
  root.appendChild(expiresField);
  root.appendChild(createButton);

  function clearList() {
    while (list.firstChild !== null) {
      list.removeChild(list.firstChild);
    }
  }

  function showTokens(rows) {
    clearList();
    if (rows.length === 0) {
      message.textContent = ADMIN_TOKENS_TEXTS.list_200;
      return;
    }
    message.textContent = '';
    for (const row of rows) {
      const rowEl = document.createElement('div');

      const name = document.createElement('span');
      name.textContent =
        typeof row.name === 'string' && row.name !== '' ? row.name : NAME_LABEL;
      rowEl.appendChild(name);

      const owner = document.createElement('span');
      owner.textContent =
        typeof row.owner_username === 'string' && row.owner_username !== ''
          ? row.owner_username
          : OWNER_LABEL;
      rowEl.appendChild(owner);

      const scope = document.createElement('span');
      scope.textContent = typeof row.scope === 'string' ? row.scope : '';
      rowEl.appendChild(scope);

      const expires = document.createElement('span');
      expires.textContent = row.expires_text;
      rowEl.appendChild(expires);

      const revokeButton = document.createElement('button');
      revokeButton.type = 'button';
      revokeButton.textContent = 'Revoke';
      let busy = false;
      revokeButton.addEventListener('click', async () => {
        if (busy) return;
        busy = true;
        revokeButton.disabled = true;
        try {
          // No token means there is nothing to send: sign the view out without a call.
          const token = getToken();
          if (token === null || token === '') {
            showSignedOut();
            return;
          }
          // The id travels in its own closure; the button never reads it back.
          await flow.revoke(row.id);
        } finally {
          busy = false;
          revokeButton.disabled = false;
        }
      });
      rowEl.appendChild(revokeButton);

      list.appendChild(rowEl);
    }
  }

  function showMessage(text) {
    message.textContent = text;
  }

  function showSecret(token) {
    secretLabel.textContent = ADMIN_TOKENS_TEXTS.create_201;
    secretValue.textContent = token;
    secretPanel.hidden = false;
  }

  function showSignedOut() {
    clearList();
    onSignedOut();
  }

  dismissButton.addEventListener('click', () => {
    secretValue.textContent = '';
    secretPanel.hidden = true;
  });

  let createBusy = false;
  createButton.addEventListener('click', async () => {
    if (createBusy) return;
    createBusy = true;
    createButton.disabled = true;
    try {
      // Same rule as a row's revoke button: no token means no call and a signed-out view.
      const token = getToken();
      if (token === null || token === '') {
        showSignedOut();
        return;
      }
      const request = {
        name: nameField.value,
        owner_username: ownerField.value,
        scope: scopeField.value,
        expires_at: expiresField.value === '' ? null : expiresField.value,
      };
      await flow.createToken(request);
    } finally {
      createBusy = false;
      createButton.disabled = false;
    }
  });

  const render = { showTokens, showMessage, showSecret, showSignedOut };
  const flow = createAdminTokens({ getToken, net, format, render });

  flow.load();

  return { render, flow };
}
