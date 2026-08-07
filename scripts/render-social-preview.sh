#!/usr/bin/env bash
#
# Renders docs/social-preview.html to docs/social-preview.png at 1280x640,
# the size GitHub wants for a repo social card.
#
# Needs Chrome (or Brave/Chromium) and network access, because the page pulls
# Poppins, Libre Baskerville and Roboto Mono from Google Fonts. The virtual time
# budget is what makes Chrome wait for those; without it the screenshot lands
# before the fonts do and everything silently falls back to Helvetica.
#
set -euo pipefail

cd "$(dirname "$0")/.."
SRC="docs/social-preview.html"
OUT="docs/social-preview.png"

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
  echo "No Chrome/Chromium found. Install one, or open $SRC and screenshot the" >&2
  echo "1280x640 .stage element by hand." >&2
  exit 1
fi

"$CHROME" \
  --headless \
  --disable-gpu \
  --hide-scrollbars \
  --force-device-scale-factor=1 \
  --virtual-time-budget=8000 \
  --window-size=1280,640 \
  --screenshot="$OUT" \
  "file://$PWD/$SRC" >/dev/null 2>&1

if command -v magick >/dev/null 2>&1; then
  size=$(magick identify -format '%wx%h' "$OUT")
  [ "$size" = "1280x640" ] || { echo "Expected 1280x640, got $size" >&2; exit 1; }
fi

echo "Wrote $OUT"
echo "Check the fonts actually loaded: the wordmark should be geometric with a"
echo "single-storey 'a'. If it looks like Helvetica, the Google Fonts fetch failed."
