"""Contract test for the `android/modules/updater` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.

The module's card also carries the facts the install path, the rollback offer,
the key list and the daily timer depend on. Those facts are checked here by
name, so a card that drops one of them fails this file.
"""
import os
import unittest

import contract_support

ROOT = contract_support.ROOT
UPDATER_CARD = "android/modules/updater/AGENTS.md"
APP_CARD = "android/app/AGENTS.md"

# Each check: (name, phrases the card must carry, phrases it must not carry).
CARD_CHECKS = (
    (
        "install session",
        ("PackageInstaller", "not exported", "MUTABLE", "abandon",
         "before the session is opened", "API 33"),
        ("FileProvider",),
    ),
    (
        "rollback shape",
        ("four numeric parts", "1.2.10.0", "1.2.9.0", "a higher version code",
         "Roll back"),
        ("keep previous APK", "Rollback restores the previous version"),
    ),
    (
        "key list",
        ("breakerUpdateKeysFile", "BREAKER_UPDATE_KEYS_FILE", "update_keys.txt",
         "never committed", "passes and pins no key",
         "the release build fails with a fixed sentence"),
        ("breaker" ".updateKeysFile", "without a key file fails",
         "fails when it is absent or has no key line"),
    ),
    (
        "daily timer",
        ("86_400_000", "3_600_000", "no WorkManager"),
        (),
    ),
    (
        "admin answer",
        ("isAdmin", "never imports auth-client"),
        ("(from `auth-client`)",),
    ),
    (
        "built state",
        ("built, not tried on a phone", "NOT VERIFIED on a device"),
        ("Nothing else in this module is built", "is the target design, planned, not built"),
    ),
)

# The app card must name the seventh permission in its permission invariant.
APP_CARD_NEEDED = ("REQUEST_INSTALL_PACKAGES", "(seven)")
APP_CARD_BANNED = ("(six)",)

# The module's public types, which the registry entry must list.
REGISTRY_PUBLIC = (
    "UpdateChecker", "Updater", "buildUpdater", "UpdaterConfig", "ApkInstaller",
    "InstallResult", "InstallReasons", "TimerScheduler", "UpdateTimes",
)


def card_gaps(card):
    """Map each failing check name to what the card lacks or still says wrongly."""
    gaps = {}
    for name, needed, banned in CARD_CHECKS:
        problems = [f"missing {p!r}" for p in needed if p not in card]
        problems += [f"still says {p!r}" for p in banned if p in card]
        if problems:
            gaps[name] = problems
    return gaps


def app_card_gaps(card):
    problems = [f"missing {p!r}" for p in APP_CARD_NEEDED if p not in card]
    problems += [f"still says {p!r}" for p in APP_CARD_BANNED if p in card]
    return problems


def registry_public_gaps(public):
    tokens = {t.strip() for t in public.split(",")}
    return [f"missing {name!r}" for name in REGISTRY_PUBLIC if name not in tokens]


class AndroidModulesUpdaterContractTest(contract_support.ModuleContractTest):
    MODULE = "android_updater"

    def _read(self, rel):
        with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
            return fh.read()

    def _assert_card_check(self, name):
        gaps = card_gaps(self._read(UPDATER_CARD))
        self.assertEqual(gaps.get(name, []), [],
                         f"{UPDATER_CARD} fails the '{name}' check")

    def test_card_install_session(self):
        self._assert_card_check("install session")

    def test_card_rollback_shape(self):
        self._assert_card_check("rollback shape")

    def test_card_key_list(self):
        self._assert_card_check("key list")

    def test_card_daily_timer(self):
        self._assert_card_check("daily timer")

    def test_card_admin_answer(self):
        self._assert_card_check("admin answer")

    def test_card_built_state(self):
        self._assert_card_check("built state")

    def test_app_card_names_the_install_permission(self):
        self.assertEqual(app_card_gaps(self._read(APP_CARD)), [],
                         f"{APP_CARD} does not name the install permission")

    def test_registry_public_list_names_the_module_types(self):
        self.assertEqual(registry_public_gaps(self.entry["public"]), [],
                         "modules.toml [module.android_updater] public list")


if __name__ == "__main__":
    unittest.main()
