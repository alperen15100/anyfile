# Changelog

## 1.9 (2026-08-06)

- Files Gander cannot render now offer a **View as text** button instead of a
  dead end, so a file with no extension can be read without renaming it to
  `.txt` first (thanks @immanuelfodor)
- The text viewer reads large files in 5 MB pages with a **Show more** button
  at the end, so a big log opens straight away instead of stalling while the
  whole thing loads, and none of it is out of reach
- Text files that start with a byte order mark, including UTF-16, now decode
  correctly instead of showing a stray character between every letter
- Gander now targets Android 16 (API 36), so it keeps working as newer
  releases tighten how apps draw behind the status and navigation bars
- The permission list is genuinely empty again. Media3 had been quietly
  adding ACCESS_NETWORK_STATE, which never did anything here because Gander
  only plays local files and has no internet access at all
- PDFs now render with pdf.js in the same sandboxed viewer that already handled
  Word and Excel, so the app ships no native code at all. The download is one
  APK for every phone instead of four, and about 8 MB instead of 15
- Very large PDFs are read a piece at a time as you scroll rather than loaded
  whole, so a 50 MB scan opens sooner and does not sit in memory while you read it
- The PDF viewer background now fills the screen behind a short document, the
  same fix the other viewers got in 1.8. It showed up most on a PDF that could
  not be opened, where the message sat on a dark band with a lighter one below
- PDFs open a little slower than before, by roughly half a second on a fast
  phone. The renderer that was faster cannot be shipped any more; it stopped
  being maintained and no longer meets current Play requirements
- The search button no longer appears for PDFs. PDF pages are drawn as images
  with no text behind them, so a search could only ever come back empty. Search
  in Word, Excel, slides, Markdown, text and code is unchanged
- On a phone whose Android System WebView is too old to run the PDF renderer,
  the viewer now says so and names the version needed, instead of sitting on
  "Rendering document…" with nothing to explain it. Updating Android System
  WebView fixes it, and no other format is affected

## 1.8 (2026-08-04)

- Short documents no longer show a grey band below the content. The viewer
  background now fills the screen in the Markdown, text, spreadsheet and
  slide views (thanks @lalalasupa0)

## 1.7 (2026-08-02)

- PDF zoom now reaches 10x instead of the previous 3x, enough to read a small
  QR code on a full page (thanks @neuos)
- OpenDocument spreadsheets (`.ods`) now appear in the Open-with list; they
  already rendered, but the MIME type was never registered (thanks to sgc on
  Hacker News)
- Screen reader support: the back button, the photo viewer, web-rendered
  images and the file rows on the home screen are all labelled now, and the
  search match count is announced as "Match 2 of 7" rather than "two sevenths"
  (thanks @freedomben for asking)

## 1.6 (2026-07-23)

- Share the open file to any app straight from the viewer toolbar
- "Show in file manager" opens the file's folder in the system Files app
  (appears when the folder can be worked out from where the file came from)

## 1.5 (2026-07-21)

- Find in document: search inside Word, Excel, PowerPoint, Markdown, text
  and code files with match count and next/previous navigation
  (PDF search is not included yet; the PDF renderer does not expose text)

## 1.4 (2026-07-19)

- Gander now appears in the Share sheet: share a document, photo, video or
  audio file from any app (WhatsApp, Gmail, a browser) straight into Gander
- Shared plain text opens in the text viewer

## 1.3.1 (2026-07-19)

- New app icon: fanned file cards on paper, matching the project artwork
  (the old eye mark read as surveillance, the opposite of what Gander is)

## 1.3 (2026-07-19)

- Thumbnail previews in Recent files and folder browsing: images (EXIF
  corrected), video frames, and PDF first pages, cached in memory and on disk

## 1.2 (2026-07-19)

- Useful home screen: Recent files (tap to reopen, long-press to remove) and
  Folders granted once via the system picker, browsable in-app with type
  badges, sizes and dates, all with zero permissions
- Note: Android refuses folder grants for the Downloads root; grant Documents,
  DCIM or a Downloads subfolder instead

## 1.1 (2026-07-19)

- Fixed toolbar sitting under display cutouts (edge-to-edge insets)
- Fixed sideways photos: EXIF orientation is now applied
- Markdown files render as formatted HTML (offline, sanitized)
- Video and audio playback via Media3 ExoPlayer, plus video/audio Open-with

## 1.0 (2026-07-19)

- First release as Gander (formerly ViewAll)
- PDF, Word (.docx), Excel (.xlsx .xls .csv .ods), PowerPoint (.pptx), photos,
  GIF/SVG, Markdown/text/code viewing, fully offline with zero permissions
- Per-ABI release APKs
