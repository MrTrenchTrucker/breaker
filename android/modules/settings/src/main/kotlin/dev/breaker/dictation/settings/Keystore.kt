package dev.breaker.dictation.settings

/**
 * The port that owns the credential *reference* the settings point at.
 *
 * A settings file holds nine plain values and no `api_key` key, so the
 * reference has to live somewhere else — here. That is what this port is for
 * here and now: [setActiveRef] and [activeRef] are how a reference survives a
 * restart, and no file is involved on either side of that. [activeRef] is asked
 * on *every* [SettingsFileStore.load] path, missing file included, which is
 * what makes the reference recoverable even when there is no file to recover it
 * from — `SettingsPersistenceTest.a missing file still reports the reference
 * the port remembers` and `an empty file still reports the reference the port
 * remembers` are the proof.
 * [SettingsFileStore.save] is the only caller that moves it.
 * [SettingsFileStore.save] calls [setActiveRef] *after* the file has been
 * written and closed, so a save that throws cannot leave the port advertising a
 * reference that was never stored — `SettingsPersistenceTest` proves both the
 * propagation and the ordering.
 *
 * **This port cannot receive or return a secret.** There is no method here that
 * takes one or hands one back; only a reference moves through it. The component
 * that would hold a secret and resolve a reference against it — the device
 * Keystore — is **not implemented and not verified yet**, and nothing here
 * should be read as a claim that a key is safe on the device. The tests that
 * would prove that come with that work.
 *
 * **No class under `src/main` implements this port yet.**
 * `SettingsNoFallbackTest` proves that: it builds the settings store over a
 * fake port and asserts that the production source tree contains no
 * implementation, so a store cannot be constructed without a reference port
 * supplied. There is no default, no internal stand-in and no no-argument
 * store constructor that would quietly skip the reference path.
 */
interface Keystore {

    /**
     * Remember which reference the settings currently point at; null clears it.
     *
     * This is where the reference is persisted. The settings file never
     * carries it, so a store that forgot to call this would drop the
     * credential pointer on every save — `SettingsKeystoreRefTest` is the
     * proof that the call happens and that a null really does clear.
     */
    fun setActiveRef(ref: String?)

    /** The reference the settings currently point at, or null when none is remembered. */
    fun activeRef(): String?
}
