"""Contract test for the `shared` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code. It also pins the shared card's second invariant: the
contract and registry files live only in their own module folders, so a stray
OpenAPI spec or model registry data file anywhere else is a repo error.
"""
import unittest

import contract_strays
import contract_support


class SharedContractTest(contract_support.ModuleContractTest):
    MODULE = "shared"

    def test_contract_and_registry_files_live_only_in_their_modules(self):
        """An OpenAPI spec outside shared/modules/api-contracts, or a model
        registry data file outside shared/modules/model-registry, is a repo
        error — the shared card's Invariants say both live only here.

        The rule itself lives in contract_strays.find_contract_strays (a
        small function the unit tests can call on a temp tree); this method
        applies it to the real root. The walk excludes .git, build/, .gradle/
        and .kotlin/ (version control and build state, never repo source).
        Nothing in the tree today is named like an offender: the only y*ml
        files are the .github issue templates and workflow, which match
        neither pattern.
        """
        offenders = contract_strays.find_contract_strays(contract_support.ROOT)
        assert not offenders, (
            "contract and registry files live only in their own module "
            "folders; found outside them:"
            + "".join(f"\n  - {o}" for o in offenders)
        )


if __name__ == "__main__":
    unittest.main()
