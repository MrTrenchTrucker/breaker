package dev.breaker.dictation.probe

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import dev.breaker.dictation.BreakerApp
import dev.breaker.dictation.commit.KeyboardProbeReadings
import dev.breaker.dictation.host.MainLooperPost
import dev.breaker.dictation.wiring.PostHandle
import dev.breaker.dictation.wiring.applyInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS_NAME: String = "breaker_probe"
private const val PREFS_KEY: String = "signal"
private const val REFRESH_MS: Long = 500L

/** The debug store over the breaker_probe preferences file, under the key signal. */
internal fun probeSettingStore(context: Context): ProbeSettingStore {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return ProbeSettingStore(
        read = { prefs.getString(PREFS_KEY, null) },
        write = { word -> prefs.edit().putString(PREFS_KEY, word).apply() },
    )
}

/** The apply with the real functions: the probe holder, the tile's listener and the overlay inset sink. */
internal fun appProbeSwitch(app: BreakerApp): ProbeSwitch = ProbeSwitch(
    setHolder = { on -> KeyboardProbeReadings.setEnabled(on) },
    setTileProbe = { listener -> app.tileHost.setImeProbe(listener) },
    onInset = { bottom, visible ->
        ProbeState.insets.add(System.currentTimeMillis(), "overlay", bottom, visible)
    },
    clearLog = { ProbeState.insets.clear() },
)

/** The debug page: the three signal choices, a text field, the latest block and the recent lines. */
class KeyboardProbeActivity : Activity() {
    private val post: MainLooperPost = MainLooperPost()
    private val clockFormat: SimpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buttons: MutableMap<KeyboardSignalSetting, RadioButton> = LinkedHashMap()
    private val activityInsets: View.OnApplyWindowInsetsListener = View.OnApplyWindowInsetsListener { v, insets ->
        val ime = WindowInsets.Type.ime()
        val bottom = insets.getInsets(ime).bottom
        ProbeState.insets.add(System.currentTimeMillis(), "activity", bottom, insets.isVisible(ime))
        v.onApplyWindowInsets(insets)
    }
    private lateinit var store: ProbeSettingStore
    private lateinit var group: RadioGroup
    private lateinit var latestText: TextView
    private lateinit var recentText: TextView
    private var setting: KeyboardSignalSetting = KeyboardSignalSetting.OFF
    private var choosing: Boolean = false
    private var refresh: PostHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = probeSettingStore(this)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "Breaker probe"
        column.addView(title)
        group = RadioGroup(this)
        group.orientation = LinearLayout.HORIZONTAL
        for (option in KeyboardSignalSetting.entries) {
            val button = RadioButton(this)
            button.id = View.generateViewId()
            button.text = optionLabel(option)
            buttons[option] = button
            group.addView(button)
        }
        group.setOnCheckedChangeListener { _, checkedId -> onRadioChecked(checkedId) }
        column.addView(group)
        val input = EditText(this)
        input.hint = "Tap here to raise a keyboard"
        column.addView(input)
        latestText = TextView(this)
        column.addView(latestText)
        recentText = TextView(this)
        val scroll = ScrollView(this)
        scroll.addView(recentText)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        applyInsets(column)
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        setting = store.load()
        choosing = true
        group.check(buttons.getValue(setting).id)
        choosing = false
        applyChoice()
        refreshText()
        scheduleRefresh()
    }

    override fun onPause() {
        super.onPause()
        refresh?.cancel()
        refresh = null
    }

    private fun onRadioChecked(checkedId: Int) {
        if (choosing) {
            return
        }
        val chosen: KeyboardSignalSetting = buttons.entries.firstOrNull { it.value.id == checkedId }?.key ?: return
        setting = chosen
        store.save(chosen)
        applyChoice()
        refreshText()
    }

    /** Applies the setting: the probe holder and the tile listener, and the activity inset listener only when wanted. */
    private fun applyChoice() {
        appProbeSwitch(applicationContext as BreakerApp).apply(setting)
        val wanted: Boolean = activityInsetsWanted(setting)
        window.decorView.setOnApplyWindowInsetsListener(if (wanted) activityInsets else null)
        if (wanted) {
            window.decorView.requestApplyInsets()
        }
    }

    private fun refreshText() {
        latestText.text = latestBlock(
            setting,
            KeyboardProbeReadings.current(),
            ProbeState.insets.latest("activity"),
            ProbeState.insets.latest("overlay"),
            ::clockText,
        ).joinToString("\n")
        recentText.text = recentLines(
            setting,
            KeyboardProbeReadings.recent(),
            ProbeState.insets.lines(),
            ::clockText,
        ).joinToString("\n")
    }

    private fun scheduleRefresh() {
        refresh = post.postDelayed(REFRESH_MS) {
            refreshText()
            scheduleRefresh()
        }
    }

    private fun clockText(time: Long): String = clockFormat.format(Date(time))

    private fun optionLabel(option: KeyboardSignalSetting): String = when (option) {
        KeyboardSignalSetting.OFF -> "Off"
        KeyboardSignalSetting.FIELD_FOCUS -> "Field focus"
        KeyboardSignalSetting.KEYBOARD_VISIBLE -> "Keyboard visible"
    }
}
