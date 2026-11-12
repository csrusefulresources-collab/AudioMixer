# Audio Mixer — Android (Kotlin) — v1.2

Every audio format in, one seamless mix out, with live controls while it plays.
Android 9 (API 28) to Android 16 (API 36).

## Build the APK
Requirements: JDK 17, Android SDK platform 36 + build-tools 36, internet (first Gradle sync).

```bash
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease      # signed APK -> app/build/outputs/apk/release/
./gradlew assembleDebug        # debug APK  -> app/build/outputs/apk/debug/
```
Or open the folder in Android Studio and press Run.

## What changed in 1.2 (screen-reader / blind-user redesign)
Screen flow is now 5 numbered steps with headings (TalkBack heading navigation jumps between them):
1 Files · 2 Play one file · 3 Settings of the selected file · 4 Final Play (live) · 5 Save.
- Step 3 holds everything for one file: Selected-file combo box, Placement combo box
  (Merge after / Merge before / Mix / Override), matching check boxes, Position slider with
  Earlier/Later buttons (±1), Volume slider with Quieter/Louder buttons (±5), Mute check box,
  Add Mark button + Marked check box, Remove Selected File.
- Placement is explained in words; the slider is still the exact 0–100 merge/override control.
- Read Mix Summary: spoken overview of every file (mode, start, end, volume, locked).
- Every action is announced (screen reader on) or shown as a toast (screen reader off):
  file ready, placement changed, locked, play/pause position, save progress every 25 %.
- Selecting files / moving sliders no longer rebuilds the screen, so TalkBack focus stays put.
- Destructive actions (Replace, Remove) ask for confirmation.

## What changed in 1.1
- Player rewritten: true Pause/Resume, no UI blocking, last half-second no longer cut off,
  audio focus (calls), pause on headphone unplug, pause when the app goes to background.
- Live volume is click-free in the previews too; one soft limiter shared by speakers and
  saved file (no more global level change on save, saved file = what you heard).
- Android 9 save fixed (storage permission declared, share/open no longer crashes).
- Android 15/16 edge-to-edge insets handled; font-size / display-size changes handled.
- Import File now really starts a new mix (with confirmation); Add Another File keeps adding.
- Failed/non-audio files are removed automatically with a clear message.
- Unlock keeps the file where it is. Start times are shown relative to file 1 (+/-).
- New: Final-mix playhead slider (jump anywhere while checking), Cancel while saving,
  per-file Options button (Add Mark / Unlock / Remove).
- Accessibility (TalkBack): headings, spoken descriptions for every control, file rows and
  the timeline, slider state text (Android 11+), 48dp+ touch targets, text in sp (scales),
  larger hint text, status messages announced as toasts.

## Signing key (demo)
`keystore/audiomixer.jks` — password `audiomixer`, alias `audiomixer`.
Keep using the same key so updates install over the old version. Replace it before Google Play.

## Structure
MainActivity (UI) · Decoder (MediaCodec, FFmpeg fallback) · MixPlayer · Pcm (resampler, MixBus,
WAV) · Mp3Encoder (LAME) · Exporter · TimelineView · UiKit

## The slider (file i, slider s 0–100, hi = furthest end placed before it)
- s >= 50 → start = (s-50)/50 * hi   (100 = exactly where everything ends, seamless; 50 = on top)
- s < 50  → start = -(50-s)/50 * length_i   (0 = plays completely before file 1)
