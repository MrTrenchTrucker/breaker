package dev.breaker.server.syncapi.transcriptions

// Pull page fixture: same text as shared/modules/api-contracts/examples/pull_page_vectors.json.
// Cross-checked byte for byte by the Python test (test_pull_page_fixture.py).
internal object PullPageFixture {
    const val PAGE = "{\"items\":[{\"client_id\":\"1b0d5e2f-7b3c-4d4e-8f9a-2b3c4d5e6f70\",\"change\":2,\"deleted\":true,\"stored_at\":\"2023-11-14T22:13:20.123Z\"},{\"client_id\":\"0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f\",\"change\":3,\"deleted\":false,\"key_version\":0,\"encrypted\":\"aGVsbG8=\",\"stored_at\":\"2023-11-14T22:13:20.250Z\"}],\"next\":\"1-3\",\"more\":false}"
}
