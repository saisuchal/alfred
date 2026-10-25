#!/usr/bin/env bash
# Builds Steward, installs it on the connected phone and pushes the on-device model.
set -e
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1  # stop Git Bash rewriting device paths like /data/local/tmp
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"

"$ADB" get-state >/dev/null || { echo "No phone found. Plug in via USB and allow USB debugging."; exit 1; }

./gradlew assembleDebug --console=plain -q
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk

if [ "$1" != "--skip-model" ]; then
  echo "Pushing model (1.6 GB, takes a minute)..."
  "$ADB" shell mkdir -p /data/local/tmp/llm
  "$ADB" push models/model.task /data/local/tmp/llm/model.task
  "$ADB" shell chmod 644 /data/local/tmp/llm/model.task
fi

"$ADB" shell am start -n com.hackquant.steward/.MainActivity
echo "Done. On the phone: enable notification access for Steward when prompted."
