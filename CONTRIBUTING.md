# Contributing to Gander

Thanks for taking a look. Gander is intentionally small; the best contributions
keep it that way.

## Ground rules

- The zero-permission, zero-network property is non-negotiable. PRs that add
  INTERNET, storage permissions, analytics, or network calls will be declined.
- Prefer small, focused PRs over large rewrites.
- New file formats are welcome when they can render fully offline with a
  reasonably sized open source renderer.

## Building

See the "Build from source" section in the README. `./gradlew assembleDebug`
produces an installable build without any signing setup.

## Testing changes

Run the app on an emulator or device and open real files of the affected
formats. The viewer routes by extension first, MIME second, in `FileKind.kt`;
WebView-based renderers live in `app/src/main/assets/viewer/`.

## Reporting bugs

Open an issue with the file type, what you expected, what happened, and if
possible a sample file that reproduces it (strip anything private first).
