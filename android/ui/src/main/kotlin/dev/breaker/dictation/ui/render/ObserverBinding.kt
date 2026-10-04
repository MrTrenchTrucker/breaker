package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.ui.theme.ThemeState

/*
 * The join between the theme choice and the view that draws it.
 *
 * The controller holds a list of observers and nothing else knows when that list
 * changes. The view has to be told when it is attached to a window and when it
 * is not, and must be told exactly once either way. That bookkeeping is the
 * whole of this class, which keeps it free of any framework type so the
 * register/detach behaviour can be driven from a plain JVM test.
 */

/**
 * Calls [onChange] whenever the theme changes, for as long as it is attached.
 *
 * The observer is built once, in a field, and the same instance is added and
 * removed. A lambda made afresh inside [attach] would be a different object from
 * the one [detach] tries to take away, because the controller matches observers
 * by identity: the view would keep receiving changes after it had left the
 * window and would be drawn again into a hierarchy nobody can see. Attaching
 * twice is therefore ignored, which also means a view that is attached, detached
 * and attached again is registered once and not twice.
 *
 * Attaching is not asking the controller to say where the theme stands now.
 * A view that was off screen when the choice changed draws the current choice
 * when it comes back, which is the view's own business.
 *
 * An observer that throws is caught by the controller and does not stop the
 * others or undo a saved change, so a failure here cannot change what was
 * stored.
 */
internal class ObserverBinding(
    private val themes: ThemeController,
    private val onChange: (ThemeState) -> Unit,
) {
    private var attached: Boolean = false

    /** The one observer instance, so that removing it can find it again. */
    private val observer: (ThemeState) -> Unit = { state -> onChange(state) }

    /**
     * Starts listening for theme changes.
     *
     * Does nothing when already attached, so a view that is attached more than
     * once is not told about the same change twice.
     */
    fun attach() {
        if (attached) return
        attached = true
        themes.addObserver(observer)
    }

    /**
     * Stops listening for theme changes.
     *
     * Does nothing when not attached, so detaching a view that was never
     * attached is harmless.
     */
    fun detach() {
        if (!attached) return
        attached = false
        themes.removeObserver(observer)
    }
}