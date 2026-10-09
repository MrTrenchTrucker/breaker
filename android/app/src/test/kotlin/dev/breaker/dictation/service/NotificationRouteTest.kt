package dev.breaker.dictation.service

import dev.breaker.dictation.gates.AppSourceFiles
import dev.breaker.dictation.gates.XmlNode
import dev.breaker.dictation.gates.XmlTree
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route a tap on the ongoing notification carries is a contract with the screen that will honour it,
 * so its names are pinned to their exact text, and the activity it targets is tied to the manifest: the
 * class named by the route is the one launcher activity the manifest declares, and its source file is there.
 */
internal class NotificationRouteTest {

    private val androidName: String = "android:name"

    private fun namespace(): String {
        val script = File(AppSourceFiles.moduleRoot, "build.gradle.kts")
        check(script.isFile) { "app: build.gradle.kts is missing under ${AppSourceFiles.moduleRoot}" }
        return namespaceOf(script.readText())
    }

    private fun namespaceOf(script: String): String =
        Regex("\\bnamespace\\s*=\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1)
            ?: error("app: the build script declares no namespace")

    private fun isLauncherFilter(filter: XmlNode): Boolean =
        filter.name == "intent-filter" &&
            filter.children.any { it.name == "action" && it.attributes[androidName] == "android.intent.action.MAIN" } &&
            filter.children.any { it.name == "category" && it.attributes[androidName] == "android.intent.category.LAUNCHER" }

    private fun collectLaunchers(node: XmlNode, found: MutableList<XmlNode>) {
        if (node.name == "activity" && node.children.any { isLauncherFilter(it) }) found.add(node)
        for (child in node.children) collectLaunchers(child, found)
    }

    /** The full class name of the one launcher activity in [manifest], resolved against [namespace]. */
    private fun launcherClass(manifest: String, namespace: String): String {
        val found: MutableList<XmlNode> = ArrayList()
        collectLaunchers(XmlTree.parse(manifest), found)
        check(found.size == 1) { "app: the manifest declares ${found.size} launcher activities, expected exactly one" }
        val name: String = found[0].attributes[androidName] ?: error("app: the launcher activity has no android:name")
        return when {
            name.startsWith(".") -> namespace + name
            name.contains(".") -> name
            else -> namespace + "." + name
        }
    }

    private fun manifestWith(activityName: String, filter: String = launcherFilter): String =
        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application>" +
            "<activity android:name=\"$activityName\">$filter</activity></application></manifest>"

    private val launcherFilter: String =
        "<intent-filter><action android:name=\"android.intent.action.MAIN\" />" +
            "<category android:name=\"android.intent.category.LAUNCHER\" /></intent-filter>"

    @Test
    fun `the route names are exactly the agreed text`() {
        assertEquals("app: the route extra name changed", "dev.breaker.dictation.extra.ROUTE", NotificationRoute.EXTRA_ROUTE)
        assertEquals("app: the history route value changed", "history", NotificationRoute.ROUTE_HISTORY)
        assertEquals(
            "app: the target activity name changed",
            "dev.breaker.dictation.SettingsLauncherActivity",
            NotificationRoute.TARGET_ACTIVITY,
        )
    }

    @Test
    fun `the route extra is not one of the service action names`() {
        assertNotEquals("app: the route extra equals the arm action", ACTION_ARM, NotificationRoute.EXTRA_ROUTE)
        assertNotEquals("app: the route extra equals the disarm action", ACTION_DISARM, NotificationRoute.EXTRA_ROUTE)
    }

    @Test
    fun `the target activity is the launcher activity the manifest declares`() {
        val declared: String = launcherClass(AppSourceFiles.mainFile("AndroidManifest.xml"), namespace())
        assertEquals("app: the route targets an activity the manifest does not declare as the launcher", declared, NotificationRoute.TARGET_ACTIVITY)
    }

    @Test
    fun `the target activity has a source file that declares that class`() {
        val target: String = NotificationRoute.TARGET_ACTIVITY
        val path: String = "kotlin/" + target.replace('.', '/') + ".kt"
        val code: String = AppSourceFiles.strip(AppSourceFiles.mainFile(path)).code
        val packageName: String = target.substringBeforeLast('.')
        val simpleName: String = target.substringAfterLast('.')
        assertTrue(
            "app: $path does not declare package $packageName",
            Regex("(?m)^\\s*package\\s+" + Regex.escape(packageName) + "\\s*$").containsMatchIn(code),
        )
        assertTrue(
            "app: $path does not declare a class named $simpleName",
            Regex("\\bclass\\s+" + Regex.escape(simpleName) + "\\b").containsMatchIn(code),
        )
    }

    @Test
    fun `the manifest reader resolves a launcher name and refuses a manifest it cannot read`() {
        val ns = "dev.breaker.dictation"
        assertEquals("app: a dotted name was not resolved", "$ns.Home", launcherClass(manifestWith(".Home"), ns))
        assertEquals("app: a bare name was not resolved", "$ns.Home", launcherClass(manifestWith("Home"), ns))
        assertEquals("app: a full name was changed", "other.pkg.Home", launcherClass(manifestWith("other.pkg.Home"), ns))
        assertNotEquals(
            "app: a renamed launcher activity still matched the route",
            NotificationRoute.TARGET_ACTIVITY,
            launcherClass(manifestWith(".SettingsLauncherActivity2"), ns),
        )
        val none = assertThrows("app: a manifest without a launcher must be refused", IllegalStateException::class.java) {
            launcherClass(manifestWith(".Home", ""), ns)
        }
        assertTrue("app: the refusal must say launcher: ${none.message}", none.message.orEmpty().contains("launcher"))
        assertThrows("app: a manifest with two launchers must be refused", IllegalStateException::class.java) {
            launcherClass(
                manifestWith(".Home").replace("</application>", "<activity android:name=\".Other\">$launcherFilter</activity></application>"),
                ns,
            )
        }
        assertThrows("app: a build script without a namespace must be refused", IllegalStateException::class.java) { namespaceOf("android { }") }
        assertEquals("app: the namespace was not read", ns, namespaceOf("android {\n    namespace = \"$ns\"\n}"))
    }
}
