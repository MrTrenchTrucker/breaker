"""`load(path)`: reading a file, and what a file can hold that a string cannot.

The fixtures are BYTES (`write_file` takes nothing else), because the shapes
under test, invalid UTF-8 above all, cannot be written as a Python `str`.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import os
import pathlib
import unittest

from yaml_support import YamlCase, YamlSubsetError, load, loads, write_file

GOOD = b"a: 1\nb:\n  - x\n  - y\n"


class LoadTest(YamlCase):
    def test_load_reads_a_file(self):
        self.assertEqual(load(write_file(self, GOOD)), {"a": "1", "b": ["x", "y"]})

    def test_load_and_loads_agree(self):
        path = write_file(self, GOOD)
        self.assertEqual(load(path), loads(GOOD.decode(), "x"))

    def test_load_accepts_a_path_object_and_a_bytes_path(self):
        path = write_file(self, GOOD)
        self.assertEqual(load(pathlib.Path(path)), {"a": "1", "b": ["x", "y"]})
        self.assertEqual(load(os.fsencode(path)), {"a": "1", "b": ["x", "y"]})
        bad = write_file(self, b"a: 1\nb: 2 # no\n", name="bad.yaml")
        with self.assertRaises(YamlSubsetError) as caught:
            load(os.fsencode(bad))
        self.assertIs(type(caught.exception.name), str)
        self.assertEqual(caught.exception.name, bad)

    def test_a_refusal_names_the_path_exactly_as_given(self):
        path = write_file(self, b"a: 1\nb: 2 # no\n")
        with self.assertRaises(YamlSubsetError) as caught:
            load(path)
        err = caught.exception
        self.assertEqual((err.name, err.line), (path, 2))
        self.assertTrue(str(err).startswith(f"{path}:2: "), str(err))

    def test_a_relative_path_is_named_as_written(self):
        path = write_file(self, b"a: 1\nb: 2 # no\n", name="rel.yaml")
        old = os.getcwd()
        self.addCleanup(os.chdir, old)
        os.chdir(os.path.dirname(path))
        with self.assertRaises(YamlSubsetError) as caught:
            load("rel.yaml")
        self.assertTrue(str(caught.exception).startswith("rel.yaml:2: "), str(caught.exception))

    def test_a_pathlib_path_is_named_by_its_text(self):
        path = write_file(self, b"a: 1\nb: 2 # no\n")
        with self.assertRaises(YamlSubsetError) as caught:
            load(pathlib.Path(path))
        self.assertEqual(caught.exception.name, path)

    def test_an_empty_file_is_refused_under_its_path(self):
        path = write_file(self, b"")
        with self.assertRaises(YamlSubsetError) as caught:
            load(path)
        self.assertEqual((caught.exception.name, caught.exception.line), (path, 1))
        self.assertIn("the document is empty", caught.exception.reason)


class MissingFileTest(YamlCase):
    def test_a_missing_file_is_a_file_error_not_a_yaml_error(self):
        path = os.path.join(os.path.dirname(write_file(self, GOOD)), "absent.yaml")
        with self.assertRaises(FileNotFoundError) as caught:
            load(path)
        self.assertNotIsInstance(caught.exception, ValueError)

    def test_a_directory_is_a_file_error_not_a_yaml_error(self):
        folder = os.path.dirname(write_file(self, GOOD))
        with self.assertRaises(OSError) as caught:
            load(folder)
        self.assertNotIsInstance(caught.exception, ValueError)


class EncodingTest(YamlCase):
    def test_invalid_utf8_is_refused_with_the_line_of_the_bad_byte(self):
        path = write_file(self, b"a: 1\nb: caf\xe9\nc: 3\n")
        with self.assertRaises(YamlSubsetError) as caught:
            load(path)
        err = caught.exception
        self.assertEqual((err.line, err.reason), (2, "the file is not valid UTF-8"))
        self.assertTrue(str(err).startswith(f"{path}:2: "), str(err))

    def test_invalid_utf8_on_the_first_line_and_on_the_last(self):
        for data, line in ((b"a: \xff\nb: 2\n", 1), (b"a: 1\nb: 2\nc: \xc3\n", 3), (b"a: 1\n\n\n# note \x80\n", 4)):
            with self.subTest(data=data):
                with self.assertRaises(YamlSubsetError) as caught:
                    load(write_file(self, data))
                self.assertEqual(caught.exception.line, line)

    def test_a_truncated_multibyte_sequence_at_the_end(self):
        with self.assertRaises(YamlSubsetError) as caught:
            load(write_file(self, b"a: 1\nb: \xe2\x80"))
        self.assertEqual((caught.exception.line, caught.exception.reason), (2, "the file is not valid UTF-8"))

    def test_a_bad_byte_is_counted_past_crlf_line_ends(self):
        with self.assertRaises(YamlSubsetError) as caught:
            load(write_file(self, b"a: 1\r\nb: 2\r\nc: \xff\r\n"))
        self.assertEqual(caught.exception.line, 3)

    def test_a_byte_order_mark(self):
        with self.assertRaises(YamlSubsetError) as caught:
            load(write_file(self, b"\xef\xbb\xbfa: 1\n"))
        self.assertEqual(caught.exception.line, 1)
        self.assertIn("a byte-order mark is not supported", caught.exception.reason)

    def test_a_nul_byte(self):
        with self.assertRaises(YamlSubsetError) as caught:
            load(write_file(self, b"a: 1\nb: 2\x00\n"))
        self.assertEqual(caught.exception.line, 2)
        self.assertIn("U+0000", caught.exception.reason)

    def test_non_ascii_text_is_read_as_utf8(self):
        text = "title: caf\u00e9 \u2014 \u2514\u2500 \U0001F69B\nkey \u00e9: v\n"
        self.assertEqual(load(write_file(self, text.encode("utf-8"))),
                         {"title": "caf\u00e9 \u2014 \u2514\u2500 \U0001F69B", "key \u00e9": "v"})

    def test_a_crlf_file_reads_the_same_as_an_lf_file(self):
        lf = b"a:\n  - b: 1\n    c: |\n      x\n\n      y\nd: [e, f]\n"
        self.assertEqual(load(write_file(self, lf.replace(b"\n", b"\r\n"))), load(write_file(self, lf)))

    def test_a_lone_carriage_return_in_a_file(self):
        with self.assertRaises(YamlSubsetError) as caught:
            load(write_file(self, b"a: 1\rb: 2\n"))
        self.assertEqual(caught.exception.line, 1)
        self.assertIn("a carriage return outside a CRLF line end", caught.exception.reason)

    def test_a_file_without_a_trailing_newline(self):
        self.assertEqual(load(write_file(self, b"a: 1\nb: 2")), {"a": "1", "b": "2"})


if __name__ == "__main__":
    unittest.main()
