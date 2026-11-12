# Audio Mixer — Android (Kotlin) — v1.1

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
