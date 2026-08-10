#!/usr/bin/env python3
"""Open a file in Gander through the SAF picker, so it lands in Recents."""
import os
import sys
import time
import ui

# The picker's label for the device's own storage, which is what "Show roots"
# lists. On an emulator it is the AVD product name; on a phone it is usually the
# model. `adb shell getprop ro.product.name` is the value to try first.
STORAGE = os.environ.get("GANDER_SHOT_STORAGE", "sdk_gphone64_arm64")
# Keeps matches out of the toolbar title and breadcrumb, which repeat the
# folder name. The type-filter chips ("Images", "Audio", "Videos",
# "Documents") are excluded by class instead: they are Buttons, and matching
# the "Documents" chip rather than the Documents folder flattens the listing.
LIST_TOP = 250


def reset():
    """Leave no picker or viewer on top of the home screen."""
    for pkg in ("com.google.android.documentsui", "com.android.documentsui",
                ui.PKG):
        ui.sh("shell", f"am force-stop {pkg}")
    time.sleep(1.2)


def _navigate(folder):
    """Drive the picker from wherever it is to the given folder listing."""
    for attempt in range(4):
        if ui.by_text("Show roots") is None:
            time.sleep(1.5)
            continue
        ui.tap_text("Show roots", wait=2.5)
        if ui.by_text(STORAGE, exact=True) is None:
            time.sleep(1.5)
            continue
        ui.tap_text(STORAGE, exact=True, wait=3.2)
        for _ in range(5):
            if ui.tap_text(folder, exact=True, wait=3.0,
                           min_y=LIST_TOP, cls="TextView", tries=1):
                return True
            ui.swipe(540, 1500, 540, 800, ms=250, wait=1.2)
        time.sleep(1.5)
    return False


def open_file(folder, filename, wait_after=6.0):
    reset()
    ui.launch()
    if not ui.tap_text("Open a file", wait=3.5):
        raise RuntimeError("no FAB")
    if not _navigate(folder):
        print("VISIBLE:\n" + "\n".join(ui.texts()))
        raise RuntimeError(f"could not reach folder {folder}")

    # List view fits far more rows than the default grid, so fewer files sit
    # below the fold.
    if ui.by_text("List view") is not None:
        ui.tap_text("List view", exact=True, wait=2.0)

    # Long names are ellipsised, so match on a prefix. Restrict to the label
    # TextView: every row also carries a "Preview the file X" button whose
    # content-desc matches the same prefix, and tapping that hands the file to
    # the system resolver instead of back to Gander.
    probe = filename.rsplit(".", 1)[0][:14]
    for scrolls in range(6):
        if ui.tap_text(probe, wait=3.0, min_y=LIST_TOP, cls="TextView", tries=1):
            break
        ui.swipe(540, 1500, 540, 800, ms=250, wait=1.2)
    else:
        print("VISIBLE:\n" + "\n".join(ui.texts()))
        raise RuntimeError(f"no file matching {probe!r}")

    time.sleep(wait_after)
    if ui.by_text("Open with") is not None:
        raise RuntimeError("fell out of picker mode into the Files app")
    return True


if __name__ == "__main__":
    open_file(sys.argv[1], sys.argv[2])
    print("OPENED", sys.argv[2])
