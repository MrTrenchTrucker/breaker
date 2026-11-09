// Device list view: the DOM part of the devices module. Everything is built
// inside the root it is given: no document lookups by id, no window, no
// browser storage. Every piece of text goes in with textContent, so a hostile
// device name ends up as literal text and never as markup. The view uses the
// browser's document.createElement to build its own elements; it never looks
// anything up in the document by id.
import { createDevices, MESSAGES } from './devices.js';

const NAME_LABEL = 'Unknown device';

export function mountDevices(root, { getToken, onSignedOut, net, format }) {
  const list = document.createElement('div');
  const message = document.createElement('div');

  const allButton = document.createElement('button');
  allButton.type = 'button';
  allButton.textContent = 'Sign out all my devices';

  // The confirm control: shown only after the first click on "Sign out all my
  // devices". Nothing is sent before the confirm button is clicked.
  const confirm = document.createElement('div');
  confirm.hidden = true;
  const confirmWords = document.createElement('span');
  confirmWords.textContent = 'Sign out all my devices?';
  const confirmButton = document.createElement('button');
  confirmButton.type = 'button';
  confirmButton.textContent = 'Sign out all';
  const cancelButton = document.createElement('button');
  cancelButton.type = 'button';
  cancelButton.textContent = 'Cancel';
  confirm.appendChild(confirmWords);
  confirm.appendChild(confirmButton);
  confirm.appendChild(cancelButton);

  root.appendChild(list);
  root.appendChild(message);
  root.appendChild(allButton);
  root.appendChild(confirm);

  function clearList() {
    while (list.firstChild !== null) {
      list.removeChild(list.firstChild);
    }
  }

  function showDevices(rows) {
    clearList();
    if (rows.length === 0) {
      message.textContent = MESSAGES.empty;
      return;
    }
    message.textContent = '';
    for (const row of rows) {
      const rowEl = document.createElement('div');

      const name = document.createElement('span');
      name.textContent =
        typeof row.name === 'string' && row.name !== '' ? row.name : NAME_LABEL;
      rowEl.appendChild(name);

      if (row.current === true) {
        const marker = document.createElement('span');
        marker.className = 'current';
        marker.textContent = 'current';
        rowEl.appendChild(marker);
      }

      const seen = document.createElement('span');
      seen.textContent = row.last_seen_text;
      seen.title = row.last_seen;
      rowEl.appendChild(seen);

      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = 'Sign out';
      let busy = false;
      button.addEventListener('click', async () => {
        if (busy) return;
        busy = true;
        button.disabled = true;
        try {
          // No token means there is nothing to send: sign the view out without a call.
          // The token is read here and used for this click only, never kept on the view.
          const token = getToken();
          if (token === null || token === '') {
            showSignedOut();
            return;
          }
          // The id travels in its own closure; the button never reads it back
          // from the row and never takes it from anywhere else.
          await devices.signOutOne(row.id);
        } finally {
          busy = false;
          button.disabled = false;
        }
      });
      rowEl.appendChild(button);

      list.appendChild(rowEl);
    }
  }

  function showMessage(text) {
    message.textContent = text;
  }

  function showSignedOut() {
    clearList();
    onSignedOut();
  }

  const render = { showDevices, showMessage, showSignedOut };
  const devices = createDevices({ getToken, net, format, render });

  allButton.addEventListener('click', () => {
    confirm.hidden = false;
  });

  let allBusy = false;
  confirmButton.addEventListener('click', async () => {
    confirm.hidden = true;
    if (allBusy) return;
    allBusy = true;
    confirmButton.disabled = true;
    try {
      // Same rule as a row's sign-out button: no token means no call and a signed-out view.
      const token = getToken();
      if (token === null || token === '') {
        showSignedOut();
        return;
      }
      await devices.signOutAll();
    } finally {
      allBusy = false;
      confirmButton.disabled = false;
    }
  });

  cancelButton.addEventListener('click', () => {
    confirm.hidden = true;
  });

  devices.load();

  return { render, devices };
}
