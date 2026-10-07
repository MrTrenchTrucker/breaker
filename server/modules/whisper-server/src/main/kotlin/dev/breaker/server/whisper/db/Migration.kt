package dev.breaker.server.whisper.db

/**
 * One step of the schema history. [version] is the value `PRAGMA user_version`
 * holds once the step has been applied; [statements] run in order, one at a time.
 */
internal class Migration(val version: Int, val statements: List<String>)
