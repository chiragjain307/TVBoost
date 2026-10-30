# I96 8K benchmark report

Measured with `benchmarks/measure.py`: 3 reboots, 5 idle minutes each, then `tools/bench.py` and cold app launches. Before is the box as it
arrived. After is Design 1 with 7 apps on Home and 19 packages disabled. Median, with the range in brackets.

## Memory

RAM metrics are listed separately, because they mean different things.

| Metric | Before | After | Change |
|---|---:|---:|---:|
| Total RAM | 1,048,576 kB | 1,048,576 kB | 0% |
| Available RAM | 460,392 kB (419,040-473,012) | 494,044 kB (488,436-494,964) | +7.3% |
| Free RAM | 185,444 kB (176,844-185,760) | 177,480 kB (176,372-180,288) | -4.3% |
| Cached RAM | 357,344 kB (319,464-376,072) | 395,064 kB (392,112-396,892) | +10.6% |
| Swap free of 596,428 kB | 426,312 kB (420,316-457,752) | 558,196 kB (558,008-559,980) | +30.9% |
| Launcher PSS | 54,171 kB (44,966-57,290) | 19,816 kB (19,606-21,754) | -63.4% |
| Google Play Services PSS | 107,439 kB | 119,886 kB | +11.6% |
| Vendor agents PSS | 39,240 kB | 0 kB | -100% |

Free RAM fell because Android keeps more in cache. Available RAM is the number that matters. Play Services grew because less of it sits in swap.

Each app tile adds memory. With 13 apps on Home, Design 2's launcher measured 48 MB after 5 idle minutes, and right after a restart it measured about 6 MB
above Design 1 with the same apps. Design 2 was not given the full 3-boot run.

## System

| Metric | Before | After | Change |
|---|---:|---:|---:|
| Background processes (userland) | 100 (94-101) | 96 (95-96) | -4.0% |
| App processes | 34 (27-34) | 29 (29-29) | -14.7% |
| Idle CPU | 6.8% (6.0-17.5) | 5.4% (4.6-5.4) | -20.6% |
| Reboot to Home | 38.9 s (38.0-39.3) | 38.1 s (36.5-38.3) | -2.1% |
| Kernel start to Home | 32.3 s (31.5-32.7) | 31.5 s (29.9-31.6) | -2.4% |
| `/data` available | 3,061,704 kB | 2,902,420 kB | -5.2% |

Storage fell because of app updates and apps installed during testing, including JioHotstar and the launcher.

## Launcher and navigation

| Metric | Before | After | Change |
|---|---:|---:|---:|
| Janky frames | 54.1% | 2.47% | -95.4% |
| Cold start p50 | 1,723 ms | 897 ms | -47.9% |
| Return to Home p50 | 87 ms | 37 ms | -57.5% |
| Key response p50 | 11 ms | 23 ms | Slower |
| Key response p95 | 66.8 ms | 29.7 ms | -55.5% |
| Focus movement p50 | 250 ms | 416 ms | Slower: the highlight glides |
| Launcher idle CPU | 3.9% | 0.1% | -97.4% |

## App launch, cold, n=3

| App | Before | After |
|---|---:|---:|
| Settings | 894 ms | 606 ms |
| YouTube | 3,585 ms | 2,957 ms |
| Prime Video | 1,414 ms | 1,404 ms |
| Netflix (phone version) | 2,277 ms | 2,830 ms |

The Netflix before figure was taken in a separate run, and its update prompt makes it noisy.

## Network

The system account moved 0.7 to 8.4 MB each way in about 6.5 minutes before setup, and about 1 KB per boot after it. That 1 KB is one web request
to the vendor's server, and what sends it was not found.

## Function tests after setup

Passed: Home, Back, D-pad, voice key, Settings, YouTube playback with audio over HDMI, Netflix, Prime Video, JioHotstar, Play Store and Chrome all
open, Wi-Fi, AdGuard DNS, screensaver, an app installed through the Play Store, and the Bluetooth remote with a real voice search.

Not tested: Netflix and Prime Video playback, because the test box was not signed in. Casting and HDMI inputs do not exist on this box.

## Stability

In about 10 hours of testing with over 20 reboots, a restart command froze twice and needed a power cycle, and twice the box dropped off the
network during app installs and reset itself after about 2 minutes. It is not known whether the setup or the firmware causes this. Before setup the
box was only observed for about 6 reboots, too few to compare.
