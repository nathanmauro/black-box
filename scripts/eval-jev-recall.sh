#!/bin/bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="$repo_dir/target/macos"
mkdir -p "$build_dir/module-cache"
xcrun swiftc -swift-version 5 -parse-as-library -module-cache-path "$build_dir/module-cache" \
  -target "$(uname -m)-apple-macosx13.0" \
  "$repo_dir/macos/BlackBoxRecall/RecallURL.swift" "$repo_dir/macos/BlackBoxRecall/JevTraceCore.swift" \
  "$repo_dir/macos/BlackBoxRecall/JevTraceClient.swift" "$repo_dir/macos/BlackBoxRecall/JevRecallEval.swift" \
  -o "$build_dir/JevRecallEval"
exec "$build_dir/JevRecallEval" "$@"
