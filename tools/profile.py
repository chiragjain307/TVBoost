#!/usr/bin/env python3
"""Ask for the details a design needs, once, and keep them on this computer in ~/.tvbox-revive/profile.json:
the name on the welcome screen and the city for the temperature and sun times.

    profile.py ask <config.json> name city     ask, then write the answers into a design's config.json
"""
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HOME = Path.home() / ".tvbox-revive"
PROFILE = HOME / "profile.json"


def load(path, empty):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return empty


def save(path, value):
    HOME.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def ask(prompt, default=""):
    shown = f"{prompt} [{default}]: " if default else f"{prompt}: "
    try:
        return input(shown).strip() or default
    except EOFError:
        return default


def find_city(name):
    url = "https://geocoding-api.open-meteo.com/v1/search?" + urllib.parse.urlencode({"name": name, "count": 1})
    try:
        with urllib.request.urlopen(url, timeout=10) as reply:
            results = json.load(reply).get("results") or []
    except (OSError, ValueError):
        return None
    return results[0] if results else None


def ask_city(profile):
    while True:
        city = ask("City for the temperature and sun times", profile.get("city", ""))
        if not city:
            return
        if city == profile.get("city") and profile.get("latitude"):
            return
        found = find_city(city)
        if not found:
            print(f"Could not find {city}. Check the spelling and your internet connection.")
            if not sys.stdin.isatty():
                return
            continue
        place = ", ".join(p for p in (found.get("name"), found.get("admin1"), found.get("country")) if p)
        print(f"Using {place}")
        profile.update(city=found["name"], latitude=str(found["latitude"]), longitude=str(found["longitude"]))
        return


def fill_config(config_path, wanted):
    profile = load(PROFILE, {})
    if sys.stdin.isatty():
        if "name" in wanted:
            profile["name"] = ask("Name for the welcome screen", profile.get("name", ""))
        if "city" in wanted:
            ask_city(profile)
        save(PROFILE, profile)
    config = load(Path(config_path), {})
    if profile.get("name"):
        config["greeting_name"] = profile["name"]
    if profile.get("latitude"):
        config.update(latitude=profile["latitude"], longitude=profile["longitude"], location_name=profile.get("city", ""))
    Path(config_path).write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n")


def main():
    args = sys.argv[1:]
    if len(args) >= 2 and args[0] == "ask":
        fill_config(args[1], args[2:])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
