# Device compatibility

> **Do not install device-specific optimization profiles on unverified hardware.**

## Verified

| Box | Firmware build | Chip | Android | Tested |
|---|---|---|---|---|
| I96 8K | `I96_8K-20251222-301SMSX300` | HiSilicon Hi3798MV300 | 9 (SDK 28) | Full test run, see [benchmarks](../devices/i96-8k-hi3798mv300/benchmarks/) |

## Universal mode

Any other Android 8+ device with ADB access can use `./install.sh --universal <ip>`. It adds the launcher, a design, your logo and DNS,
and disables nothing except the stock launcher. On Google TV it does not disable that either: the stock launcher stays, and the Home button
opens the new launcher.

### Tested with universal mode

| Device | Firmware build | Android | Result |
|---|---|---|---|
| TCL 55T89C | `UTT2.250416.001 AU02` | 14 (SDK 34), Google TV | Install in about 70 seconds with nothing disabled. Both designs, logo and no logo, Inputs (HDMI 1 to 4), RAM and storage tile, automatic location, ad blocking and undo all worked. The Home button opened the launcher from an app, from Settings and from a TV input |

Not tested on that TV yet: restarting it with the launcher installed (run with `--no-reboot`), and the remote's app buttons. Undo was checked by
comparing every app, setting and Home entry with a snapshot taken first.

## Unverified

Every other device, including other firmware builds of the I96 8K and boxes with the same name and a different chip. The installer
refuses the full setup on these and changes nothing unless you ask for `--universal`.

| Known limits | Detail |
|---|---|
| Android 11 and newer | Network ADB often needs pairing first |
| Google TV | The setup wizard claims Home once the stock launcher is off, so the installer leaves it on. Never disable the wizard: that can delete the saved Wi-Fi network |
| Samsung Tizen, LG webOS, Roku | Not supported: they do not run Android |

## Why matching is this strict

Budget TV boxes often report false details. The verified box labels itself Android 14, 8K and Amlogic, and it is really
Android 9, 1080p and HiSilicon. A model name proves nothing, so the installer compares the firmware build, the chip and the
SDK level with what was tested.

## Use it on another box

The launcher and designs are shared, and they can suit any box running Android 8 or newer. The debloat list is not shared. See
[devices/README.md](../devices/README.md) to add a profile.
