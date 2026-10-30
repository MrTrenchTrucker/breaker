package dev.breaker.shared.api.sync

// Literal vectors for SyncBodyCodec, one entry per line. The VALUES are the same as in
// examples/sync_body_vectors.json. The literal form follows the escape rule of the sync body
// format: printable ASCII is written as it is, except the backslash and the quote; every other
// character is a backslash-u escape with four lower-case hex digits per UTF-16 unit. So a line
// feed, a carriage return or a tab is written as a backslash-u escape here, although the JSON
// file may write it as a short escape. The decoded values are equal.
internal object SyncBodyVectorData {
    internal class OkBody(val name: String, val input: String, val r1: String, val base64: String)
    internal class OkBodyBytes(val name: String, val hex: String, val r1: String, val base64: String)
    internal class OkPlain(val name: String, val input: String, val r1: String, val base64: String)
    internal class OkClientId(val name: String, val input: String)
    internal class OkCreated(val name: String, val input: String, val epochMs: Long)
    internal class RefuseBytes(val name: String, val hex: String, val family: String)
    internal class RefuseText(val name: String, val input: String, val family: String)
    internal class RefusePlain(val name: String, val input: String, val family: String)
    internal class RefuseClient(val name: String, val input: String, val family: String)
    internal class RefuseCreated(val name: String, val input: String, val family: String)

    val okBody: List<OkBody> = listOf(
        OkBody("body-minimal-ascii", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0="),
        OkBody("body-model-two-chars", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"mm\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"mm\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibW0iLCJkdXJhdGlvbl9tcyI6MSwiY3JlYXRlZF9hdCI6IjIwMjYtMTAtMjFUMTI6MDA6MDAuMDAwWiJ9"),
        OkBody("body-model-three-chars", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"mmm\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"mmm\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibW1tIiwiZHVyYXRpb25fbXMiOjEsImNyZWF0ZWRfYXQiOiIyMDI2LTEwLTIxVDEyOjAwOjAwLjAwMFoifQ=="),
        OkBody("body-escape-quote-backslash", "{\"v\":1,\"text\":\"say \\\"hi\\\" \\\\ back\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"say \\\"hi\\\" \\\\ back\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0Ijoic2F5IFwiaGlcIiBcXCBiYWNrIiwic291cmNlIjoibG9jYWwiLCJtb2RlbCI6Im0iLCJkdXJhdGlvbl9tcyI6MSwiY3JlYXRlZF9hdCI6IjIwMjYtMTAtMjFUMTI6MDA6MDAuMDAwWiJ9"),
        OkBody("body-escape-short-controls", "{\"v\":1,\"text\":\"\\b\\f\\n\\r\\t\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"\\b\\f\\n\\r\\t\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiXGJcZlxuXHJcdCIsInNvdXJjZSI6ImxvY2FsIiwibW9kZWwiOiJtIiwiZHVyYXRpb25fbXMiOjEsImNyZWF0ZWRfYXQiOiIyMDI2LTEwLTIxVDEyOjAwOjAwLjAwMFoifQ=="),
        OkBody("body-escape-u00xx", "{\"v\":1,\"text\":\"\\u0000\\u0001\\u001f\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"\\u0000\\u0001\\u001f\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiXHUwMDAwXHUwMDAxXHUwMDFmIiwic291cmNlIjoibG9jYWwiLCJtb2RlbCI6Im0iLCJkdXJhdGlvbl9tcyI6MSwiY3JlYXRlZF9hdCI6IjIwMjYtMTAtMjFUMTI6MDA6MDAuMDAwWiJ9"),
        OkBody("body-slash-not-escaped-on-encode", "{\"v\":1,\"text\":\"a\\/b\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"a/b\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiYS9iIiwic291cmNlIjoibG9jYWwiLCJtb2RlbCI6Im0iLCJkdXJhdGlvbl9tcyI6MSwiY3JlYXRlZF9hdCI6IjIwMjYtMTAtMjFUMTI6MDA6MDAuMDAwWiJ9"),
        OkBody("body-e-acute-escaped-input", "{\"v\":1,\"text\":\"caf\u00e9\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"caf\u00e9\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiY2Fmw6kiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0="),
        OkBody("body-cjk-escaped-input", "{\"v\":1,\"text\":\"\u6f22\u5b57\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"\u6f22\u5b57\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0Ijoi5ryi5a2XIiwic291cmNlIjoibG9jYWwiLCJtb2RlbCI6Im0iLCJkdXJhdGlvbl9tcyI6MSwiY3JlYXRlZF9hdCI6IjIwMjYtMTAtMjFUMTI6MDA6MDAuMDAwWiJ9"),
        OkBody("body-emoji-pair-escape", "{\"v\":1,\"text\":\"\\ud83d\\ude00\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"\ud83d\ude00\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0Ijoi8J+YgCIsInNvdXJjZSI6ImxvY2FsIiwibW9kZWwiOiJtIiwiZHVyYXRpb25fbXMiOjEsImNyZWF0ZWRfYXQiOiIyMDI2LTEwLTIxVDEyOjAwOjAwLjAwMFoifQ=="),
        OkBody("body-line-separator-raw-output", "{\"v\":1,\"text\":\"a\u2028b\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"a\u2028b\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiYeKAqGIiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0="),
        OkBody("body-del-raw-output", "{\"v\":1,\"text\":\"a\\u007fb\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"a\u007fb\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiYX9iIiwic291cmNlIjoibG9jYWwiLCJtb2RlbCI6Im0iLCJkdXJhdGlvbl9tcyI6MSwiY3JlYXRlZF9hdCI6IjIwMjYtMTAtMjFUMTI6MDA6MDAuMDAwWiJ9"),
        OkBody("body-empty-text", "{\"v\":1,\"text\":\"\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiIiwic291cmNlIjoibG9jYWwiLCJtb2RlbCI6Im0iLCJkdXJhdGlvbl9tcyI6MSwiY3JlYXRlZF9hdCI6IjIwMjYtMTAtMjFUMTI6MDA6MDAuMDAwWiJ9"),
        OkBody("body-empty-model", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoiIiwiZHVyYXRpb25fbXMiOjEsImNyZWF0ZWRfYXQiOiIyMDI2LTEwLTIxVDEyOjAwOjAwLjAwMFoifQ=="),
        OkBody("body-source-server", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"server\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJzZXJ2ZXIiLCJtb2RlbCI6Im0iLCJkdXJhdGlvbl9tcyI6MSwiY3JlYXRlZF9hdCI6IjIwMjYtMTAtMjFUMTI6MDA6MDAuMDAwWiJ9"),
        OkBody("body-epoch-zero", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"1970-01-01T00:00:00.000Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"1970-01-01T00:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMTk3MC0wMS0wMVQwMDowMDowMC4wMDBaIn0="),
        OkBody("body-last-millisecond-9999", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"9999-12-31T23:59:59.999Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"9999-12-31T23:59:59.999Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiOTk5OS0xMi0zMVQyMzo1OTo1OS45OTlaIn0="),
        OkBody("body-leap-day-2028", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2028-02-29T00:00:00.000Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2028-02-29T00:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyOC0wMi0yOVQwMDowMDowMC4wMDBaIn0="),
        OkBody("body-duration-zero", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":0,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":0,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjowLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0="),
        OkBody("body-duration-max", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":9007199254740991,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":9007199254740991,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjo5MDA3MTk5MjU0NzQwOTkxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0="),
        OkBody("body-browser-style", " {\u000d\u000a\"text\" : \"caf\u00e9 \\/ ok\",\u0009\"v\": 1, \"source\":\"server\", \"model\":\"whisper-large-v3\", \"created_at\":\"2026-10-11T12:00:00.000Z\", \"duration_ms\": 1500\u000d\u000a}\u000a", "{\"v\":1,\"text\":\"caf\u00e9 / ok\",\"source\":\"server\",\"model\":\"whisper-large-v3\",\"duration_ms\":1500,\"created_at\":\"2026-10-11T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiY2Fmw6kgLyBvayIsInNvdXJjZSI6InNlcnZlciIsIm1vZGVsIjoid2hpc3Blci1sYXJnZS12MyIsImR1cmF0aW9uX21zIjoxNTAwLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0xMVQxMjowMDowMC4wMDBaIn0="),
        OkBody("body-openapi-example", "{\"v\":1,\"text\":\"hello from the phone\",\"source\":\"local\",\"model\":\"base.en\",\"duration_ms\":1500,\"created_at\":\"2026-10-11T12:00:00.000Z\"}", "{\"v\":1,\"text\":\"hello from the phone\",\"source\":\"local\",\"model\":\"base.en\",\"duration_ms\":1500,\"created_at\":\"2026-10-11T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGVsbG8gZnJvbSB0aGUgcGhvbmUiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoiYmFzZS5lbiIsImR1cmF0aW9uX21zIjoxNTAwLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0xMVQxMjowMDowMC4wMDBaIn0="),
    )

    val okBodyBytes: List<OkBodyBytes> = listOf(
        OkBodyBytes("body-raw-utf8-bytes", "7b2276223a312c2274657874223a22636166c3a920f09f9880e6bca2e5ad97222c22736f75726365223a226c6f63616c222c226d6f64656c223a226d222c226475726174696f6e5f6d73223a312c22637265617465645f6174223a22323032362d31302d32315431323a30303a30302e3030305a227d", "{\"v\":1,\"text\":\"caf\u00e9 \ud83d\ude00\u6f22\u5b57\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiY2Fmw6kg8J+YgOa8ouWtlyIsInNvdXJjZSI6ImxvY2FsIiwibW9kZWwiOiJtIiwiZHVyYXRpb25fbXMiOjEsImNyZWF0ZWRfYXQiOiIyMDI2LTEwLTIxVDEyOjAwOjAwLjAwMFoifQ=="),
    )

    val okPlain: List<OkPlain> = listOf(
        OkPlain("plain-minimal-ascii", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0=", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0="),
        OkPlain("plain-openapi-example", "eyJ2IjoxLCJ0ZXh0IjoiaGVsbG8gZnJvbSB0aGUgcGhvbmUiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoiYmFzZS5lbiIsImR1cmF0aW9uX21zIjoxNTAwLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0xMVQxMjowMDowMC4wMDBaIn0=", "{\"v\":1,\"text\":\"hello from the phone\",\"source\":\"local\",\"model\":\"base.en\",\"duration_ms\":1500,\"created_at\":\"2026-10-11T12:00:00.000Z\"}", "eyJ2IjoxLCJ0ZXh0IjoiaGVsbG8gZnJvbSB0aGUgcGhvbmUiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoiYmFzZS5lbiIsImR1cmF0aW9uX21zIjoxNTAwLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0xMVQxMjowMDowMC4wMDBaIn0="),
    )

    val okClientId: List<OkClientId> = listOf(
        OkClientId("client-id-lowercase-example", "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"),
        OkClientId("client-id-all-zero", "00000000-0000-0000-0000-000000000000"),
    )

    val okCreated: List<OkCreated> = listOf(
        OkCreated("created-epoch-zero", "1970-01-01T00:00:00.000Z", 0L),
        OkCreated("created-last-millisecond", "9999-12-31T23:59:59.999Z", 253402300799999L),
        OkCreated("created-leap-2028", "2028-02-29T00:00:00.000Z", 1835395200000L),
        OkCreated("created-openapi-example", "2026-10-11T12:00:00.000Z", 1791720000000L),
    )

    val refuseBytes: List<RefuseBytes> = listOf(
        RefuseBytes("body-utf8-invalid-byte", "7b2276223a312c2274657874223a2268ff69222c22736f75726365223a226c6f63616c222c226d6f64656c223a226d222c226475726174696f6e5f6d73223a312c22637265617465645f6174223a22323032362d31302d32315431323a30303a30302e3030305a227d", "utf8"),
        RefuseBytes("body-utf8-encoded-surrogate", "7b2276223a312c2274657874223a2278eda08079222c22736f75726365223a226c6f63616c222c226d6f64656c223a226d222c226475726174696f6e5f6d73223a312c22637265617465645f6174223a22323032362d31302d32315431323a30303a30302e3030305a227d", "utf8"),
        RefuseBytes("body-bom-before-object", "efbbbf7b2276223a312c2274657874223a226869222c22736f75726365223a226c6f63616c222c226d6f64656c223a226d222c226475726174696f6e5f6d73223a312c22637265617465645f6174223a22323032362d31302d32315431323a30303a30302e3030305a227d", "bom"),
    )

    val refuseText: List<RefuseText> = listOf(
        RefuseText("body-not-object-array", "[1]", "not_object"),
        RefuseText("body-not-object-string", "\"x\"", "not_object"),
        RefuseText("body-not-object-number", "1", "not_object"),
        RefuseText("body-not-object-null", "null", "not_object"),
        RefuseText("body-version-two", "{\"v\":2,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "version"),
        RefuseText("body-version-zero", "{\"v\":0,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "version"),
        RefuseText("body-version-string-one", "{\"v\":\"1\",\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "version"),
        RefuseText("body-version-one-point-zero", "{\"v\":1.0,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "version"),
        RefuseText("body-version-leading-zero", "{\"v\":01,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "version"),
        RefuseText("body-missing-v", "{\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "missing_key"),
        RefuseText("body-missing-text", "{\"v\":1,\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "missing_key"),
        RefuseText("body-missing-source", "{\"v\":1,\"text\":\"hi\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "missing_key"),
        RefuseText("body-missing-model", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "missing_key"),
        RefuseText("body-missing-duration-ms", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "missing_key"),
        RefuseText("body-missing-created-at", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1}", "missing_key"),
        RefuseText("body-duplicate-text-key", "{\"v\":1,\"text\":\"hi\",\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "duplicate_key"),
        RefuseText("body-duplicate-key-by-escape", "{\"v\":1,\"text\":\"hi\",\"\\u0074ext\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "duplicate_key"),
        RefuseText("body-unknown-extra-key", "{\"v\":1,\"extra\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "unknown_key"),
        RefuseText("body-unknown-audio-path-after-version", "{\"v\":1,\"audio_path\":\"/data/x.wav\",\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "unknown_key"),
        RefuseText("body-unknown-audio-path-last", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\",\"audio_path\":\"/data/x.wav\"}", "unknown_key"),
        RefuseText("body-type-text-number", "{\"v\":1,\"text\":5,\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "type"),
        RefuseText("body-type-model-null", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":null,\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "type"),
        RefuseText("body-type-duration-string", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":\"1\",\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "type"),
        RefuseText("body-type-source-number", "{\"v\":1,\"text\":\"hi\",\"source\":1,\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "type"),
        RefuseText("body-source-capitalised", "{\"v\":1,\"text\":\"hi\",\"source\":\"Local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "source"),
        RefuseText("body-source-remote", "{\"v\":1,\"text\":\"hi\",\"source\":\"remote\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "source"),
        RefuseText("body-duration-negative", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":-1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "duration"),
        RefuseText("body-duration-fraction", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1.5,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "duration"),
        RefuseText("body-duration-exponent", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1e3,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "duration"),
        RefuseText("body-duration-above-safe", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":9007199254740992,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "duration"),
        RefuseText("body-duration-minus-zero", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":-0,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "duration"),
        RefuseText("body-duration-leading-zero", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":01,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "duration"),
        RefuseText("body-created-no-milliseconds", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00Z\"}", "created_at"),
        RefuseText("body-created-no-z", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000\"}", "created_at"),
        RefuseText("body-created-offset", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000+00:00\"}", "created_at"),
        RefuseText("body-created-lower-t", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21t12:00:00.000Z\"}", "created_at"),
        RefuseText("body-created-lower-z", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000z\"}", "created_at"),
        RefuseText("body-created-hour-24", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T24:00:00.000Z\"}", "created_at"),
        RefuseText("body-created-feb-30", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-02-30T12:00:00.000Z\"}", "created_at"),
        RefuseText("body-created-second-60", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:60.000Z\"}", "created_at"),
        RefuseText("body-created-year-10000", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"10000-01-01T00:00:00.000Z\"}", "created_at"),
        RefuseText("body-created-before-1970", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"1969-12-31T23:59:59.999Z\"}", "created_at"),
        RefuseText("body-created-one-ms-digit", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.5Z\"}", "created_at"),
        RefuseText("body-surrogate-high-escape", "{\"v\":1,\"text\":\"\\ud800\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "surrogate"),
        RefuseText("body-surrogate-low-escape", "{\"v\":1,\"text\":\"\\udc00\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "surrogate"),
        RefuseText("body-truncated", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"", "truncated"),
        RefuseText("body-trailing-bytes", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}x", "trailing"),
        RefuseText("body-syntax-trailing-comma", "{\"v\":1,\"text\":\"hi\",\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\",}", "syntax"),
        RefuseText("body-nested-text-empty-object", "{\"v\":1,\"text\":{},\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "syntax"),
        RefuseText("body-nested-text-object-with-key", "{\"v\":1,\"text\":{\"a\":1},\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "syntax"),
        RefuseText("body-nested-text-empty-array", "{\"v\":1,\"text\":[],\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "syntax"),
        RefuseText("body-nested-text-array-with-elements", "{\"v\":1,\"text\":[1,2],\"source\":\"local\",\"model\":\"m\",\"duration_ms\":1,\"created_at\":\"2026-10-21T12:00:00.000Z\"}", "syntax"),
    )

    val refusePlain: List<RefusePlain> = listOf(
        RefusePlain("plain-unpadded", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0", "base64"),
        RefusePlain("plain-line-break", "eyJ2\u000aIjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0=", "base64"),
        RefusePlain("plain-space", "eyJ2 IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0=", "base64"),
        RefusePlain("plain-url-safe", "eyJ2IjoxLCJ0ZXh0Ijoi8J-YgCIsInNvdXJjZSI6ImxvY2FsIiwibW9kZWwiOiJtIiwiZHVyYXRpb25fbXMiOjEsImNyZWF0ZWRfYXQiOiIyMDI2LTEwLTIxVDEyOjAwOjAwLjAwMFoifQ==", "base64"),
        RefusePlain("plain-bad-trailing-bits", "eyJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn1=", "base64"),
        RefusePlain("plain-empty", "", "base64"),
        RefusePlain("plain-bad-character", "*yJ2IjoxLCJ0ZXh0IjoiaGkiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoibSIsImR1cmF0aW9uX21zIjoxLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0yMVQxMjowMDowMC4wMDBaIn0=", "base64"),
        RefusePlain("plain-not-object", "WzFd", "not_object"),
        RefusePlain("plain-padding-in-the-middle-qq-a", "QQ=A", "base64"),
        RefusePlain("plain-padding-in-the-middle-qq-qq", "QQ==QQ==", "base64"),
        RefusePlain("plain-padding-in-the-middle-openapi-example", "eyJ2IjoxLCJ0ZXh0IjoiaGVsbG8gZnJvbSB0aGUgcGhvbmUiLCJzb3VyY2UiOiJsb2NhbCIsIm1vZGVsIjoiYmFz=ZS5lbiIsImR1cmF0aW9uX21zIjoxNTAwLCJjcmVhdGVkX2F0IjoiMjAyNi0xMC0xMVQxMjowMDowMC4wMDBaIn0=", "base64"),
    )

    val refuseClient: List<RefuseClient> = listOf(
        RefuseClient("client-id-uppercase", "0F9C4D1E-6A2B-4C3D-9E8F-1A2B3C4D5E6F", "client_id"),
        RefuseClient("client-id-no-dashes", "0f9c4d1e6a2b4c3d9e8f1a2b3c4d5e6f", "client_id"),
        RefuseClient("client-id-braces", "{0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f}", "client_id"),
        RefuseClient("client-id-35-characters", "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6", "client_id"),
        RefuseClient("client-id-37-characters", "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f0", "client_id"),
        RefuseClient("client-id-trailing-newline", "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f\u000a", "client_id"),
    )

    val refuseCreated: List<RefuseCreated> = listOf(
        RefuseCreated("created-refuse-no-milliseconds", "2026-10-21T12:00:00Z", "created_at"),
        RefuseCreated("created-refuse-no-z", "2026-10-21T12:00:00.000", "created_at"),
        RefuseCreated("created-refuse-offset", "2026-10-21T12:00:00.000+00:00", "created_at"),
        RefuseCreated("created-refuse-lower-t", "2026-10-21t12:00:00.000Z", "created_at"),
        RefuseCreated("created-refuse-lower-z", "2026-10-21T12:00:00.000z", "created_at"),
        RefuseCreated("created-refuse-hour-24", "2026-10-21T24:00:00.000Z", "created_at"),
        RefuseCreated("created-refuse-feb-30", "2026-02-30T12:00:00.000Z", "created_at"),
        RefuseCreated("created-refuse-second-60", "2026-10-21T12:00:60.000Z", "created_at"),
        RefuseCreated("created-refuse-year-10000", "10000-01-01T00:00:00.000Z", "created_at"),
        RefuseCreated("created-refuse-before-1970", "1969-12-31T23:59:59.999Z", "created_at"),
        RefuseCreated("created-refuse-one-ms-digit", "2026-10-21T12:00:00.5Z", "created_at"),
    )
}
