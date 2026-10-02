"""Contract test for the `shared/modules/model-registry` module.

The structural half is inherited (registry entry, card, Gradle wiring,
declared dependencies). This file adds the behavioural contract ADR-016
rules in: the committed Kotlin must be exactly what the generator produces
from the committed models.yaml, so the hand-edited source of record and the
app's constants cannot drift apart.
"""
import os
import sys
import tempfile
import unittest

import contract_support

_TOOLS = os.path.join(contract_support.ROOT, "tools")
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

import gen_model_registry  # noqa: E402


class SharedModulesModelRegistryContractTest(contract_support.ModuleContractTest):
    MODULE = "shared_model_registry"

    def test_committed_kotlin_is_exactly_what_the_generator_produces(self):
        """ADR-016 rule 3: a Python test regenerates the file and fails if the
        committed copy differs, so models.yaml and the app cannot drift.

        The generator runs against the committed models.yaml and renders to a
        temporary path (the committed file is never overwritten by the test);
        a hand edit to the generated Kotlin — or a models.yaml edit whose
        rerun was not committed — changes its bytes and fails this.
        """
        models_yaml = os.path.join(
            contract_support.ROOT, "shared", "modules", "model-registry",
            "models.yaml")
        committed = os.path.join(
            contract_support.ROOT, "shared", "modules", "model-registry",
            "src", "main", "kotlin", "dev", "breaker", "shared", "models",
            "ModelRegistry.kt")
        assert os.path.isfile(models_yaml), "models.yaml is missing"
        assert os.path.isfile(committed), "the generated Kotlin is missing"
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".kt", delete=False
        ) as fh:
            regen_path = fh.name
        try:
            entries, _ = gen_model_registry.generate(
                models_yaml_path=models_yaml, output_path=regen_path)
            assert entries, "models.yaml carried no entry"
            with open(committed, "r", encoding="utf-8") as fh:
                committed_text = fh.read()
            with open(regen_path, "r", encoding="utf-8") as fh:
                regen_text = fh.read()
            assert committed_text == regen_text, (
                "the committed ModelRegistry.kt differs from what "
                "tools/gen_model_registry.py produces from the committed "
                "models.yaml — edit models.yaml, rerun the generator and "
                "commit both (ADR-016)")
        finally:
            os.unlink(regen_path)


if __name__ == "__main__":
    unittest.main()
