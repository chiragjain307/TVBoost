# Changelog

This project uses semantic versioning. Device support is tracked in [docs/DEVICE_COMPATIBILITY.md](docs/DEVICE_COMPATIBILITY.md).

## 1.0.0

First public release.

**Verified devices:** I96 8K, build `I96_8K-20251222-301SMSX300`, Android 9. Also tested with universal mode: TCL 55T89C, Google TV on Android 14. Other Android 8+ devices can use universal mode, untested.

**Added**

- One-command install with a dry-run mode, and firmware matching that refuses unverified boxes
- Universal mode for other Android 8+ devices: launcher, design and DNS, no debloat
- Google TV support: nothing is disabled, the stock launcher stays, and the Home button opens the launcher
- `--no-reboot`, for devices where network debugging may not survive a restart
- Backup before every install, and one-command undo
- A short health check after each install
- Saved designs that switch in seconds: `01-navy-gold` and `02-liquid-glass`
- A welcome screen with greeting, date, time, temperature, sunrise and sunset, which slides away on OK
- A live RAM and storage tile beside Wi-Fi, with an Inputs button to its left on TVs with HDMI ports
- All apps in one row that scrolls sideways, so nothing covers the logo
- A small helper app that holds the only internet access, so the launcher keeps none
- Automatic location: each device finds its own city for the temperature and sun times, and `--city` fixes one if it is wrong
- Your own logo on Home and the screensaver, with the design's colours taken from it
- A choice of which apps to keep before anything is disabled
- A screensaver mode with a centred logo and a clock that stays clear of it
- AdGuard DNS ad blocking, which can be turned off with `--no-dns`
- A push check that blocks logos, private addresses and keys

**Known issues**

- Automatic location comes from the internet connection, and it found the right city on both tested devices. It can be off by a nearby city. Android does not give its own location to a background helper, so that path does not help on the tested Android 14 TV
- On Google TV, restarting the TV and the remote's app buttons have not been tested yet
- Installing or switching a design restarts Home, which takes the screen from any app that is playing
- On the I96 8K, a restart command froze twice and needed a power cycle, and twice the box dropped off the network during app installs and reset itself. The cause was not found.
- On the I96 8K, about 1 KB still goes to the vendor's server at each boot. The sender was not found.
- The voice process stays in memory, because stopping it breaks the voice button.
- Netflix on the verified box is the phone version and shows its own update prompt.
