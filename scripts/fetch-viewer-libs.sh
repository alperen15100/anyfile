#!/usr/bin/env bash
# Re-fetch the vendored viewer libraries from their upstreams.
# See docs/VENDORED.md for the provenance table.
set -euo pipefail

LIB="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/viewer/lib"
mkdir -p "$LIB/pptx"

get() { curl -sfL --retry 2 -o "$LIB/$1" "$2" && echo "fetched $1"; }

get jszip3.min.js       "https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js"
# The legacy build, which is transpiled for older system WebViews.
# Needs Chromium 125+; read "Before upgrading pdf.js" in docs/VENDORED.md first,
# because Android 8 and 9 top out at 138 and cannot go past it.
PDFJS="https://cdn.jsdelivr.net/npm/pdfjs-dist@5.7.284/legacy/build"
get pdf.min.mjs         "$PDFJS/pdf.min.mjs"
get pdf.worker.min.mjs  "$PDFJS/pdf.worker.min.mjs"
get docx-preview.min.js "https://cdn.jsdelivr.net/npm/docx-preview/dist/docx-preview.min.js"
get xlsx.full.min.js    "https://cdn.sheetjs.com/xlsx-0.20.3/package/dist/xlsx.full.min.js"
get marked.min.js       "https://cdn.jsdelivr.net/npm/marked@15/marked.min.js"
get purify.min.js       "https://cdn.jsdelivr.net/npm/dompurify@3/dist/purify.min.js"

P="https://cdn.jsdelivr.net/gh/meshesha/PPTXjs@master"
get pptx/jquery.min.js  "$P/js/jquery-1.11.3.min.js"
get pptx/jszip2.min.js  "$P/js/jszip.min.js"
get pptx/filereader.js  "$P/js/filereader.js"
get pptx/d3.min.js      "$P/js/d3.min.js"
get pptx/nv.d3.min.js   "$P/js/nv.d3.min.js"
get pptx/pptxjs.js      "$P/js/pptxjs.js"
get pptx/divs2slides.js "$P/js/divs2slides.js"
get pptx/pptxjs.css     "$P/css/pptxjs.css"
get pptx/nv.d3.min.css  "$P/css/nv.d3.min.css"

echo "done"
