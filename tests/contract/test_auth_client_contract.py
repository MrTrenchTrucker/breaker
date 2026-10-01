"""Contract test for the `android/modules/auth-client` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import unittest

import contract_support


class AndroidModulesAuthClientContractTest(contract_support.ModuleContractTest):
    MODULE = "android_auth_client"


if __name__ == "__main__":
    unittest.main()
