#!/usr/bin/env python3
"""After a batch: let Android write package state, reboot with the same timing code as the baseline,
idle for the same 300 s, then show whether the batch held and what the box is talking to.

    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I benchmarks/after_batch.py --label batch1
"""
import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from func_check import net_watch  # noqa: E402
from measure import REPO, home_package, log, reboot_timed, try_tv  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--label", required=True)
ap.add_argument("--idle", type=int, default=300)
ap.add_argument("--net", type=int, default=120)
args = ap.parse_args()
out = REPO / "benchmarks" / "runs" / f"{time.strftime('%Y%m%d-%H%M')}-after-{args.label}"
out.mkdir(parents=True, exist_ok=True)

time.sleep(15)   # Android writes package state about 10 s after the last change
log("rebooting")
r = {"boot": reboot_timed(home_package())}
log("boot", r["boot"], "- idle", args.idle)
time.sleep(args.idle)
r["disabled"] = sorted(try_tv("pm list packages -d --user 0", 20).split())
r["home"] = home_package()
r["daemons"] = try_tv("getprop init.svc.lz_plug; getprop init.svc.cmd_root", 15).split()
r["suspect_procs"] = try_tv("ps -A -o NAME | grep -E 'sysplugin|tvh'", 15).split()
r["available_kb"] = try_tv("grep MemAvailable /proc/meminfo", 15).split()[1:2]
r["uptime"] = try_tv("uptime", 15).strip()
r["net"] = net_watch(args.net)
(out / "result.json").write_text(json.dumps(r, indent=2))
print(json.dumps(r, indent=2))
print("saved in", out)
