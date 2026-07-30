#!/usr/bin/env bash
# Screenshots the 6 approved chapter-4.6 test scenarios (see
# server/seed_golden_scenarios.py) on a running emulator, with NO trial-and-error
# tap/scroll hunting - every coordinate below was measured once (see the
# android-emulator-testing-workflow memory) and is reused verbatim.
#
# This is a manual/on-demand visual-check tool, not an automated test - a human
# still looks at the resulting PNGs and judges them. Only re-run this when a
# change could plausibly affect rendering (layout, colors, layering, text) -
# not for every unrelated change.
#
# Prerequisites this script does NOT set up for you:
#   - emulator running and booted (lifeos_phone)
#   - local dev server running with seed_golden_scenarios.py already applied
#   - app installed and its Settings screen pointed at http://127.0.0.1:8000
#     (persists across reinstalls via DataStore, per the workflow memory)
#
# Usage:
#   ./screenshot_scenarios.sh                       # all 6, saved to $OUT_DIR
#   ./screenshot_scenarios.sh layer_geometry         # just one
#   OUT_DIR=/some/path ./screenshot_scenarios.sh     # override output dir
#
# How it works (see android_emulator_testing_workflow.md for the why):
#   1. Force-stop + relaunch the app - guarantees a known starting state
#      (Day scale, showing today) regardless of whatever the emulator was
#      doing before.
#   2. Switch to Month scale, swipe to June 2026 (months-back computed from
#      the host's actual current date, so this stays correct as real time
#      passes - do NOT hardcode a swipe count here).
#   3. Switch to Week scale - this lands on the Monday of whatever week
#      Month's currently-displayed month starts on (June 1, 2026 is a
#      Monday - confirmed, not assumed).
#   4. Swipe forward (day-of-month - 1) times to reach the target date
#      column. Week's horizontal swipe changes date; Day's does not.
#   5. Fast flick top-to-bottom - snaps vertical scroll back to 00:00
#      regardless of prior state (belt-and-suspenders; each new date column
#      is expected to already reset to 00:00 on its own).
#   6. Slow controlled drag (long duration, no fling) grabs the first event
#      block - every one of these 6 scenarios starts its first event at
#      08:00, which is why one fixed drag start-y works for all of them -
#      and drags it down near the top, leaving a margin instead of jamming
#      it against the header.
#   7. Screenshot, pull, save as "$OUT_DIR/<scenario>.png".
#
# Direction convention (confirmed empirically, see workflow memory): dragging
# with the finger moving UP the screen reveals LATER content; DOWN reveals
# EARLIER content (back toward 00:00 or an earlier month/day). Do not assume
# the opposite without re-verifying on this app.

set -euo pipefail

ADB="/e/android-sdk/platform-tools/adb"
OUT_DIR="${OUT_DIR:-$HOME/.claude/jobs/scenario_screenshots}"
mkdir -p "$OUT_DIR"

adb_shell() { MSYS_NO_PATHCONV=1 "$ADB" shell "$@"; }
adb_tap() { adb_shell input tap "$1" "$2"; }
adb_swipe() { adb_shell input swipe "$1" "$2" "$3" "$4" "$5"; }

screenshot_to() {
  local out="$1"
  adb_shell screencap -p /sdcard/_scenario_shot.png
  MSYS_NO_PATHCONV=1 "$ADB" pull /sdcard/_scenario_shot.png "$out" >/dev/null 2>&1
}

# Scenario name -> day-of-month in June 2026 (see seed_golden_scenarios.py's
# SCENARIO_DATES - keep these in sync if that file's dates ever change).
declare -A SCENARIO_DAY=(
  [all_project_colors_part_1]=1
  [all_project_colors_part_2]=2
  [layer_geometry]=3
  [touching_intervals]=4
  [short_intervals]=5
  [instants_with_layers]=6
)
SCENARIO_ORDER=(all_project_colors_part_1 all_project_colors_part_2 layer_geometry touching_intervals short_intervals instants_with_layers)

navigate_to_june_2026_week_view() {
  # Assumes app just launched fresh (Day scale, showing today).
  adb_tap 224 148   # open the scale-selector menu (tap the date-range header)
  sleep 1
  adb_tap 120 520   # "Month" option in the menu
  sleep 1

  local current_year current_month months_back
  current_year=$(date +%Y)
  current_month=$(date +%-m)
  months_back=$(( (current_year * 12 + current_month) - (2026 * 12 + 6) ))

  if [ "$months_back" -gt 0 ]; then
    for ((i = 0; i < months_back; i++)); do
      adb_swipe 100 800 900 800 300   # drag right = go to previous month
      sleep 0.6
    done
  elif [ "$months_back" -lt 0 ]; then
    for ((i = 0; i < -months_back; i++)); do
      adb_swipe 900 800 100 800 300   # drag left = go to next month
      sleep 0.6
    done
  fi

  adb_tap 224 148   # open the scale-selector menu again
  sleep 1
  adb_tap 120 394   # "Week" option - lands on June 1's week, "Пн 1" first
  sleep 1
}

capture_scenario() {
  local name="$1"
  local day="${SCENARIO_DAY[$name]}"

  adb_shell am force-stop com.lifeos.app
  sleep 1
  adb_shell am start -n com.lifeos.app/.MainActivity
  sleep 6   # splash screen is still showing at 3s - taps before ~5-6s land on nothing

  navigate_to_june_2026_week_view

  local swipes=$((day - 1))
  for ((i = 0; i < swipes; i++)); do
    adb_swipe 900 1000 100 1000 300   # drag left = advance one day column
    sleep 0.6
  done

  adb_swipe 540 400 540 2000 150   # fast flick down = snap back to 00:00
  sleep 1.5
  adb_swipe 540 1750 540 500 1200  # slow controlled drag = bring 08:00 to top with margin
  sleep 1

  screenshot_to "$OUT_DIR/$name.png"
  echo "  saved: $OUT_DIR/$name.png"
}

MSYS_NO_PATHCONV=1 "$ADB" reverse tcp:8000 tcp:8000 >/dev/null 2>&1 || true

if [ "$#" -eq 1 ]; then
  if [ -z "${SCENARIO_DAY[$1]+x}" ]; then
    echo "Unknown scenario: $1" >&2
    echo "Known scenarios: ${SCENARIO_ORDER[*]}" >&2
    exit 1
  fi
  echo "Capturing $1..."
  capture_scenario "$1"
else
  echo "Capturing all 6 scenarios..."
  for name in "${SCENARIO_ORDER[@]}"; do
    echo "Capturing $name..."
    capture_scenario "$name"
  done
fi

echo "Done."
