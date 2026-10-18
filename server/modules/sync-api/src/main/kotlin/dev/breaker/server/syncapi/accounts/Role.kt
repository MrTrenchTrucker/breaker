package dev.breaker.server.syncapi.accounts

internal enum class Role(val dbValue: String) {
    ADMIN("admin"),
    USER("user");

    companion object {
        // The table's CHECK keeps other values out, so reaching the throw means the
        // file was changed behind our back; failing loudly beats guessing a role.
        fun fromDb(value: String): Role {
            for (role in entries) {
                if (role.dbValue == value) {
                    return role
                }
            }
            throw IllegalStateException("sync-api: unknown role in the database: $value")
        }
    }
}
