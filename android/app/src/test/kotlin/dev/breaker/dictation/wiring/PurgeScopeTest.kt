package dev.breaker.dictation.wiring

import dev.breaker.dictation.createPurgeScope
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PurgeScopeTest {
    @Test
    fun `the purge scope runs on the dispatcher for blocking input and output`() {
        val scope = createPurgeScope()
        try {
            assertSame(
                "app: the purge scope should run on Dispatchers.IO",
                Dispatchers.IO,
                scope.coroutineContext[ContinuationInterceptor],
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failing child does not cancel the purge scope`() {
        val scope = createPurgeScope()
        try {
            assertNotNull("app: the purge scope should have a job", scope.coroutineContext[Job])
            val handled = CompletableDeferred<Throwable>()
            scope.launch(CoroutineExceptionHandler { _, e -> handled.complete(e) }) {
                throw IllegalStateException("a purge child failed")
            }
            awaitBounded("the failing child to be handled", handled)
            assertTrue("app: a failing child must not cancel the purge scope", scope.coroutineContext[Job]!!.isActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the application class builds its scope with the purge scope function and not the default dispatcher`() {
        val text = appSource("BreakerApp.kt")
        assertTrue("app: BreakerApp.kt should call createPurgeScope()", text.contains("createPurgeScope()"))
        assertTrue("app: BreakerApp.kt should not use Dispatchers.Default", !text.contains("Dispatchers.Default"))
    }

    /** The text of a main source file; fails by name when it is not there. A near copy of the gate tests' module finder. */
    private fun appSource(name: String): String {
        var folder: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (folder != null) {
            if (File(folder, "build.gradle.kts").isFile && File(folder, "src/main/AndroidManifest.xml").isFile) {
                val file = File(folder, "src/main/kotlin/dev/breaker/dictation/$name")
                check(file.isFile) { "app: the file $file is missing" }
                return file.readText(Charsets.UTF_8)
            }
            folder = folder.parentFile
        }
        error("app: no module folder with build.gradle.kts and src/main/AndroidManifest.xml at or above ${System.getProperty("user.dir")}")
    }
}
