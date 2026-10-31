#!/usr/bin/env python3
"""Short health check after an install. It only looks at what can differ between two boxes of the same
firmware: did the setup land, is the box online, and has hidden traffic stopped. App behaviour was tested
on the test box and is not tested again. It changes nothing, apart from the optional reboot.

    TV_HOST=<ip> TV_PORT=<port> tools/venv/bin/python -I tools/health_check.py <device id> [--reboot]

Exit status: 0 everything passed, 1 something failed.
"""
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "benchmarks"))
from func_check import focus, net_watch  # noqa: E402
from measure import HOME_Q, reboot_timed, try_tv  # noqa: E402

HOME = "com.tvboost.home"
MAX_SYSTEM_BYTES = 200_000   # the system account moved about 1 KB per boot once the agents were off
failed = []


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}  {name:26} {detail}", flush=True)
    if not ok:
        failed.append(name)


def resolved(host):
    m = re.search(r"\((\d+\.\d+\.\d+\.\d+)\)", try_tv(f"ping -c 1 -W 2 {host}", 12))
    return m.group(1) if m else ""


def main():
    args = [a for a in sys.argv[1:] if a != "--reboot"]
    if len(args) != 1:
        sys.exit("usage: health_check.py <device id> [--reboot]")
    if "--reboot" in sys.argv:
        try:
            boot = reboot_timed(HOME)
        except RuntimeError as e:
            print(f"FAIL  the box did not come back after the reboot ({e}).")
            print("      Unplug its power for 10 seconds, plug it back in, wait 2 minutes, then run this again.")
            return 1
        check("reboot to Home", boot["home_resumed_s"] is not None, f"{boot['home_resumed_s']} s")
    verified = args[0] != "universal"
    if verified:
        # What the owner chose to disable, not the full suggested list: a kept app is not a failure.
        chosen = Path.home() / ".tvboost" / f"selected-{os.environ.get('TV_HOST', '')}.txt"
        debloat = chosen if chosen.is_file() else ROOT / "devices" / args[0] / "debloat.txt"
        listed = [ln.split("#")[0].strip() for ln in debloat.read_text().splitlines()]
        listed = [p for p in listed if p]
        enabled = set(re.findall(r"package:(\S+)", try_tv("pm list packages -e --user 0", 30)))
        still_on = [p for p in listed if p in enabled]
        check("listed apps disabled", not still_on, ", ".join(still_on) or f"{len(listed)} listed")
    if (Path.home() / ".tvboost" / f"stock-stays-{os.environ.get('TV_HOST', '')}").is_file():
        services = try_tv("settings get secure enabled_accessibility_services", 15)
        check("Home button service", "HomeKeyService" in services, services.strip())
        try_tv("input keyevent KEYCODE_HOME; sleep 4", 15)   # here Home only appears when the Home button asks for it
    else:
        owner = try_tv(HOME_Q, 20).strip().splitlines()
        check("Home owner", bool(owner) and owner[-1].startswith(HOME), owner[-1] if owner else "no answer")
    check("Home on screen", HOME in focus(), focus())
    if verified:
        check("voice setting kept", "katniss" in try_tv("settings get secure voice_interaction_service", 15))
    check("Wi-Fi online", "VALIDATED" in try_tv("dumpsys connectivity", 30))
    google = resolved("www.google.com")
    check("normal sites resolve", bool(google) and not google.startswith(("0.", "127.")), google)
    if "hostname" in try_tv("settings get global private_dns_mode", 15):
        blocked = resolved("doubleclick.net")
        check("ad domain blocked", not blocked or blocked.startswith(("0.", "127.")), blocked or "no address")
    if not verified:
        print("HEALTH CHECK PASSED" if not failed else f"HEALTH CHECK FAILED: {', '.join(failed)}")
        return 1 if failed else 0
    net = net_watch(30)
    moved = net["uid1000_since_boot_rx"] + net["uid1000_since_boot_tx"]
    check("system traffic quiet", moved < MAX_SYSTEM_BYTES, f"{moved} bytes since boot")
    # One small web request to the vendor's server still happens at boot, about 1 KB, and its source is not
    # known. It is reported here so a change is visible. The traffic check above is the one that fails a box.
    hosts = net["hosts_by_uid"].get("1000", [])
    if hosts:
        print(f"NOTE  system account connected to: {', '.join(hosts)}", flush=True)
    print("HEALTH CHECK PASSED" if not failed else f"HEALTH CHECK FAILED: {', '.join(failed)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
