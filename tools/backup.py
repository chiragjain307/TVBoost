#!/usr/bin/env python3
"""Save a timestamped recovery manifest before anything changes on a box: package states, settings,
Home, DNS and the launcher version. This is a configuration backup only. It holds no app data, no APKs
and no firmware, so it can restore settings but cannot bring back anything that was deleted.

The manifest stays on this computer under ~/.tvboost/backups, never in the repository, because
settings can hold private details such as Wi-Fi names.

    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I tools/backup.py <device id>
"""
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKUPS = Path.home() / ".tvboost" / "backups"
HOME_Q = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"


def tv(command):
    run = subprocess.run([sys.executable, "-I", str(ROOT / "tools/tv.py"), command],
                         capture_output=True, text=True, errors="replace", timeout=120, cwd=ROOT)
    if run.returncode:
        raise RuntimeError(run.stderr.strip()[-200:])
    return run.stdout


def packages(flag):
    return sorted(re.findall(r"package:(\S+)", tv(f"pm list packages {flag} --user 0")))


def main():
    if len(sys.argv) != 2 or not os.environ.get("TV_HOST"):
        sys.exit("usage: TV_HOST=<ip> backup.py <device id>")
    folder = BACKUPS / f"{sys.argv[1]}-{time.strftime('%Y%m%d-%H%M%S')}"
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    for name in ("global", "secure", "system"):
        (folder / f"settings-{name}.txt").write_text(tv(f"settings list {name}"))
    version = re.search(r"versionName=(\S+)", tv("dumpsys package com.tvboost.home"))
    manifest = {
        "kind": "configuration backup only, no app data, no APKs, no firmware",
        "created": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "device": sys.argv[1],
        "host": os.environ["TV_HOST"],
        "home": tv(HOME_Q).strip().splitlines()[-1],
        "launcher_version": version.group(1) if version else None,
        "private_dns_mode": tv("settings get global private_dns_mode").strip(),
        "private_dns_specifier": tv("settings get global private_dns_specifier").strip(),
        "notification_listeners": tv("settings get secure enabled_notification_listeners").strip(),
        "accessibility_services": tv("settings get secure enabled_accessibility_services").strip(),
        "accessibility_enabled": tv("settings get secure accessibility_enabled").strip(),
        "touch_exploration": tv("settings get secure touch_exploration_granted_accessibility_services").strip(),
        "enabled": packages("-e"),
        "disabled": packages("-d"),
    }
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2))
    latest = BACKUPS / f"latest-{os.environ['TV_HOST']}.txt"
    if not latest.exists():    # the first backup is the one that holds the box as it was
        latest.write_text(str(folder))
    print(folder)


if __name__ == "__main__":
    main()
