# Optimization profiles

Optimization lives in each device folder, because a list that suits one box can break another.

| File | What it holds |
|---|---|
| `devices/<box>/debloat.txt` | The packages to disable, in order |
| `devices/<box>/debloat-profile.json` | The reason, measured RAM, side effects and restore command for each package |
| `devices/<box>/dns-config.json` | DNS ad blocking, its privacy trade-off and its limits |

Rules:

- Disable, never uninstall. Everything can be re-enabled.
- No root, no firmware changes and no boot partition changes.
- No memory-clearing scripts, no killing processes, and no changes to Android's memory management.
- A saving is only claimed once `benchmarks/measure.py` has measured it.
