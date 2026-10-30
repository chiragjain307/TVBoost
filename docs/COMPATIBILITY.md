# Launcher compatibility

This page covers the launcher by itself. Whether a specific box is supported is a separate question, covered in
[DEVICE_COMPATIBILITY.md](DEVICE_COMPATIBILITY.md).

| Requirement | Value |
|---|---|
| Android | 8.0 or newer (minSdk 26, targetSdk 34) |
| Permissions | Network and Wi-Fi state only. No internet permission |
| Input | Remote control, D-pad and OK |
| Graphics | Tested on Mali-450 with OpenGL ES 2.0 |
| Root | Not needed |

| Project version | Launcher | Verified devices |
|---|---|---|
| 1.0.0 | 3.2 | I96 8K (`I96_8K-20251222-301SMSX300`) |

## Build from source

Needs JDK 17 to 22 and Android SDK platform 34 with build-tools 34.

```bash
./build.sh
```

Docker builds are not supported on Apple Silicon, because Android's `aapt2` has no Linux ARM build.
