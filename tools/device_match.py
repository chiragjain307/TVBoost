#!/usr/bin/env python3
"""Find the device profile that matches the connected box, and refuse if there is none. A profile is
only used when every property in its `match` block is identical, because results from one firmware
build say nothing about another.

    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I tools/device_match.py

Prints the device folder name on stdout. Exit status: 0 matched, 1 no profile fits, 2 the box did not answer.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def getprop(name):
    run = subprocess.run([sys.executable, "-I", str(ROOT / "tools/tv.py"), f"getprop {name}"],
                         capture_output=True, text=True, errors="replace", timeout=90, cwd=ROOT)
    if run.returncode:
        raise RuntimeError(run.stderr.strip()[-200:])
    return run.stdout.strip()


def main():
    registry = json.loads((ROOT / "devices/registry.json").read_text())
    seen = {}
    try:
        for entry in registry["devices"]:
            device = json.loads((ROOT / "devices" / entry["id"] / "device.json").read_text())
            for prop, wanted in device["match"].items():
                seen.setdefault(prop, getprop(prop))
            if all(seen[p] == w for p, w in device["match"].items()):
                print(entry["id"])
                return 0
    except (RuntimeError, subprocess.TimeoutExpired) as e:
        print(f"could not read the box: {e}", file=sys.stderr)
        return 2
    print("No device profile matches this box. Nothing was changed.", file=sys.stderr)
    for prop, value in seen.items():
        print(f"  {prop} = {value or '(empty)'}", file=sys.stderr)
    print("A different build needs its own test run: see docs/DEVICE_COMPATIBILITY.md.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    if not os.environ.get("TV_HOST"):
        sys.exit("set TV_HOST to the box's IP address")
    sys.exit(main())
