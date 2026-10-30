package dev.breaker.dictation

/**
 * Whether the phone keeps its clock set automatically.
 *
 * A phone that sets its clock itself (the network or a time server drives it)
 * cannot have jumped forward on its own: the instant it says is trustworthy, so
 * an old [lastPurgeAt] beside a new now means the rows really are old and the
 * retention purge runs as written. A phone whose clock is set by hand can jump
 * forward and fool the retention rule into seeing rows as older than they are.
 *
 * This is the seam that reads that setting. The real reading is
 * [AndroidAutomaticTimeOn]; the value is taken from `Settings.Global.AUTO_TIME`,
 * which needs no permission. Kept as a seam so the guard's rule is testable on a
 * plain JVM, where the platform setting cannot be read.
 */
fun interface AutomaticTimeOn {
    fun isOn(): Boolean
}
