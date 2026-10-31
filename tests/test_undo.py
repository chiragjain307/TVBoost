"""Run undo.sh against a simulated TV, which is the one way to reach both of its branches on demand.

Notification access has to go through Android's own command: the setting of the same name only copies Android's list,
so writing it looks right until the next restart empties it.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
IP = "192.168.1.50"
LISTENERS = "com.example/.One:com.example/.Two"

# Stands in for the venv's python: real for `-c`, and a TV that logs each command for tools/tv.py.
FAKE_PYTHON = r'''
import os
import sys

args = [a for a in sys.argv[1:] if a != "-I"]
if args and args[0] == "-c":
    exec(args[1])
elif args and args[0].endswith("device_match.py"):
    sys.exit(1)
elif args and args[0].endswith("tv.py"):
    command = args[-1]
    with open(os.environ["FAKE_LOG"], "a") as log:
        log.write(command + "\n")
    if command.startswith("settings get secure "):
        print(os.environ.get("FAKE_SETTING", ""))
'''


class UndoTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        tools = self.base / "tools"
        (tools / "venv" / "bin").mkdir(parents=True)
        shutil.copy2(ROOT / "undo.sh", self.base)
        shutil.copy2(ROOT / "tools" / "common.sh", tools)
        self.log = self.base / "tv.log"
        restore = tools / "restore.sh"
        restore.write_text(f'#!/bin/sh\necho restore.sh >> "{self.log}"\n')
        restore.chmod(0o755)
        fake = self.base / "fake_python.py"
        fake.write_text(FAKE_PYTHON)
        client = tools / "venv" / "bin" / "python"
        client.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{fake}" "$@"\n')
        client.chmod(0o755)
        # The waits are for a real TV.
        bin_dir = self.base / "bin"
        bin_dir.mkdir()
        sleep = bin_dir / "sleep"
        sleep.write_text("#!/bin/sh\nexit 0\n")
        sleep.chmod(sleep.stat().st_mode | stat.S_IEXEC)
        self.home = self.base / "home"
        saved = self.home / ".tvboost"
        (saved / "backups" / "first").mkdir(parents=True)
        (saved / f"universal-{IP}.txt").write_text("com.example.stock\n")
        (saved / f"installed-{IP}.txt").write_text(str(saved / f"universal-{IP}.txt") + "\n")
        (saved / "backups" / f"latest-{IP}.txt").write_text(str(saved / "backups" / "first") + "\n")
        (saved / "backups" / "first" / "manifest.json").write_text(json.dumps({
            "private_dns_mode": "null", "private_dns_specifier": "null", "notification_listeners": LISTENERS,
            "accessibility_services": "", "accessibility_enabled": "0", "touch_exploration": "null"}))
        self.stays = saved / f"stock-stays-{IP}"

    def undo(self):
        if os.name != "posix" or not shutil.which("bash"):
            raise unittest.SkipTest("undo.sh is a POSIX script; it needs bash")
        env = dict(os.environ, HOME=str(self.home), TV_PORT="5555", FAKE_LOG=str(self.log), FAKE_SETTING="0",
                   PATH=f"{self.base / 'bin'}:{os.environ['PATH']}")
        result = subprocess.run(["bash", str(self.base / "undo.sh"), IP], capture_output=True, text=True,
                                timeout=60, env=env, cwd=self.base)
        self.assertEqual(result.returncode, 0, result.stderr)
        return self.log.read_text().splitlines()

    def test_notification_access_goes_through_android(self):
        commands = self.undo()
        self.assertIn("cmd notification allow_listener com.example/.One", commands)
        self.assertIn("cmd notification allow_listener com.example/.Two", commands)
        self.assertFalse(any("settings put secure enabled_notification_listeners" in c for c in commands))

    def test_stock_launcher_that_was_never_disabled_is_left_alone(self):
        self.stays.write_text("")
        self.assertNotIn("restore.sh", self.undo())
        self.assertFalse(self.stays.exists(), "the marker goes with the launcher")

    def test_disabled_stock_launcher_comes_back_first(self):
        commands = self.undo()
        self.assertLess(commands.index("restore.sh"), commands.index("cmd notification allow_listener com.example/.One"))


if __name__ == "__main__":
    unittest.main()
