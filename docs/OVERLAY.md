# Floating captions and chunk profiles

Enable **Settings → Allow floating captions**, grant Android's **Display over
other apps** permission, return to the app, enable **Show while listening**, then
Listen. Denying overlay access does not affect the main caption UI or microphone
permission. The existing microphone foreground service owns the overlay; show/hide
notification actions do not restart recognition or clear caption history.

The window uses `TYPE_APPLICATION_OVERLAY` and `SYSTEM_ALERT_WINDOW`, never an
Accessibility Service. It is not focusable, so it does not take keyboard focus.
Background opacity is adjustable from transparent to 90%; text is 16–40 sp, with
1–4 translated lines and an optional two-line source transcript underneath. The
latest available translated segment remains visible while the next translation
is pending. Revision updates replace text, and provisional text is italicized.

Drag with touch-through disabled. The app saves normalized positions separately
for compact (<600 dp wide) and expanded displays. Display changes recreate the
window with the new display context, text density and bounded coordinates. System
bar/cutout insets and window height constrain placement. The implementation targets
the Magic V5 cover/inner display transition but has not been tested on that phone.

Touch-through uses `FLAG_NOT_TOUCHABLE` and caps **window alpha** at Android's
`InputManager.getMaximumObscuringOpacityForTouch()` limit (at most 0.8). Merely
making a background drawable transparent would not satisfy Android 12's obscuring
checks. There is one overlay window. System UI or apps that deliberately block
overlays can hide it. A hidden/locked/stopped overlay does not own audio or change
the recognizer. Settings persist; Stop removes the window and closes it on service
destruction. Permission revocation is checked on each render and every 5 seconds.

The performance panel samples proportional RAM (PSS) every 5 seconds, including
the app's additional same-UID QNN process when available. This approximates app
and model memory together, including resident model mappings. The separate native
heap measurement covers only the main process. These are approximate process
metrics, not an exact allocation breakdown per neural model.

## Nemotron chunk assets

The [upstream ASR release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)
contains distinct multilingual Nemotron 3.5 INT8 archives dated 2026-06-11:

| Profile | Chunk | Archive bytes | SHA-256 |
| --- | --- | --- | --- |
| Ultra Low Latency | 160 ms | 475273363 | `a81909a1780d84cff16d73c15e13e67d9d81d8839faf14870d507d8499f7a61a` |
| Balanced | 320 ms | 475272949 | `5f311142337a5c161e92d49f7a3009d8607d3836f39d610bff5307c74d1d2c53` |
| Accuracy (default) | 560 ms | 475271763 | `c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a` |
| Max Context | 1120 ms | 475276334 | `adbdd5e9fef87300c37cebfcfc4f1ebe56845c860c8a760af0a1dd65ce9beed3` |

These are model choices, not a runtime chunk-size parameter. All four archives
were downloaded and hashed, then loaded and executed using sherpa-onnx 1.13.8
against the pinned FLEURS Dutch recording. Each produced multiple partials and
a Dutch final containing the expected words. See
[recorded host results](validation/cpu-chunk-models.txt) and
`scripts/chunk_smoke.py`. Profile labels are descriptive, not comparative quality
or phone latency guarantees. The 160-ms model has more frequent decoder work.

Settings exposes download/test-load controls for CPU profiles. Selection of an
additional profile requires a successful load and silent decode on the phone's
CPU runtime; that validation is bound to the app version and asset digest.
Stopping is required before model downloads, removal, backend or chunk changes.
Removing a required asset makes the profile not offline-ready until reinstalled.

QNN remains a separate 560-ms SM8750 context and is experimental. Switching to
QNN selects its matching 560-ms CPU fallback. Additional QNN profiles are not
offered without actual hardware execution evidence; no CPU ONNX file is passed
to a QNN context loader.

## Validation scope

Pure unit tests cover opacity policy, fold/rotation coordinate bounds, settings
limits, caption revisions and chunk routing. Robolectric tests exercise actual
Android View/WindowManager calls, permission denial/revocation, touch-through
flags, caption/source rendering, stop/restart cleanup, display changes and saved
settings. Robolectric is a simulated framework, not physical-device validation.
On-device drag/touch forwarding across other apps, MagicOS folding behavior and
NPU execution still require testing on the Honor phone.

Android references:
[overlay windows](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY),
[touch-through restrictions](https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events),
[overlay permission](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION).
