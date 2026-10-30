# Recovery guide

You don't need to know ADB to follow this. Every command runs from the `tvbox-revive` folder on your computer.
Replace `<box ip>` with your box's address, from Settings > Network & Internet.

Nothing here is a factory reset. This project never runs one.

## Put the box back as it was

```bash
./undo.sh <box ip>
```

This re-enables the disabled apps, brings back the stock Home, restores your DNS settings and removes the launcher.
Reboot the box once afterwards.

## If something goes wrong

| What you see | What to do |
|---|---|
| The box did not return after a reboot | Unplug the power for 10 seconds, plug it back in and wait 2 minutes |
| The box is on but off the network | On the box, open Settings > Network & Internet and reconnect to Wi-Fi |
| Home is blank or the launcher keeps closing | Run `./undo.sh <box ip>` |
| An app you need is missing | Run `tools/tv.py 'pm enable --user 0 <package>'`. Package names are in `devices/<box>/debloat-profile.json` |
| Websites or apps stop loading | Turn DNS off, using the command below |
| The install stopped halfway | Run `./install.sh <box ip>` again, or `./undo.sh <box ip>` to go back |
| The installer says no profile matches | Nothing changed. Don't force it: see [DEVICE_COMPATIBILITY.md](DEVICE_COMPATIBILITY.md) |
| The installer cannot reach the box | Check the IP address, and that this computer is on the same network |
| A bad design or logo | Run `./design.sh <design> <box ip>` and answer the logo question again |

`tools/tv.py` needs the box's address set first:

```bash
export TV_HOST=<box ip> TV_PORT=3222
```

Turn DNS ad blocking off:

```bash
tools/venv/bin/python -I tools/tv.py "settings delete global private_dns_mode; settings delete global private_dns_specifier"
```

Bring back the stock Home only:

```bash
tools/venv/bin/python -I tools/tv.py "pm enable --user 0 com.google.android.tvlauncher"
tools/venv/bin/python -I tools/tv.py "cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity"
```

## What can and cannot be recovered

| Type | Saved? | Where |
|---|---|---|
| Configuration: package states, settings, Home, DNS | Yes, before every install | `~/.tvbox-revive/backups` on your computer |
| Undo record | Yes, before the first change | `~/.tvbox-revive` on your computer |
| App data and sign-ins | **No** | Disabling an app keeps its data on the box, but this project does not back it up |
| APK files | **No** | Disabled apps stay on the box |
| Firmware | **No** | This project never touches firmware |

Keep the `~/.tvbox-revive` folder. Without the undo record, `undo.sh` still re-enables every listed app, but it cannot
tell which ones were already off before you installed.

## Last resort

A factory reset from the box's own Settings menu also clears everything this project changed, because disabled states are
stored in user data. It also erases your sign-ins and installed apps, so do it yourself only if nothing above works. It
brings back the vendor's background apps too.
