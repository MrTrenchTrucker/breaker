package dev.breaker.dictation.wiring

import android.content.pm.ApplicationInfo

/** One pure decision: is sync allowed in this build.
 *
 * Reads the runtime debuggable flag (ApplicationInfo.FLAG_DEBUGGABLE), never the
 * manifest attribute (ManifestGateTest refuses android:debuggable). Plain JVM:
 * an Int in, a Boolean out. Tests pass raw flag ints.
 *
 * applicationInfo.flags on the Application holds FLAG_DEBUGGABLE (2) exactly
 * when the build is debuggable; any other value means release. */
internal fun syncAllowed(flags: Int): Boolean = (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
