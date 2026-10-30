# Project inventory

Each part is marked **shared**, **device** or **optional**. Shared parts work on any compatible box, device parts belong to one firmware
build, and optional parts can be turned off.

## Commands

| File | Role | Type |
|---|---|---|
| `install.sh` | Match, back up, install, disable, apply design, DNS, reboot, health check. Has `--dry-run` | Shared |
| `undo.sh` | Restore packages, stock Home and DNS, remove the launcher | Shared |
| `design.sh` | List, read and switch designs, and ask about a logo | Shared |
| `build.sh` | Run launcher tests and build | Shared |

## Launcher

| Path | Role | Type |
|---|---|---|
| `launcher/app/src/main/java/com/tvboost/home/` | Launcher source | Shared |
| `.../ClockScene.java` | Screensaver, with the `clock.jpg` picture and the side-column clock | Shared |
| `.../Config.java`, `res/values/config.xml` | Defaults that a design overrides | Shared |
| `.../HomeExtras.java` | Welcome screen and the RAM and storage tile. Off unless a design asks | Optional |
| `.../SystemStats.java` | Tile numbers, and reading `df` | Optional |
| `launcher/helper/` | Helper app for the temperature. Holds the only internet permission | Optional |
| `launcher/app/src/test/` | 309 unit tests | Shared |
| `launcher/build-and-install.sh` | Install with Home handover and rollback | Shared |
| `launcher/RELEASE.sha256` | Hash of the released APK, checked by `install.sh` | Shared |

## Tools

| File | Role | Type |
|---|---|---|
| `tools/tv.py`, `tools/b64push.py` | ADB connection and file transfer | Shared |
| `tools/device_match.py` | Match a box to a profile, or refuse | Shared |
| `tools/backup.py` | Recovery manifest before each install | Shared |
| `tools/choose_apps.py` | Ask which apps to keep before anything is disabled | Shared |
| `tools/profile.py` | Name for the welcome screen, kept on this computer, and the optional `--city` lookup | Optional |
| `tools/common.sh` | Find the device's ADB port | Shared |
| `tools/recovery.py`, `tools/disable.sh`, `tools/restore.sh` | Undo record, guarded disabling, restoring | Shared |
| `tools/check.py` | Find what a firmware update changed | Shared |
| `tools/theme.sh`, `tools/theme_restart.py` | Push a design and restart Home | Shared |
| `tools/make_design_images.py` | Draw design pictures, take colours from a logo, bake the glass | Shared |
| `tools/health_check.py` | Short check after install | Shared |
| `tools/check_public.sh`, `.githooks/pre-push` | Block private data and logos from a push | Shared |
| `tools/privacy.py` | Audit which apps can reach the network | Shared |
| `tools/adb-closed.py` | Confirm that ADB is closed | Shared |
| `tools/bench.py` | Launcher speed benchmark | Shared |

## Device profile

| File | Role | Type |
|---|---|---|
| `devices/registry.json` | Known profiles | Shared |
| `devices/<box>/device.json` | Identity, match rules, hardware | Device |
| `devices/<box>/debloat.txt` | Packages to disable | Device |
| `devices/<box>/debloat-profile.json` | Reason, RAM, side effects and restore for each package | Device |
| `devices/<box>/dns-config.json` | DNS setup, trade-offs and undo | Optional |
| `devices/<box>/*.md`, `box.jpg`, `benchmarks/` | Hardware, install, recovery and results | Device |

## Designs and assets

| Path | Role | Type |
|---|---|---|
| `designs/<name>/` | One saved look | Optional |
| `assets/` | Screenshots and the sample logo | Shared |
| `docs/design-system/` | Colours, type, layout, components | Shared |

## Tests and measurement

| Path | Role | Type |
|---|---|---|
| `tests/` | Tests for the kept tools, using stubs | Shared |
| `benchmarks/measure.py` | Boot, memory, CPU, network and launch measurements | Shared |
| `benchmarks/func_check.py` | Function test on a real box | Shared |
| `benchmarks/launcher_check.py`, `clock_check.py`, `soak.py`, `after_batch.py` | Focused real-box tests | Shared |
