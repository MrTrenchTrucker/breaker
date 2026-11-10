package dev.breaker.server.syncapi.updates

import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.transcriptions.SyncJsonNumeral
import dev.breaker.server.syncapi.transcriptions.SyncJsonText
import dev.breaker.server.syncapi.transcriptions.SyncJsonValue
import dev.breaker.server.syncapi.transcriptions.readSyncJsonObject
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The most bytes of manifest file the route accepts. A file above it is a server fault (503).
internal const val MANIFEST_CAP: Int = 65_536

// The fixed 404 and 503 bodies. Nothing from the file text reaches a body or a log.
private const val NOT_FOUND_BODY: String = """{"error":"not_found","message":"No release yet"}"""

private const val UNAVAILABLE_BODY: String =
    """{"error":"unavailable","message":"The service is unavailable, try again later"}"""

// The six fields of a valid manifest. The 200 body is rebuilt from them, so nothing else in the
// file (extra members, white space, key order) is ever echoed.
internal data class ValidatedManifest(
    val version: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val signature: String,
    val notes: String,
)

// What reading the manifest file came to: a valid manifest (200), no release yet (404), or a
// file that is there but unusable (503).
internal sealed class ManifestRead {
    class Found(val manifest: ValidatedManifest) : ManifestRead()

    object Missing : ManifestRead()

    object Invalid : ManifestRead()
}

private class ManifestTooLarge : Exception()

// GET /v1/updates/latest.json: public, read-only, no session. The manifest is pinned by the
// release process; the phone checks its content with the SHA-256 and the signature.
internal fun Route.updatesRoute(manifestFile: String) {
    get("/v1/updates/latest.json") {
        when (val read = readManifest(manifestFile)) {
            is ManifestRead.Found ->
                call.respondText(manifestBody(read.manifest), ContentType.Application.Json, HttpStatusCode.OK)
            ManifestRead.Missing ->
                call.respondText(NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
            ManifestRead.Invalid ->
                call.respondText(UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
        }
    }
}

// Reads the file named by the config. An unset path and a path with no file are Missing; every
// other failure (a directory, an unreadable file, too large, not UTF-8, not the six fields) is Invalid.
internal suspend fun readManifest(manifestFile: String): ManifestRead {
    if (manifestFile.isBlank()) return ManifestRead.Missing
    return withContext(Dispatchers.IO) { readManifestBlocking(manifestFile) }
}

private fun readManifestBlocking(manifestFile: String): ManifestRead {
    return try {
        val parsed = readSyncJsonObject(readTextStrictly(manifestFile))
        val manifest = if (parsed == null) null else validate(parsed)
        if (manifest == null) ManifestRead.Invalid else ManifestRead.Found(manifest)
    } catch (_: NoSuchFileException) {
        ManifestRead.Missing
    } catch (_: Exception) {
        ManifestRead.Invalid
    }
}

// At most MANIFEST_CAP bytes are accepted: one byte more is read to see that the file is longer.
// The bytes are decoded as UTF-8 and any bad byte sequence throws.
private fun readTextStrictly(path: String): String {
    val bytes = Files.newInputStream(Paths.get(path)).use { input -> input.readNBytes(MANIFEST_CAP + 1) }
    if (bytes.size > MANIFEST_CAP) throw ManifestTooLarge()
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}

// The manifest the members make, or null when a rule fails. The four text fields must be strings
// that are not blank; notes must be a string and may be empty; version_code is read by readVersionCode.
private fun validate(parsed: Map<String, SyncJsonValue>): ValidatedManifest? {
    val version = nonBlankText(parsed, "version") ?: return null
    val versionCode = readVersionCode(parsed) ?: return null
    val apkUrl = nonBlankText(parsed, "apk_url") ?: return null
    val sha256 = nonBlankText(parsed, "sha256") ?: return null
    val signature = nonBlankText(parsed, "signature") ?: return null
    val notes = (parsed["notes"] as? SyncJsonText)?.value ?: return null
    return ValidatedManifest(version, versionCode, apkUrl, sha256, signature, notes)
}

private fun nonBlankText(parsed: Map<String, SyncJsonValue>, key: String): String? {
    val text = (parsed[key] as? SyncJsonText)?.value ?: return null
    return if (text.isBlank()) null else text
}

// version_code is a number written as 1 to 18 digits with no leading zero, and at least 1: the
// same floor as the phone's UpdateManifest.
private fun readVersionCode(parsed: Map<String, SyncJsonValue>): Long? {
    val raw = (parsed["version_code"] as? SyncJsonNumeral)?.raw ?: return null
    if (raw.isEmpty() || raw.length > 18) return null
    if (raw.any { it < '0' || it > '9' }) return null
    if (raw.length > 1 && raw[0] == '0') return null
    val value = raw.toLong()
    return if (value >= 1L) value else null
}

// The 200 body: the six fields in the order of the contract.
internal fun manifestBody(manifest: ValidatedManifest): String =
    "{\"version\":${escapeJsonString(manifest.version)}," +
        "\"version_code\":${manifest.versionCode}," +
        "\"apk_url\":${escapeJsonString(manifest.apkUrl)}," +
        "\"sha256\":${escapeJsonString(manifest.sha256)}," +
        "\"signature\":${escapeJsonString(manifest.signature)}," +
        "\"notes\":${escapeJsonString(manifest.notes)}}"
