#!/usr/bin/env python3
"""System-level metrics for this box. The same script, with the same flags, produces the baseline and
the final numbers, so the two are comparable. It changes nothing on the TV: it reboots, reads, presses
keys, and opens and force-stops apps.

    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I benchmarks/measure.py --label baseline

For each of --boots reboots: timing to ADB, boot_completed and Home resumed, then --idle seconds with
no input, then memory, process, CPU, network and storage readings. After the last boot:
tools/bench.py on the launcher, then cold launches of the test apps. Raw output is kept next to
summary.json.
"""
import argparse
import json
import os
import re
import socket
import statistics
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
PY = str(REPO / "tools/venv/bin/python")
TV_PY = str(REPO / "tools/tv.py")
HOST = os.environ["TV_HOST"]
PORT = int(os.environ["TV_PORT"])
HOME_Q = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
TEST_APPS = ["com.android.tv.settings", "com.google.android.youtube.tv", "com.netflix.mediaclient",
             "com.amazon.amazonvideo.livingroom"]
SUSPECTS = [ln.split("#")[0].strip() for ln in (REPO / "devices/i96-8k-hi3798mv300/debloat.txt").read_text().splitlines()[:6]
            if ln.split("#")[0].strip()]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def tv(cmd, timeout=150, retries=3):
    last = ""
    for _ in range(retries):
        try:
            r = subprocess.run([PY, "-I", TV_PY, cmd], capture_output=True, text=True, errors="replace",
                               timeout=timeout, cwd=REPO)
            if r.returncode == 0:
                return r.stdout
            last = r.stderr[-300:]
        except subprocess.TimeoutExpired:
            last = "timeout"
        time.sleep(2)
    raise RuntimeError(f"tv({cmd!r}): {last}")


def try_tv(cmd, timeout=12):
    try:
        return tv(cmd, timeout=timeout, retries=1)
    except RuntimeError:
        return ""


def port_open():
    s = socket.socket()
    s.settimeout(1)
    try:
        s.connect((HOST, PORT))
        return True
    except OSError:
        return False
    finally:
        s.close()


def home_package():
    return tv(HOME_Q).strip().splitlines()[-1].split("/")[0].strip()


def reboot_timed(home):
    t0 = time.time()
    subprocess.run([PY, "-I", TV_PY, "--reboot"], capture_output=True, timeout=30, cwd=REPO)
    deadline = t0 + 60
    while port_open() and time.time() < deadline:
        time.sleep(0.25)
    t_down = time.time()
    while not port_open():
        if time.time() - t0 > 400:
            raise RuntimeError("ADB did not come back within 400 s")
        time.sleep(0.5)
    t_adb = time.time()
    while try_tv("getprop sys.boot_completed").strip() != "1":
        if time.time() - t0 > 400:
            raise RuntimeError("boot_completed never set")
        time.sleep(1)
    t_boot = time.time()
    t_home, resumed = None, ""
    while time.time() - t_boot < 180:
        resumed = try_tv("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")
        if home in resumed:
            t_home = time.time()
            break
        time.sleep(0.5)
    uptime = float(try_tv("cat /proc/uptime").split()[0] or 0)
    return {"down_s": round(t_down - t0, 1), "adb_s": round(t_adb - t0, 1), "boot_completed_s": round(t_boot - t0, 1),
            "home_resumed_s": round(t_home - t0, 1) if t_home else None, "uptime_at_home_s": uptime,
            "resumed": resumed.strip()[:160], "t_boot_epoch": t_boot}


def boot_events(text):
    out = {}
    for name in ("boot_progress_enable_screen", "boot_progress_ams_ready", "boot_progress_pms_ready",
                 "sf_stop_bootanim", "wm_boot_animation_done"):
        m = re.search(rf"\b{name}\b[^\d\[]*\[?(\d+)", text)
        if m:
            out[name + "_ms"] = int(m.group(1))
    return out


def parse_meminfo(text):
    return {k: int(v) for k, v in re.findall(r"^(\w+(?:\(\w+\))?):\s+(\d+) kB", text, re.M)}


def parse_dumpsys_meminfo(text, home):
    section = text.split("Total PSS by process:", 1)[-1].split("Total PSS by OOM", 1)[0]
    procs = [(int(k.replace(",", "")), n) for k, n in re.findall(r"^\s*([\d,]+)K: (\S+)", section, re.M)]
    procs.sort(reverse=True)
    lines = {k: int(v.replace(",", "")) for k, v in
             re.findall(r"^\s*(Total RAM|Free RAM|Used RAM|Lost RAM):\s+([\d,]+)K", text, re.M)}
    return {"pss_kb_by_process": procs, "launcher_pss_kb": sum(p for p, n in procs if n.split(":")[0] == home),
            "gms_pss_kb": sum(p for p, n in procs if n.startswith("com.google.android.gms")),
            "suspect_pss_kb": sum(p for p, n in procs if n.split(":")[0] in SUSPECTS),
            "summary_kb": lines}


def parse_ps(text):
    rows = [l.split(None, 4) for l in text.splitlines()[1:] if len(l.split(None, 4)) == 5]
    rows = [r for r in rows if r[0].isdigit() and r[0] != "2" and r[1] != "2" and r[4].strip() not in ("ps", "sh")]
    zygote = next((r[0] for r in rows if r[4].strip() == "zygote"), None)
    apps = [r for r in rows if r[1] == zygote]
    return {"userland_processes": len(rows), "app_processes": len(apps)}


def cpu_times(text):
    v = [int(x) for x in text.splitlines()[0].split()[1:8]]
    return sum(v), v[3], v[4]


def net_by_uid(text):
    rx, tx = {}, {}
    for line in text.splitlines()[1:]:
        p = line.split()
        if len(p) > 7 and p[2] == "0x0":
            rx[p[3]] = rx.get(p[3], 0) + int(p[5])
            tx[p[3]] = tx.get(p[3], 0) + int(p[7])
    return rx, tx


def remote_rows(text):
    """Outside-LAN TCP rows by owning uid. CLOSE_WAIT counts, because a short web request sits there once the
    server has answered. TIME_WAIT rows carry uid 0, so they are left out."""
    uids, ends = {}, {}
    for line in text.splitlines():
        p = line.split()
        if len(p) < 8 or ":" not in p[2] or p[3] not in ("01", "02", "08"):
            continue
        rem = p[2].split(":")[0]
        if rem.strip("0") == "":
            continue
        a = ".".join(str(b) for b in bytes.fromhex(rem[-8:])[::-1])
        if a.startswith(("192.168.", "127.", "10.")):
            continue
        uids[p[7]] = uids.get(p[7], 0) + 1
        ends.setdefault(p[7], set()).add(a)
    return uids, {u: sorted(v) for u, v in ends.items()}


def capture(out, home):
    r = {}
    for name, cmd in (("wakefulness", "dumpsys power | grep -E 'mWakefulness=|mDisplayReady'"),
                      ("focus", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"),
                      ("swaps", "cat /proc/swaps"), ("zram_mm", "cat /sys/block/zram0/mm_stat"),
                      ("lmk_minfree", "cat /sys/module/lowmemorykiller/parameters/minfree")):
        (out / f"{name}.txt").write_text(try_tv(cmd, 30))
    samples = []
    stat0, net0 = tv("cat /proc/stat"), tv("cat /proc/net/xt_qtaguid/stats")
    t0 = time.time()
    for i in range(3):
        m = tv("cat /proc/meminfo")
        (out / f"meminfo-{i}.txt").write_text(m)
        samples.append(parse_meminfo(m))
        (out / f"tcp6-{i}.txt").write_text(tv("cat /proc/net/tcp6"))
        time.sleep(24)
    stat1, net1 = tv("cat /proc/stat"), tv("cat /proc/net/xt_qtaguid/stats")
    elapsed = time.time() - t0
    (out / "stat0.txt").write_text(stat0), (out / "stat1.txt").write_text(stat1)
    (out / "xt0.txt").write_text(net0), (out / "xt1.txt").write_text(net1)
    total0, idle0, io0 = cpu_times(stat0)
    total1, idle1, io1 = cpu_times(stat1)
    dt = max(total1 - total0, 1)
    rx0, tx0 = net_by_uid(net0)
    rx1, tx1 = net_by_uid(net1)
    deltas = {u: {"rx": rx1.get(u, 0) - rx0.get(u, 0), "tx": tx1.get(u, 0) - tx0.get(u, 0)} for u in rx1}
    deltas = {u: d for u, d in deltas.items() if d["rx"] or d["tx"]}
    rows, ends = {}, {}
    for i in range(3):
        u, e = remote_rows((out / f"tcp6-{i}.txt").read_text())
        for k, v in u.items():
            rows[k] = rows.get(k, 0) + v
        for k, v in e.items():
            ends.setdefault(k, set()).update(v)
    for name in ("free -m", "dumpsys meminfo", "dumpsys activity processes", "dumpsys activity services",
                 "ps -A -o PID,PPID,USER,RSS,NAME", "df -k", "dumpsys diskstats"):
        (out / (name.replace(" ", "_").replace("-", "") + ".txt")).write_text(tv(name, 180))
    dm = parse_dumpsys_meminfo((out / "dumpsys_meminfo.txt").read_text(), home)
    pinfo = parse_ps((out / "ps_A_o_PID,PPID,USER,RSS,NAME.txt").read_text())
    procs_text = (out / "dumpsys_activity_processes.txt").read_text()
    df = {m[3]: {"total_kb": int(m[0]), "used_kb": int(m[1]), "avail_kb": int(m[2])} for m in
          re.findall(r"\s(\d+)\s+(\d+)\s+(\d+)\s+\d+%\s+(/data|/)$", (out / "df_k.txt").read_text(), re.M)}
    keys = ("MemTotal", "MemAvailable", "MemFree", "Cached", "Buffers", "SwapTotal", "SwapFree", "SwapCached")
    r.update({k + "_kB": int(statistics.median(s[k] for s in samples)) for k in keys if k in samples[0]})
    r.update(dm)
    r.update(pinfo)
    r["lru_procs"] = len(re.findall(r"Proc #\s*\d+:", procs_text))
    r["cpu_busy_pct"] = round(100 * (dt - (idle1 - idle0) - (io1 - io0)) / dt, 1)
    r["cpu_iowait_pct"] = round(100 * (io1 - io0) / dt, 1)
    r["window_s"] = round(elapsed, 1)
    r["net_delta_bytes_by_uid"] = deltas
    r["remote_tcp_rows_by_uid"] = rows
    r["remote_endpoints_by_uid"] = {u: sorted(v) for u, v in ends.items()}
    r["df_kb"] = df
    return r


def app_launches(out, rounds):
    res = {}
    comps = {}
    for pkg in TEST_APPS:
        comps[pkg] = None
        # A phone app such as com.netflix.mediaclient has no LEANBACK_LAUNCHER entry, only LAUNCHER.
        for category in ("LEANBACK_LAUNCHER", "LAUNCHER"):
            lines = tv("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                       f"-c android.intent.category.{category} {pkg}").strip().splitlines()
            if lines and re.fullmatch(r"[\w.]+/[\w.$]+", lines[-1].strip()):
                comps[pkg] = lines[-1].strip()
                break
        if not comps[pkg]:
            log("not launchable, not measured:", pkg)
    raw = []
    for i in range(rounds):
        tv("input keyevent 3")
        time.sleep(6)
        for pkg, comp in comps.items():
            if not comp:
                continue
            tv(f"am force-stop {pkg}")
            time.sleep(3)
            text = tv(f"am start -W -n {comp}", timeout=90)
            raw.append(f"--- round {i} {comp}\n{text}")
            m = re.search(r"TotalTime: (\d+)", text)
            w = re.search(r"WaitTime: (\d+)", text)
            res.setdefault(pkg, []).append({"total_ms": int(m.group(1)) if m else None,
                                            "wait_ms": int(w.group(1)) if w else None})
            log(pkg, res[pkg][-1])
            time.sleep(10)
            tv("input keyevent 3")
            time.sleep(5)
    (out / "app_launch_raw.txt").write_text("\n".join(raw))
    return {"components": comps, "runs": res}


def spread(values):
    v = [x for x in values if x is not None]
    return {"median": statistics.median(v), "min": min(v), "max": max(v), "n": len(v)} if v else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("--boots", type=int, default=3)
    ap.add_argument("--idle", type=int, default=300)
    ap.add_argument("--app-rounds", type=int, default=3)
    a = ap.parse_args()
    out = REPO / "benchmarks" / "runs" / f"{time.strftime('%Y%m%d-%H%M')}-{a.label}"
    out.mkdir(parents=True)
    log("writing to", out)
    home = home_package()
    summary = {"label": a.label, "idle_s": a.idle, "home": home, "started": time.strftime("%F %T"), "boots": []}
    for n in range(a.boots):
        bdir = out / f"boot{n + 1}"
        bdir.mkdir()
        log(f"boot {n + 1}/{a.boots}: rebooting")
        b = reboot_timed(home)
        log("timing", {k: v for k, v in b.items() if k != "t_boot_epoch"})
        ev = try_tv("logcat -d -b events | grep -E 'boot_progress|wm_boot_animation_done|sf_stop_bootanim'", 30)
        (bdir / "boot_events.txt").write_text(ev)
        b.update(boot_events(ev))
        wait = b["t_boot_epoch"] + a.idle - time.time()
        log(f"idle {max(wait, 0):.0f} s, no input")
        time.sleep(max(wait, 0))
        b.update(capture(bdir, home))
        for pkg in SUSPECTS:
            info = try_tv(f"dumpsys package {pkg} | grep -E 'firstInstallTime|lastUpdateTime' | head -2", 30)
            b[f"pkg_{pkg}"] = " ".join(info.split())
        summary["boots"].append(b)
        (out / "summary.json").write_text(json.dumps(summary, indent=1))
        log("captured:", {k: b.get(k) for k in ("MemAvailable_kB", "MemFree_kB", "launcher_pss_kb", "suspect_pss_kb",
                                                 "userland_processes", "cpu_busy_pct")})
    subprocess.run([PY, "-I", TV_PY, "--screenshot", str(out / "home.png"), "HOME", "--wait", "4"],
                   capture_output=True, cwd=REPO)
    log("bench.py on", home)
    with open(out / "bench.log", "w") as f:
        subprocess.run([PY, "-I", str(REPO / "tools/bench.py"), "--keys", "36", "--series", "3", "--cold", "5",
                        "--returns", "10", "--idle", "60", "--out-dir", str(out / "bench")],
                       stdout=f, stderr=subprocess.STDOUT, cwd=REPO, timeout=1500)
    log("app launches")
    summary["apps"] = app_launches(out, a.app_rounds)
    tv("input keyevent 3")
    b = summary["boots"]
    summary["median_range"] = {k: spread([x.get(k) for x in b]) for k in (
        "adb_s", "boot_completed_s", "home_resumed_s", "uptime_at_home_s", "boot_progress_enable_screen_ms",
        "MemTotal_kB", "MemAvailable_kB", "MemFree_kB", "Cached_kB", "SwapFree_kB", "launcher_pss_kb", "gms_pss_kb",
        "suspect_pss_kb", "userland_processes", "app_processes", "lru_procs", "cpu_busy_pct", "cpu_iowait_pct")}
    summary["median_range"]["app_launch_total_ms"] = {p: spread([r["total_ms"] for r in rs])
                                                      for p, rs in summary["apps"]["runs"].items()}
    summary["finished"] = time.strftime("%F %T")
    (out / "summary.json").write_text(json.dumps(summary, indent=1))
    log("DONE", out)


if __name__ == "__main__":
    sys.exit(main())
