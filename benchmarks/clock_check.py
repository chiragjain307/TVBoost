#!/usr/bin/env python3
"""Leave Home untouched past home_idle_clock_minutes, capture the clock screensaver, press one key and
check that the same key press brings Home back.

    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I benchmarks/clock_check.py --wait 640
"""
import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from func_check import focus, key, shot  # noqa: E402
from measure import REPO, try_tv  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--wait", type=int, default=640)
args = ap.parse_args()
out = REPO / "benchmarks" / "runs" / f"{time.strftime('%Y%m%d-%H%M')}-clock-check"
out.mkdir(parents=True, exist_ok=True)
key("HOME")
time.sleep(args.wait)
r = {"waited_s": args.wait, "focus_idle": focus(),
     "temp": try_tv("grep -m1 Tsensor /proc/msp/pm_cpu", 15).strip(),
     "pss": try_tv("dumpsys meminfo com.tvboost.home | grep -m1 'TOTAL:'", 20).split()[1:2]}
shot(out / "screensaver.png")
key("DPAD_DOWN")
time.sleep(2.5)
r["focus_after_key"] = focus()
shot(out / "after-key.png")
(out / "result.json").write_text(json.dumps(r, indent=2))
print(json.dumps(r, indent=2))
print("saved in", out)
