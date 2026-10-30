#!/usr/bin/env python3
"""Ask which apps to disable before anything on the box changes, and write the answer as a list. Nothing is
disabled without this choice: an install that cannot ask, and has no saved choice, stops here.

    choose_apps.py <device id> <output list> [--saved]

--saved reuses the last choice for this device without asking, for identical boxes. Choices are kept in
~/.tvbox-revive/choices. Exit status: 0 a list was written, 1 there was no way to ask and no saved choice.
"""
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHOICES = Path.home() / ".tvbox-revive" / "choices"


def ordered_list(device):
    return [ln.split("#")[0].strip() for ln in (ROOT / "devices" / device / "debloat.txt").read_text().splitlines()
            if ln.split("#")[0].strip()]


def main():
    args = [a for a in sys.argv[1:] if a != "--saved"]
    if len(args) != 2:
        sys.exit(__doc__)
    device, out = args
    saved = CHOICES / f"{device}.txt"
    if "--saved" in sys.argv[1:] or (saved.is_file() and not sys.stdin.isatty()):
        if not saved.is_file():
            sys.exit("no saved app choice yet: run the installer once where it can ask")
        shutil.copy(saved, out)
        print(f"using your saved choice: {len(saved.read_text().split())} apps to disable")
        return
    if not sys.stdin.isatty():
        sys.exit("the installer has to ask which apps to disable: run it in a terminal")
    if saved.is_file():
        count = len(saved.read_text().split())
        if input(f"Use your saved choice of {count} apps to disable? (Y/n): ").strip().lower() not in ("n", "no"):
            shutil.copy(saved, out)
            return
    profile = json.loads((ROOT / "devices" / device / "debloat-profile.json").read_text())
    about = {p["package"]: p for p in profile["packages"]}
    packages = ordered_list(device)
    print("\nThese apps are suggested for disabling. Disabled apps stay on the box and can come back.\n")
    for number, package in enumerate(packages, 1):
        info = about.get(package, {})
        print(f"{number:3}. {package}")
        print(f"     {info.get('reason', 'No notes')}")
        side = info.get("side_effects", "")
        if side and side != "None seen":
            print(f"     If disabled: {side}")
    print("\nType the numbers to KEEP, separated by spaces, such as: 3 12")
    print("Press Enter to disable all of them. Type 'all' to keep all of them.")
    while True:
        reply = input("Keep: ").strip().lower()
        if reply == "all":
            keep = set(range(1, len(packages) + 1))
            break
        try:
            keep = {int(n) for n in reply.replace(",", " ").split()}
        except ValueError:
            print("Use numbers, Enter or 'all'.")
            continue
        if keep - set(range(1, len(packages) + 1)):
            print(f"Numbers go from 1 to {len(packages)}.")
            continue
        break
    chosen = [p for n, p in enumerate(packages, 1) if n not in keep]
    kept = [p for n, p in enumerate(packages, 1) if n in keep]
    print(f"\nDisable {len(chosen)}, keep {len(kept)}.")
    for package in kept:
        print(f"  keep {package}")
    if input("Go ahead? (y/N): ").strip().lower() not in ("y", "yes"):
        sys.exit("cancelled: nothing was changed")
    CHOICES.mkdir(parents=True, exist_ok=True)
    text = "".join(p + "\n" for p in chosen)
    saved.write_text(text)
    Path(out).write_text(text)


if __name__ == "__main__":
    main()
