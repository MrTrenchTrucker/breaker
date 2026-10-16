package dev.breaker.dictation.ui.controls

/** The ten view files, by their path relative to this module's package. */
internal val VIEW_FILES = listOf(
    "render/controls/Button3dView.kt",
    "render/controls/NeutralButtonView.kt",
    "render/controls/OutlinedButtonView.kt",
    "render/controls/DeleteButtonView.kt",
    "render/controls/FullWidthButtonView.kt",
    "render/controls/SwitchView.kt",
    "render/controls/BadgeView.kt",
    "render/controls/TextFieldView.kt",
    "render/controls/StepMarksView.kt",
    "render/controls/DownloadBarView.kt",
)

/** The plain-Kotlin files of the kit, by their path relative to this module's package. */
internal val PLAIN_FILES = listOf(
    "controls/ControlsFactors.kt",
    "controls/ControlsGeometry.kt",
    "controls/ControlsPalette.kt",
    "controls/DownloadBarMath.kt",
)

/** The display-only views, by their paths: they take no touch, so they have no floor to stand at. */
internal val DISPLAY_ONLY = listOf(
    "render/controls/BadgeView.kt",
    "render/controls/DownloadBarView.kt",
)

/** The views that draw what the kit's plain math returns, rather than ControlsGeometry. */
internal val MATH_DRAWN = listOf(
    "render/controls/DownloadBarView.kt",
)
