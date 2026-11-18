// The three calls the transcriptions view makes to the server's own routes: list the
// account's transcriptions (paged with since = next), list older ones (required
// older_than, either "<ms>" or "<ms>-<change>") and delete one row by client id.
// Every call carries the bearer token, no cookies, no cache, and no body. There is
// deliberately no delete-all call: the contract has no such route.
// The fetch function is passed in and is called as a plain function.
export function createTranscriptionsNet({ baseUrl, fetchFn }) {
  function transcriptionCall(method, path, token) {
    return fetchFn(`${baseUrl}${path}`, {
      method,
      headers: { Authorization: 'Bearer ' + token },
      credentials: 'omit',
      cache: 'no-store',
    });
  }

  // The parsed answer, or null when the text is not JSON. A 204 has no body, so its
  // answer is a null body rather than a parse failure.
  async function readBody(response) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  // GET /v1/transcriptions. The first page takes no cursor; every later page takes
  // the next value the previous answer carried.
  async function listTranscriptions(token, since) {
    const cursor = since === null || since === undefined || since === ''
      ? ''
      : '?since=' + encodeURIComponent(String(since));
    const response = await transcriptionCall('GET', '/v1/transcriptions' + cursor, token);
    return { status: response.status, body: await readBody(response) };
  }

  // GET /v1/transcriptions/older. older_than is required, so it is always sent.
  async function listOlder(token, olderThan) {
    const response = await transcriptionCall(
      'GET',
      '/v1/transcriptions/older?older_than=' + encodeURIComponent(String(olderThan)),
      token,
    );
    return { status: response.status, body: await readBody(response) };
  }

  // DELETE /v1/transcriptions/{client_id}: one row, by the client id the list gave.
  async function deleteTranscription(token, clientId) {
    const response = await transcriptionCall(
      'DELETE',
      '/v1/transcriptions/' + encodeURIComponent(String(clientId)),
      token,
    );
    return { status: response.status, body: await readBody(response) };
  }

  return { listTranscriptions, listOlder, deleteTranscription };
}
