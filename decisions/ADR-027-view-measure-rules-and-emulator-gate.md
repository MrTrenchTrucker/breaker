# ADR-027: App screens follow three layout rules and are proven on an Android 16 emulator before an APK ships

**Status:** accepted
**Date:** 2026-10-20
**Approved by:** Commander (Talk 8mzm2err, 2026-10-20 order)

See ADR-026 for the Home mic these rules were first broken on.

## Context
APK 7, the first build with the new Home mic (ADR-026), crashed on launch. All
of its JVM unit tests and source-text gates were green. RELAY ran it on an
Android 16 emulator and found three defects that no JVM test could see, because
each one lives in Android's layout pass or the Activity lifecycle:

1. `HomeHostView.show()` runs on every redraw. The one mic view the overlay
   module builds was added to the new view tree while it was still a child of
   the old one, so Android threw `IllegalStateException`. Fixed in main (PR
   #193): `attachMicSlot` removes the mic from its old parent first
   (`HomeHostMicParentGateTest`).
2. `SettingsLauncherActivity.settingsView` is a `lateinit`. The download
   helpers received it as an argument on every path, including first run,
   which returns before Settings is built, so the first resume threw
   `UninitializedPropertyAccessException`. Fixed in main (PR #194): `onCreate`
   sets `settingsView` to a plain View before the first-run decision
   (`SettingsViewSetBeforeFirstRunTest`).
3. `TouchGapView` (`ui/render/NodeViews.kt`) was a plain View with no
   `onMeasure`. When the page is shorter than the window, the ScrollView fills
   its viewport and measures again; the gap then took the whole AT_MOST height,
   the top bar became as tall as the window, and every row after it measured 0:
   a blank Home on a tall phone (1440x3120). Fixed in main (PR #195):
   `TouchGapView` measures `resolveSize(side, spec)` on both axes
   (`TouchGapMeasureGateTest`).

The Commander's phone test of APK 8 then showed more defects of the same kind,
which are OPEN at the time of this ADR: the X and check on the recording Home
mic are drawn but cannot be tapped (cause not found; one hypothesis is a Home
redraw during recording that re-parents the mic in the middle of a touch); the
first History card stretched to the window height; and Settings uses a narrower
content band than Home (not reproduced on the emulator, whose edges match Home; a phone screenshot is needed).

RELAY reproduced the History stretch on the emulator and found its cause, the
same fill-pass class as defect 3. A spread row places a plain `View` between
its children (`separatorOf()`, `ui/render/ScreenRenderer.kt` lines 166-167,
added at line 123) with height `WRAP_CONTENT` and no `onMeasure` of its own.
When the list is shorter than the window, the second measure pass gives it the
whole AT_MOST height: with one to three rows the first card is stretched and
the others measure 0 px. It happens on every draw of a short list, not only the
first. The fix is OPEN: it is not in main.

## Decision
**R1. The emulator gate.** JVM unit tests and source-text gates cannot see
Android layout or the lifecycle. Every UI change that touches layout, Views,
redraws or the Activity lifecycle is proven on an Android 16 emulator before an
APK goes to the Commander:
- an upgrade over the previous APK (with the first-run marker already set) and
  a fresh install;
- window heights 1500, 1800, 2000 and 2400 px, and 1440x3120 at density 560;
- background and foreground, rotation, and dark mode;
- the pass condition each time: the full Home screen draws and nothing crashes.

RELAY owns the emulator (container `emu36`, AVD `t36`, API 36 `google_apis`
x86_64). It lives outside this repository. The emulator cannot record audio
(no speech model, and the native libraries are arm64 only), so the recording
states of the mic are proven through a hook in the app's `debug` source set
that pushes a state into the mic. That hook is PLANNED: it does not exist in
main yet (`android/app/src/debug` holds only the keyboard probe). Until it
exists, the recording states are proven on a real phone only.

**R2. Views measure themselves and survive a redraw.**
- Every custom View in the `ui` render layer (`ui/render/`) overrides
  `onMeasure` and measures from its own size with `resolveSize`; none relies on
  the default `View.onMeasure`. A plain `View(context)` used as a spacer or
  separator gets a fixed size in its layout parameters, never `WRAP_CONTENT`
  or `MATCH_PARENT` along the axis a fill pass can stretch. Instances so far:
  `TouchGapView` (defect 3, fixed in main), and the spread-row separator
  `separatorOf()` in `ScreenRenderer.kt` (the History stretch, OPEN). Also
  OPEN at main: `CardLipView` (`ui/render/BrandArtViews.kt`) is the one View
  subclass under `ui/render/` with no `onMeasure` of its own, and
  `ScreenRenderer.kt` builds further plain `View(context)` objects (lines 160,
  235 and 321) that the audit below must check. `placedChild` (lines 135-139)
  also builds a horizontal Spacer as a plain `View` of a fixed width and
  `WRAP_CONTENT` height: the same class as `separatorOf()`, so it is on the audit
  list too (not yet shown safe or unsafe).
- A View that is built outside a draw and re-added on a redraw is removed from
  its old parent first (the `attachMicSlot` rule, defect 1).
- An argument or property that may be unset on some path (`lateinit`, `lazy`)
  is never passed eagerly (defect 2).
- A redraw never happens in the middle of a touch gesture on a view that gets
  re-parented. Whether this is the cause of the untappable X and check is OPEN.

**R3. One gutter, one place.** System-bar and cutout insets are margins on a
screen's root view; the 16 dp gutter is padding; both are applied once
(`wiring/Insets.kt` `layoutFor`, `wiring/AndroidInsets.kt`). Home, Settings,
History, How it works, About and First run all use the same content edge. OPEN
at main: Settings is narrower than Home on the phone (not reproduced on the emulator), a hypothesis being that `SettingsLauncherActivity`
wraps the host in its own column and ScrollView that also get insets, while the
host applies its own gutter.

## Reasons
- Three launch defects, a blank Home and a stretched History card passed every
  JVM gate. A gate that has
  never seen the layout pass cannot report a layout bug; the emulator can.
- Each R2 rule is the general form of one defect already seen, so the next View
  or redraw is checked against the failure, not only the one line that was
  fixed.
- The release check stays cheap: the emulator runs on RELAY's box, not the
  Commander's phone, and the phone test is kept for what an emulator cannot do
  (audio, real touch feel).

## Consequences
Rules in: an emulator run, with the heights, install modes and lifecycle
events above, as a release step for every UI change of that kind, recorded in
the change's evidence; a `debug`-only state hook for the mic (PLANNED);
an audit of every View subclass and every plain `View(context)` in the `ui`
render layer for its measure (`onMeasure` with `resolveSize`, or a fixed size),
starting with the spread-row separator and `CardLipView`; one place for insets
and gutter on every screen.

Rules out: an APK going to the Commander for a layout or lifecycle change on
the strength of JVM tests alone; a custom View that uses the default measure;
re-adding a view without detaching it; a second gutter or inset applied by an
outer wrapper.
