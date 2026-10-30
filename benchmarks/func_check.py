#!/usr/bin/env python3
"""Function test for this box, run once before any change and again after every batch. It changes
nothing on the TV: it presses keys, opens and force-stops apps, plays one public YouTube video and
reads state. A result only means what its evidence says, so each test keeps that evidence.

    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I benchmarks/func_check.py --label pre-change
    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I benchmarks/func_check.py --net-only 300
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from measure import PY, REPO, TV_PY, log, net_by_uid, remote_rows, try_tv, tv  # noqa: E402

APPS = {"settings": "com.android.tv.settings", "youtube": "com.google.android.youtube.tv",
        "netflix": "com.netflix.mediaclient", "prime": "com.amazon.amazonvideo.livingroom",
        "playstore": "com.android.vending", "hotstar": "in.startv.hotstar", "chrome": "com.android.chrome"}
YOUTUBE = APPS["youtube"]
HOME_APPS = ("com.google.android.tvlauncher", "com.tvboxrevive.home")
VIDEO = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"   # Big Buck Bunny, public and long enough


def focus():
    said = try_tv("dumpsys window | grep mCurrentFocus", 20)
    m = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}", said)
    return m.group(1) if m else said.strip()


def key(name):
    try_tv(f"input keyevent KEYCODE_{name}", 15)


def shot(path):
    subprocess.run([PY, "-I", TV_PY, "--screenshot", str(path)], capture_output=True, cwd=REPO, timeout=60)


def component(pkg):
    for category in ("LEANBACK_LAUNCHER", "LAUNCHER"):
        lines = tv("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                   f"-c android.intent.category.{category} {pkg}").strip().splitlines()
        if lines and re.fullmatch(r"[\w.]+/[\w.$]+", lines[-1].strip()):
            return lines[-1].strip()
    return None


def check_network():
    conn = try_tv("dumpsys connectivity", 30)
    return {"wifi_validated": "VALIDATED" in conn and "WIFI" in conn,
            "ip_ping": " 0% packet loss" in try_tv("ping -c 2 -W 3 8.8.8.8", 15),
            "dns_ping": " 0% packet loss" in try_tv("ping -c 2 -W 3 www.google.com", 15),
            "private_dns": " ".join(try_tv("settings get global private_dns_mode; "
                                           "settings get global private_dns_specifier", 15).split()),
            "dns_lines": re.findall(r".*(?:PrivateDns|DnsAddresses).*", conn)[:4]}


def check_bluetooth():
    said = try_tv("dumpsys bluetooth_manager", 30)
    return {"enabled": "enabled: true" in said,
            "remote_input_present": "MG4-3" in try_tv("dumpsys input | grep -m1 MG4-3", 20)}


def check_home(out):
    key("HOME")
    time.sleep(3)
    home = focus()
    shot(out / "home.png")
    key("DPAD_RIGHT"), key("DPAD_DOWN"), key("DPAD_LEFT")
    time.sleep(1)
    key("BACK")
    time.sleep(2)
    after_back = focus()
    key("HOME")
    time.sleep(2)
    return {"focus": home, "after_dpad_and_back": after_back, "ok": home == after_back and any(n in home for n in HOME_APPS)}


def check_voice(out):
    """Sends the key the remote's Generic.kl maps to Assist. It proves the route to the search screen,
    not the remote's microphone."""
    key("HOME")
    time.sleep(2)
    key("ASSIST")
    time.sleep(4)
    # Assistant is an overlay window from katniss:interactor, not an activity, so the launcher stays
    # resumed. What moves is the input focus, and the screenshot shows what opened.
    now = focus()
    recording = try_tv("dumpsys audio | grep -A6 -iE 'recording activity|recording configs'", 20)
    shot(out / "voice.png")
    key("BACK")
    time.sleep(1)
    key("BACK")
    time.sleep(1)
    key("HOME")
    time.sleep(2)
    return {"focus": now, "ok": bool(now) and not any(n in now for n in HOME_APPS), "recording": recording.strip()[:400]}


def check_app(name, pkg, out):
    comp = component(pkg)
    if not comp:
        return {"ok": False, "why": "no launcher entry"}
    try_tv(f"am force-stop {pkg}", 20)
    said = try_tv(f"am start -W -n {comp}", 60)
    time.sleep(7)
    now = focus()
    shot(out / f"{name}.png")
    key("HOME")
    time.sleep(2)
    total = re.search(r"TotalTime: (\d+)", said)
    return {"focus": now, "ok": pkg in now and "Error" not in now and "Not Responding" not in now,
            "total_ms": int(total.group(1)) if total else None}


def playback_state():
    said = try_tv("dumpsys media_session", 20)
    m = re.search(rf"package={re.escape(YOUTUBE)}.*?state=PlaybackState \{{state=(\d+)", said, re.S)
    return int(m.group(1)) if m else None


def uid_rx(uid):
    return net_by_uid(tv("cat /proc/net/xt_qtaguid/stats"))[0].get(uid, 0)


def check_playback(out):
    """The session's position only changes when the app reports it, so it cannot show progress. State 3
    (playing), audio out of standby and YouTube's own bytes arriving can."""
    uid = re.search(r"userId=(\d+)", try_tv(f"dumpsys package {YOUTUBE} | grep -m1 userId", 20)).group(1)
    try_tv(f"am force-stop {YOUTUBE}", 20)
    try_tv(f"am start -W -a android.intent.action.VIEW -d '{VIDEO}' {YOUTUBE}", 60)
    time.sleep(25)
    rx0 = uid_rx(uid)
    time.sleep(15)
    rx1 = uid_rx(uid)
    state = playback_state()
    audio = try_tv("dumpsys media.audio_flinger | grep -E 'Standby:' | head -4", 20)
    now = focus()
    shot(out / "youtube-playing.png")
    key("HOME")
    time.sleep(2)
    try_tv(f"am force-stop {YOUTUBE}", 20)
    return {"focus": now, "state": state, "rx_15s_bytes": rx1 - rx0, "audio_flinger": audio.strip(),
            "ok": state == 3 and "Standby: no" in audio and YOUTUBE in now}


def crashes_since(stamp):
    said = try_tv(f"logcat -d -v time -T '{stamp}' -b crash -b main -b system", 60)
    # Normal cache kills also say "has died", so only a real crash or a hang counts here.
    lines = [ln for ln in said.splitlines() if re.search(r"FATAL EXCEPTION|ANR in |E/AndroidRuntime.*Process: ", ln)]
    return [ln[:160] for ln in lines][:30]


def net_watch(seconds):
    """Which outside hosts the box keeps talking to, and how much UID 1000 moves, over a window."""
    net0, t0 = tv("cat /proc/net/xt_qtaguid/stats"), time.time()
    rows, ends = {}, {}
    while time.time() - t0 < seconds:
        text = tv("cat /proc/net/tcp /proc/net/tcp6")
        uids, seen = remote_rows(text)
        for u, n in uids.items():
            rows[u] = rows.get(u, 0) + n
        for u, hosts in seen.items():
            ends.setdefault(u, set()).update(hosts)
        time.sleep(5)
    net1 = tv("cat /proc/net/xt_qtaguid/stats")
    rx0, tx0 = net_by_uid(net0)
    rx1, tx1 = net_by_uid(net1)
    delta = {u: {"rx": rx1.get(u, 0) - rx0.get(u, 0), "tx": tx1.get(u, 0) - tx0.get(u, 0)} for u in rx1}
    return {"window_s": round(time.time() - t0), "uid1000_since_boot_rx": rx1.get("1000", 0),
            "uid1000_since_boot_tx": tx1.get("1000", 0),
            "uid1000_window": delta.get("1000", {"rx": 0, "tx": 0}),
            "rows_by_uid": rows, "hosts_by_uid": {u: sorted(v) for u, v in ends.items()}}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default="check")
    ap.add_argument("--net-only", type=int, default=0, help="only watch the network for this many seconds")
    args = ap.parse_args()
    out = REPO / "benchmarks" / "runs" / f"{time.strftime('%Y%m%d-%H%M')}-check-{args.label}"
    out.mkdir(parents=True, exist_ok=True)
    r = {"label": args.label, "time": time.strftime("%Y-%m-%d %H:%M:%S")}
    if not args.net_only:
        stamp = try_tv("date '+%m-%d %H:%M:%S.000'", 15).strip()
        r["pid_system_server_before"] = try_tv("pidof system_server", 15).strip()
        r["network"] = check_network()
        r["bluetooth"] = check_bluetooth()
        r["home"] = check_home(out)
        r["voice"] = check_voice(out)
        for name, pkg in APPS.items():
            log("app", name)
            r[name] = check_app(name, pkg, out)
        log("playback")
        r["youtube_playback"] = check_playback(out)
        r["pid_system_server_after"] = try_tv("pidof system_server", 15).strip()
        r["crashes"] = crashes_since(stamp)
        r["disabled"] = sorted(try_tv("pm list packages -d --user 0", 20).split())
    r["net"] = net_watch(args.net_only or 60)
    (out / "result.json").write_text(json.dumps(r, indent=2))
    if not args.net_only:
        for name in ("home", "voice", *APPS, "youtube_playback"):
            print(f"{'PASS' if r[name].get('ok') else 'FAIL'}  {name:18} {r[name].get('focus', r[name].get('why'))}")
        print("network  ", {k: v for k, v in r["network"].items() if k != "dns_lines"})
        print("bluetooth", r["bluetooth"])
        print("playback ", {k: r["youtube_playback"][k] for k in ("state", "rx_15s_bytes", "audio_flinger")})
        print("system_server pid", r["pid_system_server_before"], "->", r["pid_system_server_after"])
        print("crashes  ", len(r["crashes"]))
        for line in r["crashes"]:
            print("   ", line)
    print("net      ", {k: v for k, v in r["net"].items() if k != "hosts_by_uid"})
    print("hosts    ", r["net"]["hosts_by_uid"])
    print("saved in ", out)


if __name__ == "__main__":
    main()
