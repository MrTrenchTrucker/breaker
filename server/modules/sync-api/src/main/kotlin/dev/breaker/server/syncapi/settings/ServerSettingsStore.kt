package dev.breaker.server.syncapi.settings

import dev.breaker.server.syncapi.db.SqliteDatabase

/** Reads and writes the wrong-password limit switch from the server_settings table. */
internal class ServerSettingsStore(private val db: SqliteDatabase) {
    companion object {
        const val WRONG_PW_LIMIT_KEY = "wrong_pw_limit"
    }

    suspend fun wrongPwLimitEnabled(): Boolean {
        return db.transaction<Boolean> { connection ->
            connection.prepareStatement(
                "SELECT int_value, typeof(int_value) FROM server_settings WHERE setting_key = ?",
            ).use { statement ->
                statement.setString(1, WRONG_PW_LIMIT_KEY)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        // Only an integer 0 reads off; any other stored value (a real, text, a blob) reads on.
                        !(rows.getString(2) == "integer" && rows.getLong(1) == 0L)
                    } else {
                        true
                    }
                }
            }
        }
    }

    suspend fun setWrongPwLimitEnabled(enabled: Boolean) {
        db.transaction<Unit> { connection ->
            val value = if (enabled) 1 else 0
            connection.prepareStatement(
                "INSERT INTO server_settings (setting_key, int_value) VALUES (?, ?) " +
                    "ON CONFLICT(setting_key) DO UPDATE SET int_value = excluded.int_value",
            ).use { statement ->
                statement.setString(1, WRONG_PW_LIMIT_KEY)
                statement.setInt(2, value)
                statement.executeUpdate()
            }
        }
    }
}
