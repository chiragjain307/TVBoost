# Device profiles

Each folder is one verified firmware build. `install.sh` reads the box's properties and only uses a profile
when every value in `device.json`'s `match` block is identical. No match means nothing changes on the box.

| Folder | Box | Status |
|---|---|---|
| [i96-8k-hi3798mv300](i96-8k-hi3798mv300/) | I96 8K Android TV box | Verified |

A profile never uses an IP address or a serial number as its identity.

## Add a box

1. Copy a device folder and rename it `<model>-<chip>`.
2. Set `match` in `device.json` from `getprop`, and list the box in `registry.json`.
3. Build `debloat.txt` from that box's own package list. Never reuse another device's list.
4. Test with `benchmarks/func_check.py` and `benchmarks/measure.py`, and add it to `docs/DEVICE_COMPATIBILITY.md`.
