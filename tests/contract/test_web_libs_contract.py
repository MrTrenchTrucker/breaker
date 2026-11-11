"""Contract test for the `shared/modules/web-libs` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the pin table in
web_libs_pins.py.
"""
import unittest

import contract_support


class SharedModulesWebLibsContractTest(contract_support.ModuleContractTest):
    MODULE = "shared_web_libs"


from web_libs_pins import WebLibsPinsTest  # noqa: F401  (lib pin table, collected here)
from web_libs_pins import LibraryFolderProblemsTempTreeTest  # noqa: F401  (the folder check on temporary trees, collected here)
from weblibs_copy_mapping import WebLibsCopyMappingTest  # noqa: F401  (copy task vs helper table, collected here)


if __name__ == "__main__":
    unittest.main()
