package dev.breaker.dictation.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader

/** The debug probe log page: shows the BreakerMicProbe touch lines from the ring and the app's own
 * logcat lines under the BreakerMicProbe tag. Opened only by the breaker_probe launcher shortcut.
 * Debug build only; removed before release. The ring is the reliable part; logcat buffers roll over. */
internal class ProbeLogActivity : Activity() {

    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        text = TextView(this)
        text.isFocusable = false
        text.text = "Probe log. Tap Share to send, Clear to reset."
        val scroll = ScrollView(this)
        scroll.addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        // The app targets API 36, where a window draws edge to edge: keep the page clear of the status bar,
        // the cutout and the navigation bar (gesture or buttons), or the first lines and the Clear button sit under them.
        column.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val share = Button(this)
        share.text = "Share"
        share.setOnClickListener { shareLog() }
        column.addView(share, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val clear = Button(this)
        clear.text = "Clear"
        clear.setOnClickListener { probeRing.clear(); render() }
        column.addView(clear, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val ring = probeRing.snapshot().joinToString("\n")
        text.text = page(ring, "(reading the log)")
        Thread {
            val log = readLogcat()
            runOnUiThread { text.text = page(ring, log) }
        }.start()
    }

    private fun page(ring: String, log: String): String =
        "Touch lines (ring, the reliable part)\n---\n$ring\n\nLog (logcat, buffers roll over)\n---\n$log"

    /** The app's own lines under the probe tag. The output is read to its end BEFORE the wait, so a long answer cannot fill the pipe. */
    private fun readLogcat(): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-t", "2000", "-v", "time", "-s", "BreakerMicProbe:V").redirectErrorStream(true).start()
            val answer = StringBuilder()
            val collector = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { answer.append(it).append('\n') }
                } catch (ignored: Exception) {
                    // the process was destroyed after the time limit
                }
            }
            collector.start()
            collector.join(5000)
            if (collector.isAlive) {
                process.destroyForcibly()
                "logcat: no answer within 5 seconds"
            } else {
                answer.toString()
            }
        } catch (failure: Exception) {
            "logcat: ${failure.message ?: failure::class.simpleName}"
        }
    }

    private fun shareLog() {
        val body = text.text.toString()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, "Share probe log"))
    }
}
