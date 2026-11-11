"""Meta-test: every TestCase class defined in a tests/contract helper module is collected.

The contract tier runs the test_*.py files of tests/contract. The helper modules next to them (the *_pins.py files and
others) hold TestCase classes that run only because some test_*.py imports them by name or subclasses them. Deleting
such an import line leaves the file green with fewer tests, and nothing noticed. This test reads tests/contract with
ast (it never imports a helper module: importing runs its code and hides a broken module behind an ImportError) and
reports every TestCase class of a helper module that no test_*.py collects.

A class counts as collected when a test_*.py, at module level (also inside a module-level if or try, never inside a
def or a class body), imports it by name (`from M import C`, with or without `as`), or imports its module and uses
`M.C` (`import M`, `import M as A`; a class that extends it names it that way). A use of `M.C` counts only in module-level
code outside the BODY of a def, a lambda or a class (a class base list, a decorator and a plain assignment count;
a use inside a body runs later, so unittest never sees the class). A star import counts for nothing (the target is not
named), and neither does a relative import (`from .M import C`). A TestCase class nested in another class is never collected by unittest, so it is reported.
A class is a TestCase when one of its bases is `TestCase` or `unittest.TestCase`, or is the name of another TestCase
class of the folder (resolved to a fixed point), so a base class that other classes extend is covered by the same rule.
"""
import ast
import os
import shutil
import tempfile
import unittest

CONTRACT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))),
    "tests",
    "contract",
)


def _module_level_statements(tree):
    """The statements that run when the module is imported: the body, and the bodies of module-level if and try.

    Never the inside of a def or a class.
    """
    pending = list(tree.body)
    found = []
    while pending:
        node = pending.pop(0)
        found.append(node)
        if isinstance(node, ast.If):
            pending.extend(node.body + node.orelse)
        elif isinstance(node, ast.Try):
            pending.extend(node.body + node.orelse + node.finalbody)
            for handler in node.handlers:
                pending.extend(handler.body)
    return found


def _import_time_nodes(node):
    """[node] and everything inside it that runs at import time: never the body of a def, a lambda or a class.

    What still runs at import time is kept: the decorators and default values of a def, the bases, keywords and
    decorators of a class, and the default values of a lambda.
    """
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        children = list(node.decorator_list) + list(node.args.defaults) + [d for d in node.args.kw_defaults if d is not None]
    elif isinstance(node, ast.ClassDef):
        children = list(node.decorator_list) + list(node.bases) + [keyword.value for keyword in node.keywords]
    elif isinstance(node, ast.Lambda):
        children = list(node.args.defaults) + [d for d in node.args.kw_defaults if d is not None]
    else:
        children = list(ast.iter_child_nodes(node))
    yield node
    for child in children:
        yield from _import_time_nodes(child)


def _parse(path):
    with open(path, encoding="utf-8") as handle:
        return ast.parse(handle.read(), filename=path)


def _base_names(node):
    """The bases of a class as dotted text, for example 'unittest.TestCase' or 'ModuleContractTest'."""
    return [ast.unparse(base) for base in node.bases]


def _python_files(contract_dir):
    return sorted(name for name in os.listdir(contract_dir) if name.endswith(".py"))


def _class_table(contract_dir):
    """Every class defined at module level in every .py file of the folder: (file, name) -> (lineno, bases, is_test_file)."""
    table = {}
    for name in _python_files(contract_dir):
        tree = _parse(os.path.join(contract_dir, name))
        for node in _module_level_statements(tree):
            if isinstance(node, ast.ClassDef):
                table[(name[:-3], node.name)] = (node.lineno, _base_names(node), name.startswith("test_"))
    return table


def _testcase_classes(table):
    """The (module, class) keys that are TestCase classes: direct bases, then bases named after a TestCase class of the folder."""
    known = set()
    changed = True
    while changed:
        changed = False
        known_names = {cls for (_mod, cls) in known}
        for key, (_line, bases, _is_test) in table.items():
            if key in known:
                continue
            for base in bases:
                last = base.split(".")[-1]
                if base in ("TestCase", "unittest.TestCase") or last in known_names:
                    known.add(key)
                    changed = True
                    break
    return known


def _nested_testcase_classes(contract_dir):
    """TestCase classes written inside another class of a helper module: unittest never collects them."""
    nested = []
    for name in _python_files(contract_dir):
        if name.startswith("test_"):
            continue
        tree = _parse(os.path.join(contract_dir, name))
        for outer in _module_level_statements(tree):
            if not isinstance(outer, ast.ClassDef):
                continue
            for inner in ast.walk(outer):
                if inner is not outer and isinstance(inner, ast.ClassDef):
                    if any(b in ("TestCase", "unittest.TestCase") for b in _base_names(inner)):
                        nested.append("%s.%s.%s" % (name[:-3], outer.name, inner.name))
    return sorted(nested)


def _collected_by_test_files(contract_dir):
    """The (module, class) pairs that the test_*.py files collect, by import, by attribute or by subclassing."""
    collected = set()
    for name in _python_files(contract_dir):
        if not name.startswith("test_"):
            continue
        tree = _parse(os.path.join(contract_dir, name))
        module_aliases = {}
        statements = _module_level_statements(tree)
        for node in statements:
            if isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
                for alias in node.names:
                    if alias.name != "*":
                        collected.add((node.module, alias.name))
            elif isinstance(node, ast.Import):
                for alias in node.names:
                    module_aliases[alias.asname or alias.name] = alias.name
        # `import M` or `import M as A` collects M.C when the file uses M.C (or A.C) in module-level code.
        for node in statements:
            for sub in _import_time_nodes(node):
                if isinstance(sub, ast.Attribute) and isinstance(sub.value, ast.Name) and sub.value.id in module_aliases:
                    collected.add((module_aliases[sub.value.id], sub.attr))
    return collected


def uncollected_test_classes(contract_dir):
    """The problems of a contract folder, as text; an empty list means every helper TestCase is collected.

    Pure: it reads the folder and writes nothing, so it runs on the real tree and on temporary trees.
    """
    table = _class_table(contract_dir)
    test_cases = _testcase_classes(table)
    collected = _collected_by_test_files(contract_dir)
    problems = []
    for key in sorted(test_cases):
        module, cls = key
        line, _bases, is_test_file = table[key]
        if is_test_file:
            continue
        if key in collected:
            continue
        problems.append("uncollected: %s.%s (line %d)" % (module, cls, line))
    problems.extend("nested: %s" % entry for entry in _nested_testcase_classes(contract_dir))
    return problems


class CollectsHelperTestCasesTest(unittest.TestCase):
    def test_every_helper_testcase_of_the_real_contract_folder_is_collected(self):
        problems = uncollected_test_classes(CONTRACT_DIR)
        self.assertEqual(problems, [], "contract helper TestCase classes that no test_*.py collects: %s" % problems)

    def test_the_scan_sees_the_real_helper_classes(self):
        table = _class_table(CONTRACT_DIR)
        helpers = [key for key in _testcase_classes(table) if not table[key][2]]
        self.assertGreaterEqual(len(helpers), 10, "the scan found too few helper TestCase classes: %s" % helpers)
        self.assertIn(("web_libs_pins", "WebLibsPinsTest"), helpers)

    def test_dropping_one_real_import_line_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            copy = os.path.join(tmp, "contract")
            shutil.copytree(CONTRACT_DIR, copy, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
            target = os.path.join(copy, "test_web_libs_contract.py")
            with open(target, encoding="utf-8") as handle:
                lines = handle.read().splitlines(keepends=True)
            kept = [line for line in lines if "import WebLibsPinsTest" not in line]
            self.assertEqual(len(kept), len(lines) - 1, "exactly one import line must be dropped")
            with open(target, "w", encoding="utf-8") as handle:
                handle.write("".join(kept))
            problems = uncollected_test_classes(copy)
            self.assertEqual(len(problems), 1, problems)
            self.assertTrue(problems[0].startswith("uncollected: web_libs_pins.WebLibsPinsTest"), problems)


class UncollectedTestClassesTempTreeTest(unittest.TestCase):
    """uncollected_test_classes on temporary folders: each rule of the module docstring has a case."""

    HELPER = "import unittest\n\n\nclass AlphaPinsTest(unittest.TestCase):\n    def test_x(self):\n        pass\n"

    def _problems(self, files):
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in files.items():
                with open(os.path.join(tmp, name), "w", encoding="utf-8") as handle:
                    handle.write(text)
            return uncollected_test_classes(tmp)

    def test_a_helper_class_imported_by_name_is_collected(self):
        files = {"alpha_pins.py": self.HELPER, "test_alpha.py": "from alpha_pins import AlphaPinsTest  # noqa: F401\n"}
        self.assertEqual(self._problems(files), [])

    def test_a_helper_class_that_nothing_imports_is_reported_with_its_line(self):
        files = {"alpha_pins.py": self.HELPER, "test_alpha.py": "import unittest\n"}
        self.assertEqual(self._problems(files), ["uncollected: alpha_pins.AlphaPinsTest (line 4)"])

    def test_an_import_with_an_alias_collects_the_class(self):
        files = {"alpha_pins.py": self.HELPER, "test_alpha.py": "from alpha_pins import AlphaPinsTest as Other  # noqa: F401\n"}
        self.assertEqual(self._problems(files), [])

    def test_a_module_import_with_an_attribute_use_collects_the_class(self):
        for header in ("import alpha_pins\n", "import alpha_pins as ap\n"):
            use = "alpha_pins" if "as" not in header else "ap"
            files = {"alpha_pins.py": self.HELPER, "test_alpha.py": header + "Collected = %s.AlphaPinsTest\n" % use}
            self.assertEqual(self._problems(files), [], header)

    def test_a_module_import_without_any_use_collects_nothing(self):
        files = {"alpha_pins.py": self.HELPER, "test_alpha.py": "import alpha_pins\n"}
        self.assertEqual(self._problems(files), ["uncollected: alpha_pins.AlphaPinsTest (line 4)"])

    def test_a_star_import_collects_nothing(self):
        files = {"alpha_pins.py": self.HELPER, "test_alpha.py": "from alpha_pins import *  # noqa: F403\n"}
        self.assertEqual(self._problems(files), ["uncollected: alpha_pins.AlphaPinsTest (line 4)"])

    def test_an_import_inside_a_function_or_a_class_body_collects_nothing(self):
        inside_def = "def later():\n    from alpha_pins import AlphaPinsTest\n    return AlphaPinsTest\n"
        inside_class = "class Holder:\n    from alpha_pins import AlphaPinsTest\n"
        for text in (inside_def, inside_class):
            files = {"alpha_pins.py": self.HELPER, "test_alpha.py": text}
            self.assertEqual(self._problems(files), ["uncollected: alpha_pins.AlphaPinsTest (line 4)"], text)

    def test_an_import_inside_a_module_level_if_or_try_collects_the_class(self):
        in_if = "import sys\nif sys.version_info >= (3, 0):\n    from alpha_pins import AlphaPinsTest  # noqa: F401\n"
        in_try = "try:\n    from alpha_pins import AlphaPinsTest  # noqa: F401\nexcept ImportError:\n    pass\n"
        for text in (in_if, in_try):
            files = {"alpha_pins.py": self.HELPER, "test_alpha.py": text}
            self.assertEqual(self._problems(files), [], text)

    def test_an_import_inside_an_except_handler_collects_the_class(self):
        text = "try:\n    import json  # noqa: F401\nexcept ImportError:\n    from alpha_pins import AlphaPinsTest  # noqa: F401\n"
        self.assertEqual(self._problems({"alpha_pins.py": self.HELPER, "test_alpha.py": text}), [])

    def test_a_nested_class_that_is_not_a_testcase_is_not_reported(self):
        helper = "class Outer:\n    class Inner:\n        pass\n\n    class Other(object):\n        pass\n"
        self.assertEqual(self._problems({"support.py": helper, "test_a.py": "import unittest\n"}), [])

    def test_several_uncollected_classes_are_reported_in_sorted_order(self):
        helper = (
            "import unittest\n\n\nclass ZetaTest(unittest.TestCase):\n    pass\n\n\n"
            "class AlphaTest(unittest.TestCase):\n    pass\n\n\nclass MidTest(unittest.TestCase):\n    pass\n"
        )
        got = self._problems({"support.py": helper, "test_a.py": "import unittest\n"})
        self.assertEqual(got, [
            "uncollected: support.AlphaTest (line 8)",
            "uncollected: support.MidTest (line 12)",
            "uncollected: support.ZetaTest (line 4)",
        ])

    def test_a_module_attribute_used_only_inside_a_body_collects_nothing(self):
        in_def = "import alpha_pins\n\n\ndef later():\n    return alpha_pins.AlphaPinsTest\n"
        in_class = "import alpha_pins\n\n\nclass Holder:\n    X = alpha_pins.AlphaPinsTest\n"
        in_lambda = "import alpha_pins\n\nf = lambda: alpha_pins.AlphaPinsTest\n"
        for text in (in_def, in_class, in_lambda):
            files = {"alpha_pins.py": self.HELPER, "test_alpha.py": text}
            self.assertEqual(self._problems(files), ["uncollected: alpha_pins.AlphaPinsTest (line 4)"], text)

    def test_a_module_attribute_in_a_class_base_a_decorator_or_an_assignment_collects_the_class(self):
        as_base = "import alpha_pins\n\n\nclass T(alpha_pins.AlphaPinsTest):\n    pass\n"
        as_assignment = "import alpha_pins\n\nCollected = alpha_pins.AlphaPinsTest\n"
        as_default = "import alpha_pins\n\n\ndef f(x=alpha_pins.AlphaPinsTest):\n    return x\n"
        for text in (as_base, as_assignment, as_default):
            files = {"alpha_pins.py": self.HELPER, "test_alpha.py": text}
            self.assertEqual(self._problems(files), [], text)

    def test_an_import_in_another_helper_file_collects_nothing(self):
        other = "from alpha_pins import AlphaPinsTest  # noqa: F401\n"
        files = {"alpha_pins.py": self.HELPER, "helper_b.py": other, "test_alpha.py": "import unittest\n"}
        self.assertEqual(self._problems(files), ["uncollected: alpha_pins.AlphaPinsTest (line 4)"])

    def test_a_relative_import_in_a_test_file_collects_nothing(self):
        files = {"alpha_pins.py": self.HELPER, "test_alpha.py": "from .alpha_pins import AlphaPinsTest  # noqa: F401\n"}
        self.assertEqual(self._problems(files), ["uncollected: alpha_pins.AlphaPinsTest (line 4)"])

    def test_a_base_class_collected_through_a_subclass_in_a_test_file(self):
        helper = "import unittest\n\n\nclass BaseTest(unittest.TestCase):\n    pass\n"
        files = {"support.py": helper, "test_a.py": "from support import BaseTest\n\n\nclass ATest(BaseTest):\n    MODULE = 'a'\n"}
        self.assertEqual(self._problems(files), [])
        files = {"support.py": helper, "test_a.py": "import support\n\n\nclass ATest(support.BaseTest):\n    MODULE = 'a'\n"}
        self.assertEqual(self._problems(files), [])

    def test_a_base_class_that_nothing_extends_or_imports_is_reported(self):
        helper = "import unittest\n\n\nclass BaseTest(unittest.TestCase):\n    pass\n"
        self.assertEqual(self._problems({"support.py": helper, "test_a.py": "import unittest\n"}), ["uncollected: support.BaseTest (line 4)"])

    def test_a_class_that_is_not_a_testcase_is_never_reported(self):
        helper = "class Plain:\n    pass\n\n\nclass Also(object):\n    pass\n"
        self.assertEqual(self._problems({"support.py": helper, "test_a.py": "import unittest\n"}), [])

    def test_a_testcase_nested_in_another_class_is_reported(self):
        helper = "import unittest\n\n\nclass Outer:\n    class Inner(unittest.TestCase):\n        pass\n"
        self.assertEqual(self._problems({"support.py": helper, "test_a.py": "import unittest\n"}), ["nested: support.Outer.Inner"])

    def test_importing_a_name_that_does_not_exist_collects_nothing(self):
        files = {"alpha_pins.py": self.HELPER, "test_alpha.py": "from alpha_pins import Missing  # noqa: F401\n"}
        self.assertEqual(self._problems(files), ["uncollected: alpha_pins.AlphaPinsTest (line 4)"])

    def test_a_class_with_a_testcase_base_through_another_helper_class_is_resolved(self):
        helper = (
            "import unittest\n\n\nclass Base(unittest.TestCase):\n    pass\n\n\n"
            "class Derived(Base):\n    def test_x(self):\n        pass\n"
        )
        files = {"support.py": helper, "test_a.py": "from support import Base  # noqa: F401\n"}
        self.assertEqual(self._problems(files), ["uncollected: support.Derived (line 8)"])

    def test_a_testcase_defined_in_a_test_file_is_not_a_helper(self):
        files = {"test_a.py": "import unittest\n\n\nclass OwnTest(unittest.TestCase):\n    pass\n"}
        self.assertEqual(self._problems(files), [])

    def test_the_bare_testcase_name_is_a_testcase_base(self):
        helper = "from unittest import TestCase\n\n\nclass BareTest(TestCase):\n    pass\n"
        self.assertEqual(self._problems({"support.py": helper, "test_a.py": "import unittest\n"}), ["uncollected: support.BareTest (line 4)"])


if __name__ == "__main__":
    unittest.main()
