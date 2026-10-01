package dev.breaker.dictation.core

import dev.breaker.dictation.core.testing.ForbiddenFrameworks
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The two framework gates share one list, [ForbiddenFrameworks.packages]. A shared
 * list is only as good as the proof that every entry in it is enforced by both gates,
 * so this plants a probe for each package, in the source form and in the compiled
 * form, and runs the very scans the gates run.
 *
 * The table below is written out on purpose and is not read from the list under test:
 * it is the policy, and a package dropped from the list, or from one gate's use of it,
 * fails here by name. Adding a package to the list means adding its probe here.
 */
class ForbiddenFrameworksTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A package, the import a source file would use for it, and the name a class file would carry. */
    private class Probe(val pkg: String, val importLine: String, val internalName: String)

    private val probes = listOf(
        Probe("android", "import android.os.Bundle", "android/os/Bundle"),
        Probe("androidx", "import androidx.core.app.NotificationCompat", "androidx/core/app/NotificationCompat"),
        Probe("dalvik", "import dalvik.system.DexFile", "dalvik/system/DexFile"),
        Probe("java.net", "import java.net.URL", "java/net/URL"),
        Probe("javax.net", "import javax.net.ssl.SSLContext", "javax/net/ssl/SSLContext"),
        Probe("java.sql", "import java.sql.Connection", "java/sql/Connection"),
        Probe("javax.sql", "import javax.sql.DataSource", "javax/sql/DataSource"),
        Probe("java.nio.file", "import java.nio.file.Files", "java/nio/file/Files"),
        Probe("jakarta", "import jakarta.inject.Inject", "jakarta/inject/Inject"),
        Probe("okhttp3", "import okhttp3.OkHttpClient", "okhttp3/OkHttpClient"),
        Probe("okio", "import okio.Buffer", "okio/Buffer"),
        Probe("retrofit2", "import retrofit2.Retrofit", "retrofit2/Retrofit"),
        Probe("com.squareup", "import com.squareup.moshi.Moshi", "com/squareup/moshi/Moshi"),
        Probe("io.ktor", "import io.ktor.client.HttpClient", "io/ktor/client/HttpClient"),
        Probe("org.springframework", "import org.springframework.stereotype.Component", "org/springframework/stereotype/Component"),
        Probe("org.json", "import org.json.JSONObject", "org/json/JSONObject"),
        Probe("com.google.gson", "import com.google.gson.Gson", "com/google/gson/Gson"),
        Probe("kotlinx.serialization", "import kotlinx.serialization.Serializable", "kotlinx/serialization/Serializable"),
        Probe("org.jetbrains.exposed", "import org.jetbrains.exposed.sql.Table", "org/jetbrains/exposed/sql/Table"),
        Probe("dagger", "import dagger.Module", "dagger/Module"),
        Probe("javax.inject", "import javax.inject.Singleton", "javax/inject/Singleton"),
        Probe("org.koin", "import org.koin.core.Koin", "org/koin/core/Koin"),
    )

    @Test
    fun `the shared list forbids exactly the packages the policy names`() {
        val policy = probes.map { it.pkg }

        assertEquals("the probe table must not list a package twice", policy.size, policy.toSet().size)
        assertEquals(
            "a package was dropped from, or added to, the shared forbidden list without its probe",
            policy.sorted(),
            ForbiddenFrameworks.packages.sorted(),
        )
    }

    @Test
    fun `the source scan finds an import from every forbidden package`() {
        val root = temp.newFolder("sources")
        probes.forEach { probe ->
            File(root, "${probe.pkg}.kt").writeText("package probe\n\n${probe.importLine}\n\nclass Planted\n")
        }

        val flagged = ForbiddenFrameworks.importOffenders(root).map { File(it.substringBefore(": ")).name }.toSet()

        val missed = probes.map { it.pkg }.filterNot { "$it.kt" in flagged }
        assertTrue("the source scan let an import from these packages through: $missed", missed.isEmpty())
    }

    @Test
    fun `the bytecode scan finds a reference to every forbidden package`() {
        val root = temp.newFolder("classes")
        probes.forEach { probe ->
            // Not a loadable class: the scan reads text out of the bytes, and that is all it needs.
            File(root, "${probe.pkg}.class").writeBytes("\u0001${probe.internalName}\u0000".toByteArray(Charsets.ISO_8859_1))
        }

        val flagged = ForbiddenFrameworks.bytecodeOffenders(root).map { it.substringBefore(" -> ") }.toSet()

        val missed = probes.map { it.pkg }.filterNot { "$it.class" in flagged }
        assertTrue("the bytecode scan let a reference to these packages through: $missed", missed.isEmpty())
    }

    @Test
    fun `both scans leave ordinary code and near misses alone`() {
        val ordinaryImports = listOf(
            "import kotlin.collections.List",
            "import java.util.UUID",
            "import dev.breaker.dictation.core.model.AppSettings",
            "import androidish.Thing", // shares a prefix with "android" but is not it
            "import java_net.Client", // a dot in the package name is a dot, not "any character"
            "// import android.os.Bundle, written in a comment",
        )
        val ordinaryNames = listOf(
            "kotlin/collections/CollectionsKt",
            "java/lang/String",
            "java/util/List",
            "java/nio/ByteBuffer",
            "dev/breaker/dictation/core/model/AppSettings",
            "androidish/Thing",
            "java_net/Client",
        )

        val flaggedImports = ordinaryImports.filter { ForbiddenFrameworks.isForbiddenImport(it) }
        val flaggedNames = ordinaryNames.filter { ForbiddenFrameworks.isForbiddenInternalName(it) }

        assertTrue("these ordinary imports were flagged: $flaggedImports", flaggedImports.isEmpty())
        assertTrue("these ordinary references were flagged: $flaggedNames", flaggedNames.isEmpty())
    }
}
