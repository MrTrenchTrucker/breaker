package dev.breaker.dictation.ui.screen.home

import kotlin.math.PI
import kotlin.math.tan

internal data class HomeRect(val x: Float, val y: Float, val width: Float, val height: Float)

/** A word the art draws as text, in dp: its left edge [x], its baseline [y] and its size. */
internal data class HomeText(val x: Float, val y: Float, val size: Float)

/** A word the art draws as lettering, in dp: its left edge [x] (its centre when the art centres it), the top of its capitals [top], and the cap height [cap]. */
internal data class HomeWordmark(val x: Float, val top: Float, val cap: Float)

/** One slanted stripe, in dp: a parallelogram [width] wide and [height] tall whose top edge is shifted right by [slant]. */
internal data class HomeStripe(val x: Float, val y: Float, val width: Float, val height: Float, val slant: Float)

internal data class HomeSwitch(val track: HomeRect, val trackRadius: Float, val knob: HomeRect, val knobRadius: Float)

/** One status row, in dp: its baseline [y], its label's and its value's left edge, and the accent dash the first two rows carry. */
internal data class HomeRow(val y: Float, val labelX: Float, val valueX: Float, val dash: HomeRect?)

/** The mic, in dp: the scale the art draws it at, and where its own origin goes. */
internal data class HomeMic(val scale: Float, val x: Float, val y: Float)

/** The top bar: its height, the clock, the menu button's three lines, the logo mic, the wordmark and the accent rule. */
internal data class HomeTopBar(val height: Float, val clock: HomeText, val menuLines: List<HomeRect>, val logoMic: HomeMic, val wordmark: HomeWordmark, val accentRule: HomeRect)

/** The on/off card: its rect and radius, the accent bar, the title, the switch and the three rows. */
internal data class HomeStatusCard(val rect: HomeRect, val radius: Float, val accentBar: HomeRect, val title: HomeWordmark, val switch: HomeSwitch, val rows: List<HomeRow>)

/** The lettering over the big mic: the centred TAP TO TALK and the hint line under it. */
internal data class HomeLettering(val tap: HomeWordmark, val hint: HomeText)

/** The last-transcript box: its rect and radius, the outline's width, the heading, the time and the two text lines. */
internal data class HomeTranscript(val rect: HomeRect, val radius: Float, val outline: Float, val heading: HomeWordmark, val time: HomeText, val lines: List<HomeText>)

/** The Copy button: its rect and radius, and its centred label. */
internal data class HomeCopyButton(val rect: HomeRect, val radius: Float, val label: HomeWordmark)

/** The menu drawer: its panel's width, the mic, the wordmark, the accent rule and the three stripes. */
internal data class HomeDrawer(val panelWidth: Float, val mic: HomeMic, val wordmark: HomeWordmark, val accentRule: HomeRect, val stripes: List<HomeStripe>)

/** One menu item: its lettering and the divider under it. */
internal data class HomeMenuItem(val label: HomeWordmark, val divider: HomeRect)

/**
 * The numbers the home screen is drawn with, in dp. The art gives them in px at 1.5 px per dp
 * (540x1170 px = 360x780 dp), and each constant says its px. This is plain Kotlin with no platform
 * names: the view that draws these numbers belongs to another layer, and no colour is written down
 * here because a colour belongs to the theme.
 */
internal object HomeGeometry {

    // The top bar, in px: 108 tall; the clock at 22/32, size 20; the menu lines x 22..56 and
    // y 58 + i*12, 5 tall; the logo mic scale 0.052; the wordmark 214/56, cap 30; the rule 106..108.
    const val BAR_H_DP: Float = 108f / 1.5f
    const val CLOCK_X_DP: Float = 22f / 1.5f
    const val CLOCK_Y_DP: Float = 32f / 1.5f
    const val CLOCK_SIZE_DP: Float = 20f / 1.5f
    const val MENU_X0_DP: Float = 22f / 1.5f
    const val MENU_X1_DP: Float = 56f / 1.5f
    const val MENU_Y0_DP: Float = 58f / 1.5f
    const val MENU_STEP_DP: Float = 12f / 1.5f
    const val MENU_H_DP: Float = 5f / 1.5f
    const val MENU_LINE_COUNT: Int = 3
    const val LOGO_MIC_SCALE: Float = 0.052f
    const val LOGO_MIC_X_DP: Float = (174f - 174f * LOGO_MIC_SCALE) / 1.5f
    const val LOGO_MIC_Y_DP: Float = (44f - 30f * LOGO_MIC_SCALE) / 1.5f
    const val WORDMARK_X_DP: Float = 214f / 1.5f
    const val WORDMARK_TOP_DP: Float = 56f / 1.5f
    const val WORDMARK_CAP_DP: Float = 30f / 1.5f
    const val RULE_Y0_DP: Float = 106f / 1.5f
    const val RULE_H_DP: Float = 2f / 1.5f

    // The on/off card, in px: x 20..520, y 128..336, radius 4; the accent bar 6 wide; the title
    // 44/154, cap 24; the switch x 432..496, y 148..182, radius 4, knob x 464..492, y 152..178,
    // radius 3; the rows at y 228 + i*36, labels x 44, values x 320, a dash at x 298..310 on the first two.
    const val CARD_X0_DP: Float = 20f / 1.5f
    const val CARD_X1_DP: Float = 520f / 1.5f
    const val CARD_Y0_DP: Float = 128f / 1.5f
    const val CARD_Y1_DP: Float = 336f / 1.5f
    const val CARD_R_DP: Float = 4f / 1.5f
    const val ACCENT_BAR_W_DP: Float = 6f / 1.5f
    const val TITLE_X_DP: Float = 44f / 1.5f
    const val TITLE_TOP_DP: Float = 154f / 1.5f
    const val TITLE_CAP_DP: Float = 24f / 1.5f
    const val SWITCH_X0_DP: Float = 432f / 1.5f
    const val SWITCH_X1_DP: Float = 496f / 1.5f
    const val SWITCH_Y0_DP: Float = 148f / 1.5f
    const val SWITCH_Y1_DP: Float = 182f / 1.5f
    const val SWITCH_R_DP: Float = 4f / 1.5f
    const val KNOB_X0_DP: Float = 464f / 1.5f
    const val KNOB_X1_DP: Float = 492f / 1.5f
    const val KNOB_Y0_DP: Float = 152f / 1.5f
    const val KNOB_Y1_DP: Float = 178f / 1.5f
    const val KNOB_R_DP: Float = 3f / 1.5f
    const val ROW_Y0_DP: Float = 228f / 1.5f
    const val ROW_STEP_DP: Float = 36f / 1.5f
    const val ROW_COUNT: Int = 3
    const val ROW_LABEL_X_DP: Float = 44f / 1.5f
    const val ROW_VALUE_X_DP: Float = 320f / 1.5f
    const val ROW_DASH_X0_DP: Float = 298f / 1.5f
    const val ROW_DASH_X1_DP: Float = 310f / 1.5f
    const val ROW_DASH_H_DP: Float = 12f / 1.5f
    const val ROW_DASH_COUNT: Int = 2

    // The big mic: scale 0.33, its own origin at the art's x = 540/2 - 476*0.33, y = 380 - 30*0.33.
    const val MIC_SCALE: Float = 0.33f
    const val MIC_X_DP: Float = (540f / 2f - 476f * MIC_SCALE) / 1.5f
    const val MIC_Y_DP: Float = (380f - 30f * MIC_SCALE) / 1.5f

    // The lettering over the mic: TAP TO TALK centred on 270, top 726, cap 26; the hint at 62/792, size 21.
    const val TAP_CENTER_X_DP: Float = 270f / 1.5f
    const val TAP_TOP_DP: Float = 726f / 1.5f
    const val TAP_CAP_DP: Float = 26f / 1.5f
    const val HINT_X_DP: Float = 62f / 1.5f
    const val HINT_Y_DP: Float = 792f / 1.5f
    const val HINT_SIZE_DP: Float = 21f / 1.5f

    // The last-transcript box, in px: x 20..520, y 822..1056, r 4, outline 2; heading 44/846 cap 15; time 436/862 size 20; lines x 44, y 908/940, size 24.
    const val BOX_X0_DP: Float = 20f / 1.5f
    const val BOX_X1_DP: Float = 520f / 1.5f
    const val BOX_Y0_DP: Float = 822f / 1.5f
    const val BOX_Y1_DP: Float = 1056f / 1.5f
    const val BOX_R_DP: Float = 4f / 1.5f
    const val BOX_OUTLINE_DP: Float = 2f / 1.5f
    const val HEADING_X_DP: Float = 44f / 1.5f
    const val HEADING_TOP_DP: Float = 846f / 1.5f
    const val HEADING_CAP_DP: Float = 15f / 1.5f
    const val TIME_X_DP: Float = 436f / 1.5f
    const val TIME_Y_DP: Float = 862f / 1.5f
    const val TIME_SIZE_DP: Float = 20f / 1.5f
    const val LINE_X_DP: Float = 44f / 1.5f
    const val LINE1_Y_DP: Float = 908f / 1.5f
    const val LINE2_Y_DP: Float = 940f / 1.5f
    const val LINE_SIZE_DP: Float = 24f / 1.5f

    // The Copy button, in px: x 44..194, y 964..1036, r 4, so 150 x 72 = 100 x 48 dp; label centred on 119, top 990, cap 20.
    const val COPY_X0_DP: Float = 44f / 1.5f
    const val COPY_X1_DP: Float = 194f / 1.5f
    const val COPY_Y0_DP: Float = 964f / 1.5f
    const val COPY_Y1_DP: Float = 1036f / 1.5f
    const val COPY_R_DP: Float = 4f / 1.5f
    const val COPY_LABEL_X_DP: Float = 119f / 1.5f
    const val COPY_LABEL_TOP_DP: Float = 990f / 1.5f
    const val COPY_LABEL_CAP_DP: Float = 20f / 1.5f

    // The four bottom stripes, in px: y0 1086 + i*18, 10 tall, slanted the art's twelve degrees,
    // at the x spans 0..300, 40..430, 0..220 and 250..520.
    const val STRIPE_Y0_DP: Float = 1086f / 1.5f
    const val STRIPE_STEP_DP: Float = 18f / 1.5f
    const val STRIPE_H_DP: Float = 10f / 1.5f
    const val STRIPE_SLANT_DEG: Float = 12f

    // The drawer, in px: the panel 400 wide; the mic scale 0.07; the wordmark 84/68, cap 40; the rule 132..134;
    // the four items at y 170 + i*72 with a divider 70 below each label's top, x 30..370, 1 tall; the three stripes at y0 1100 + i*18, 10 tall, at 0..260, 30..330, 0..190.
    const val DRAWER_DIM: Float = 0.55f
    const val PANEL_W_DP: Float = 400f / 1.5f
    const val DRAWER_MIC_SCALE: Float = 0.07f
    const val DRAWER_MIC_X_DP: Float = (30f - 174f * DRAWER_MIC_SCALE) / 1.5f
    const val DRAWER_MIC_Y_DP: Float = (52f - 30f * DRAWER_MIC_SCALE) / 1.5f
    const val DRAWER_WORDMARK_X_DP: Float = 84f / 1.5f
    const val DRAWER_WORDMARK_TOP_DP: Float = 68f / 1.5f
    const val DRAWER_WORDMARK_CAP_DP: Float = 40f / 1.5f
    const val DRAWER_RULE_Y0_DP: Float = 132f / 1.5f
    const val DRAWER_RULE_H_DP: Float = 2f / 1.5f
    const val ITEM_Y0_DP: Float = 170f / 1.5f
    const val ITEM_STEP_DP: Float = 72f / 1.5f
    const val ITEM_COUNT: Int = 4
    const val ITEM_LABEL_X_DP: Float = 30f / 1.5f
    const val ITEM_LABEL_TOP_DP: Float = 22f / 1.5f
    const val ITEM_CAP_DP: Float = 22f / 1.5f
    const val DIVIDER_X0_DP: Float = 30f / 1.5f
    const val DIVIDER_X1_DP: Float = 370f / 1.5f
    const val DIVIDER_H_DP: Float = 1f / 1.5f
    const val DIVIDER_OFFSET_DP: Float = 70f / 1.5f
    const val DRAWER_STRIPE_Y0_DP: Float = 1100f / 1.5f
    const val DRAWER_STRIPE_STEP_DP: Float = 18f / 1.5f
    const val DRAWER_STRIPE_H_DP: Float = 10f / 1.5f

    /** The top bar, [w] wide: the art's 108 px bar with the clock, the menu button, the logo mic, the wordmark and the accent rule. */
    fun topBar(w: Float): HomeTopBar = HomeTopBar(
        height = BAR_H_DP,
        clock = HomeText(CLOCK_X_DP, CLOCK_Y_DP, CLOCK_SIZE_DP),
        menuLines = List(MENU_LINE_COUNT) { index ->
            HomeRect(MENU_X0_DP, MENU_Y0_DP + index * MENU_STEP_DP, MENU_X1_DP - MENU_X0_DP, MENU_H_DP)
        },
        logoMic = HomeMic(LOGO_MIC_SCALE, LOGO_MIC_X_DP, LOGO_MIC_Y_DP),
        wordmark = HomeWordmark(WORDMARK_X_DP, WORDMARK_TOP_DP, WORDMARK_CAP_DP),
        accentRule = HomeRect(0f, RULE_Y0_DP, w, RULE_H_DP),
    )

    /** The on/off card: the art's rect and radius, its accent bar, title, switch and three rows 36 px apart. */
    fun statusCard(): HomeStatusCard = HomeStatusCard(
        rect = HomeRect(CARD_X0_DP, CARD_Y0_DP, CARD_X1_DP - CARD_X0_DP, CARD_Y1_DP - CARD_Y0_DP),
        radius = CARD_R_DP,
        accentBar = HomeRect(CARD_X0_DP, CARD_Y0_DP, ACCENT_BAR_W_DP, CARD_Y1_DP - CARD_Y0_DP),
        title = HomeWordmark(TITLE_X_DP, TITLE_TOP_DP, TITLE_CAP_DP),
        switch = HomeSwitch(
            track = HomeRect(SWITCH_X0_DP, SWITCH_Y0_DP, SWITCH_X1_DP - SWITCH_X0_DP, SWITCH_Y1_DP - SWITCH_Y0_DP),
            trackRadius = SWITCH_R_DP,
            knob = HomeRect(KNOB_X0_DP, KNOB_Y0_DP, KNOB_X1_DP - KNOB_X0_DP, KNOB_Y1_DP - KNOB_Y0_DP),
            knobRadius = KNOB_R_DP,
        ),
        rows = List(ROW_COUNT) { index ->
            HomeRow(
                y = ROW_Y0_DP + index * ROW_STEP_DP,
                labelX = ROW_LABEL_X_DP,
                valueX = ROW_VALUE_X_DP,
                dash = if (index < ROW_DASH_COUNT) {
                    HomeRect(ROW_DASH_X0_DP, ROW_Y0_DP + index * ROW_STEP_DP - ROW_DASH_H_DP, ROW_DASH_X1_DP - ROW_DASH_X0_DP, ROW_DASH_H_DP)
                } else {
                    null
                },
            )
        },
    )

    /** The big mic: the art's scale and its own origin. */
    fun bigMic(): HomeMic = HomeMic(MIC_SCALE, MIC_X_DP, MIC_Y_DP)

    /** The lettering over the big mic: the centred TAP TO TALK and the hint line under it. */
    fun lettering(): HomeLettering = HomeLettering(
        tap = HomeWordmark(TAP_CENTER_X_DP, TAP_TOP_DP, TAP_CAP_DP),
        hint = HomeText(HINT_X_DP, HINT_Y_DP, HINT_SIZE_DP),
    )

    /** The last-transcript box: its rect and radius, its outline, heading, time and two text lines. */
    fun transcriptBox(): HomeTranscript = HomeTranscript(
        rect = HomeRect(BOX_X0_DP, BOX_Y0_DP, BOX_X1_DP - BOX_X0_DP, BOX_Y1_DP - BOX_Y0_DP),
        radius = BOX_R_DP,
        outline = BOX_OUTLINE_DP,
        heading = HomeWordmark(HEADING_X_DP, HEADING_TOP_DP, HEADING_CAP_DP),
        time = HomeText(TIME_X_DP, TIME_Y_DP, TIME_SIZE_DP),
        lines = listOf(HomeText(LINE_X_DP, LINE1_Y_DP, LINE_SIZE_DP), HomeText(LINE_X_DP, LINE2_Y_DP, LINE_SIZE_DP)),
    )

    /** The Copy button: the art's rect and radius, 100 x 48 dp, and its centred label. */
    fun copyButton(): HomeCopyButton = HomeCopyButton(
        rect = HomeRect(COPY_X0_DP, COPY_Y0_DP, COPY_X1_DP - COPY_X0_DP, COPY_Y1_DP - COPY_Y0_DP),
        radius = COPY_R_DP,
        label = HomeWordmark(COPY_LABEL_X_DP, COPY_LABEL_TOP_DP, COPY_LABEL_CAP_DP),
    )

    /** The four bottom stripes: the art's y0 and step, 10 px tall, slanted twelve degrees. */
    fun stripes(): List<HomeStripe> {
        val spans = listOf(0f to 300f, 40f to 430f, 0f to 220f, 250f to 520f)
        return spans.mapIndexed { index, span ->
            HomeStripe(
                x = span.first / 1.5f,
                y = STRIPE_Y0_DP + index * STRIPE_STEP_DP,
                width = (span.second - span.first) / 1.5f,
                height = STRIPE_H_DP,
                slant = slantOf(STRIPE_H_DP),
            )
        }
    }

    /** The menu drawer: the art's panel width, mic, wordmark, accent rule and three stripes. */
    fun drawer(): HomeDrawer {
        val spans = listOf(0f to 260f, 30f to 330f, 0f to 190f)
        return HomeDrawer(
            panelWidth = PANEL_W_DP,
            mic = HomeMic(DRAWER_MIC_SCALE, DRAWER_MIC_X_DP, DRAWER_MIC_Y_DP),
            wordmark = HomeWordmark(DRAWER_WORDMARK_X_DP, DRAWER_WORDMARK_TOP_DP, DRAWER_WORDMARK_CAP_DP),
            accentRule = HomeRect(0f, DRAWER_RULE_Y0_DP, PANEL_W_DP, DRAWER_RULE_H_DP),
            stripes = spans.mapIndexed { index, span ->
                HomeStripe(
                    x = span.first / 1.5f,
                    y = DRAWER_STRIPE_Y0_DP + index * DRAWER_STRIPE_STEP_DP,
                    width = (span.second - span.first) / 1.5f,
                    height = DRAWER_STRIPE_H_DP,
                    slant = slantOf(DRAWER_STRIPE_H_DP),
                )
            },
        )
    }

    /** The four menu items, 72 px apart: each label's lettering and the divider under it. */
    fun menuItems(): List<HomeMenuItem> = List(ITEM_COUNT) { index ->
        HomeMenuItem(
            label = HomeWordmark(
                ITEM_LABEL_X_DP,
                ITEM_Y0_DP + index * ITEM_STEP_DP + ITEM_LABEL_TOP_DP,
                ITEM_CAP_DP,
            ),
            divider = HomeRect(
                DIVIDER_X0_DP,
                ITEM_Y0_DP + index * ITEM_STEP_DP + DIVIDER_OFFSET_DP,
                DIVIDER_X1_DP - DIVIDER_X0_DP,
                DIVIDER_H_DP,
            ),
        )
    }

    /** The art's twelve-degree slant on a stripe [height] tall, in dp. */
    private fun slantOf(height: Float): Float = tan(STRIPE_SLANT_DEG * PI / 180.0).toFloat() * height
}
