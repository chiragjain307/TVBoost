#!/usr/bin/env python3
"""Drive the launcher with the same key events the remote sends: move to each dock tile, open it, come
back, and check Home kept the focused tile. --direct opens the launcher by component, for the check
before the stock launcher is disabled, when the Home key still goes to the stock one.

    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I benchmarks/launcher_check.py --label pre-handover --direct
"""
import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from func_check import focus, key, shot  # noqa: E402
from measure import REPO, log, try_tv  # noqa: E402

LAUNCHER = "com.tvboxrevive.home"
DOCK = [("youtube", "com.google.android.youtube.tv"), ("netflix", "com.netflix.mediaclient"),
        ("prime", "com.amazon.amazonvideo.livingroom"), ("hotstar", "in.startv.hotstar"),
        ("playstore", "com.android.vending"), ("chrome", "com.android.chrome")]

ap = argparse.ArgumentParser()
ap.add_argument("--label", required=True)
ap.add_argument("--direct", action="store_true")
args = ap.parse_args()
out = REPO / "benchmarks" / "runs" / f"{time.strftime('%Y%m%d-%H%M')}-launcher-{args.label}"
out.mkdir(parents=True, exist_ok=True)


def to_home():
    if args.direct:
        try_tv(f"am start -n {LAUNCHER}/.MainActivity", 20)
    else:
        key("HOME")
    time.sleep(2.5)


def first_tile():
    """The launcher opens on the dock's first tile, but a run can start elsewhere."""
    for _ in range(len(DOCK)):
        key("DPAD_LEFT")
        time.sleep(0.4)


r = {"label": args.label, "tiles": {}}
for name, pkg in DOCK:
    try_tv(f"am force-stop {pkg}", 20)
to_home()
for index, (name, pkg) in enumerate(DOCK):
    log("tile", name)
    first_tile()
    for _ in range(index):
        key("DPAD_RIGHT")
        time.sleep(0.5)
    time.sleep(1)
    shot(out / f"focus-{name}.png")
    key("DPAD_CENTER")
    time.sleep(7)
    opened = focus()
    key("BACK")   # the path a viewer takes first; apps may hold it, so Home is what must get back
    time.sleep(1.5)
    to_home()
    back_home = focus()
    shot(out / f"back-from-{name}.png")
    r["tiles"][name] = {"opened": opened, "ok_open": pkg in opened, "home_after": back_home,
                        "ok_home": LAUNCHER in back_home}
    try_tv(f"am force-stop {pkg}", 20)

to_home()
key("BACK")
time.sleep(1)
key("BACK")
time.sleep(1.5)
r["back_keeps_home"] = LAUNCHER in focus()
key("DPAD_UP")
time.sleep(0.6)
key("DPAD_RIGHT")
time.sleep(0.6)
key("DPAD_RIGHT")
time.sleep(0.8)
shot(out / "top-buttons.png")
key("DPAD_CENTER")
time.sleep(3)
r["gear_opens"] = focus()
shot(out / "gear-opened.png")
key("HOME" if not args.direct else "BACK")
time.sleep(2)
to_home()
r["pss_kb_after_tour"] = try_tv(f"dumpsys meminfo {LAUNCHER} | grep -m1 'TOTAL:'", 20).split()[1:2]
r["crashes"] = [ln[:150] for ln in try_tv("logcat -d -v time -b crash", 30).splitlines()
                if LAUNCHER in ln or "FATAL" in ln][:10]
(out / "result.json").write_text(json.dumps(r, indent=2))
print(json.dumps(r, indent=2))
print("saved in", out)
