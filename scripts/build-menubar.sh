#!/bin/bash
set -euo pipefail
if [[ $# -ne 0 ]]; then
  echo 'Usage: scripts/build-menubar.sh' >&2
  exit 2
fi
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="$repo_dir/target/macos"
app_dir="$build_dir/Black Box Recall.app"
mkdir -p "$build_dir/module-cache"
staging_dir="$(mktemp -d "$build_dir/build.XXXXXX")"
trap 'rm -rf "$staging_dir"' EXIT
mkdir -p "$staging_dir/Black Box Recall.app/Contents/MacOS"
xcrun swiftc -swift-version 5 -O -module-cache-path "$build_dir/module-cache" \
  -target "$(uname -m)-apple-macosx13.0" \
  -framework AppKit -framework Carbon -framework Security -framework LocalAuthentication \
  "$repo_dir/macos/BlackBoxRecall/RecallURL.swift" "$repo_dir/macos/BlackBoxRecall/ChromeLaunch.swift" \
  "$repo_dir/macos/BlackBoxRecall/RecallSuggestions.swift" "$repo_dir/macos/BlackBoxRecall/JevTraceCore.swift" \
  "$repo_dir/macos/BlackBoxRecall/JevTraceClient.swift" "$repo_dir/macos/BlackBoxRecall/JevKeychain.swift" \
  "$repo_dir/macos/BlackBoxRecall/main.swift" \
  -o "$staging_dir/Black Box Recall.app/Contents/MacOS/BlackBoxRecall"
cp "$repo_dir/macos/BlackBoxRecall/Info.plist" "$staging_dir/Black Box Recall.app/Contents/Info.plist"
codesign --force --sign - "$staging_dir/Black Box Recall.app"
codesign --verify --deep --strict "$staging_dir/Black Box Recall.app"
if [[ -e "$app_dir" || -L "$app_dir" ]]; then
  [[ ! -L "$app_dir" && "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app_dir/Contents/Info.plist" 2>/dev/null)" == dev.blackbox.quick-recall ]] || {
    echo "Refusing to replace an unrecognized build at $app_dir" >&2; exit 1;
  }
  rm -rf "$app_dir"
fi
mv "$staging_dir/Black Box Recall.app" "$app_dir"
echo "$app_dir"
