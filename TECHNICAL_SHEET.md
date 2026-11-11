# ECHO Live Monitor — Technical Sheet

**Document version:** 1.0  
**Project version:** 0.1.0 (Android application versionCode 1)  
**Snapshot date:** 2026-10-19  
**Project type:** Android live-audio processing prototype

## 1. Project summary

ECHO captures audio from a microphone exposed by Android, processes it in a native
real-time audio callback, and plays it through an Android output device. The target
prototype setup is a Nothing Phone (3a) using its built-in microphone and OnePlus
Nord Buds 3R for playback, with Gaming Mode enabled when available.

The application demonstrates live monitoring and experimental hearing-assistance
DSP. It is not a medical device, a fitted hearing aid, or a validated hearing test.
It has no calibrated measurement of sound pressure at the eardrum.

## 2. Audio path and software architecture

```text
Ambient sound
  → Android-selected microphone
  → Oboe full-duplex input stream (mono float PCM)
  → native C++ DSP callback
  → Oboe output stream (stereo float PCM)
  → Android-selected output / Bluetooth transport
  → earbuds
```

The Android UI is written in Kotlin. It selects audio devices, exposes live
controls, and stores settings and records in app-private `SharedPreferences`. JNI
connects the UI to the C++ audio engine. The native engine uses Oboe's
`FullDuplexStream` callback for capture and playback. There is no network service
or account requirement in this implementation.

The microphone signal is downmixed to mono before processing. The processed mono
signal is sent to both output channels; each channel gets its own six-filter EQ
profile. Output channel 0 uses the left profile and channel 1 the right profile.

## 3. Platform and build configuration

| Item | Configuration |
|---|---|
| Application label | ECHO Live Monitor |
| Application ID | `org.echo.livemonitor` |
| Android minimum SDK | 29 |
| Compile / target SDK | 35 / 35 |
| Kotlin and Java bytecode target | JVM 17 |
| Native language standard | C++17 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 |
| Audio library | Oboe 1.9.3 |
| Build ABIs | `arm64-v8a`, `armeabi-v7a` |
| Microphone permission | `RECORD_AUDIO` |
| Native linker page alignment | 16 KB maximum and common page size flags for project-built native library |

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk` by
`gradlew.bat assembleDebug` on Windows.

The current APK's ELF `LOAD` segment alignment was inspected: all packaged
`arm64-v8a` libraries (`libechoaudio.so`, `liboboe.so`, and `libc++_shared.so`)
show 16 KB alignment (`0x4000`). In `armeabi-v7a`, `libechoaudio.so` and
`liboboe.so` show `0x4000`, while `libc++_shared.so` shows 4 KB (`0x1000`). The
64-bit native libraries relevant to the reported phone warning are now aligned;
the 32-bit packaged C++ runtime remains a page-size compatibility caveat. ELF
segment inspection alone is not a runtime test on a 16 KB page-size Android
system.

## 4. Audio stream configuration

| Stream property | Input | Output |
|---|---|---|
| Direction | Capture | Playback |
| Device | Selected Android input device ID | Selected Android output device ID |
| Requested performance | Low latency | Low latency |
| Requested sharing | Exclusive | Shared |
| Format | Float PCM | Float PCM |
| Channels | Mono | Stereo |
| Sample rate | Set to the opened output stream's sample rate | Chosen by Android/Oboe; reported at runtime |
| Input preset | `VoicePerformance` | — |
| Conversion | Channel and format conversion allowed | Channel and format conversion allowed |
| Buffer request | Capacity set to twice the output stream's capacity | Oboe/Android manages the active stream buffer |
| Audio usage/content | — | Accessibility assistance / speech |

The low-latency mode is requested, not guaranteed. The app displays the actual
stream sample rate, channel count, granted performance mode, and Oboe input/output
latency estimates when available. Android's stream estimates can omit Bluetooth
codec, radio, buffering, and earbud processing delay.

Android device lists are refreshed when devices attach or detach. The app defaults
to a built-in microphone and prefers Bluetooth A2DP playback when present. Device
menus are locked while monitoring; losing a selected device stops the monitor. The
app requests transient audio focus and stops monitoring when it leaves the
foreground.

## 5. Native signal-processing chain

In processed mode, the signal path is:

1. Downmix captured input channels to mono.
2. Split into four bands using cascaded second-order Butterworth crossover sections
   at 500, 1,500, and 3,500 Hz (Linkwitz–Riley fourth-order crossover branches).
3. Apply optional adaptive noise reduction independently to each band.
4. Apply the four-band noise gate independently to each band.
5. Apply optional downward compression independently to each band.
6. Sum the four bands.
7. Apply the selected ear's six peaking EQ filters.
8. Apply monitor trim with 10 ms smoothing.
9. Apply the per-channel peak limiter and write stereo output.

The controls can be changed while audio is running. DSP calculations use no
external model or frame-based neural denoiser. The crossover filters and envelope
smoothing have time and phase responses, so the callback's lack of frame buffering
does not mean the acoustic path has zero latency.

## 6. DSP settings and tuning

| Stage | Algorithm / bands | Current initial values | Available user controls |
|---|---|---|---|
| Four-band crossover | LR4 branches at 500, 1,500, 3,500 Hz | Always active in processed mode | Fixed |
| Adaptive noise reduction | Per-band envelope and adaptive noise-floor gain; smooth gain curve | Disabled; max attenuation 9 dB; envelope attack/release 3/30 ms; noise-floor down/up tracking 250 ms/8 s; gain opening/closing 5/80 ms | Enable/disable; 0–18 dB maximum attenuation |
| Noise gate | Per-band threshold detector with hysteresis | Enabled; −54 dBFS open threshold; 6 dB hysteresis; up to −24 dB attenuation; detector 3/60 ms; gain opening/closing 4/100 ms | Enable/disable; −72 to −36 dBFS threshold |
| Speech compression | Per-band envelope and downward compression above threshold | Enabled; threshold −36 dBFS; ratio 2:1; attack/release 8/120 ms; no makeup gain or look-ahead | Enable/disable; ratio 1:1 to 3:1 in 0.1 steps |
| Per-ear EQ | Six peaking biquads per output channel, Q=1.0 | Flat; centers 250, 500, 1k, 2k, 4k, 6k Hz | Each band −6 to +6 dB in 0.5 dB steps, left/right separately |
| Monitor trim | Output amplitude scaling | 15%; 10 ms smoothing | 0–50% |
| Peak limiter | Per-output-channel peak clamp with release smoothing | −12 dBFS ceiling; 50 ms release; no look-ahead | Fixed |

### Adaptive noise reduction detail

For each band, the engine tracks a smoothed signal envelope and a slowly changing
noise-floor estimate. The envelope uses a 3 ms attack and 30 ms release. The floor
falls with a 250 ms time constant and rises with an 8 s time constant, which helps
avoid immediately learning sustained speech as background noise.

The gain is at its selected minimum near the estimated floor and returns to unity
around 15 dB above the floor. The transition uses a smooth curve. Selected maximum
attenuation is converted to a linear minimum gain before entering the audio
callback. This is a lightweight adaptive multiband expander, not RNNoise or a
validated speech denoiser. Sustained noise or speech can affect its floor estimate;
strong settings can soften quiet consonants.

### Noise gate detail

The gate opens at the selected threshold and closes 6 dB below it. Closed bands
approach −24 dB attenuation with a 100 ms closing transition; opening uses 4 ms.
The control is relative digital level in dBFS, not an acoustic noise measurement.
It is intended to reduce low-level sound during pauses and can clip quiet speech
when set too high.

### Compressor detail

For each band, an envelope below −36 dBFS passes unchanged. Above that threshold,
the output envelope follows the selected downward ratio. The default ratio is 2:1.
There is no makeup gain, so this implementation primarily reduces louder portions;
it does not automatically raise quiet speech above its original level.

## 7. Audiogram, fitting, and verification records

The app can store left- and right-ear threshold entries at 250, 500, 1,000, 2,000,
4,000, and 6,000 Hz. The entry range is −10 to 120 dB HL. These entries are
reference data only: they are not connected to DSP gain calculations.

Fitting gain offsets are separate controls. Each ear has six peaking EQ bands from
−6 to +6 dB in 0.5 dB steps. The prototype does not implement NAL-NL2, DSL, or
another hearing-aid prescription formula.

The fitting-verification area stores a user-entered checkbox and notes for an
external real-ear measurement, such as date, equipment, input level, target, and
measured output. The app cannot perform real-ear measurement, coupler measurement,
or SPL calibration. The checkbox and notes do not certify the fit.

## 8. Processed/baseline speech comparison

The A/B switch selects between:

- **Processed:** currently enabled adaptive reducer, gate, compression, and
  per-ear EQ are audible.
- **Baseline:** raw downmixed microphone audio is selected before these DSP stages.

Monitor trim and the digital limiter remain active in both modes. The environment
selector offers Quiet and Background noise. A user records understood words and
total list size (1–200); the app stores up to 40 scores with environment, mode, and
timestamp. Use the same unseen word material under matched conditions when
comparing results. The log is manual and is not a standardized speech-in-noise or
clinical test.

## 9. Latency and performance status

The audio callback uses Oboe full-duplex streaming and the app asks Android for
low-latency streams. The adaptive reducer does not add frame collection or model
inference buffering. End-to-end acoustic latency has not been established by the
app. Previously observed input/output stream estimates of roughly 3–5 ms and about
15 ms are platform-reported estimates, not a microphone-to-earbud acoustic
measurement; the Bluetooth portion can be missing from those estimates.

To quantify latency, capture a repeatable acoustic event at the phone microphone
and earbud output with a calibrated external recording setup, or use an equivalent
validated measurement method. Record route, sample rate, buffer behavior, Gaming
Mode state, dropouts, and repeated-run variation.

## 10. Data, safety, and known limitations

- Settings, audiogram entries, verification notes, and speech scores are stored in
  app-private preferences. The manifest disables Android backup.
- Reset to cautious defaults resets DSP tuning but preserves audiogram entries,
  verification notes, and score history.
- The −12 dBFS limiter is a digital ceiling. It does not set or guarantee a safe
  acoustic level at the ear. Earbud sensitivity, fit, Android media volume, and
  output calibration are outside the app's measurement.
- There is no validated audiogram fitting, automatic gain prescription, real-ear
  measurement, feedback cancellation, directional beamforming, ambient speech
  classifier, or end-to-end latency measurement in this snapshot.
- The app stops monitoring when it leaves the foreground; it is not a background
  listening service.
- This is an engineering prototype and should not be presented or used as a fitted
  hearing aid.

## 11. Source map

| File | Responsibility |
|---|---|
| `app/src/main/java/org/echo/livemonitor/MainActivity.kt` | User interface, device selection, settings, A/B score log, audiogram and verification records |
| `app/src/main/java/org/echo/livemonitor/NativeAudioEngine.kt` | JNI API declarations |
| `app/src/main/cpp/native_audio_engine.cpp` | Oboe stream setup, full-duplex callback, DSP and JNI implementation |
| `app/src/main/cpp/CMakeLists.txt` | Native build, Oboe linkage, C++17 and 16 KB ELF page alignment flags |
| `app/build.gradle.kts` | Android SDK, NDK, ABI, Kotlin and dependency configuration |
| `app/src/main/AndroidManifest.xml` | App identity, launcher activity and microphone permission |

