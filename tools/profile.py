#!/usr/bin/env python3
"""Ask for the details a design needs, once, and keep them on this computer in ~/.tvboost/profile.json.
The place is not one of them: each device works out its own, unless --city sets one for this run.

    profile.py name <config.json>         ask for the name on the welcome screen
    profile.py city <config.json> <city>  set a fixed city in a design's config.json
    profile.py redirect <config.json> <package>  name the stock launcher that stays Home, or pass "" for none
"""
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HOME = Path.home() / ".tvboost"
PROFILE = HOME / "profile.json"


def load(path, empty):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return empty


def fill_name(config_path):
    profile = load(PROFILE, {})
    if sys.stdin.isatty():
        default = profile.get("name", "")
        shown = f"Name for the welcome screen [{default}]: " if default else "Name for the welcome screen: "
        try:
            profile["name"] = input(shown).strip() or default
        except EOFError:
            pass
        HOME.mkdir(parents=True, exist_ok=True)
        PROFILE.write_text(json.dumps(profile, indent=2, ensure_ascii=False) + "\n")
    config = load(Path(config_path), {})
    if profile.get("name"):
        config["greeting_name"] = profile["name"]
    Path(config_path).write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n")


def set_city(config_path, name):
    url = "https://geocoding-api.open-meteo.com/v1/search?" + urllib.parse.urlencode({"name": name, "count": 1})
    try:
        with urllib.request.urlopen(url, timeout=10) as reply:
            results = json.load(reply).get("results") or []
    except (OSError, ValueError):
        results = []
    if not results:
        sys.exit(f"could not find {name}: check the spelling and your internet connection")
    found = results[0]
    print("Using", ", ".join(p for p in (found.get("name"), found.get("admin1"), found.get("country")) if p))
    config = load(Path(config_path), {})
    config.update(latitude=str(found["latitude"]), longitude=str(found["longitude"]), location_name=found["name"])
    Path(config_path).write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n")


def set_redirect(config_path, package):
    config = load(Path(config_path), {})
    if package:
        config["stock_home_redirect"] = package
    else:
        config.pop("stock_home_redirect", None)
    Path(config_path).write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n")


def main():
    args = sys.argv[1:]
    if len(args) == 2 and args[0] == "name":
        fill_name(args[1])
    elif len(args) == 3 and args[0] == "city":
        set_city(args[1], args[2])
    elif len(args) == 3 and args[0] == "redirect":
        set_redirect(args[1], args[2])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
