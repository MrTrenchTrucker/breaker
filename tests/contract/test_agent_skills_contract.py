"""Contract test for the `agent_skills` module.

On top of the structural contract every module carries, this pins the four
invariants the module card states: each skill file matches its hash in
`SKILLS.sha256`, each skill sits in a folder named after its own front-matter
`name`, each rank holds exactly one module SOP skill and one readback skill,
and the `shared` folder holds only skills that no single rank owns.
"""
import hashlib
import os
import re
import unittest

import contract_support

MODULE_DIR = os.path.join(contract_support.ROOT, "agent-skills")
RANKS = ("project-leader", "it-manager", "worker")
SHARED = "shared"
RANK_KINDS = ("module-sop", "parrot-protocol")


def _shipped_skills():
    """Every SKILL.md under the module, as paths relative to the module."""
    found = []
    for dirpath, _dirs, files in os.walk(MODULE_DIR):
        if "SKILL.md" in files:
            found.append(os.path.relpath(os.path.join(dirpath, "SKILL.md"), MODULE_DIR).replace(os.sep, "/"))
    return sorted(found)


def _pins():
    pins = {}
    with open(os.path.join(MODULE_DIR, "SKILLS.sha256"), encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            digest, rel = line.split(None, 1)
            pins[rel] = digest
    return pins


def _front_matter_name(rel):
    with open(os.path.join(MODULE_DIR, rel), encoding="utf-8") as fh:
        text = fh.read()
    block = re.match(r"---\n(.*?)\n---\n", text, re.S)
    assert block, f"agent-skills/{rel} has no YAML front matter"
    name = re.search(r"^name:\s*(\S+)\s*$", block.group(1), re.M)
    assert name, f"agent-skills/{rel} front matter has no name"
    return name.group(1)


class AgentSkillsContractTest(contract_support.ModuleContractTest):
    MODULE = "agent_skills"

    def test_every_skill_matches_its_pinned_hash(self):
        pins = _pins()
        shipped = _shipped_skills()
        self.assertEqual(
            sorted(pins), shipped,
            "SKILLS.sha256 and the skill files on disk list different skills; "
            "pin every shipped skill and nothing else",
        )
        for rel in shipped:
            with open(os.path.join(MODULE_DIR, rel), "rb") as fh:
                digest = hashlib.sha256(fh.read()).hexdigest()
            self.assertEqual(
                digest, pins[rel],
                f"agent-skills/{rel} no longer matches its pinned hash; skills "
                f"are copied from their source, never edited in place",
            )

    def test_every_skill_sits_in_a_folder_named_after_itself(self):
        for rel in _shipped_skills():
            folder = rel.split("/")[-2]
            self.assertEqual(
                _front_matter_name(rel), folder,
                f"agent-skills/{rel}: front-matter name does not match its "
                f"folder '{folder}'; an agent looking the skill up by name "
                f"will not find it",
            )

    def test_each_rank_has_one_module_sop_and_one_readback_skill(self):
        by_folder = {}
        for rel in _shipped_skills():
            folder, skill = rel.split("/")[0], rel.split("/")[1]
            by_folder.setdefault(folder, []).append(skill)
        self.assertEqual(
            sorted(by_folder), sorted(RANKS + (SHARED,)),
            "agent-skills must hold exactly the three rank folders and the shared folder",
        )
        for rank in RANKS:
            skills = by_folder[rank]
            kinds = sorted(s.split("-")[0] + "-" + s.split("-")[1] for s in skills)
            self.assertEqual(
                kinds, sorted(RANK_KINDS),
                f"agent-skills/{rank} holds {sorted(skills)}; each rank needs "
                f"exactly one module-sop-* and one parrot-protocol-* skill",
            )

    def test_shared_folder_holds_no_rank_skill(self):
        for rel in _shipped_skills():
            folder, name = rel.split("/")[0], rel.split("/")[1]
            if folder != SHARED:
                continue
            self.assertFalse(
                name.startswith(tuple(kind + "-" for kind in RANK_KINDS)),
                f"agent-skills/{SHARED}/{name} is a rank skill; module-sop-* and "
                f"parrot-protocol-* skills belong in their rank's folder",
            )


if __name__ == "__main__":
    unittest.main()
