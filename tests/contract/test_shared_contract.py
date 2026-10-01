"""Contract test for the `shared` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code. It also pins the shared card's second invariant: the
contract and registry files live only in their own module folders, so a stray
OpenAPI spec or model registry data file anywhere else is a repo error.
"""
import os
import unittest

import contract_support


class SharedContractTest(contract_support.ModuleContractTest):
    MODULE = "shared"

    def test_contract_and_registry_files_live_only_in_their_modules(self):
        """An OpenAPI spec outside shared/modules/api-contracts, or a model
        registry data file outside shared/modules/model-registry, is a repo
        error — the shared card's Invariants say both live only here.

        The walk excludes .git, build/, .gradle/ and .kotlin/ (version control
        and build state, never repo source). Nothing in the tree today is
        named like an offender: the only y*ml files are the .github issue
        templates and workflow, which match neither pattern.
        """
        offenders = []
        for root, dirs, files in os.walk(contract_support.ROOT):
            dirs[:] = [
                d for d in dirs
                if d not in (".git", "build", ".gradle", ".kotlin")
            ]
            for name in files:
                rel = os.path.relpath(os.path.join(root, name),
                                      contract_support.ROOT)
                if name.startswith("openapi") and (
                    name.endswith(".yml") or name.endswith(".yaml")
                ):
                    if not rel.startswith("shared/modules/api-contracts/"):
                        offenders.append(
                            f"{rel} — an OpenAPI spec belongs in "
                            f"shared/modules/api-contracts"
                        )
                elif name == "models.yaml":
                    if not rel.startswith("shared/modules/model-registry/"):
                        offenders.append(
                            f"{rel} — the model registry data file belongs in "
                            f"shared/modules/model-registry"
                        )
        assert not offenders, (
            "contract and registry files live only in their own module "
            "folders; found outside them:"
            + "".join(f"\n  - {o}" for o in offenders)
        )


if __name__ == "__main__":
    unittest.main()
