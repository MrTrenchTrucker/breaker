"""Every unittest.TestCase class in a tests/contract/*_pins.py file must be
bound at module scope by some tests/contract/test_*.py, or deleting one
import line drops its tests silently (the web-libs pins lost 40 that way).
Imported means: ``from <pinsmod> import <name>`` at module scope (also in a
top-level ``try``/``if``), the same ``as <alias>`` (left of ``as``), or
``from <pinsmod> import *`` for a public class (no leading underscore, in
``__all__`` when defined). Not counted: an import inside a function or
class body, a plain ``import <pinsmod>`` plus attribute use, or a name in
a comment or string. Only TestCase subclasses are checked; the tree is
read only and planted cases run in temp dirs.
"""
import ast
import os
import tempfile
import unittest

REPO_ROOT = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", ".."))
CONTRACT_DIR = ("tests", "contract")


def _base_names(tree):
    """Map every class name in the module to its base names as strings."""
    out = {}
    for node in ast.walk(tree):
        if not isinstance(node, ast.ClassDef):
            continue
        out[node.name] = [
            base.id if isinstance(base, ast.Name) else ast.unparse(base)
            for base in node.bases
        ]
    return out


def pin_case_classes(tree):
    """TestCase subclasses, direct or through a same-file base."""
    bases = _base_names(tree)

    def is_case(name, depth=0):
        if depth > 8:
            return False
        if name in ("TestCase", "unittest.TestCase"):
            return True
        return any(is_case(b, depth + 1) for b in bases.get(name, ()))

    return sorted(name for name in bases if is_case(name))


def module_scope_from_imports(tree):
    """(module, name) pairs bound at module scope by from-imports.

    Module scope is the module body plus top-level try/if bodies (and a
    try's except handler bodies); an import inside a function or class
    body does not bind a module-level name and does not count. A renamed
    import contributes the name left of ``as``; a star import contributes
    the marker ``*`` for the caller to expand.
    """
    def walk(stmts):
        out = set()
        for node in stmts:
            if isinstance(node, ast.ImportFrom) and node.module:
                out.update((node.module, a.name) for a in node.names)
            elif isinstance(node, ast.Try):
                sub = walk(node.body) | walk(node.orelse)
                for handler in node.handlers:
                    sub |= walk(handler.body)
                out |= sub
            elif isinstance(node, ast.If):
                out |= walk(node.body) | walk(node.orelse)
        return out

    return walk(tree.body)


def star_export_names(tree):
    """Names a ``from <this module> import *`` would bind."""
    names = set()
    allnames = None
    for node in tree.body:
        if isinstance(node, (ast.ClassDef, ast.FunctionDef,
                             ast.AsyncFunctionDef)):
            names.add(node.name)
        elif isinstance(node, ast.Assign):
            for target in node.targets:
                if not isinstance(target, ast.Name):
                    continue
                if (target.id == "__all__"
                        and isinstance(node.value, (ast.List, ast.Tuple))):
                    allnames = [e.value for e in node.value.elts
                                if isinstance(e, ast.Constant)]
                else:
                    names.add(target.id)
        elif isinstance(node, (ast.Import, ast.ImportFrom)):
            for alias in node.names:
                names.add(alias.asname or alias.name.split(".")[0])
    if allnames is not None:
        return {n for n in allnames if not n.startswith("_")}
    return {n for n in names if not n.startswith("_")}


def unimported_pins_classes(root):
    """(file, class) pairs the unit tier would silently drop."""
    cdir = os.path.join(root, *CONTRACT_DIR)
    if not os.path.isdir(cdir):
        return []
    names = sorted(os.listdir(cdir))
    trees = {}
    for name in names:
        if name.endswith(".py"):
            with open(os.path.join(cdir, name), encoding="utf-8") as fh:
                trees[name] = ast.parse(fh.read())
    imported = set()
    for name in names:
        if name.startswith("test_") and name.endswith(".py"):
            imported |= module_scope_from_imports(trees[name])
    bound = set()
    for module, name in imported:
        if name != "*":
            bound.add((module, name))
        elif module + ".py" in trees:
            bound.update((module, pub)
                         for pub in star_export_names(trees[module + ".py"]))
    orphans = []
    for name in names:
        if not name.endswith("_pins.py"):
            continue
        for cls in pin_case_classes(trees[name]):
            if (name[:-3], cls) not in bound:
                orphans.append(("tests/contract/" + name, cls))
    return orphans


def _case(cls, base="unittest.TestCase"):
    return ("import unittest\n\n\n"
            "class %s(%s):\n"
            "    def test_a(self):\n"
            "        self.assertTrue(True)\n" % (cls, base))


def _test_module(cls, extra_import=""):
    return ("import unittest\n" + extra_import +
            "class %s(unittest.TestCase):\n"
            "    def test_a(self):\n"
            "        self.assertTrue(True)\n" % cls)


def _plant(tmp, *files):
    for rel, text in files:
        path = os.path.join(tmp, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)


class PinsImportedTest(unittest.TestCase):
    """Every pin class has a module-scope binder, or this test names it."""

    def test_every_pins_class_in_the_real_tree_is_imported_by_a_contract_test(self):
        orphans = unimported_pins_classes(REPO_ROOT)
        assert not orphans, (
            "tests/contract holds pin class(es) that no tests/contract/"
            "test_*.py binds at module scope, so deleting an import line "
            "would drop those tests silently:\n"
            + "  ".join("%s -> %s" % pair for pair in orphans))

    def test_it_names_a_pins_class_with_no_module_scope_import(self):
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/alpha_pins.py", _case("AlphaPinsTest")),
                  ("tests/contract/test_alpha_contract.py",
                   _test_module("AlphaContractTest")))
            self.assertEqual(
                unimported_pins_classes(tmp),
                [("tests/contract/alpha_pins.py", "AlphaPinsTest")])

    def test_it_is_silent_when_the_class_is_module_scope_imported(self):
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/alpha_pins.py", _case("AlphaPinsTest")),
                  ("tests/contract/test_alpha_contract.py",
                   _test_module("AlphaContractTest",
                                "from alpha_pins import AlphaPinsTest\n\n\n")))
            self.assertEqual(unimported_pins_classes(tmp), [])

    def test_a_renamed_module_scope_import_is_still_an_import(self):
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/alpha_pins.py", _case("AlphaPinsTest")),
                  ("tests/contract/test_alpha_contract.py",
                   _test_module("AlphaContractTest",
                                "from alpha_pins import AlphaPinsTest "
                                "as Alias\n\n\n")))
            self.assertEqual(unimported_pins_classes(tmp), [])

    def test_an_import_only_inside_a_function_does_not_count(self):
        # The from-import sits in a function body, not at module scope.
        body = _test_module("AlphaContractTest").replace(
            "self.assertTrue(True)",
            "from alpha_pins import AlphaPinsTest\n"
            "        self.assertTrue(True)")
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/alpha_pins.py", _case("AlphaPinsTest")),
                  ("tests/contract/test_alpha_contract.py", body))
            self.assertEqual(
                unimported_pins_classes(tmp),
                [("tests/contract/alpha_pins.py", "AlphaPinsTest")])

    def test_a_plain_import_with_attribute_use_does_not_count(self):
        # A plain `import alpha_pins` plus an attribute use never binds the
        # class name at module scope, so the pin stays an orphan.
        body = ("import unittest\n"
                "import alpha_pins\n\n\n"
                "class AlphaContractTest(unittest.TestCase):\n"
                "    def test_a(self):\n"
                "        self.assertTrue(isinstance(\n"
                "            alpha_pins.AlphaPinsTest, type))\n")
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/alpha_pins.py", _case("AlphaPinsTest")),
                  ("tests/contract/test_alpha_contract.py", body))
            self.assertEqual(
                unimported_pins_classes(tmp),
                [("tests/contract/alpha_pins.py", "AlphaPinsTest")])

    def test_imports_in_a_top_level_try_or_if_are_module_scope(self):
        pins = (_case("AlphaPinsTest") + _case("BetaPinsTest"))
        body = ("import unittest\n\n\n"
                "class AlphaContractTest(unittest.TestCase):\n"
                "    def test_a(self):\n"
                "        self.assertTrue(True)\n\n"
                "try:\n"
                "    from alpha_pins import AlphaPinsTest\n"
                "except ImportError:\n"
                "    pass\n\n"
                "if True:\n"
                "    from alpha_pins import BetaPinsTest\n")
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/alpha_pins.py", pins),
                  ("tests/contract/test_alpha_contract.py", body))
            self.assertEqual(unimported_pins_classes(tmp), [])

    def test_a_star_import_collects_a_public_pins_class_only(self):
        pins = (_case("AlphaPinsTest") + _case("_HiddenPinsTest"))
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/alpha_pins.py", pins),
                  ("tests/contract/test_alpha_contract.py",
                   _test_module("AlphaContractTest",
                                "from alpha_pins import *\n\n\n")))
            self.assertEqual(
                unimported_pins_classes(tmp),
                [("tests/contract/alpha_pins.py", "_HiddenPinsTest")])

    def test_it_names_only_the_unimported_class_of_a_two_class_pins_file(self):
        pins = (_case("KeptPinsTest") + _case("DroppedPinsTest"))
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/twin_pins.py", pins),
                  ("tests/contract/test_twin_contract.py",
                   _test_module("TwinContractTest",
                                "from twin_pins import KeptPinsTest\n\n\n")))
            self.assertEqual(
                unimported_pins_classes(tmp),
                [("tests/contract/twin_pins.py", "DroppedPinsTest")])

    def test_it_names_a_case_subclass_of_a_same_file_base_when_only_the_base_is_imported(self):
        # The child is not a direct unittest.TestCase subclass; the checker
        # must follow the same-file base to find it, report it, and stay
        # silent about the imported base.
        pins = (_case("_SharedBase") + _case("OmegaPinsTest", "_SharedBase"))
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/omega_pins.py", pins),
                  ("tests/contract/test_omega_contract.py",
                   _test_module("OmegaContractTest",
                                "from omega_pins import _SharedBase\n\n\n")))
            self.assertEqual(
                unimported_pins_classes(tmp),
                [("tests/contract/omega_pins.py", "OmegaPinsTest")])

    def test_a_case_class_outside_a_pins_file_is_not_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            _plant(tmp,
                  ("tests/contract/beta_pins.py", _case("BetaPinsTest")),
                  ("tests/contract/support_helpers.py",
                   _case("HelperCaseTest")),
                  ("tests/contract/test_beta_contract.py",
                   _test_module("BetaContractTest",
                                "from beta_pins import BetaPinsTest\n\n\n")))
            self.assertEqual(unimported_pins_classes(tmp), [])


if __name__ == "__main__":
    unittest.main()
