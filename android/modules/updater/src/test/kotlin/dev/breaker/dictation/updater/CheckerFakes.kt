package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.port.AuthService
import dev.breaker.dictation.core.port.Clock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** The ordered record of the calls that the fakes received. */
internal class CallLog {
    private val entries = mutableListOf<String>()

    /** Adds one entry at the end of the record. */
    fun add(entry: String) {
        entries.add(entry)
    }

    /** A copy of the record, in the order in which the calls happened. */
    fun snapshot(): List<String> = entries.toList()

    /** Empties the record. */
    fun clear() {
        entries.clear()
    }
}

/** A clock that always reports the same epoch time, and records each read. */
internal class FakeClock(private val nowMillis: Long, private val log: CallLog) : Clock {
    override fun nowEpochMillis(): Long {
        log.add("clock")
        return nowMillis
    }
}

/** An auth service that gives the session set by the test; the sign-in members are never reached. */
internal class FakeAuth(private val log: CallLog) : AuthService {
    var session: AuthSession? = null

    override fun register(username: String, password: String): AuthSession = unused()

    override fun login(username: String, password: String): AuthSession = unused()

    override fun logout(): Unit = unused()

    override fun currentSession(): AuthSession? {
        log.add("auth.currentSession")
        return session
    }

    private fun unused(): Nothing = throw IllegalStateException("the checker does not sign users in or out")
}

/** A ledger that holds the two times the test sets, and records every write. */
internal class FakeLedger(private val log: CallLog) : UpdateLedger {
    var definitive: Long? = null
    var attempt: Long? = null

    override fun lastDefinitiveCheckMillis(): Long? {
        log.add("ledger.lastDefinitive")
        return definitive
    }

    override fun lastUpdateMillis(): Long? = null

    override fun lastAttemptMillis(): Long? {
        log.add("ledger.lastAttempt")
        return attempt
    }

    override fun recordDefinitiveCheck(atMillis: Long) {
        log.add("ledger.recordDefinitive:$atMillis")
    }

    override fun recordUpdate(atMillis: Long) {
        log.add("ledger.recordUpdate:$atMillis")
    }

    override fun recordAttempt(atMillis: Long) {
        log.add("ledger.recordAttempt:$atMillis")
    }
}

/** A manifest source that returns the load the test set. */
internal class FakeManifests(private val log: CallLog) : ManifestSource {
    var next: ManifestLoad = ManifestLoad.Unavailable

    override fun load(): ManifestLoad {
        log.add("manifest.load")
        return next
    }
}

/** An APK downloader that returns the result the test set, and records the address it was given. */
internal class FakeDownloads(private val log: CallLog) : ApkDownloader {
    var next: ApkDownload = ApkDownload.Unavailable

    override fun download(apkUrl: String): ApkDownload {
        log.add("download:$apkUrl")
        return next
    }
}

/** A staged file whose stream is the one the test gives; the stream is null when the file cannot be opened. */
internal class FakeStagedApk(private val stream: InputStream?, private val log: CallLog) : StagedApk {
    override fun open(): InputStream? {
        log.add("stream.open")
        return stream
    }

    override fun discard() {
        log.add("discard")
    }
}

/** An in-memory file that records when it is closed. */
internal class RecordingStream(bytes: ByteArray, private val log: CallLog) : ByteArrayInputStream(bytes) {
    override fun close() {
        log.add("stream.close")
        super.close()
    }
}

/** A stream that fails on every read, as a broken file would. */
internal class BrokenStream(private val log: CallLog) : InputStream() {
    override fun read(): Int = throw IOException("the test stream cannot be read")

    override fun close() {
        log.add("stream.close")
    }
}

/** An installer that answers with the outcome the test set, or throws the exception the test set. */
internal class FakeInstaller(private val log: CallLog) : PackageInstallerPort {
    var outcome: InstallerResult = InstallerResult.Accepted
    var thrown: RuntimeException? = null
    var calls: Int = 0

    override fun install(staged: StagedApk): InstallerResult {
        calls += 1
        log.add("installer.install")
        thrown?.let { throw it }
        return outcome
    }
}

/** An installed-version reader that returns the build the test set. */
internal class FakeInstalled(private val log: CallLog) : InstalledVersion {
    var build: InstalledBuild = InstalledBuild(versionCode = 11, versionName = "1.2.0-installed")

    override fun current(): InstalledBuild {
        log.add("installed.current")
        return build
    }
}
