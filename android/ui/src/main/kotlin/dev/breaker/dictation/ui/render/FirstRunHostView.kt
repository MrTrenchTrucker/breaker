package dev.breaker.dictation.ui.render

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import dev.breaker.dictation.ui.ModelStepControl
import dev.breaker.dictation.ui.screen.firstrun.DownloadPhase
import dev.breaker.dictation.ui.screen.firstrun.FirstRunController
import dev.breaker.dictation.ui.screen.firstrun.FirstRunLayout
import dev.breaker.dictation.ui.screen.firstrun.FirstRunPage
import dev.breaker.dictation.ui.screen.firstrun.FirstRunPages
import dev.breaker.dictation.ui.screen.firstrun.FirstRunPictures
import dev.breaker.dictation.ui.screen.firstrun.FirstRunSwipe
import dev.breaker.dictation.ui.screen.firstrun.FirstRunTexts
import dev.breaker.dictation.ui.screen.firstrun.WelcomeTimer
import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.screen.onboarding.SetupActions
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/*
 * The view the app puts on the display for the first run.
 *
 * It draws the controller's page and decides nothing: every decision is already in the plain
 * files. The welcome page is the logo, which leaves after a few seconds or at a tap. The other
 * pages sit in a scroll area, so a tall page reaches its last button on a short screen. A
 * right-to-left drag moves a permission page on; the drag is only watched, so every touch still
 * reaches the page under the finger.
 */

/**
 * The first-run page view. Every change replaces the one child; the one exception is the download
 * bar, which takes new numbers in place so the scroll position stays where the user left it.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param controller the controller that decides the page and what a tap does.
 * @param theme the colours the page is drawn in.
 * @param dark whether the phone is in its dark mode, which chooses the pictures.
 */
internal class FirstRunHostView(
    context: Context,
    private val controller: FirstRunController,
    private val theme: Theme,
    private val dark: Boolean,
) : FrameLayout(context), ModelStepControl {
    private val density: Float = context.resources.displayMetrics.density
    private val welcome = WelcomeTimer(ViewDelayedWork(this), this::next)
    private var drawnPage: FirstRunPage? = null
    private var scroller: ScrollView? = null
    private var download: DownloadPage? = null
    private var downX: Float = 0f
    private var downY: Float = 0f

    init {
        setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
        draw()
    }

    /** Draws the page the controller shows now, keeping the scroll position when the page is the same. */
    private fun draw() {
        val shown = controller.shown()
        val held = if (shown.page == drawnPage && shown.notice == null) scroller?.scrollY ?: 0 else 0
        removeAllViews()
        download = null
        scroller = null
        if (shown.page == FirstRunPage.WELCOME) {
            addView(welcomeView())
        } else {
            val builder = PageBuilder(context, theme)
            val content: View = if (shown.page == FirstRunPage.DOWNLOAD) {
                val page = builder.downloadPage(shown, this::downloadTap)
                if (page.hasBar) download = page
                page.root
            } else {
                builder.permissionPage(shown, dark, this::grant, this::next)
            }
            val area = ScrollView(context).apply {
                isFillViewport = true
                addView(content)
            }
            scroller = area
            addView(area)
        }
        drawnPage = shown.page
        scroller?.scrollTo(0, held)
    }

    /** The welcome page: the logo for the theme, centred, and a tap that moves on. */
    private fun welcomeView(): View {
        val id = resources.getIdentifier(FirstRunPictures.logoName(dark), "drawable", context.packageName)
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (id != 0) setImageResource(id)
            contentDescription = FirstRunTexts.LOGO_DESCRIPTION
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { welcome.tap() }
        }
    }

    /** Does what a grant button asks, then draws the page again. */
    private fun grant(action: OpenAction) {
        controller.tap(SetupActions.stringFor(action))
        draw()
    }

    /** Moves to the next page, then draws it. */
    private fun next() {
        controller.advance()
        draw()
    }

    /** Asks the controller to start the download, then draws the page again. */
    private fun downloadTap() {
        controller.tapDownload()
        draw()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.refresh()
        draw()
        if (controller.page == FirstRunPage.WELCOME) welcome.start()
    }

    override fun onDetachedFromWindow() {
        welcome.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            controller.refresh()
            draw()
        }
    }

    /** Watches for a right-to-left drag on the permission pages and never takes the touch. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> swipeIfAdvance(event)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun swipeIfAdvance(event: MotionEvent) {
        if (!FirstRunPages.swipes(controller.page)) return
        if (FirstRunSwipe.isAdvance((event.x - downX) / density, (event.y - downY) / density)) next()
    }

    override fun refresh() {
        controller.refresh()
        draw()
    }

    override fun focusDownload(): Boolean {
        val button: View = findViewWithTag<View>(FirstRunLayout.DOWNLOAD_BUTTON_TAG) ?: return false
        return button.requestFocus()
    }

    override fun showSwitchRefused(sentence: String) {
        controller.switchRefused(sentence)
        draw()
    }

    override fun downloadProgress(doneMb: Int, totalMb: Int) {
        controller.downloadProgress(doneMb, totalMb)
        val live = download
        if (live != null && controller.shown().download.phase == DownloadPhase.RUNNING) {
            live.showProgress(doneMb, totalMb)
        } else {
            draw()
        }
    }

    override fun downloadChecking() {
        controller.downloadChecking()
        draw()
    }

    override fun downloadStopped() {
        controller.downloadStopped()
        draw()
    }
}
