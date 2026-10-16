package dev.breaker.dictation.ui.screen.firstrun

/*
 * The names of the pictures the first-run pages show.
 *
 * Each permission page has a picture for the light theme and one for the dark theme, and
 * the welcome page shows the logo in both themes. The views look the pictures up by these
 * resource names, so the names are fixed in this one place.
 */

/** The resource names of the first-run pictures. */
internal object FirstRunPictures {
    /**
     * The picture name for [page] in the given theme, or null for the pages that have no
     * permission picture: the welcome page and the download page.
     */
    fun nameFor(page: FirstRunPage, dark: Boolean): String? {
        val part = when (page) {
            FirstRunPage.MICROPHONE -> "mic"
            FirstRunPage.OVERLAY -> "overlay"
            FirstRunPage.NOTIFICATIONS -> "notify"
            FirstRunPage.ACCESSIBILITY -> "access"
            FirstRunPage.WELCOME, FirstRunPage.DOWNLOAD -> return null
        }
        return permissionName(part, dark)
    }

    /** The logo picture name for the given theme. */
    fun logoName(dark: Boolean): String = if (dark) "breaker_logo_dark" else "breaker_logo_light"

    /** Every picture name the first run uses: each permission picture light then dark, then the two logos. */
    val ALL: List<String> = listOf(
        permissionName("mic", dark = false),
        permissionName("mic", dark = true),
        permissionName("overlay", dark = false),
        permissionName("overlay", dark = true),
        permissionName("notify", dark = false),
        permissionName("notify", dark = true),
        permissionName("access", dark = false),
        permissionName("access", dark = true),
        logoName(dark = false),
        logoName(dark = true),
    )

    /** The resource name of one permission picture. */
    private fun permissionName(part: String, dark: Boolean): String =
        if (dark) "breaker_perm_${part}_dark" else "breaker_perm_${part}_light"
}
