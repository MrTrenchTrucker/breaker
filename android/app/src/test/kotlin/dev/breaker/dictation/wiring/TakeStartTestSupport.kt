package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow

/**
 * The window and recorder both take-start test files drive. The production shape: the composition
 * root adapts BOTH members to the commit module. Shared so [TakeStartFunnelTest] and
 * [TakeStartRaceTest] cannot drift apart from each other or from that shape.
 *
 * UNCOMPILED: no JVM/Gradle/kotlinc in this environment. No test was run; no RED/GREEN is claimed.
 */
internal val takeStartTestWindow: TakeStartWindow =
    TakeStartWindow(windowId = 77, packageName = "com.stale.field")

internal val takeStartTestRecorder: TakeStartRecorder = object : TakeStartRecorder {
    override fun capture() {
        TakeStartAnchor.capture(takeStartTestWindow)
    }

    override fun clear() {
        TakeStartAnchor.clear()
    }
}
