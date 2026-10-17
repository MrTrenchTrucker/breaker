package dev.breaker.dictation.probe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.breaker.dictation.BreakerApp
import dev.breaker.dictation.commit.KeyboardProbeReadings
import dev.breaker.dictation.host.MainLooperPost

/**
 * Starts the probe with the process, so the accessibility service learns on or off before the probe page
 * is opened. Declared in the debug manifest only, and not exported. Its data methods do nothing.
 */
class ProbeStartProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? BreakerApp ?: return true
        val setting: KeyboardSignalSetting = probeSettingStore(app).load()
        KeyboardProbeReadings.setEnabled(setting != KeyboardSignalSetting.OFF)
        if (startNeedsFullApply(setting)) {
            MainLooperPost().post { appProbeSwitch(app).apply(setting) }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? = null
}
