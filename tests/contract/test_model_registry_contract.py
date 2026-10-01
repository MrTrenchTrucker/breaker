"""Contract test for the `shared/modules/model-registry` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import unittest

import contract_support


class SharedModulesModelRegistryContractTest(contract_support.ModuleContractTest):
    MODULE = "shared_model_registry"


if __name__ == "__main__":
    unittest.main()
