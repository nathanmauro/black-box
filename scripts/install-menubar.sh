#!/bin/bash
set -euo pipefail
install=false
enable_jev=false
for argument in "$@"; do
  case "$argument" in
    --install) install=true ;;
    --enable-jev) enable_jev=true ;;
    --help|-h) echo 'Usage: scripts/install-menubar.sh [--install [--enable-jev]]'; exit 0 ;;
    *) echo 'Usage: scripts/install-menubar.sh [--install [--enable-jev]]' >&2; exit 2 ;;
  esac
done
if [[ "$enable_jev" == true && "$install" != true ]]; then
  echo '--enable-jev requires --install; dry run changed nothing.' >&2
  exit 2
fi
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
source_app="$repo_dir/target/macos/Black Box Recall.app"
label=dev.blackbox.quick-recall
applications_dir="$HOME/Applications"
destination="$applications_dir/Black Box Recall.app"
agent_dir="$HOME/Library/LaunchAgents"
agent="$agent_dir/$label.plist"
executable="$destination/Contents/MacOS/BlackBoxRecall"
plist_value() { /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null; }
[[ -d "$source_app" && ! -L "$source_app" && "$(plist_value "$source_app/Contents/Info.plist" CFBundleIdentifier)" == "$label" ]] || {
  echo 'Build the helper with scripts/build-menubar.sh first.' >&2; exit 1;
}
for path in "$applications_dir" "$agent_dir" "$destination" "$agent"; do
  [[ ! -L "$path" ]] || { echo "Refusing a symlink install target: $path" >&2; exit 1; }
done
if [[ -e "$destination" ]]; then
  [[ -d "$destination" && "$(plist_value "$destination/Contents/Info.plist" CFBundleIdentifier)" == "$label" && "$(plist_value "$destination/Contents/Info.plist" CFBundleExecutable)" == BlackBoxRecall ]] || {
    echo "Refusing to replace an unrelated app: $destination" >&2; exit 1;
  }
fi
if [[ -e "$agent" ]]; then
  [[ "$(plist_value "$agent" Label)" == "$label" && "$(plist_value "$agent" ProgramArguments:0)" == "$executable" ]] || {
    echo "Refusing to replace an unrelated launch agent: $agent" >&2; exit 1;
  }
fi
# Preserve only owned non-secret configuration on ordinary updates.
configured_url="${BLACKBOX_URL:-}"
if [[ -z "${BLACKBOX_URL+x}" && -f "$agent" ]]; then
  configured_url="$(plist_value "$agent" EnvironmentVariables:BLACKBOX_URL || true)"
fi
jev_enabled=0
if [[ -f "$agent" && "$(plist_value "$agent" EnvironmentVariables:BLACKBOX_JEV_ENABLED || true)" == 1 ]]; then
  jev_enabled=1
fi
if [[ "$enable_jev" == true ]]; then
  "$source_app/Contents/MacOS/BlackBoxRecall" --validate-jev-key
  jev_enabled=1
fi
if [[ -n "$configured_url" || -n "${BLACKBOX_URL+x}" ]]; then
  BLACKBOX_URL="$configured_url" "$source_app/Contents/MacOS/BlackBoxRecall" --validate-base-url
else
  "$source_app/Contents/MacOS/BlackBoxRecall" --validate-base-url
fi
printf 'App: %s\nLogin agent: %s\n' "$destination" "$agent"
if [[ "$install" == false ]]; then
  echo 'Dry run: no files or services changed. Pass --install to install and start the helper.'
  echo 'Quit any manually launched copy before reinstalling.'
  if [[ -n "$configured_url" ]]; then echo 'The validated BLACKBOX_URL will be preserved for the helper.'; fi
  if [[ "$jev_enabled" == 1 ]]; then echo 'The existing Jev enable flag will be preserved; no key is printed or written by dry run.'; fi
  exit 0
fi
if launchctl print "gui/$UID/$label" >/dev/null 2>&1 && [[ ! -f "$agent" ]]; then
  echo 'Refusing to replace a loaded helper service without its verified launch-agent file.' >&2
  exit 1
fi
loaded_pid="$(launchctl print "gui/$UID/$label" 2>/dev/null | awk '/^[[:space:]]*pid = / { print $3 }' || true)"
for helper_pid in $(pgrep -x BlackBoxRecall || true); do
  if [[ "$helper_pid" != "$loaded_pid" ]]; then
    echo 'Quit the manually launched Black Box Recall app before reinstalling; no files were replaced.' >&2
    exit 1
  fi
done
codesign --verify --deep --strict "$source_app"
mkdir -p "$applications_dir" "$agent_dir"
agent_tmp="$(mktemp "$agent_dir/.blackbox-quick-recall.XXXXXX")"
install_staging=""
trap 'rm -f "$agent_tmp"; if [[ -n "$install_staging" ]]; then rm -rf "$install_staging"; fi' EXIT
rm "$agent_tmp"
plutil -create xml1 "$agent_tmp"
plutil -insert Label -string "$label" "$agent_tmp"
plutil -insert ProgramArguments -xml '<array/>' "$agent_tmp"
plutil -insert ProgramArguments.0 -string "$executable" "$agent_tmp"
plutil -insert RunAtLoad -bool true "$agent_tmp"
plutil -insert ProcessType -string Interactive "$agent_tmp"
if [[ -n "$configured_url" || "$jev_enabled" == 1 ]]; then
  plutil -insert EnvironmentVariables -xml '<dict/>' "$agent_tmp"
fi
if [[ -n "$configured_url" ]]; then
  plutil -insert EnvironmentVariables.BLACKBOX_URL -string "$configured_url" "$agent_tmp"
fi
if [[ "$jev_enabled" == 1 ]]; then
  plutil -insert EnvironmentVariables.BLACKBOX_JEV_ENABLED -string 1 "$agent_tmp"
fi
plutil -lint "$agent_tmp"
# Replace the bundle by rename, preserving the old running executable's inode until restart.
install_staging="$(mktemp -d "$applications_dir/.blackbox-recall-install.XXXXXX")"
ditto "$source_app" "$install_staging/Black Box Recall.app"
codesign --verify --deep --strict "$install_staging/Black Box Recall.app"
if [[ -d "$destination" ]]; then mv "$destination" "$install_staging/previous.app"; fi
if ! mv "$install_staging/Black Box Recall.app" "$destination"; then
  if [[ -d "$install_staging/previous.app" ]]; then mv "$install_staging/previous.app" "$destination"; fi
  exit 1
fi
if [[ "$enable_jev" == true ]]; then
  # The installed executable creates its own Keychain access; the API key never enters a plist.
  echo 'macOS may request approval for Black Box Recall to access its own TypeSafe Keychain item.'
  if ! "$executable" --configure-jev-key; then
    rm -rf "$destination"
    if [[ -d "$install_staging/previous.app" ]]; then mv "$install_staging/previous.app" "$destination"; fi
    echo 'Jev setup failed. The previous helper bundle, login configuration, and running service were preserved.' >&2
    exit 1
  fi
elif [[ "$jev_enabled" == 1 ]] && ! "$executable" --check-jev-key >/dev/null; then
  echo 'The Jev flag is preserved, but its Keychain record needs reconfiguration after this rebuild. Re-run --install --enable-jev.' >&2
fi
if [[ -f "$agent" ]] && launchctl print "gui/$UID/$label" >/dev/null 2>&1; then
  launchctl bootout "gui/$UID" "$agent"
fi
mv "$agent_tmp" "$agent"
launchctl bootstrap "gui/$UID" "$agent"
launchctl kickstart "gui/$UID/$label"
started=false
for attempt in {1..50}; do
  started_pid="$(launchctl print "gui/$UID/$label" 2>/dev/null | awk '/^[[:space:]]*pid = / { print $3 }' || true)"
  if [[ -n "$started_pid" ]] && kill -0 "$started_pid" 2>/dev/null; then
    started=true
    break
  fi
  sleep 0.1
done
if [[ "$started" != true ]]; then
  echo 'The helper was installed but did not start. Inspect its launch agent before retrying.' >&2
  exit 1
fi
printf 'Installed and started Black Box Recall. Click the box in the menu bar or press Control–Option–B.\n'
