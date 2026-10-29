package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UserRole

/** The current epoch time that every test uses. */
internal const val NOW_MILLIS: Long = 1_700_000_000_000L

/** The configured server address that the manifest source reports. */
internal const val SERVER_BASE: String = "https://breaker.example.test:8443"

/** The download address of the default release; it is on the server origin. */
internal const val APK_URL: String = "https://breaker.example.test:8443/v1/apk/breaker.apk"

/** A session token that must never reach a port. */
internal const val HIDDEN_TOKEN: String = "hidden-session-token-0001"

/** The installed version code of the default test install: below the default release. */
internal const val INSTALLED_CODE_BELOW: Int = 11

/** The installed display name of the default test install. */
internal const val INSTALLED_NAME: String = "1.2.0-installed"

/** The payload of the golden vectors; its digest is the default release digest. */
internal val PAYLOAD: ByteArray = ManifestVectors.PAYLOAD_TEXT.toByteArray(Charsets.UTF_8)

/** The pinned keys of the tests: the current key and the next key. */
internal val PINNED_KEYS: List<PinnedKey> = listOf(
    PinnedKey(ManifestVectors.CURRENT_KEY_ID, ManifestVectors.CURRENT_SPKI),
    PinnedKey(ManifestVectors.NEXT_KEY_ID, ManifestVectors.NEXT_SPKI),
)

/** An administrator's session that carries the hidden token. */
internal fun adminSession(): AuthSession = AuthSession(
    userId = "user-1",
    username = "admin",
    token = HIDDEN_TOKEN,
    role = UserRole.ADMIN,
    scopes = emptySet(),
)

/** An ordinary user's session that carries the hidden token. */
internal fun userSession(): AuthSession = AuthSession(
    userId = "user-2",
    username = "user",
    token = HIDDEN_TOKEN,
    role = UserRole.USER,
    scopes = emptySet(),
)

/** The release that the default manifest offers. */
internal fun defaultRelease(): ReleaseInfo = ReleaseInfo(
    version = "1.2.0",
    apkUrl = APK_URL,
    sha256 = ManifestVectors.DIGEST,
    signature = ManifestVectors.SIG_CURRENT,
)

/**
 * The text of a manifest. The optional key id is left out when [keyId] is null. The default values make a
 * manifest that the current key signs for version code 12.
 */
internal fun manifestJson(
    version: String = "1.2.0",
    versionCode: String = ManifestVectors.VERSION_CODE,
    apkUrl: String = APK_URL,
    sha256: String = ManifestVectors.DIGEST,
    signature: String = ManifestVectors.SIG_CURRENT,
    keyId: String? = ManifestVectors.CURRENT_KEY_ID,
): String {
    val fields = mutableListOf(
        "\"version\": \"$version\"",
        "\"version_code\": \"$versionCode\"",
        "\"apk_url\": \"$apkUrl\"",
        "\"sha256\": \"$sha256\"",
        "\"signature\": \"$signature\"",
    )
    if (keyId != null) fields.add("\"key_id\": \"$keyId\"")
    return fields.joinToString(separator = ", ", prefix = "{", postfix = "}")
}

/**
 * One checker wired to fakes. The fakes share one call log, so a test reads the exact order of the calls. The
 * checker is built on first use, so a test sets [apkByteCap] before it touches [checker].
 */
internal class CheckerRig {
    val log = CallLog()
    val clock = FakeClock(NOW_MILLIS, log)
    val auth = FakeAuth(log)
    val ledger = FakeLedger(log)
    val manifests = FakeManifests(log)
    val downloads = FakeDownloads(log)
    val installer = FakeInstaller(log)
    val installed = FakeInstalled(log)
    var apkByteCap: Long? = null

    val checker: ReleaseUpdateChecker by lazy {
        val cap = apkByteCap
        if (cap == null) {
            ReleaseUpdateChecker(clock, auth, ledger, manifests, downloads, installer, installed, PINNED_KEYS)
        } else {
            ReleaseUpdateChecker(clock, auth, ledger, manifests, downloads, installer, installed, PINNED_KEYS, cap)
        }
    }

    init {
        manifests.next = ManifestLoad.Loaded(manifestJson(), SERVER_BASE)
        stageBytes(PAYLOAD)
    }

    /** Makes the downloaded file hold [bytes]. */
    fun stageBytes(bytes: ByteArray) {
        downloads.next = ApkDownload.Staged(FakeStagedApk(RecordingStream(bytes, log), log))
    }

    /** Makes the downloaded file one that cannot be read. */
    fun stageBroken() {
        downloads.next = ApkDownload.Staged(FakeStagedApk(BrokenStream(log), log))
    }

    /** Makes the downloaded file one that cannot be opened. */
    fun stageUnopenable() {
        downloads.next = ApkDownload.Staged(FakeStagedApk(null, log))
    }

    /** Makes the manifest source answer with [text] from the configured server. */
    fun manifestWith(text: String) {
        manifests.next = ManifestLoad.Loaded(text, SERVER_BASE)
    }
}
