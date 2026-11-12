package dev.breaker.shared.api.sync

// Literal vectors for SyncBodyCodec.decodeStrictServerBody, one entry per line. The VALUES are the
// same as in examples/sealed_body_vectors.json. The literal form follows the escape rule of the sync
// body format: printable ASCII is written as it is, except the backslash and the quote; every other
// character is a backslash-u escape with four lower-case hex digits per UTF-16 unit. So a line feed
// is written as a backslash-u escape here, although the JSON file may write it as a short escape.
// The decoded values are equal.
internal object SealedBodyVectorData {
    internal class OkBody(
        val name: String,
        val input: String,
        val text: String,
        val model: String,
        val durationMs: Long,
        val createdAtMs: Long,
    )
    internal class RefuseText(val name: String, val input: String, val family: String)
    internal class RefuseBytes(val name: String, val hex: String, val family: String)

    val okBodies: List<OkBody> = listOf(
        OkBody("ok-minimal-server", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "hi", "m", 1L, 1794398400000L),
        OkBody("ok-text-escapes", "{\"v\":1,\"text\":\"say \\\"hi\\\" \\\\ \\u00e9 \\ud83d\\ude00 \\n\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "say \"hi\" \\ \u00e9 \ud83d\ude00 \u000a", "m", 1L, 1794398400000L),
        OkBody("ok-empty-text-and-model", "{\"v\":1,\"text\":\"\",\"source\":\"server\",\"model\":\"\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "", "", 1L, 1794398400000L),
        OkBody("ok-duration-zero", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":0,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "hi", "m", 0L, 1794398400000L),
        OkBody("ok-duration-max-safe-integer", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":9007199254740991,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "hi", "m", 9007199254740991L, 1794398400000L),
        OkBody("ok-epoch-zero-created", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"1970-01-01T00:00:00.000Z\"}", "hi", "m", 1L, 0L),
    )

    val refuseTexts: List<RefuseText> = listOf(
        RefuseText("refused-source-local", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "source"),
        RefuseText("refused-source-other-word", "{\"v\":1,\"text\":\"hi\",\"source\":\"Server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "source"),
        RefuseText("refused-source-not-a-string", "{\"v\":1,\"text\":\"hi\",\"source\":1,\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "type"),
        RefuseText("refused-version-2", "{\"v\":2,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "version"),
        RefuseText("refused-missing-model", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "missing_key"),
        RefuseText("refused-missing-v", "{\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "missing_key"),
        RefuseText("refused-order-source-first", "{\"source\":\"server\",\"v\":1,\"text\":\"hi\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "key_order"),
        RefuseText("refused-order-created-first", "{\"created_at\":\"2026-11-11T12:00:00.000Z\",\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1}", "key_order"),
        RefuseText("refused-order-model-before-source", "{\"v\":1,\"text\":\"hi\",\"model\":\"m\",\"source\":\"server\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "key_order"),
        RefuseText("refused-order-duration-after-created", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"created_at\":\"2026-11-11T12:00:00.000Z\",\"duration_ms\":1}", "key_order"),
        RefuseText("refused-extra-key", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\",\"extra\":1}", "unknown_key"),
        RefuseText("refused-duplicate-key", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\",\"text\":\"again\"}", "duplicate_key"),
        RefuseText("refused-trailing-data", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"} x", "trailing"),
        RefuseText("refused-trailing-object", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "trailing"),
        RefuseText("refused-nested-value", "{\"v\":1,\"text\":{\"a\":1},\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "syntax"),
        RefuseText("refused-array-value", "{\"v\":1,\"text\":[\"a\"],\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "syntax"),
        RefuseText("refused-text-not-a-string", "{\"v\":1,\"text\":5,\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "type"),
        RefuseText("refused-lone-surrogate-escape", "{\"v\":1,\"text\":\"\\ud800\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "surrogate"),
        RefuseText("refused-negative-duration", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":-1,\"created_at\":\"2026-11-11T12:00:00.000Z\"}", "duration"),
        RefuseText("refused-bad-created-at", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-13-40T00:00:00.000Z\"}", "created_at"),
        RefuseText("refused-empty-input", "", "truncated"),
        RefuseText("refused-not-an-object", "\"hi\"", "not_object"),
        RefuseText("refused-truncated", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-11-11T12:00:00.0", "truncated"),
    )

    val refuseBytes: List<RefuseBytes> = listOf(
        RefuseBytes("refused-bom", "efbbbf7b2276223a312c2274657874223a226869222c22736f75726365223a22736572766572222c226d6f64656c223a226d222c226475726174696f6e5f6d73223a312c22637265617465645f6174223a22323032362d31312d31315431323a30303a30302e3030305a227d", "bom"),
        RefuseBytes("refused-non-utf8-byte", "7b2276223a312c2274657874223a2268ff69222c22736f75726365223a22736572766572222c226d6f64656c223a226d222c226475726174696f6e5f6d73223a312c22637265617465645f6174223a22323032362d31312d31315431323a30303a30302e3030305a227d", "utf8"),
        RefuseBytes("refused-overlong-utf8", "7b2276223a312c2274657874223a2268c08069222c22736f75726365223a22736572766572222c226d6f64656c223a226d222c226475726174696f6e5f6d73223a312c22637265617465645f6174223a22323032362d31312d31315431323a30303a30302e3030305a227d", "utf8"),
    )
}
