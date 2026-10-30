# Design system

These values describe `01-navy-gold`, with `02-liquid-glass` at the end. With a logo, colours come from the logo instead. Sources are `designs/` and the launcher's `res/` folder.

## Colour

| Role | Value |
|---|---|
| Background | `#090C14` |
| Glow behind the logo | `#18264A` |
| Logo ink on dark | `#F6F1E3` |
| Primary text | White |
| Secondary text | White at reduced opacity |
| Dock band | Frosted glass at 65% opacity |
| Focus | White tile lift, with a soft glow |

## Typography

Outfit, a font under the SIL Open Font License (`launcher/FONT-LICENSE-OFL.txt`). The clock is large and thin. The greeting and
date use wide letter spacing in capitals.

## Layout

| Element | Value |
|---|---|
| Screen | 1920x1080 |
| App row | 6 tiles on screen, one row, scrolls sideways |
| Logo height on Home | 560 px, centred at y 372 |
| Logo on the screensaver | 560 px, centred at y 540 |
| Top bar | Greeting, clock and date on the left. Inputs (TVs only), system tile (Design 2), Wi-Fi and Settings on the right |

## Components

| Component | Behaviour | Source |
|---|---|---|
| Dock tile | Grows and lifts on focus, banner or icon with name | `launcher/.../AppAdapter.java` |
| Top buttons | Round icon-only buttons | `launcher/.../MainActivity.java` |
| Screensaver | Starts after 10 idle minutes, any key returns to Home | `launcher/.../ClockActivity.java`, `ClockScene.java` |
| Clock on the screensaver | Moves each minute, keeps to the side columns when `clock.jpg` is present | `ClockScene.java` |

## Motion and navigation

- `focus_overshoot` is off, so focus glides straight to its target.
- `advanced_effects` is off to protect the Mali-450 GPU.
- Back stays on Home and never exits it. Home remembers the focused tile.
- Everything works from the remote: D-pad, OK, Back and Home.

## Liquid glass (`02-liquid-glass`)

| Element | Value |
|---|---|
| Ground | Dark `#0A0F24` with pools of `#2B4CC2` and `#7A3FC9` |
| Glass pane | Backdrop blurred 34 px, 10% white lift, a top sheen and a bright rim, plus a soft shadow |
| Where it lives | Baked into `wallpaper.jpg` and `welcome.jpg`, because the Mali-450 cannot blur live |
| System tile | Translucent pill before the Wi-Fi button, with a thin meter for RAM and for storage |
| Dock pane | x 113 to 1805, y 826 to 1006, radius 44, set in `images.json` |
| App row | One row that scrolls sideways, so tiles never rise over the logo |
| Home logo | The same size and place as Design 1 |
| Welcome screen | With a logo, the logo leads at 470 px and the text sits in a small pane. Without one, the time leads |

With no logo, nothing is drawn in its place. The sample logo is only for the repository's screenshots.

## Logo colours

`tools/make_design_images.py` reads the logo's main hues. The largest becomes the ground and glow, and the next becomes the second pool. Yellow
and gold are softened to a warm neutral, because they turn olive when darkened. A black and white logo keeps the design's own colours.

## Add a design

1. Copy `designs/01-navy-gold` to the next number, such as `designs/02-my-design`.
2. Change `config.json`, using the keys in [THEME_KEYS.md](../THEME_KEYS.md), and the colours in `images.json`.
3. Write one line for `./design.sh list` at the top of `about.txt`.
4. Test it on a box, and save `preview-home.jpg` from a real screenshot.

Existing designs are never replaced. Each new look gets its own folder.
