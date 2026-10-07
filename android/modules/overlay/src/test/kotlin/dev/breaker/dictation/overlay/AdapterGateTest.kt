package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window adapter cannot run on a plain JVM, so its rules are checked on its source text:
 * where the platform classes may be named, which window type and flags it asks for, how a
 * refusal is handled, and what the manifest asks for.
 *
 * Each check is a pure function from source text to a list of violations. Every test runs its
 * check first on short made-up samples (a wrong one must be reported, a correct one must not)
 * and only then on the real files. A file or construct that cannot be found is a violation,
 * never a pass. Comments are blanked and string literals are kept.
 */
class AdapterGateTest {

    private val window = "WindowManagerTileWindow.kt"
    private val view = "TileView.kt"
    private val facade = "FloatingTile.kt"
    private val adapterFiles = listOf(window, view, facade)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("android_overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("android_overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** The violations of [check] on one file of [files], or one violation when the file is not there. */
    private fun inFile(files: Map<String, String>, name: String, check: (String) -> List<String>): List<String> =
        files[name]?.let(check) ?: listOf("$name is missing from the main sources")

    private fun requireAll(code: String, patterns: List<String>): List<String> =
        patterns.filterNot { Regex("\\b$it\\b").containsMatchIn(code) }.map { "$it is not used" }

    private fun forbidAll(code: String, patterns: List<String>): List<String> =
        patterns.filter { Regex("\\b$it\\b").containsMatchIn(code) }.map { "$it is used" }

    // ---- Only the three adapter files use android classes ----

    private val androidName = Regex("(?<![\\w.])android\\.")

    private fun confinementProblems(files: Map<String, String>): List<String> {
        val missing = adapterFiles.filterNot { it in files }.map { "$it is missing from the main sources" }
        val stray = files.filterKeys { it !in adapterFiles }
            .filterValues { androidName.containsMatchIn(SourceText.code(it)) }
            .map { "${it.key} names an android class outside the adapter files" }
        return missing + stray
    }

    /** A failure here means a platform class leaked out of the three adapter files, so the logic is no longer testable on a plain JVM. */
    @Test
    fun `android classes are imported only in the three adapter files`() {
        val adapters = adapterFiles.associateWith { "import android.view.View\n" }
        assertFires("an import in another file", confinementProblems(adapters + ("TileController.kt" to "import android.content.Context\n")), "TileController.kt names")
        assertFires("a full name in another file", confinementProblems(adapters + ("TilePlacement.kt" to "val v = android.graphics.Color.RED\n")), "TilePlacement.kt names")
        assertFires("a missing adapter file", confinementProblems(adapters - view), "$view is missing")
        val quiet = adapters + ("TileController.kt" to "// import android.view.View\n/* android.os.Handler */\nimport dev.breaker.dictation.core.port.SettingsStore\n")
        assertQuiet("the adapters importing, and another file naming it only in comments", confinementProblems(quiet))

        assertEquals("android_overlay: android classes only in $adapterFiles", emptyList<String>(), confinementProblems(ModuleFiles.mainTexts()))
    }

    // ---- The window type and the window flags ----

    private val legacyTypes = listOf(
        "TYPE_PHONE", "TYPE_PRIORITY_PHONE", "TYPE_SYSTEM_ALERT", "TYPE_SYSTEM_OVERLAY", "TYPE_SYSTEM_ERROR", "TYPE_TOAST", "TYPE_APPLICATION_PANEL",
    )

    private fun windowTypeProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return requireAll(code, listOf("TYPE_APPLICATION_OVERLAY")) + forbidAll(code, legacyTypes)
    }

    private fun windowFlagProblems(source: String): List<String> =
        requireAll(SourceText.code(source), listOf("FLAG_NOT_FOCUSABLE", "FLAG_LAYOUT_IN_SCREEN"))

    /** A failure here means the overlay is asked for with a window type that is wrong for this Android level. */
    @Test
    fun `the overlay window uses the application overlay type and no legacy type`() {
        legacyTypes.forEach { legacy ->
            assertFires("legacy $legacy", windowTypeProblems("val t = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY\nval u = $legacy\n"), "$legacy is used")
        }
        assertFires("a missing type", windowTypeProblems("val t = 1\n"), "TYPE_APPLICATION_OVERLAY is not used")
        assertFires("a type named only in a comment", windowTypeProblems("// TYPE_APPLICATION_OVERLAY\nval t = 1\n"), "TYPE_APPLICATION_OVERLAY is not used")
        assertQuiet("the application type with legacy names in a comment", windowTypeProblems("/* TYPE_PHONE TYPE_TOAST */\nval t = TYPE_APPLICATION_OVERLAY\n"))
        assertQuiet("the application type with every legacy name in a comment", windowTypeProblems("// ${legacyTypes.joinToString(" ")}\nval t = TYPE_APPLICATION_OVERLAY\n"))
        assertQuiet("the application type with a longer name that only starts like a legacy one", windowTypeProblems("val t = TYPE_APPLICATION_OVERLAY\nval u = TYPE_PHONE_X\n"))

        assertEquals("android_overlay: $window must use only the application overlay type", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, this::windowTypeProblems))
    }

    /** A failure here means the tile window can take focus (the app underneath loses its keyboard) or is not placed from the screen corner. */
    @Test
    fun `the overlay window is not focusable and is placed from the screen corner`() {
        val both = "val f = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN\n"
        assertFires("a missing not-focusable flag", windowFlagProblems("val f = FLAG_LAYOUT_IN_SCREEN\n"), "FLAG_NOT_FOCUSABLE is not used")
        assertFires("a missing layout flag", windowFlagProblems("val f = FLAG_NOT_FOCUSABLE\n"), "FLAG_LAYOUT_IN_SCREEN is not used")
        assertFires("flags named only in a comment", windowFlagProblems("// $both"), "FLAG_NOT_FOCUSABLE is not used")
        assertFires("a near-miss flag name", windowFlagProblems("val f = FLAG_NOT_FOCUSABLE_X or FLAG_LAYOUT_IN_SCREEN\n"), "FLAG_NOT_FOCUSABLE is not used")
        assertQuiet("both flags present", windowFlagProblems(both))

        assertEquals("android_overlay: $window must be not focusable and laid out in the screen", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, this::windowFlagProblems))
    }

    // ---- Adding the window is guarded ----

    private fun guardSample(call: String = "wm.addView(v, p)", catches: String = "catch (e: BadTokenException) { return AddOutcome.REFUSED }\n catch (e: SecurityException) { return AddOutcome.REFUSED }") =
        "fun add(): AddOutcome {\n try { $call } $catches\n return AddOutcome.ADDED\n}\n"

    /** A failure here means a refused window can crash the app instead of being reported as a refusal. */
    @Test
    fun `adding the window is guarded and a refusal becomes REFUSED`() {
        assertFires("no addView", AdapterRules.guardProblems("val x = 1\n// wm.addView(v, p)\n"), "no addView call")
        assertFires("an unguarded call", AdapterRules.guardProblems("fun add() { wm.addView(v, p) }\n"), "is not inside a try")
        assertFires("a missing security catch", AdapterRules.guardProblems(guardSample(catches = "catch (e: BadTokenException) { return AddOutcome.REFUSED }")), "does not catch")
        assertFires("a missing bad-token catch", AdapterRules.guardProblems(guardSample(catches = "catch (e: SecurityException) { return AddOutcome.REFUSED }")), "does not catch")
        assertFires("a catch that does not refuse", AdapterRules.guardProblems(guardSample(catches = "catch (e: BadTokenException) { return AddOutcome.ADDED } catch (e: SecurityException) { return AddOutcome.REFUSED }")), "does not catch")
        assertFires("a catch that swallows", AdapterRules.guardProblems(guardSample(catches = "catch (e: BadTokenException) { } catch (e: SecurityException) { return AddOutcome.REFUSED }")), "does not catch")
        assertFires("a call in the catch, after the try", AdapterRules.guardProblems("fun a() {\n try { x() } catch (e: BadTokenException) { return AddOutcome.REFUSED } catch (e: SecurityException) { return AddOutcome.REFUSED }\n wm.addView(v, p)\n}\n"), "is not inside a try")
        assertFires("a call inside the catch body", AdapterRules.guardProblems("fun a() {\n try { x() } catch (e: BadTokenException) { wm.addView(v, p); return AddOutcome.REFUSED } catch (e: SecurityException) { return AddOutcome.REFUSED }\n}\n"), "is not inside a try")
        assertQuiet("a guarded call", AdapterRules.guardProblems(guardSample()))
        assertQuiet("a guarded call with the catches swapped and the call nested", AdapterRules.guardProblems(guardSample(call = "if (ok) { wm.addView(v, p) }", catches = "catch (e: SecurityException) { return AddOutcome.REFUSED }\n catch (e: WindowManager.BadTokenException) { return AddOutcome.REFUSED }")))

        assertEquals("android_overlay: $window must guard addView and answer REFUSED", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, AdapterRules::guardProblems))
    }

    // ---- Touches are filtered when covered, and there is no text input ----

    private val filterTrue = Regex("\\bsetFilterTouchesWhenObscured\\s*\\(\\s*true\\s*\\)")
    private val filterFalse = Regex("\\bsetFilterTouchesWhenObscured\\s*\\(\\s*false\\s*\\)|\\bfilterTouchesWhenObscured\\s*=\\s*false\\b")
    private val securityFilterName = Regex("\\bonFilterTouchEventForSecurity\\b")

    private fun touchFilterProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return listOfNotNull(
            "setFilterTouchesWhenObscured(true) is not called".takeUnless { filterTrue.containsMatchIn(code) },
            "touches are not filtered: setFilterTouchesWhenObscured is switched off".takeIf { filterFalse.containsMatchIn(code) },
            "onFilterTouchEventForSecurity is named, and an override of it can undo the obscured-touch filter".takeIf { securityFilterName.containsMatchIn(code) },
        )
    }

    private val textInputTokens = listOf("EditText", "onCreateInputConnection", "InputMethod")

    private fun textInputProblems(files: Map<String, String>): List<String> = listOf(view, window).flatMap { name ->
        inFile(files, name) { source -> SourceText.hits(SourceText.code(source), textInputTokens).map { "$name uses $it" } }
    }

    /** A failure here means a window laid over the tile could feed it touches (the spoofing risk the tile is built against), or the tile overrode the system hook that applies the filter. */
    @Test
    fun `the tile view drops touches when it is covered`() {
        assertFires("no call", touchFilterProblems("init { }\n"), "is not called")
        assertFires("a call in a comment", touchFilterProblems("// setFilterTouchesWhenObscured(true)\n"), "is not called")
        assertFires("a false call", touchFilterProblems("init { setFilterTouchesWhenObscured(false) }\n"), "switched off")
        assertFires("a true call and a false call", touchFilterProblems("init { setFilterTouchesWhenObscured(true); setFilterTouchesWhenObscured(false) }\n"), "switched off")
        assertFires("a false property", touchFilterProblems("init { setFilterTouchesWhenObscured(true); filterTouchesWhenObscured = false }\n"), "switched off")
        val securityOverride = "override fun onFilterTouchEventForSecurity(e: MotionEvent): Boolean = true\n"
        assertFires("an override of the security filter", touchFilterProblems("init { setFilterTouchesWhenObscured(true) }\n$securityOverride"), "onFilterTouchEventForSecurity is named")
        assertFires("an override with the true call missing", touchFilterProblems(securityOverride), "is not called")
        assertQuiet("a true call", touchFilterProblems("init { setFilterTouchesWhenObscured( true ) }\n"))
        assertQuiet("a true call and the override named only in a comment", touchFilterProblems("// onFilterTouchEventForSecurity\ninit { setFilterTouchesWhenObscured(true) }\n"))
        assertQuiet("a true call and a longer name that only starts like the override", touchFilterProblems("init { setFilterTouchesWhenObscured(true) }\nval onFilterTouchEventForSecurityX = 1\n"))

        assertEquals("android_overlay: $view must call setFilterTouchesWhenObscured(true) and not touch onFilterTouchEventForSecurity", emptyList<String>(), inFile(ModuleFiles.mainTexts(), view, this::touchFilterProblems))
    }

    /** A failure here means the tile grew a text input, which an overlay window cannot serve and which would pull focus. */
    @Test
    fun `the tile takes no text input`() {
        val clean = mapOf(view to "class V : View()\n", window to "// EditText InputMethod\nclass W\n")
        assertQuiet("two clean files, one naming the words in a comment", textInputProblems(clean))
        assertFires("EditText", textInputProblems(clean + (view to "val e = EditText(context)\n")), "$view uses EditText")
        assertFires("an input connection", textInputProblems(clean + (view to "override fun onCreateInputConnection(o: EditorInfo) = null\n")), "$view uses onCreateInputConnection")
        assertFires("an input method", textInputProblems(clean + (window to "val m = getSystemService(InputMethodManager::class.java)\n")), "$window uses InputMethod")
        assertFires("a missing file", textInputProblems(clean - window), "$window is missing")

        assertEquals("android_overlay: $view and $window must hold no text input", emptyList<String>(), textInputProblems(ModuleFiles.mainTexts()))
    }

    // ---- No colour literal in a main source ----

    private val colourConstants = listOf("WHITE", "BLACK", "RED", "GREEN", "BLUE", "YELLOW", "CYAN", "MAGENTA", "GRAY", "DKGRAY", "LTGRAY")
    private val colourCall = Regex("(?<![A-Za-z0-9_])Color\\.(parseColor|rgb|argb|valueOf|${colourConstants.joinToString("|")})\\b")
    private val hexLiteral = Regex("\\b0[xX]([0-9A-Fa-f]+(?:_[0-9A-Fa-f]+)*)")

    private fun colourProblems(files: Map<String, String>): List<String> {
        if (files.isEmpty()) return listOf("no main source was found")
        return files.flatMap { (name, source) ->
            val code = SourceText.code(source)
            colourCall.findAll(code).map { "$name calls ${it.value}" }.toList() +
                hexLiteral.findAll(code).filter { it.groupValues[1].replace("_", "").length.let { n -> n == 6 || n == 8 } }
                    .map { "$name has the hex literal ${it.value}" }.toList()
        }
    }

    /** A failure here means a colour is written into a main source instead of being taken from the theme palette. */
    @Test
    fun `main sources hold no colour literal`() {
        assertFires("no sources", colourProblems(emptyMap()), "no main source")
        listOf("Color.parseColor(s)", "Color.rgb(1, 2, 3)", "Color.argb(1, 2, 3, 4)", "Color.valueOf(1f)", "android.graphics.Color.parseColor(s)").forEach { call ->
            assertFires(call, colourProblems(mapOf("A.kt" to "val c = $call\n")), "A.kt calls")
        }
        colourConstants.forEach { name ->
            assertFires("Color.$name", colourProblems(mapOf("A.kt" to "val c = Color.$name\n")), "A.kt calls Color.$name")
            assertFires("a full name for Color.$name", colourProblems(mapOf("A.kt" to "paint.color = android.graphics.Color.$name\n")), "A.kt calls Color.$name")
        }
        assertFires("a six digit hex", colourProblems(mapOf("A.kt" to "val c = 0x1E7A46\n")), "0x1E7A46")
        assertFires("an eight digit hex", colourProblems(mapOf("A.kt" to "val c = 0xFF1E7A46.toInt()\n")), "0xFF1E7A46")
        assertFires("a hex with separators", colourProblems(mapOf("A.kt" to "val c = 0xFF_1E_7A_46\n")), "hex literal")
        assertQuiet("palette colours, short hex, other valueOf and a comment", colourProblems(mapOf(
            "A.kt" to "// Color.parseColor(\"#1E7A46\") 0x1E7A46\nval c = palette.surface.argb\nval m = ThemeMode.valueOf(s)\nval k = 0xFFFF\nval t = TokenColor.valueOf(s)\n",
        )))

        assertQuiet("the transparent constant, other owners of the same names, longer names and comments", colourProblems(mapOf(
            "A.kt" to "val c = Color.TRANSPARENT\nval t = TokenColor.RED\nval u = ThemeColor.WHITE\nval v = Color.REDDISH\nval w = Color.GRAY_X\nval x = Color.Red\n// Color.BLACK\n/* Color.BLUE */\n",
        )))

        assertEquals("android_overlay: main sources must hold no colour literal", emptyList<String>(), colourProblems(ModuleFiles.mainTexts()))
    }

    // ---- The manifest declares one permission ----

    private val manifestComment = Regex("<!--[\\s\\S]*?-->")
    private val permissionTag = Regex("<\\s*(uses-permission[\\w-]*|permission[\\w-]*)(?=[\\s/>])([^>]*)>")
    private val nameAttribute = Regex("android:name\\s*=\\s*\"([^\"]*)\"")
    private val onlyPermission = "uses-permission android.permission.SYSTEM_ALERT_WINDOW"

    private fun manifestProblems(manifest: String?): List<String> {
        if (manifest == null) return listOf("src/main/AndroidManifest.xml is missing")
        val declared = permissionTag.findAll(manifestComment.replace(manifest, " ")).map { tag ->
            tag.groupValues[1] + " " + (nameAttribute.find(tag.groupValues[2])?.groupValues?.get(1) ?: "(no name)")
        }.toList()
        return if (declared == listOf(onlyPermission)) emptyList() else listOf("the manifest declares $declared, expected exactly [$onlyPermission]")
    }

    private fun manifestWith(vararg lines: String) =
        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n${lines.joinToString("\n")}\n</manifest>\n"

    private fun permission(name: String, tag: String = "uses-permission") = "<$tag android:name=\"$name\" />"

    /** A failure here means the module asks for a permission it was not meant to, or stopped asking for the overlay one. */
    @Test
    fun `the manifest declares exactly the overlay permission`() {
        val overlay = permission("android.permission.SYSTEM_ALERT_WINDOW")
        assertFires("no manifest", manifestProblems(null), "is missing")
        assertFires("no permission", manifestProblems(manifestWith()), "expected exactly")
        assertFires("a second permission", manifestProblems(manifestWith(overlay, permission("android.permission.INTERNET"))), "INTERNET")
        assertFires("another permission only", manifestProblems(manifestWith(permission("android.permission.INTERNET"))), "expected exactly")
        assertFires("a sdk-23 permission", manifestProblems(manifestWith(overlay, permission("android.permission.CAMERA", "uses-permission-sdk-23"))), "CAMERA")
        assertFires("a declared permission", manifestProblems(manifestWith(overlay, permission("com.example.X", "permission"))), "com.example.X")
        assertFires("the overlay permission twice", manifestProblems(manifestWith(overlay, overlay)), "expected exactly")
        assertQuiet("the overlay permission", manifestProblems(manifestWith(overlay)))
        assertQuiet("the overlay permission and a commented one", manifestProblems(manifestWith(overlay, "<!-- " + permission("android.permission.INTERNET") + " -->")))

        assertEquals("android_overlay: the manifest must declare exactly the overlay permission", emptyList<String>(), manifestProblems(ModuleFiles.manifestText()))
    }

    // ---- The permission answer comes from the system ----

    /** A failure here means the tile can claim it may draw over other apps without asking the system. */
    @Test
    fun `the overlay permission answer comes from the system`() {
        val system = "Settings.canDrawOverlays(context)"
        assertFires("no function", AdapterRules.permissionAnswerProblems("val x = $system\n"), "no canDrawOverlays function")
        assertFires("a literal true", AdapterRules.permissionAnswerProblems("override fun canDrawOverlays(): Boolean = true\n"), "literal true")
        assertFires("a block with a literal true", AdapterRules.permissionAnswerProblems("override fun canDrawOverlays(): Boolean {\n return true\n}\n"), "literal true")
        assertFires("a true fallback", AdapterRules.permissionAnswerProblems("override fun canDrawOverlays(): Boolean = $system || true\n"), "literal true")
        assertFires("a true fallback on the next line", AdapterRules.permissionAnswerProblems("override fun canDrawOverlays(): Boolean = $system ||\n true\nfun other() = 1\n"), "literal true")
        assertFires("a stored answer", AdapterRules.permissionAnswerProblems("override fun canDrawOverlays(): Boolean = granted\n"), "does not call")
        assertFires("the call in another function", AdapterRules.permissionAnswerProblems("fun other() = $system\noverride fun canDrawOverlays(): Boolean = true\n"), "literal true")
        assertQuiet("an expression body", AdapterRules.permissionAnswerProblems("override fun canDrawOverlays(): Boolean = $system\nfun other() = true\n"))
        assertQuiet("a block body, with true only in a comment", AdapterRules.permissionAnswerProblems("override fun canDrawOverlays(): Boolean {\n // never true\n return $system\n}\n"))
        assertQuiet("a wrapped expression body", AdapterRules.permissionAnswerProblems("override fun canDrawOverlays(): Boolean =\n    android.provider.$system\nfun other() = true\n"))

        assertEquals("android_overlay: $window must take canDrawOverlays from the system", emptyList<String>(), inFile(ModuleFiles.mainTexts(), window, AdapterRules::permissionAnswerProblems))
    }
}
