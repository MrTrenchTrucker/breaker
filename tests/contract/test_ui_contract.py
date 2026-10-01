"""Contract test for the `android/ui` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import unittest

import contract_support


class AndroidUiContractTest(contract_support.ModuleContractTest):
    MODULE = "android_ui"


if __name__ == "__main__":
    unittest.main()
