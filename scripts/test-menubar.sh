#!/bin/bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/blackbox-recall-tests.XXXXXX")"
trap 'rm -rf "$test_dir"' EXIT
cat > "$test_dir/main.swift" <<'SWIFT'
import Foundation
func expect(_ condition: Bool, _ message: String) {
    if !condition { fatalError(message) }
}
let base = try RecallURL.base(RecallURL.defaultBase)
for query in ["  /repos/example  ", "a+b&c#d", "café 日本 🐈", "", "100% / ? = ;"] {
    let url = RecallURL.search(base: base, query: query)
    let parts = URLComponents(url: url, resolvingAgainstBaseURL: false)!
    expect(parts.path == "/recall", "recall path")
    expect(parts.queryItems?.first(where: { $0.name == "scope" })?.value == query.trimmingCharacters(in: .whitespacesAndNewlines), "query round trip")
    expect(parts.queryItems?.first(where: { $0.name == "run" })?.value == "1", "autorun")
    expect(parts.fragment == nil, "query cannot become fragment")
    expect(!(parts.percentEncodedQuery?.contains("+") ?? false), "plus must be percent escaped")
}
for invalid in ["file:///tmp/x", "javascript:alert(1)", "http://user:secret@localhost:8766/", "https://host/?token=secret", "https://host/#fragment", "localhost:8766", "http://", ""] {
    do { _ = try RecallURL.base(invalid); fatalError("accepted invalid URL") }
    catch RecallURL.ValidationError.invalidBase { }
}
expect(RecallURL.sameOrigin(try RecallURL.base("https://example.com/"), try RecallURL.base("https://EXAMPLE.com:443/elsewhere")), "default origin port")
expect(!RecallURL.sameOrigin(base, try RecallURL.base("http://localhost:9999/")), "different origin port")
print("Recall URL fixtures passed")
let appID = String(repeating: "a", count: 32)
let info: [String: Any] = ["CrAppModeShortcutID": appID, "CrAppModeUserDataDir": "/tmp/Chrome/-/Web Applications/_crx_" + appID]
let registration: [String: Any] = ["installed_profiles": ["Default"], "last_active_profiles": ["Default"]]
let launch = ChromeLaunch(info: info, registration: registration)!
let queryURL = RecallURL.search(base: base, query: "café + a&b#c")
expect(launch.arguments(for: queryURL) == ["--user-data-dir=/tmp/Chrome", "--profile-directory=Default", "--app-id=" + appID, "--app-launch-url-for-shortcuts-menu-item=" + queryURL.absoluteString], "PWA launch URL and original profile")
expect(ChromeLaunch(info: info, registration: ["installed_profiles": ["Default", "Profile 1"]]) == nil, "ambiguous profiles")
expect(ChromeLaunch(info: info, registration: ["installed_profiles": ["-"], "last_active_profiles": ["-"]]) == nil, "shim placeholder is not a profile")
expect(ChromeLaunch(info: info, registration: ["installed_profiles": ["../other"]]) == nil, "profile traversal")
print("Chrome PWA launch fixtures passed")
let fixture = """
{"scope":"café + a&b#c","count":2,"items":[
 {"eventId":"evt+/&#日本","sessionId":"session +&/#","kind":"decision","repo":"/repos/black-box","headline":"Keep café + punctuation & context. Further explanation.","nextAction":null,"rationale":null},
 {"eventId":"handoff","sessionId":"session2","kind":"handoff","repo":"/repos/日本/","headline":"\(String(repeating: "x", count: 180))\\nMore handoff detail","nextAction":"Continue","rationale":null}
]}
"""
let matches = try RecallSuggestions.decode(Data(fixture.utf8))
expect(matches.count == 2, "decode representative response")
expect(matches[0].title == "Keep café + punctuation & context.", "first sentence title")
expect(matches[0].detail == "black-box · Decision", "project and kind")
expect(matches[1].title.count == 110 && matches[1].title.hasSuffix("…"), "long handoff title is bounded")
expect(matches[1].detail == "日本 · Handoff", "Unicode project and trailing slash")
let event = URLComponents(url: matches[0].eventURL(base: base), resolvingAgainstBaseURL: false)!
expect(event.path == "/" && event.fragment == nil, "event route")
expect(event.queryItems?.first(where: {$0.name == "event"})?.value == matches[0].eventId, "event ID roundtrip")
expect(event.queryItems?.first(where: {$0.name == "session"})?.value == matches[0].sessionId, "session ID roundtrip")
expect(event.queryItems?.first(where: {$0.name == "project"})?.value == "", "clear remembered project scope")
expect(!(event.percentEncodedQuery?.contains("+") ?? false), "event plus escaping")
let request = RecallSuggestions.request(base: base, query: "  café + a&b#c  ")
let preview = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
expect(preview.path == "/api/recall" && request.timeoutInterval == 8, "preview endpoint and timeout")
let params = Dictionary(uniqueKeysWithValues: preview.queryItems!.map { ($0.name, $0.value!) })
expect(params == ["scope":"café + a&b#c","withinHours":"168","kinds":"decision,handoff","limit":"5"], "bounded existing recall contract")
expect(request.cachePolicy == .reloadIgnoringLocalCacheData, "no preview cache")
expect(try RecallSuggestions.decode(Data("{\"items\":[]}".utf8)).isEmpty, "empty matches")
let many = try JSONSerialization.jsonObject(with: Data(fixture.utf8)) as! [String: Any]
let repeated = Array(repeating: (many["items"] as! [[String: Any]])[0], count: 9)
expect(try RecallSuggestions.decode(JSONSerialization.data(withJSONObject:["items":repeated])).count == 5, "defensive five-item maximum")
do { _ = try RecallSuggestions.decode(Data("not JSON".utf8)); fatalError("accepted invalid response") } catch { }
let optionalFixture = """
{"items":[{"eventId":"next","sessionId":"s","kind":"handoff","repo":null,"headline":null,"nextAction":"Resume the work! More context.","rationale":null},{"eventId":"skip","sessionId":"s","kind":"observation","repo":null,"headline":"Skip"}]}
"""
let optionalMatches = try RecallSuggestions.decode(Data(optionalFixture.utf8))
expect(optionalMatches.count == 1 && optionalMatches[0].title == "Resume the work!", "optional fallback and structured-kind filter")
expect(optionalMatches[0].detail == "Unknown project · Handoff", "unknown project label")
print("Recall suggestion fixtures passed")
SWIFT
xcrun swiftc -swift-version 5 -module-cache-path "$test_dir/module-cache" \
  "$repo_dir/macos/BlackBoxRecall/RecallURL.swift" "$repo_dir/macos/BlackBoxRecall/ChromeLaunch.swift" \
  "$repo_dir/macos/BlackBoxRecall/RecallSuggestions.swift" "$test_dir/main.swift" -o "$test_dir/url-tests"
"$test_dir/url-tests"
/bin/bash -n "$repo_dir/scripts/build-menubar.sh" "$repo_dir/scripts/install-menubar.sh" "$repo_dir/scripts/test-menubar.sh" "$repo_dir/scripts/test-jev-trace.sh" "$repo_dir/scripts/eval-jev-recall.sh"
"$repo_dir/scripts/build-menubar.sh"
install_snapshot() {
  for path in "$HOME/Applications/Black Box Recall.app/Contents/Info.plist" \
              "$HOME/Applications/Black Box Recall.app/Contents/MacOS/BlackBoxRecall" \
              "$HOME/Library/LaunchAgents/dev.blackbox.quick-recall.plist"; do
    if [[ -e "$path" ]]; then
      stat -f '%i %m %z' "$path"
      shasum -a 256 "$path"
    else
      printf 'Absent: %s\n' "$path"
    fi
  done
}
install_snapshot > "$test_dir/before.txt"
"$repo_dir/scripts/install-menubar.sh" > "$test_dir/dry-run.txt"
install_snapshot > "$test_dir/after.txt"
cmp "$test_dir/before.txt" "$test_dir/after.txt"
if BLACKBOX_URL='http://user:fixture-secret@localhost:8766/' "$repo_dir/scripts/install-menubar.sh" > "$test_dir/invalid.txt" 2>&1; then
  echo 'Installer accepted a credential URL' >&2; exit 1
fi
if rg -q 'fixture-secret' "$test_dir/invalid.txt"; then
  echo 'Installer exposed rejected credentials' >&2; exit 1
fi
rg -q '^Dry run: no files or services changed\.' "$test_dir/dry-run.txt"
echo 'Build, URL fixtures, shell syntax, and installer dry run passed'

"$repo_dir/scripts/test-jev-trace.sh"
python3 "$repo_dir/scripts/test-menubar-installer.py"
