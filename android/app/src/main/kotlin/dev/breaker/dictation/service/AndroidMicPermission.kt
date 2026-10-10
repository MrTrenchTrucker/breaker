package dev.breaker.dictation.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** The [MicPermission] over the platform: whether the user has allowed Breaker to record audio. */
class AndroidMicPermission(private val context: Context) : MicPermission {

    override fun isRecordAudioGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
