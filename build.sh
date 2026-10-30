#!/usr/bin/env bash
# Build the launcher from source. Needs JDK 17 to 22 and Android SDK platform 34 with build-tools 34.
# Most people do not need this: install.sh downloads the released APK.
set -euo pipefail
cd "$(dirname "$0")/launcher"
./gradlew :app:testReleaseUnitTest assembleRelease --console=plain
ls -la app/build/outputs/apk/release/app-release.apk helper/build/outputs/apk/release/helper-release.apk
