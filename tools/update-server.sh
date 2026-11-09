#!/usr/bin/env bash
# Build the launcher for Android 8 and serve it so the TV's Update button can fetch it.
# Usage: tools/update-server.sh        (Ctrl-C to stop)
set -euo pipefail
cd "$(dirname "$0")/.."
T="$HOME/tv-home-tools"
export JAVA_HOME="$(echo "$T"/jdk/*/Contents/Home)" ANDROID_HOME="$T/sdk" MIN_SDK="${MIN_SDK:-26}"
launcher/build-and-install.sh --build
mkdir -p "$T/serve" && cp launcher/build/tvhome.apk "$T/serve/tvhome.apk"
ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1)
echo "Serving http://$ip:8000/tvhome.apk  - press Update in the TV's Home app. Ctrl-C to stop."
cd "$T/serve" && exec python3 -m http.server 8000 --bind "$ip"
