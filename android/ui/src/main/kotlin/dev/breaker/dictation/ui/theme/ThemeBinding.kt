package dev.breaker.dictation.ui.theme

/**
 * Wires [themes] so that [onThemeChanged] fires once per saved theme change, and never on a
 * failed write (the observer is only told after the new state has been stored). The same instance
 * registered here re-fires; nothing else about [themes]'s lifecycle is assumed.
 *
 * A null callback registers no observer: there is nothing to report, so [themes] is left untouched.
 */
internal fun bindThemeChanges(themes: ThemeController, onThemeChanged: (() -> Unit)?) {
    if (onThemeChanged != null) themes.addObserver { onThemeChanged() }
}
