"""Shared machinery for the Breaker module contract tests.

Every registered module in `modules.toml` owns exactly one contract test under
`tests/contract/`. A contract test does not test behaviour — the module's own
unit tests do that. It tests the *structural* contract: that the module is
registered, that its card and README exist and describe it accurately, that
the card's stated dependencies match the registry, and that the Gradle build
wires the module in the way the registry says it should.

That makes the skeleton self-policing: if a module is added to the tree
without a registry entry, or a build file is deleted, or a module starts
depending on a sibling it does not own, these tests fail loudly and say why.

Every guarantee here is checked in BOTH directions, because a check that only
runs one way passes vacuously when the thing it protects is absent:

* the Gradle boundary is a BIJECTION against the registry's `depends_on` — a
  build file may not name a project the registry forbids, and may not omit one
  the registry requires. There is ONE exemption in the "may not omit" half: a
  target that applies only the `base` plugin publishes no artifact, so a code
  module's build file may omit the `project(":…")` edge to it. Which targets
  are base-only is decided from the build file by `_applies_artifact_plugin`;
  the exemption is the plugin-based skip, not a blanket one;
* `settings.gradle.kts` includes exactly the registered modules, no more and no
  fewer, so the include list genuinely derives from `modules.toml`;
* `tests/contract/` holds exactly one contract test per registered module and
  no test without one, so a module cannot lose its contract test unnoticed.

A check that cannot fail proves nothing, so when you touch one of these, break
the thing it protects on purpose and watch it fail.

Run them with:

    python3 -m pytest tests/contract
"""
import os
import re
import sys
import tomllib
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# The sections every module card must carry, per the repo's own definition of
# done. Kept in step with tools/check_repo.py.
REQUIRED_CARD_SECTIONS = [
    "## Purpose",
    "## Owns",
    "## Does Not Own",
    "## Public Interface",
    "## Depends On",
    "## Invariants",
    "## Test Locations",
    "## Test Requirement",
    "## Known Gotchas",
]


def _load_registry():
    with open(os.path.join(ROOT, "modules.toml"), "rb") as fh:
        return tomllib.load(fh)["module"]


def _card_text(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
        return fh.read()


def _heading_indexes(card):
    """The indexes of the card's `## ` heading lines, in order.

    A heading is a line that opens with `## ` — never a `### ` sub-heading —
    and sits OUTSIDE a fenced code block: a card may show a section layout in
    a fenced example, and that example is content, not a declaration. A fence
    is a line that opens with ``` (the form every card uses); it closes at
    the next such line.
    """
    headings = []
    in_fence = False
    for i, line in enumerate(card.splitlines()):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if not in_fence and line.startswith("## ") and not line.startswith("### "):
            headings.append(i)
    return headings


def _section_heading_count(card, heading):
    """How many `## ` headings carry the NAME of `heading`.

    Matches the NAME at the start of the heading text with a word boundary
    (so "## Owns" is not "## Ownership notes"), never counts a `### `
    sub-heading, and never counts a heading inside a fenced code block.
    """
    name = heading[3:]  # strip the leading "## "
    lines = card.splitlines()
    count = 0
    for i in _heading_indexes(card):
        head = lines[i][3:]
        if head == name or (
            head.startswith(name)
            and not (head[len(name)].isalnum() or head[len(name)] == "_")
        ):
            count += 1
    return count


def _card_section(card, heading):
    """Body of a `## heading` section (the heading line through the next `## `).

    Line-anchored: the heading must be the NAME at the start of a `## ` line —
    a `### ` sub-heading is content, not a section, and a prose mention is not
    a heading — so "## Does Not Own business logic…" is the Does Not Own
    section while "## Ownership notes" is not. A heading inside a fenced code
    block is an example, not a section, and is never counted. A repeated
    heading is a broken card, not a first-wins read: if the name opens more
    than one `## ` line this fails loudly rather than silently returning the
    first body. An absent section still returns "" (absence is the presence
    check's job, not the reader's).
    """
    name = heading[3:]  # strip the leading "## "
    lines = card.splitlines()
    heading_lines = _heading_indexes(card)
    matches = []
    for i in heading_lines:
        head = lines[i][3:]
        if head == name or (
            head.startswith(name)
            and not (head[len(name)].isalnum() or head[len(name)] == "_")
        ):
            matches.append(i)
    if not matches:
        return ""
    if len(matches) != 1:
        raise AssertionError(
            f"{heading!r} opens {len(matches)} sections in this card — a "
            "repeated heading is a broken card, not a first-wins read"
        )
    start = matches[0]
    end = len(lines)
    for j in heading_lines:
        if j > start:
            end = j
            break
    return "\n".join(lines[start:end])


def _gradle_path(path):
    return ":" + path.replace("/", ":")


def _settings_includes():
    with open(os.path.join(ROOT, "settings.gradle.kts"), encoding="utf-8") as fh:
        text = fh.read()
    return set(re.findall(r'include\("(:[^"]+)"\)', text))


def _project_dependencies(rel):
    """Gradle project paths a module's build file declares as dependencies."""
    with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
        return set(re.findall(r'project\("(:[^"]+)"\)', fh.read()))


# ── what may be depended on ──────────────────────────────────────────────
#
# The registry's `depends_on` mixes two kinds of edge, and the boundary check
# has to tell them apart or it demands the impossible.
#
# A CONTAINER is a module that exists only to group other modules: `android`,
# `server`, `shared`, and the `modules` level beneath each. Its build file
# applies `base`, which produces no artifact — there is no jar for anyone to
# link against — and its own `depends_on` lists other containers. Containers
# are the registry's way of saying "this subtree belongs together", not "this
# code calls that code". They are therefore never REQUIRED as a dependency.
#
# A CODE module applies an artifact-producing plugin (`android.application`,
# `android.library`, `kotlin.jvm` + `java-library`, …) and compiles real
# sources. When a code module's registry entry names a code module, the
# consumer has to reach the sibling's classes, so the edge must be real in the
# build file too — `project(":…")`. That is the edge whose absence used to
# pass silently, leaving a module that cannot compile wired to nothing.
#
# Both facts come from data the repo already carries: an aggregate from the
# registry's own `path` values (a container is a strict path prefix of another
# module), and an artifact-producing status from the module's build file.


def _is_container(path, registry):
    """True when `path` only groups other registered modules."""
    return any(
        other["path"].startswith(path.rstrip("/") + "/")
        for other in registry.values()
        if other["path"] != path
    )


def _applies_artifact_plugin(rel):
    """True when the build file applies a plugin that produces an artifact.

    Only `base` does not count: it configures lifecycle tasks only, so the
    project publishes nothing a sibling could link against. Everything else in
    the `plugins` block — including the bare `java-library` and `kotlin`
    markers — is treated as artifact-producing, which is the safe direction:
    an unrecognised plugin makes a module look like a code module, so an
    omission is caught rather than waved through.
    """
    if not os.path.isfile(os.path.join(ROOT, rel)):
        return False
    with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
        text = fh.read()
    block = re.search(r"^plugins\s*\{(.*?)^\}", text, re.S | re.M)
    if not block:
        return False
    ids = set()
    for line in block.group(1).splitlines():
        line = re.sub(r"//.*$", "", line).strip()
        if line:
            ids.update(p for p in re.split(r"[^A-Za-z0-9_.\-]+", line) if p)
    return bool(ids - {"base"})


class ModuleContractTest(unittest.TestCase):
    """Structural contract shared by every module's contract test.

    Subclasses set MODULE to their key in modules.toml and inherit the whole
    structural contract, so no stub repeats it. Deriving from
    unittest.TestCase means `python3 -m pytest tests/contract` collects the
    subclasses under their own class names.
    """

    MODULE = None

    def setUp(self):
        if self.MODULE is None:
            raise AssertionError("contract test does not name its module")
        self.registry = _load_registry()
        assert self.MODULE in self.registry, (
            f"modules.toml has no [module.{self.MODULE}] entry — a module "
            f"folder exists in the tree but was never registered"
        )
        self.entry = self.registry[self.MODULE]
        self.path = self.entry["path"]
        self.folder = os.path.join(ROOT, self.path)

    # ── registry and tree agree ──────────────────────────────────────────
    def test_module_folder_exists_with_card_and_readme(self):
        assert os.path.isdir(self.folder), f"module folder missing: {self.path}"
        assert os.path.isfile(os.path.join(self.folder, "AGENTS.md")), (
            f"{self.path}/AGENTS.md missing — the module card is required"
        )
        assert os.path.isfile(os.path.join(self.folder, "README.md")), (
            f"{self.path}/README.md missing"
        )

    def test_card_carries_every_required_section(self):
        card = _card_text(self.entry["card"])
        missing = [s for s in REQUIRED_CARD_SECTIONS if s not in card]
        assert not missing, (
            f"{self.entry['card']} is missing required section(s): "
            + ", ".join(missing)
        )

    def test_card_carries_each_required_section_exactly_once(self):
        """Each required section name appears exactly ONCE as a `## ` heading.

        A repeated `## Invariants` / `## Does Not Own` (or a renamed section) is
        a broken card, not a first-wins read: a repeated heading means the
        reader would silently take the first and the second goes unread. This
        matches the NAME at the START of the heading text (so
        "## Does Not Own business logic…" counts as Does Not Own — the text on
        the heading line does not change which section it is), with a word
        boundary (so "## Owns" is not "## Ownership notes"), and never counts a
        "### " sub-heading as a "## " section.
        """
        card = _card_text(self.entry["card"])
        problems = []
        for sec in REQUIRED_CARD_SECTIONS:
            count = _section_heading_count(card, sec)
            if count != 1:
                problems.append(f"{sec[3:]} x{count}")
        assert not problems, (
            f"{self.entry['card']} does not carry each required section exactly "
            f"once as a '## ' heading: " + ", ".join(problems)
            + " — a required section is missing or repeated"
        )

    def test_card_does_not_own_what_it_declares_it_does_not_own(self):
        """Owns and Does Not Own must not name the same thing."""
        card = _card_text(self.entry["card"])
        owns = _card_section(card, "## Owns").lower()
        not_owns = _card_section(card, "## Does Not Own").lower()
        for line in not_owns.splitlines():
            line = line.strip(" -\t")
            # Skip the section header and any bullet that only names a registry
            # key rather than a responsibility.
            if not line or "(" in line and ")" in line and "registered in" in line:
                continue
            if len(line) < 8:
                continue
            head = re.split(r"[.;:,]", line)[0].strip(" -*")
            if len(head) >= 8 and head in owns:
                raise AssertionError(
                    f"{self.entry['card']} lists '{head}' under both Owns and "
                    f"Does Not Own — the module's boundary is ambiguous"
                )

    def test_declared_dependencies_are_registered_modules(self):
        for dep in self.entry.get("depends_on", []):
            assert dep in self.registry, (
                f"[module.{self.MODULE}] depends_on '{dep}', which is not a "
                f"registered module"
            )

    def test_card_dependency_list_matches_the_registry(self):
        """The card's Depends On list must be the registry's depends_on."""
        card = _card_text(self.entry["card"])
        section = _card_section(card, "## Depends On")
        listed = set(re.findall(r"^- (\w+)", section, re.M))
        # A card with no dependencies may say so in words rather than leaving
        # the list empty; both spellings mean the same thing.
        listed.discard("none")
        expected = set(self.entry.get("depends_on", []))
        assert listed == expected, (
            f"{self.entry['card']} Depends On {sorted(listed) or ['(none)']} but "
            f"modules.toml says {sorted(expected) or ['(none)']} — keep the card "
            f"and the registry in step"
        )

    def test_card_names_this_contract_test(self):
        """A module card that stops naming its contract test is a broken card."""
        card = _card_text(self.entry["card"])
        section = _card_section(card, "## Test Locations")
        rel = os.path.relpath(os.path.abspath(sys.modules[type(self).__module__].__file__), ROOT)
        rel = rel.replace(os.sep, "/")
        assert rel in section, (
            f"{self.entry['card']} does not name this contract test ({rel}) "
            f"under Test Locations"
        )

    # ── the Gradle skeleton matches the registry ─────────────────────────
    def test_module_is_included_in_the_gradle_build(self):
        assert _gradle_path(self.path) in _settings_includes(), (
            f"settings.gradle.kts does not include '{_gradle_path(self.path)}' — "
            f"every registered module must be wired into the build"
        )

    def test_module_has_a_build_file(self):
        rel = os.path.join(self.path, "build.gradle.kts")
        assert os.path.isfile(os.path.join(ROOT, rel)), (
            f"{rel} missing — every registered module needs a build file"
        )

    def test_gradle_dependencies_stay_inside_the_registered_boundary(self):
        """Build files may only depend on modules the registry allows.

        This is the mechanical half of the no-cross-module-imports rule: a
        module may reference a sibling only when `depends_on` says so. Set
        inclusion only — the converse direction lives in
        `test_gradle_declares_every_dependency_the_registry_requires`, and
        together they are a bijection.
        """
        rel = os.path.join(self.path, "build.gradle.kts")
        if not os.path.isfile(os.path.join(ROOT, rel)):
            return  # reported by test_module_has_a_build_file
        allowed = {
            _gradle_path(self.registry[d]["path"])
            for d in self.entry.get("depends_on", [])
        }
        declared = _project_dependencies(rel)
        extra = declared - allowed
        assert not extra, (
            f"{rel} depends on {sorted(extra)}, which [module.{self.MODULE}] "
            f"does not list in depends_on — modules talk through core ports or "
            f"the app's dependency wiring, not directly"
        )

    def test_gradle_declares_every_dependency_the_registry_requires(self):
        """A code module must actually wire the code modules it depends on.

        The converse of the boundary check above, and the one that used to be
        missing. `depends_on` is a claim; `project(":…")` in the build file is
        the proof. Without this, a module whose build file declared nothing at
        all satisfied the boundary check vacuously — an empty set is a subset
        of every set — so forgetting to wire a required dependency was
        invisible.

        Only CODE-module edges are required. A container (`android`, `server`,
        `shared`, and the `modules` level beneath each) publishes no artifact,
        and a code module with no sources of its own has nothing to link
        against, so demanding `project(":android")` from `android/modules/core`
        would encode a build that cannot work. The rule is stated once, in the
        "what may be depended on" block above.
        """
        rel = os.path.join(self.path, "build.gradle.kts")
        if not os.path.isfile(os.path.join(ROOT, rel)):
            return  # reported by test_module_has_a_build_file
        if not _applies_artifact_plugin(rel):
            # Nothing is published, so nothing can be required of it.
            return
        required = set()
        for dep in self.entry.get("depends_on", []):
            if dep not in self.registry:
                continue  # reported by test_declared_dependencies_are_registered_modules
            dep_path = self.registry[dep]["path"]
            if _is_container(dep_path, self.registry):
                continue
            if not _applies_artifact_plugin(
                os.path.join(dep_path, "build.gradle.kts")
            ):
                continue
            required.add(_gradle_path(dep_path))
        declared = _project_dependencies(rel)
        missing = required - declared
        assert not missing, (
            f"{rel} declares no project dependency on {sorted(missing)}, which "
            f"[module.{self.MODULE}] lists in depends_on and which publishes an "
            f"artifact — the registry and the Gradle build have drifted apart, "
            f"so this module would not compile against them"
        )

    def test_no_module_depends_on_itself(self):
        assert self.MODULE not in self.entry.get("depends_on", []), (
            f"[module.{self.MODULE}] lists itself in depends_on"
        )

    # ── repo-wide: the registry, the include list and the test tree ──────
    #
    # These two hold the whole tree, not one module, so each is checked from
    # every module's contract test rather than once. contract_support.py is
    # not itself a collected test file, so a shared TestCase living only here
    # would never run; inheriting into the per-module stubs is what puts them
    # in front of pytest. Redundant work, but a check that cannot fail is
    # worse than a check that runs more than once.

    def test_include_list_is_exactly_the_registered_modules(self):
        """settings.gradle.kts includes exactly the registry — set equality.

        Checking only that every registered module is included would let the
        list drift the other way: an include with no registry entry, or a
        registered module quietly dropped from the build, would both pass. The
        claim under test is that the include list DERIVES from modules.toml,
        and that is set equality in both directions.
        """
        includes = _settings_includes()
        registered = {
            _gradle_path(entry["path"]) for entry in self.registry.values()
        }
        omitted = sorted(registered - includes)
        extra = sorted(includes - registered)
        assert not omitted and not extra, (
            "settings.gradle.kts and modules.toml do not describe the same "
            "module set "
            f"(include list has {len(includes)}, registry has "
            f"{len(registered)}):"
            + (f"\n  registered but NOT included: {omitted}" if omitted else "")
            + (f"\n  included but NOT registered: {extra}" if extra else "")
            + "\n  The include list is supposed to derive from modules.toml, so "
            "the two must be equal — not merely one a subset of the other."
        )

    def test_contract_tests_are_in_bijection_with_the_registry(self):
        """One contract test per registered module, and no test without one.

        Without this, deleting a module's contract test left no trace: nothing
        counted the files, so the suite stayed green over a module that had
        quietly lost its structural contract. A module's test file is the one
        its card names under Test Locations, so the two sides are compared as
        sets — a module with no file and a file with no module both fail.
        """
        tests_dir = os.path.join(ROOT, "tests", "contract")
        present = {
            f"tests/contract/{name}"
            for name in os.listdir(tests_dir)
            if name.startswith("test_") and name.endswith(".py")
        }
        expected = {}
        for key, entry in self.registry.items():
            section = _card_section(_card_text(entry["card"]), "## Test Locations")
            named = set(re.findall(r"tests/contract/test_[a-z0-9_]+\.py", section))
            assert len(named) == 1, (
                f"{entry['card']} must name exactly one contract test under Test "
                f"Locations; it names {sorted(named) or ['(none)']}"
            )
            (rel,) = named
            assert rel not in expected, (
                f"{entry['card']} and {expected[rel]} both name {rel} — a "
                f"contract test belongs to exactly one module"
            )
            expected[rel] = entry["card"]
        missing = sorted(set(expected) - present)
        orphan = sorted(present - set(expected))
        assert not missing and not orphan, (
            "tests/contract/ and modules.toml are not in bijection"
            + (f"; no such contract test: {missing}" if missing else "")
            + (f"; contract test with no registered module: {orphan}" if orphan else "")
        )

