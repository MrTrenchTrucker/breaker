// Transcription dashboard view: the DOM part. Everything is built inside the root it
// is given; there is no lookup by id, no window and no browser storage. Every piece of
// text goes in with textContent, so a hostile transcription ends up as literal text
// and never as markup. Copy is client-side; delete is one row at a time, behind an
// in-view confirm, and there is no delete-all control.

import { createTranscriptions, MESSAGES } from './transcriptions.js';

export function mountTranscriptions(root, { getToken, onSignedOut, net, dek, copy, format }) {
  const list = document.createElement('div');
  const message = document.createElement('div');
  const search = document.createElement('input');
  search.type = 'search';
  search.placeholder = 'Search transcriptions';

  root.appendChild(list);
  root.appendChild(message);
  root.appendChild(search);

  function clearList() {
    while (list.firstChild !== null) list.removeChild(list.firstChild);
  }

  function showRows(rows) {
    clearList();
    if (rows.length === 0) {
      message.textContent = MESSAGES.empty;
      return;
    }
    message.textContent = '';
    for (const row of rows) {
      const rowEl = document.createElement('div');

      const text = document.createElement('span');
      text.textContent = row.text;

      const stored = document.createElement('span');
      stored.textContent = row.stored_at_text;
      stored.title = row.stored_at;

      const copyButton = document.createElement('button');
      copyButton.type = 'button';
      copyButton.textContent = 'Copy text';
      // A row that cannot be decrypted has no text to copy.
      if (row.unreadable === true) copyButton.disabled = true;

      const deleteButton = document.createElement('button');
      deleteButton.type = 'button';
      deleteButton.textContent = 'Delete';

      const confirm = document.createElement('div');
      confirm.hidden = true;
      const confirmWords = document.createElement('span');
      confirmWords.textContent = 'Delete this transcription?';
      const confirmButton = document.createElement('button');
      confirmButton.type = 'button';
      confirmButton.textContent = 'Delete';
      const cancelButton = document.createElement('button');
      cancelButton.type = 'button';
      cancelButton.textContent = 'Cancel';
      confirm.appendChild(confirmWords);
      confirm.appendChild(confirmButton);
      confirm.appendChild(cancelButton);

      rowEl.appendChild(text);
      rowEl.appendChild(stored);
      rowEl.appendChild(copyButton);
      rowEl.appendChild(deleteButton);
      rowEl.appendChild(confirm);
      list.appendChild(rowEl);

      copyButton.addEventListener('click', async () => {
        if (copyButton.disabled) return;
        copyButton.disabled = true;
        try {
          await transcriptions.copyText(row.text);
        } finally {
          copyButton.disabled = row.unreadable === true;
        }
      });

      let deleteBusy = false;
      deleteButton.addEventListener('click', () => {
        confirm.hidden = false;
      });
      cancelButton.addEventListener('click', () => {
        confirm.hidden = true;
      });
      confirmButton.addEventListener('click', async () => {
        confirm.hidden = true;
        if (deleteBusy) return;
        deleteBusy = true;
        confirmButton.disabled = true;
        try {
          const token = getToken();
          if (token === null || token === '') {
            showSignedOut();
            return;
          }
          await transcriptions.deleteOne(row.id);
        } finally {
          deleteBusy = false;
          confirmButton.disabled = false;
        }
      });
    }
  }

  function showMessage(text) {
    message.textContent = text;
  }

  function showSignedOut() {
    clearList();
    onSignedOut();
  }

  const render = { showRows, showMessage, showSignedOut };
  const transcriptions = createTranscriptions({ getToken, net, dek, copy, format, render });

  search.addEventListener('input', () => {
    transcriptions.search(search.value);
  });

  transcriptions.load();

  return { render, transcriptions };
}
