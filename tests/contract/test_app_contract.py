"""Contract test for the `android/app` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import unittest

import contract_support
from app_sync_wiring_pins import AppSyncWiringPinsTest  # noqa: F401  (sync wiring pins, collected here)


class AndroidAppContractTest(contract_support.ModuleContractTest):
    MODULE = "android_app"


if __name__ == "__main__":
    unittest.main()
