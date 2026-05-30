#!/usr/bin/env bash
#
# capture-hero.sh - Record the real Black Box recall moment as docs/assets/hero.gif.
#
# This script drives the running Black Box UI at http://localhost:8766, fires the
# recall form for the demo scope, captures animation frames with Playwright, and
# encodes them to a looping GIF. It does not install tools, start the app, edit
# app code, or commit files.
#
# Expected setup:
#   1. Run ./scripts/demo.sh in another terminal.
#   2. Leave the seeded app running at http://localhost:8766.
#   3. Run ./scripts/capture-hero.sh from anywhere in this repo.
#
# Optional overrides:
#   SBA_AGENTIC_URL=http://localhost:8766
#   HERO_SCOPE=/tmp/acme-auth
#   HERO_OUT=docs/assets/hero.gif
#   HERO_WIDTH=1280 HERO_HEIGHT=760 HERO_FPS=12 HERO_DURATION_MS=5200
#   KEEP_HERO_FRAMES=1
#
# External capture dependencies, checked but never installed automatically:
#   - curl
#   - node with the playwright package available
#   - a Playwright Chromium browser
#   - gifski or ffmpeg for GIF encoding

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${SBA_AGENTIC_URL:-http://localhost:8766}"
HERO_SCOPE="${HERO_SCOPE:-/tmp/acme-auth}"
OUT="${HERO_OUT:-${PROJECT_DIR}/docs/assets/hero.gif}"
WIDTH="${HERO_WIDTH:-1280}"
HEIGHT="${HERO_HEIGHT:-760}"
FPS="${HERO_FPS:-12}"
DURATION_MS="${HERO_DURATION_MS:-5200}"
PRE_ROLL_FRAMES="${HERO_PRE_ROLL_FRAMES:-6}"
TMP_DIR=""
NODE_PATH_FOR_CAPTURE=""

say() { printf '%s\n' "$*"; }
err() { printf '%s\n' "$*" >&2; }

fail() {
  err "capture-hero: $*"
  exit 1
}

cleanup() {
  if [[ -n "${TMP_DIR:-}" && -d "$TMP_DIR" && "${KEEP_HERO_FRAMES:-0}" != "1" ]]; then
    rm -rf "$TMP_DIR"
  elif [[ -n "${TMP_DIR:-}" && -d "$TMP_DIR" ]]; then
    say "Kept frames at: $TMP_DIR"
  fi
}
trap cleanup EXIT INT TERM

need_cmd() {
  command -v "$1" >/dev/null 2>&1
}

build_node_path() {
  local parts=""
  local root
  if need_cmd npm; then
    root="$(npm root 2>/dev/null || true)"
    if [[ -n "$root" ]]; then
      parts="${parts:+$parts:}$root"
    fi
    root="$(npm root -g 2>/dev/null || true)"
    if [[ -n "$root" ]]; then
      parts="${parts:+$parts:}$root"
    fi
  fi
  if [[ -n "${NODE_PATH:-}" ]]; then
    parts="${parts:+$parts:}${NODE_PATH}"
  fi
  printf '%s' "$parts"
}

check_playwright() {
  NODE_PATH_FOR_CAPTURE="$(build_node_path)"
  if ! NODE_PATH="$NODE_PATH_FOR_CAPTURE" node -e 'require("playwright");' >/dev/null 2>&1; then
    err "Missing Playwright for Node."
    err ""
    err "Install it as an external capture tool, then install Chromium:"
    err "  npm install --no-save --no-package-lock playwright"
    err "  npx playwright install chromium"
    err ""
    err "This script does not auto-install dependencies or add app runtime dependencies."
    exit 1
  fi
}

check_app() {
  if ! curl -fsS "${BASE_URL}/api/status" >/dev/null 2>&1; then
    err "No Black Box app responded at ${BASE_URL}."
    err ""
    err "Start and seed the live demo first:"
    err "  ./scripts/demo.sh"
    err ""
    err "Leave it running, then run this script again."
    exit 1
  fi

  local recall_json
  if ! recall_json="$(curl -fsSG "${BASE_URL}/api/recall" \
      --data-urlencode "scope=${HERO_SCOPE}" \
      --data-urlencode "kinds=decision,handoff" 2>/dev/null)"; then
    fail "Could not query ${BASE_URL}/api/recall."
  fi

  if ! printf '%s' "$recall_json" | grep -Eq '"count"[[:space:]]*:[[:space:]]*[1-9]'; then
    err "The app is running, but no recalled demo intent was found for scope:"
    err "  ${HERO_SCOPE}"
    err ""
    err "Seed the demo data first:"
    err "  ./scripts/demo.sh"
    err ""
    err "If you want another scope, set HERO_SCOPE=/path/or/topic."
    exit 1
  fi
}

check_encoder() {
  if need_cmd gifski; then
    printf 'gifski'
    return 0
  fi
  if need_cmd ffmpeg; then
    printf 'ffmpeg'
    return 0
  fi
  err "Missing GIF encoder."
  err ""
  err "Install one external tool:"
  err "  brew install gifski"
  err "  # or"
  err "  brew install ffmpeg"
  exit 1
}

run_capture() {
  local capture_js="$TMP_DIR/capture-hero.js"
  cat >"$capture_js" <<'JS'
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const baseUrl = process.env.BASE_URL;
const scope = process.env.HERO_SCOPE;
const framesDir = process.env.FRAMES_DIR;
const width = Number(process.env.HERO_WIDTH || 1280);
const height = Number(process.env.HERO_HEIGHT || 760);
const fps = Number(process.env.HERO_FPS || 12);
const durationMs = Number(process.env.HERO_DURATION_MS || 5200);
const preRollFrames = Number(process.env.HERO_PRE_ROLL_FRAMES || 6);
const frameInterval = 1000 / fps;

function framePath(index) {
  return path.join(framesDir, `frame-${String(index).padStart(4, "0")}.png`);
}

async function grab(page, index) {
  await page.screenshot({
    path: framePath(index),
    fullPage: false,
    animations: "allow",
  });
}

(async () => {
  fs.mkdirSync(framesDir, { recursive: true });

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({
    viewport: { width, height },
    deviceScaleFactor: 1,
  });
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "no-preference" });
  await page.addInitScript(() => {
    try { localStorage.setItem("blackbox.theme", "dark"); } catch (_) {}
  });

  await page.goto(baseUrl, { waitUntil: "domcontentloaded", timeout: 30000 });
  await page.waitForSelector("#recallForm input[name='scope']", { timeout: 15000 });
  await page.waitForSelector(".spine", { timeout: 15000 });
  await page.fill("#recallForm input[name='scope']", scope);
  await page.selectOption("#recallForm select[name='withinHours']", "168").catch(() => {});

  let frame = 0;
  for (let i = 0; i < preRollFrames; i += 1) {
    await grab(page, frame++);
    await page.waitForTimeout(frameInterval);
  }

  await page.click("#recallForm button[type='submit']");
  const started = Date.now();
  let next = started;
  while (Date.now() - started < durationMs) {
    await grab(page, frame++);
    next += frameInterval;
    await page.waitForTimeout(Math.max(0, next - Date.now()));
  }

  const memoryCount = await page.locator(".memory-cards .mem").count();
  await browser.close();

  if (memoryCount < 1) {
    throw new Error(`Recall returned no memory cards for scope ${scope}. Run ./scripts/demo.sh and leave it running.`);
  }
})().catch(error => {
  const message = error && error.message ? error.message : String(error);
  console.error(message);
  if (/Executable doesn't exist|browserType.launch|Chromium/i.test(message)) {
    console.error("");
    console.error("Playwright is installed, but Chromium is missing. Install the browser with:");
    console.error("  npx playwright install chromium");
  }
  process.exit(1);
});
JS

  NODE_PATH="$NODE_PATH_FOR_CAPTURE" \
  BASE_URL="$BASE_URL" \
  HERO_SCOPE="$HERO_SCOPE" \
  FRAMES_DIR="$TMP_DIR/frames" \
  HERO_WIDTH="$WIDTH" \
  HERO_HEIGHT="$HEIGHT" \
  HERO_FPS="$FPS" \
  HERO_DURATION_MS="$DURATION_MS" \
  HERO_PRE_ROLL_FRAMES="$PRE_ROLL_FRAMES" \
    node "$capture_js"
}

encode_gif() {
  local encoder="$1"
  local frame_pattern="$TMP_DIR/frames/frame-%04d.png"
  local out_dir
  out_dir="$(dirname "$OUT")"
  mkdir -p "$out_dir"

  case "$encoder" in
    gifski)
      local frames=("$TMP_DIR"/frames/frame-*.png)
      gifski --fps "$FPS" --quality 82 --width 960 -o "$OUT" "${frames[@]}" >/dev/null
      ;;
    ffmpeg)
      ffmpeg -hide_banner -loglevel error -y \
        -framerate "$FPS" \
        -i "$frame_pattern" \
        -vf "fps=${FPS},scale=960:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" \
        -loop 0 \
        "$OUT"
      ;;
    *)
      fail "Unknown encoder: $encoder"
      ;;
  esac
}

main() {
  cd "$PROJECT_DIR"

  need_cmd curl || fail "curl is required. macOS usually includes it; Homebrew: brew install curl"
  need_cmd node || fail "node is required for Playwright. Install with: brew install node"
  check_playwright
  check_app
  encoder="$(check_encoder)"

  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/black-box-hero.XXXXXX")"

  say "Capturing Black Box recall moment from ${BASE_URL}"
  say "Scope: ${HERO_SCOPE}"
  run_capture
  encode_gif "$encoder"
  say "Wrote ${OUT}"
}

main "$@"
