#!/bin/sh
# Sets up the weekly RandyFlix channel guide check on the Pi (Sundays at 4:30 AM).
# Run it once, as the user that owns the RandyFlix folder:
#     sh /path/to/_randyflix/tools/install.sh
# Running it again just replaces the schedule. To remove it:
#     sh /path/to/_randyflix/tools/install.sh --remove
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$DIR/check_catalog.py"
LOG="$DIR/check_catalog.log"
TAG="# randyflix-catalog-check"

if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is needed: sudo apt install python3"
    exit 1
fi

# Everything except our old line, then (unless removing) the new one.
CURRENT="$(crontab -l 2>/dev/null | grep -v "$TAG" || true)"
if [ "$1" = "--remove" ]; then
    printf '%s\n' "$CURRENT" | grep -v '^$' | crontab - || crontab -r
    echo "Weekly channel guide check removed."
    exit 0
fi
LINE="30 4 * * 0 python3 \"$SCRIPT\" >> \"$LOG\" 2>&1 $TAG"
{ printf '%s\n' "$CURRENT" | grep -v '^$' || true; echo "$LINE"; } | crontab -

echo "Weekly channel guide check scheduled: Sundays at 4:30 AM."
echo "  Script: $SCRIPT"
echo "  Log:    $LOG"
echo "  Report: $(dirname "$DIR")/catalog-report.txt"
echo
echo "Running a first check now (report only, nothing changed)..."
python3 "$SCRIPT" --dry-run
