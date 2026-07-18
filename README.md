# ViewAll

A small, private Android file viewer. Open a file from the in-app picker or via
"Open with" from any file manager, then pinch to zoom and scroll. Everything
renders on the device: the app requests no permissions and has no internet
access at all.

## Supported formats

| Category | Formats | Renderer |
| --- | --- | --- |
| Documents | PDF | Pdfium (native, `android-pdf-viewer`) |
| | Word `.docx` | `docx-preview` in an offline WebView |
| Spreadsheets | `.xlsx` `.xls` `.xlsm` `.xlsb` `.csv` `.ods` | SheetJS in an offline WebView |
| Slides | `.pptx` | PPTXjs in an offline WebView |
| Photos | JPG, PNG, WebP, BMP, HEIC/HEIF | SubsamplingScaleImageView (tiled, deep zoom) |
| | GIF (animated), SVG, AVIF, ICO | WebView |
| Text | `.txt` `.md` `.json` `.xml` logs and most code files | WebView text viewer |

Legacy binary Office files (`.doc`, `.ppt`) are not supported; the app shows a
hint to re-save them in the modern format. `.xls` works.

## Building

Requirements already on this machine: Android SDK at `~/Library/Android/sdk`,
Gradle 8.x, and a JDK 17+ (the build is pinned to Android Studio's bundled
JDK 21 via `org.gradle.java.home` in `gradle.properties`; adjust that path if
Android Studio moves).

```sh
gradle assembleRelease
# APK lands in app/build/outputs/apk/release/app-release.apk
```

The release build is signed with `keystore/viewall.jks`
(store and key password: `viewall-local`, alias `viewall`). This keystore was
generated locally for personal sideloading. Keep using the same keystore for
updates, otherwise Android will refuse to update the installed app.

## Installing on a phone

1. Copy `app-release.apk` to the phone (or `adb install app-release.apk`).
2. Tap it, allow "install unknown apps" for your file manager when prompted.

## Architecture notes

- `ViewerActivity` routes by file extension first, MIME type second
  (`FileKind.kt`), then shows one of three surfaces: a native tiled image view,
  a native Pdfium view, or a WebView.
- WebView pages are served through `WebViewAssetLoader`
  (`https://appassets.androidplatform.net/`): `/assets/` maps to bundled
  assets, `/doc/` streams the opened document from its content URI. Because
  everything is intercepted in-process, the app works with zero permissions.
- Office rendering libraries are vendored under
  `app/src/main/assets/viewer/lib/`: JSZip (MIT), docx-preview (Apache-2.0),
  SheetJS CE (Apache-2.0), PPTXjs + divs2slides (MIT) with jQuery 1.11 (MIT),
  D3 3.x + NVD3 (BSD/Apache) for PPTX charts.
- PPTX fidelity is approximate by nature (JS re-implementation of PowerPoint
  layout). Complex decks (smart art, exotic themes, embedded fonts) will look
  simplified.

## Ideas for later

- Recent files list on the home screen
- Video and audio playback (ExoPlayer/Media3)
- Legacy `.doc` and `.ppt` support
- Markdown rendered as HTML instead of plain text
- iOS: either a thin native SwiftUI app around QuickLook (Apple renders
  PDF/Office/images natively) or a Flutter port reusing the same JS viewer
  assets in a WebView
