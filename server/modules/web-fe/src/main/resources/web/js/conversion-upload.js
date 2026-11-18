// Record conversion, upload side. The one call the
// conversion makes to the server's sync route: POST /v1/sync with the bearer
// token, Content-Type application/json, no cookies and no cache. The body is
// exactly {client_id, key_version, encrypted}; resave is never sent. The answer
// maps to one fixed outcome: a 204 with no body answers CONFIRMED; every other
// status, a body that is present but not JSON, or a thrown fetch answers FAILED.
// No token answers FAILED and nothing is sent. fetchFn and getToken are injected
// and called as plain functions. No DOM, no storage, no message text.

export function createRecordUpload({ baseUrl, fetchFn, getToken }) {
  return async function uploadRecord(clientId, keyVersion, encrypted) {
    let token;
    try {
      token = getToken();
    } catch {
      return 'FAILED';
    }
    if (token === null || token === undefined || token === '') return 'FAILED';

    let response;
    try {
      response = await fetchFn(`${baseUrl}/v1/sync`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer ' + token,
        },
        credentials: 'omit',
        cache: 'no-store',
        body: JSON.stringify({
          client_id: clientId,
          key_version: keyVersion,
          encrypted,
        }),
      });
    } catch {
      return 'FAILED';
    }
    if (response === null || typeof response !== 'object') return 'FAILED';

    // A 204 carries no body (sync-api answers 204 No Content). Read it as text:
    // an empty body is the no-content form, and a body that is present but is
    // not JSON is a failure.
    let text;
    try {
      text = await response.text();
    } catch {
      return 'FAILED';
    }
    if (response.status !== 204) return 'FAILED';
    if (text !== '') {
      try {
        JSON.parse(text);
      } catch {
        return 'FAILED';
      }
    }
    return 'CONFIRMED';
  };
}
