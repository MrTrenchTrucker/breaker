package dev.breaker.dictation

import android.content.ContentResolver
import android.provider.Settings

/**
 * The real [AutomaticTimeOn]: it reads `Settings.Global.AUTO_TIME` over the
 * app's [ContentResolver].
 *
 * `AUTO_TIME` is `1` when the system sets the clock from the network or a time
 * server and `0` when the user set it by hand; the default is `1`, the network
 * clock a phone carries by default. Reading it needs no permission, so this is
 * one platform call and nothing else. A resolver that cannot answer is read as
 * automatic time on: the default is the safe one for the retention rule, because
 * suspecting a jump only ever *delays* a purge, and a false "manual" would delay
 * one it did not need to.
 */
class AndroidAutomaticTimeOn(private val resolver: ContentResolver) : AutomaticTimeOn {
    override fun isOn(): Boolean = Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 1) == 1
}
