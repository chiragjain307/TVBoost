"""tools/profile.py names the stock launcher that stays Home, and removes the name where none stays.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class RedirectTest(unittest.TestCase):
    def redirect(self, package):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.json"
            config.write_text(json.dumps({"columns": 6, "stock_home_redirect": "com.example.old"}))
            subprocess.run([sys.executable, "-I", str(ROOT / "tools/profile.py"), "redirect", str(config), package],
                           check=True)
            return json.loads(config.read_text())

    def test_names_the_launcher(self):
        self.assertEqual(self.redirect("com.example.stock"), {"columns": 6, "stock_home_redirect": "com.example.stock"})

    def test_no_launcher_removes_the_name(self):
        self.assertEqual(self.redirect(""), {"columns": 6})


if __name__ == "__main__":
    unittest.main()
