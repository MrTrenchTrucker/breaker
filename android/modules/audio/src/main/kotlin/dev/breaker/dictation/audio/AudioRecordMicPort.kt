package dev.breaker.dictation.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build

/**
 * The one way to get a [MicSource] that records from the best microphone on the
 * phone and follows it when it changes.
 *
 * A Bluetooth microphone is preferred, a wired or USB one comes next, and the
 * phone's own microphone is the silent fallback: nothing is shown or reported
 * when a lesser device is used, at the start of a take or in the middle of one.
 *
 * The caller holds the permissions. RECORD_AUDIO is needed to record at all.
 * A Bluetooth microphone also needs BLUETOOTH_CONNECT; without it the platform
 * lists no Bluetooth input, so the wired or phone microphone is used, silently.
 *
 * This file is the only one in the module that names the platform framework.
 */
object AndroidMicSource {

    /** A source over [audioManager]'s inputs, recording 16 kHz mono 16-bit PCM. */
    fun create(audioManager: AudioManager): MicSource =
        RoutedMicSource(
            MicDeviceSupplier { listInputs(audioManager) },
            AudioRecordMicPort(audioManager),
        )
}

/** The recording inputs the platform lists now, mapped to the kinds the policy knows. */
private fun listInputs(audioManager: AudioManager): List<MicDevice> =
    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { info ->
        kindOf(info.type)?.let { kind -> MicDevice(kind, info.id) }
    }

/** The kind of an input of platform type [type], or null for a type that is not used. */
private fun kindOf(type: Int): MicDeviceKind? = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicDeviceKind.BLUETOOTH
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    -> MicDeviceKind.WIRED
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicDeviceKind.BUILT_IN
    else -> if (Build.VERSION.SDK_INT >= API_31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
        MicDeviceKind.BLUETOOTH
    } else {
        null
    }
}

/** First API level that has a Bluetooth LE headset type and a communication device. */
private const val API_31 = 31

/** Recording source for a call-style Bluetooth route. */
private const val BLUETOOTH_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION

/** Recording source for every other input. */
private const val PLAIN_SOURCE = MediaRecorder.AudioSource.MIC

/** Buffer size as a multiple of the platform's minimum. */
private const val BUFFER_FACTOR = 2

/**
 * [MicInputPort] over [AudioRecord].
 *
 * One recording at a time. [open] starts it and registers a device callback;
 * [close] stops it, unregisters the callback and undoes any Bluetooth routing
 * that [open] set up. Called by one caller at a time, except that the device
 * callback runs on the main looper and only writes two volatile fields.
 */
internal class AudioRecordMicPort(private val audioManager: AudioManager) : MicInputPort {

    override val sampleRateHz: Int get() = RATE_HZ

    private var record: AudioRecord? = null
    private var callback: AudioDeviceCallback? = null
    private var scoStarted = false
    private var communicationSet = false

    /** Id of the device in use, or -1 for the system default; read by the callback. */
    @Volatile
    private var watchedId = NO_DEVICE

    /** Set by the callback when the device in use is removed; cleared only by [open]. */
    @Volatile
    private var lost = false

    override fun open(device: MicDevice?) {
        close()
        try {
            lost = false
            watchedId = device?.id ?: NO_DEVICE
            val info = device?.let { inputInfoFor(it.id) }
            if (device != null && info == null) throw MicSourceException("audio: the chosen microphone is gone")
            val bluetooth = device?.kind == MicDeviceKind.BLUETOOTH
            if (bluetooth && info != null) routeBluetooth(info)
            val created = newRecord(if (bluetooth) BLUETOOTH_SOURCE else PLAIN_SOURCE)
            record = created
            if (info != null) created.setPreferredDevice(info)
            registerCallback()
            created.startRecording()
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MicSourceException("audio: the microphone is in use by another app")
            }
        } catch (e: MicSourceException) {
            close()
            throw e
        } catch (e: SecurityException) {
            close()
            throw MicSourceException("audio: the microphone permission is missing", e)
        } catch (e: IllegalStateException) {
            close()
            throw MicSourceException("audio: the microphone could not be started", e)
        } catch (e: IllegalArgumentException) {
            close()
            throw MicSourceException("audio: the microphone refused the request", e)
        } catch (e: UnsupportedOperationException) {
            close()
            throw MicSourceException("audio: the microphone cannot be created", e)
        }
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
        val active = record ?: throw MicSourceException("audio: the microphone is not open")
        try {
            return active.read(buffer, offset, lengthInShorts)
        } catch (e: IllegalStateException) {
            throw MicSourceException("audio: the microphone stopped while reading", e)
        } catch (e: IllegalArgumentException) {
            throw MicSourceException("audio: the microphone read was refused", e)
        }
    }

    /**
     * Reading does not clear the flag, so a loss reported by the callback at any
     * moment cannot be erased by a read. Only [open] clears it, and the routed
     * source always closes and opens again after a true answer.
     */
    override fun routeLost(): Boolean = lost

    /**
     * True when the platform is silencing this recorder because another client
     * holds the microphone. The rate limit that keeps this a bounded pull lives
     * in [RoutedMicSource]; this method only asks the recorder itself.
     *
     * Uses the API 29+ `getActiveRecordingConfiguration()` / `isClientSilenced()`
     * accessors (mapped to Kotlin properties). minSdk is 30, so the framework
     * is always present; a null configuration means no signal, not "taken".
     */
    override fun silenced(): Boolean =
        record?.activeRecordingConfiguration?.isClientSilenced == true

    override fun close() {
        val active = record
        record = null
        watchedId = NO_DEVICE
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        callback = null
        if (active != null) {
            try {
                active.stop()
            } catch (ignored: IllegalStateException) {
                // Already stopped or never started: nothing to undo.
            }
            active.release()
        }
        undoBluetoothRoute()
    }

    /** The listed input with platform id [id], or null if it is no longer listed. */
    private fun inputInfoFor(id: Int): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }

    /**
     * An [AudioRecord] at 16 kHz mono 16-bit, or a [MicSourceException] if the platform refuses.
     * The caller holds RECORD_AUDIO (module card); a missing grant surfaces as the
     * SecurityException that [open] turns into a [MicSourceException].
     */
    @SuppressLint("MissingPermission")
    private fun newRecord(source: Int): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(RATE_HZ, CHANNELS, ENCODING)
        if (minimum <= 0) throw refused()
        val created = AudioRecord(source, RATE_HZ, CHANNELS, ENCODING, minimum * BUFFER_FACTOR)
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            throw refused()
        }
        return created
    }

    private fun refused() = MicSourceException("audio: the microphone refused 16 kHz mono 16-bit")

    private fun registerCallback() {
        val watcher = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val id = watchedId
                if (id != NO_DEVICE && removedDevices.any { it.id == id }) lost = true
            }
        }
        callback = watcher
        audioManager.registerAudioDeviceCallback(watcher, null)
    }

    private fun routeBluetooth(info: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= API_31) {
            audioManager.setCommunicationDevice(communicationTargetFor(info))
            communicationSet = true
        } else {
            startSco()
        }
    }

    /**
     * The communication device that matches the input [info]: the platform wants
     * a device from its own communication list, which names the same headset
     * by type and address. Falls back to [info] itself when none matches.
     */
    private fun communicationTargetFor(info: AudioDeviceInfo): AudioDeviceInfo {
        if (Build.VERSION.SDK_INT < API_31) return info
        return audioManager.availableCommunicationDevices.firstOrNull {
            it.type == info.type && it.address == info.address
        } ?: info
    }

    @Suppress("DEPRECATION")
    private fun startSco() {
        audioManager.startBluetoothSco()
        scoStarted = true
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        audioManager.stopBluetoothSco()
    }

    private fun undoBluetoothRoute() {
        if (communicationSet) {
            communicationSet = false
            if (Build.VERSION.SDK_INT >= API_31) audioManager.clearCommunicationDevice()
        }
        if (scoStarted) {
            scoStarted = false
            stopSco()
        }
    }

    private companion object {
        const val RATE_HZ = 16_000
        const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val NO_DEVICE = -1
    }
}
