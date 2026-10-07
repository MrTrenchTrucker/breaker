package dev.breaker.server.whisper.jobs

internal enum class JobStatus(val dbValue: String) {
    QUEUED("queued"),
    PROCESSING("processing"),
    DONE("done"),
    FAILED("failed");

    companion object {
        // The table's CHECK keeps other values out, so reaching the throw means the
        // file was changed behind our back; failing loudly beats guessing a status.
        fun fromDb(value: String): JobStatus {
            for (status in entries) {
                if (status.dbValue == value) {
                    return status
                }
            }
            throw IllegalStateException("whisper-server: unknown job status in the database: $value")
        }
    }
}
