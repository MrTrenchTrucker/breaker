"""Contract test for the `server/modules/whisper-server` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import unittest

import contract_support


class ServerModulesWhisperServerContractTest(contract_support.ModuleContractTest):
    MODULE = "server_whisper"


if __name__ == "__main__":
    unittest.main()
