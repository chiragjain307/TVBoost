# Device compatibility

> **Do not install device-specific optimization profiles on unverified hardware.**

## Verified

| Box | Firmware build | Chip | Android | Tested |
|---|---|---|---|---|
| I96 8K | `I96_8K-20251222-301SMSX300` | HiSilicon Hi3798MV300 | 9 (SDK 28) | Full test run, see [benchmarks](../devices/i96-8k-hi3798mv300/benchmarks/) |

## Universal mode

Any other Android 8+ device with ADB access can use `./install.sh --universal <ip>`. It adds the launcher, a design, your logo and DNS,
and disables nothing except the stock launcher. It has only been tested on the verified box so far.

## Unverified

Every other device, including other firmware builds of the I96 8K and boxes with the same name and a different chip. The installer
refuses the full setup on these and changes nothing unless you ask for `--universal`.

| Known limits | Detail |
|---|---|
| Android 11 and newer | Network ADB often needs pairing first |
| Google TV | Home is harder to replace, and the setup wizard can claim Home |
| Samsung Tizen, LG webOS, Roku | Not supported: they do not run Android |

## Why matching is this strict

Budget TV boxes often report false details. The verified box labels itself Android 14, 8K and Amlogic, and it is really
Android 9, 1080p and HiSilicon. A model name proves nothing, so the installer compares the firmware build, the chip and the
SDK level with what was tested.

## Use it on another box

The launcher and designs are shared, and they can suit any box running Android 8 or newer. The debloat list is not shared. See
[devices/README.md](../devices/README.md) to add a profile.
