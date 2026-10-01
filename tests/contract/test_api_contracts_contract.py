"""Contract test for the `shared/modules/api-contracts` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import unittest

import contract_support


class SharedModulesApiContractsContractTest(contract_support.ModuleContractTest):
    MODULE = "shared_api_contracts"


if __name__ == "__main__":
    unittest.main()
