package dev.breaker.dictation.probe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.breaker.dictation.BreakerApp
import dev.breaker.dictation.host.MainLooperPost

/**
 * Starts the debug mic probe with the process, so the receiver can reach the Home mic through the last
 * resumed activity. Declared in the debug manifest only, and not exported. Its data methods do nothing.
 */
class MicProbeBootstrap : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? BreakerApp ?: return true
        MainLooperPost().post { MicProbeState.register(app) }
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
