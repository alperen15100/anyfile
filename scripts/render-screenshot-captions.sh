#!/usr/bin/env bash
#
# Renders docs/screenshot-captions.html into the numbered Play Store shots in
# docs/screenshots/<version>/, plus a contact sheet for reviewing them together.
#
# The HTML stacks one 1080x1920 .stage per screenshot, so Chrome is run once
# over the whole strip and the strip is then cut apart. Rendering them
# separately would risk the caption band drifting between shots.
#
# Needs Chrome (or Brave/Chromium) and ImageMagick, and network access, because
# the page pulls Poppins and Libre Baskerville from Google Fonts. The virtual
# time budget is what makes Chrome wait for those; without it the screenshot
# lands before the fonts do and the captions silently fall back to Helvetica.
#
# Usage: scripts/render-screenshot-captions.sh [version]   (default v1.9)
#
set -euo pipefail

cd "$(dirname "$0")/.."
VERSION="${1:-v1.9}"
SRC="docs/screenshot-captions.html"
DIR="docs/screenshots/$VERSION"
STRIP="$DIR/.strip.png"

# Keep in step with the .stage blocks in the HTML, in order.
NAMES=(01-pdf 02-photo 03-home 04-xlsx 05-docx 06-pptx)
W=1080
H=1920

[ -d "$DIR/raw" ] || { echo "No $DIR/raw: put the device captures there first." >&2; exit 1; }

CHROME=""
for candidate in \
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  "/Applications/Chromium.app/Contents/MacOS/Chromium" \
  "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser" \
  "$(command -v google-chrome || true)" \
  "$(command -v chromium || true)"
do
  if [ -n "$candidate" ] && [ -x "$candidate" ]; then CHROME="$candidate"; break; fi
done

if [ -z "$CHROME" ]; then
  echo "No Chrome/Chromium found. Install one, or open $SRC and screenshot each" >&2
  echo "1080x1920 .stage element by hand." >&2
  exit 1
fi

command -v magick >/dev/null 2>&1 || { echo "ImageMagick (magick) is required." >&2; exit 1; }

TOTAL=$(( H * ${#NAMES[@]} ))

"$CHROME" \
  --headless \
  --disable-gpu \
  --hide-scrollbars \
  --force-device-scale-factor=1 \
  --virtual-time-budget=15000 \
  --window-size="$W,$TOTAL" \
  --screenshot="$STRIP" \
  "file://$PWD/$SRC" >/dev/null 2>&1

size=$(magick identify -format '%wx%h' "$STRIP")
[ "$size" = "${W}x${TOTAL}" ] || { echo "Expected ${W}x${TOTAL}, got $size" >&2; exit 1; }

i=0
SHOTS=()
for name in "${NAMES[@]}"; do
  magick "$STRIP" -crop "${W}x${H}+0+$(( i * H ))" +repage \
    -strip -depth 8 -define png:compression-level=9 "$DIR/$name.png"
  echo "Wrote $DIR/$name.png"
  SHOTS+=("$DIR/$name.png")
  i=$(( i + 1 ))
done

rm -f "$STRIP"

# -depth 8 matters: montage promotes to 16 bit on its own and doubles the file
# for a sheet nobody uploads anywhere.
magick montage "${SHOTS[@]}" \
  -tile "${#SHOTS[@]}x1" -geometry '360x640+14+14' -background '#ffffff' \
  -strip -depth 8 -define png:compression-level=9 \
  "$DIR/contact-sheet.png"
echo "Wrote $DIR/contact-sheet.png"

echo
echo "Check the fonts actually loaded: the headline should be geometric with a"
echo "single-storey 'a'. If it looks like Helvetica, the Google Fonts fetch failed."
