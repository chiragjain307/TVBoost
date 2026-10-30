#!/usr/bin/env python3
"""Stability watch for this box. It reboots --cycles times and watches each boot for --window seconds,
then watches for --idle seconds with no reboot. Every 10 s it saves uptime, chip temperature, available
RAM and the new log lines, so a freeze or a restart leaves its last moments on this computer. It changes
nothing on the TV.

    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I benchmarks/soak.py --cycles 3 --window 300 --idle 600
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from measure import PY, REPO, TV_PY, log, port_open, try_tv  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--cycles", type=int, default=3)
ap.add_argument("--window", type=int, default=300)
ap.add_argument("--idle", type=int, default=600)
ap.add_argument("--no-return", type=int, default=180, help="seconds a reboot may take before it counts as a hang")
args = ap.parse_args()
out = REPO / "benchmarks" / "runs" / f"{time.strftime('%Y%m%d-%H%M')}-soak"
out.mkdir(parents=True, exist_ok=True)
SAMPLE = "cat /proc/uptime; grep -m1 Tsensor /proc/msp/pm_cpu; grep MemAvailable /proc/meminfo; date '+%m-%d %H:%M:%S.000'"
events = []
samples = out / "samples.csv"
samples.write_text("time,phase,uptime_s,temp_c,available_kb\n")


def event(text):
    log(text)
    events.append({"time": time.strftime("%H:%M:%S"), "event": text})
    (out / "events.json").write_text(json.dumps(events, indent=2))


def watch(phase, seconds):
    """Returns once the window ends. A lost connection or a falling uptime is recorded, not fatal."""
    end, last_up, stamp, lost_since = time.time() + seconds, None, None, None
    logs = out / f"{phase}.log"
    while time.time() < end:
        said = try_tv(SAMPLE, 12)
        up = re.match(r"([\d.]+) ", said)
        if not up:
            if lost_since is None:
                lost_since = time.time()
                event(f"{phase}: lost ADB")
            time.sleep(5)
            continue
        if lost_since is not None:
            event(f"{phase}: ADB back after {round(time.time() - lost_since)} s")
            lost_since = None
        uptime = float(up.group(1))
        if last_up is not None and uptime < last_up:
            event(f"{phase}: BOX RESTARTED BY ITSELF, uptime {last_up:.0f} s then {uptime:.0f} s")
            stamp = None
        last_up = uptime
        temp = re.search(r"temperature = (\d+)", said)
        avail = re.search(r"MemAvailable:\s+(\d+)", said)
        now = re.search(r"(\d\d-\d\d \d\d:\d\d:\d\d\.000)", said)
        with samples.open("a") as f:
            f.write(f"{time.strftime('%H:%M:%S')},{phase},{uptime:.0f},{temp.group(1) if temp else ''},"
                    f"{avail.group(1) if avail else ''}\n")
        since = f"-T '{stamp}'" if stamp else "-t 3000"
        new = try_tv(f"logcat -d -b all -v time {since}", 25)
        with logs.open("a", errors="replace") as f:
            f.write(new)
        stamp = now.group(1) if now else stamp
        time.sleep(10)


for cycle in range(1, args.cycles + 1):
    event(f"cycle {cycle}: reboot")
    t0 = time.time()
    subprocess.run([PY, "-I", TV_PY, "--reboot"], capture_output=True, timeout=30, cwd=REPO)
    while port_open() and time.time() - t0 < 60:
        time.sleep(0.25)
    while not port_open():
        if time.time() - t0 > args.no_return:
            event(f"cycle {cycle}: HANG, ADB not back {args.no_return} s after reboot")
            print("saved in", out)
            sys.exit(3)
        time.sleep(0.5)
    event(f"cycle {cycle}: ADB back in {round(time.time() - t0, 1)} s")
    watch(f"cycle{cycle}", args.window)
event("idle watch, no reboot")
watch("idle", args.idle)
event("done")
print(json.dumps(events, indent=2))
print("saved in", out)
