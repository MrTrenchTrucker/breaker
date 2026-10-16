package dev.breaker.dictation.ui.render

import android.view.View
import android.widget.TextView
import dev.breaker.dictation.ui.controls.DownloadBarMath
import dev.breaker.dictation.ui.render.controls.DownloadBarView
import dev.breaker.dictation.ui.screen.firstrun.DownloadPhase
import dev.breaker.dictation.ui.screen.firstrun.FirstRunLayout
import dev.breaker.dictation.ui.screen.firstrun.FirstRunShown
import dev.breaker.dictation.ui.screen.firstrun.FirstRunTexts
import dev.breaker.dictation.ui.screen.firstrun.FirstRunWords

/*
 * The model download page: the reason, the source, the button, and the bar while the download runs.
 *
 * The page shows one bar at most. While the download runs the bar takes the megabytes it is
 * given, and the percent and the megabytes sit under it. Once every byte is in, the same full
 * bar shows the checking line instead.
 */

/**
 * The download page as it was drawn. [hasBar] is true when a bar is on the page; then
 * [showProgress] moves the bar and its captions without drawing the page again.
 */
internal class DownloadPage(
    /** The page's column, the view that goes into the scroll area. */
    val root: View,
    private val bar: DownloadBarView?,
    private val percent: TextView?,
    private val mb: TextView?,
) {
    /** Whether a bar is on this page. */
    val hasBar: Boolean
        get() = bar != null

    /** Puts the megabytes done into the bar and its two captions, in place. */
    fun showProgress(doneMb: Int, totalMb: Int) {
        bar?.setProgress(doneMb, totalMb)
        val pct = DownloadBarMath.percent(doneMb, totalMb)
        percent?.apply {
            text = if (pct == null) "" else FirstRunTexts.percentLine(pct)
            visibility = if (pct == null) View.GONE else View.VISIBLE
        }
        mb?.text = FirstRunTexts.mbLine(doneMb, totalMb)
    }
}

/** The download page for [shown]. [onDownload] is what the download button does. */
internal fun PageBuilder.downloadPage(shown: FirstRunShown, onDownload: () -> Unit): DownloadPage {
    val page = column()
    shown.notice?.let { page.addView(dangerText(it)) }
    page.addView(bodyText(FirstRunWords.modelWhy()))
    val download = shown.download
    return when (download.phase) {
        DownloadPhase.IDLE, DownloadPhase.FAILED -> {
            if (download.phase == DownloadPhase.FAILED) download.line?.let { page.addView(dangerText(it)) }
            page.addView(mutedText(FirstRunWords.modelSource(shown.state.modelDownloadMb)))
            val button = fullButton(FirstRunWords.modelButton(), onDownload)
            button.tag = FirstRunLayout.DOWNLOAD_BUTTON_TAG
            page.addView(button)
            DownloadPage(page, null, null, null)
        }
        DownloadPhase.RUNNING -> {
            val bar = DownloadBarView(context, theme)
            bar.setProgress(download.doneMb, download.totalMb)
            page.addView(bar, rowParams(true))
            val pct = DownloadBarMath.percent(download.doneMb, download.totalMb)
            val percent = monoText(if (pct == null) "" else FirstRunTexts.percentLine(pct))
            percent.visibility = if (pct == null) View.GONE else View.VISIBLE
            page.addView(percent)
            val mb = monoText(FirstRunTexts.mbLine(download.doneMb, download.totalMb))
            page.addView(mb)
            download.line?.let { page.addView(mutedText(it)) }
            DownloadPage(page, bar, percent, mb)
        }
        DownloadPhase.CHECKING, DownloadPhase.READY -> {
            val bar = DownloadBarView(context, theme)
            bar.setChecking()
            page.addView(bar, rowParams(true))
            download.line?.let { page.addView(mutedText(it)) }
            DownloadPage(page, bar, null, null)
        }
    }
}
