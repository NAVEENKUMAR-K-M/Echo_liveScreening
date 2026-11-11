# ECHO Live Monitor — Audio Technical Sheet

**Project version:** 0.1.0  
**Snapshot date:** 2026-10-19  
**Target route:** Nothing Phone (3a) microphone → on-phone processing → OnePlus Nord Buds 3R playback

## Audio system overview

ECHO is a live audio-monitoring prototype. It captures an Android-selected input,
runs sample-by-sample DSP in a native Oboe full-duplex callback, then writes the
processed signal to an Android-selected output.

```text
Ambient sound
  → selected phone / headset microphone
  → Android input path
  → Oboe float-PCM mono input
  → C++ full-duplex callback
  → four-band DSP and per-ear EQ
  → Oboe float-PCM stereo output
  → Android output route / Bluetooth transport
  → earbuds
```

The intended setup uses the phone's built-in microphone and Bluetooth earbuds for
playback. The app does not require the earbud microphone; the microphone and
playback output can be selected separately from Android's currently exposed audio
devices.

## Capture and playback stream requests

| Property | Capture input | Playback output |
|---|---|---|
| Device routing | Selected Android input device ID | Selected Android output device ID |
| Stream API | Oboe full-duplex stream | Oboe full-duplex stream |
| Performance mode | Low latency requested | Low latency requested |
| Sharing mode | Exclusive requested | Shared requested |
| PCM format | Float | Float |
| Channel count | Mono requested | Stereo requested |
| Sample rate | Set to the output stream's opened sample rate | Selected by Android/Oboe and shown at runtime |
| Input preset | `VoicePerformance` | — |
| Format/channel conversion | Allowed | Allowed |
| Buffer request | Input capacity requested as 2× output stream capacity | No fixed output buffer size requested by the app |
| Usage / content type | — | Accessibility assistance / speech |

These are stream requests; Android may grant a different performance mode or
route-specific behavior. The app reports the opened sample rate, output channel
count, low-latency mode status, and Oboe-reported stream latency estimates when
available. Device changes are refreshed from Android. If a selected device is
removed during monitoring, the app stops the audio stream.

The input is downmixed to mono in the callback. That mono signal feeds both
playback channels. The left and right output channels then use separate EQ settings.

## Processing order

Processed mode uses the following order:

1. Downmix captured input to mono.
2. Split into four frequency bands at 500, 1,500, and 3,500 Hz.
3. Apply optional adaptive noise reduction to each band.
4. Apply the noise gate to each band.
5. Apply optional speech compression to each band.
6. Sum the four bands.
7. Apply six peaking EQ filters for each output ear.
8. Apply monitor output trim with smoothing.
9. Apply a per-channel digital peak limiter.
10. Write stereo output.

The crossover branches use two cascaded second-order Butterworth sections per
branch, forming fourth-order Linkwitz–Riley crossovers. There is no frame
collection or neural-model inference in the adaptive reducer. The crossover and
gain envelopes still have phase and time responses, so this does not mean zero
audio-path latency.

## Current audio tuning

| Processing stage | Current initial value | Adjustment range / behavior |
|---|---|---|
| Crossover frequencies | 500 / 1,500 / 3,500 Hz | Fixed; four bands |
| Adaptive noise reduction | Disabled; maximum attenuation 9 dB | Enable/disable; 0–18 dB maximum attenuation |
| Noise reducer envelope | 3 ms attack / 30 ms release | Fixed |
| Noise floor tracking | 250 ms downward / 8 s upward | Fixed per band |
| Noise reducer gain smoothing | 5 ms opening / 80 ms closing | Fixed |
| Noise gate | Enabled; open threshold −54 dBFS | Enable/disable; −72 to −36 dBFS |
| Noise gate hysteresis | 6 dB; closed gain down to −24 dB | Fixed |
| Gate detector | 3 ms attack / 60 ms release | Fixed |
| Gate gain smoothing | 4 ms opening / 100 ms closing | Fixed |
| Speech compression | Enabled; threshold −36 dBFS; ratio 2:1 | Enable/disable; 1:1–3:1 in 0.1 steps |
| Compressor envelope | 8 ms attack / 120 ms release | Fixed; no makeup gain or look-ahead |
| Per-ear peaking EQ | Flat by default; Q = 1.0 | Six centers per ear; −6 to +6 dB in 0.5 dB steps |
| EQ center frequencies | 250 / 500 / 1,000 / 2,000 / 4,000 / 6,000 Hz | Fixed |
| Monitor output trim | 15% amplitude | 0–50%; smoothed over 10 ms |
| Digital output limiter | −12 dBFS ceiling | 50 ms release; no look-ahead |

### Adaptive noise reduction

The reducer maintains a smoothed signal envelope and estimated noise floor for
each crossover band. Its envelope uses 3 ms attack and 30 ms release. The noise
floor falls with a 250 ms time constant and rises with an 8 s time constant. This
lets the estimate follow falling background levels more quickly than rising levels.

The gain is reduced near the estimated floor and returns to unity when the band
envelope is about 15 dB above it. A smooth transition connects those points. The
selected maximum attenuation is clamped to 0–18 dB and converted to linear gain
outside the audio callback. At the 9 dB initial setting, the minimum gain near the
floor is approximately 0.355.

This is an experimental adaptive multiband expander. It is not RNNoise, spectral
subtraction, or a validated hearing-aid noise reducer. A changing noise floor or
sustained speech can influence its estimate; quiet consonants may be softened.

### Noise gate

The gate tracks each band with a 3 ms attack and 60 ms release. It opens at the
selected threshold and closes 6 dB below it. When closed, the band gain approaches
0.063 (−24 dB), with 4 ms opening and 100 ms closing gain transitions. This is
intended to reduce low-level sound during pauses. A high threshold can cut quiet
speech. The threshold is dBFS, a digital value, not a calibrated acoustic level.

### Speech compression

The compressor tracks a separate envelope for each band. Below −36 dBFS, that
band passes unchanged. Above threshold, the envelope follows the selected downward
ratio, initially 2:1. Attack is 8 ms and release is 120 ms. There is no makeup
gain, look-ahead, or automatic quiet-speech gain. It reduces louder portions rather
than applying a complete hearing-aid prescription.

### Per-ear EQ

Each output channel has six cascaded peaking biquad filters centered at 250, 500,
1,000, 2,000, 4,000, and 6,000 Hz. Each filter uses Q=1.0. Left and right gains are
independent and adjustable from −6 to +6 dB in 0.5 dB increments. These controls
are manual fitting offsets; they do not calculate gain from audiogram thresholds.

### Output trim and limiter

The app's digital monitor trim defaults to 15% amplitude and is adjustable to a
maximum of 50%. Trim changes are smoothed over 10 ms. A separate per-output-channel
limiter reduces digital peaks to −12 dBFS and releases over 50 ms. It has no
look-ahead. Neither the trim cap nor the limiter sets a known sound-pressure level
at the eardrum.

## Processed and baseline comparison

The processed/baseline selector is available during monitoring:

- **Processed** selects the four-band DSP sum and per-ear EQ, with optional stages
  active according to their controls.
- **Baseline** selects the downmixed microphone signal before the gate, reducer,
  compressor, and EQ.

Monitor trim and the digital limiter remain active in both modes. The app can save
manual word-recognition scores under Quiet or Background noise conditions. These
scores are a prototype A/B log, not a standardized speech-in-noise test.

## Latency and measurement status

The app requests low-latency Oboe streams and displays Android/Oboe input and
output latency estimates if available. During development, the reported values
were approximately 3–5 ms input and about 15 ms output. Those are stream estimates,
not a measured acoustic microphone-to-earbud delay. Bluetooth codec, buffering,
radio transport, and earbud processing can add delay that the Android estimates
may not include.

End-to-end latency has not been measured by the app. A measurement should record
the same acoustic event at the phone microphone and earbud output using a calibrated
external recording path, repeated across runs and routes. Record sample rate,
reported stream estimates, buffer behavior, Gaming Mode state, and any dropouts.

## A/B mode DSP detail

The audio callback continues advancing the DSP path while A/B mode is switched, but
baseline output is taken from the mono microphone sample before the four-band
processing. Both routes continue through monitor trim and the limiter. This keeps
the user-adjustable output level and digital ceiling consistent during the
comparison.

## Audio limits and risks

- No acoustic SPL calibration, real-ear measurement, coupler measurement, or
  feedback-cancellation system is implemented.
- Android microphone processing may affect the captured signal; this app does not
  independently measure or characterize those effects.
- The phone microphone's position relative to the listener's ears differs from a
  hearing aid microphone and affects spatial cues and handling noise.
- The gate and reducer may remove quiet speech details; test at low listening
  volume and compare against baseline.
- The −12 dBFS ceiling is digital only. Android media volume, earbud sensitivity,
  earbud fit, and acoustic output are not calibrated here.
- Bluetooth output latency and total acoustic latency require external measurement.
- This prototype is an engineering monitor, not a fitted hearing aid or substitute
  for an audiologist's fitting.

## Implementation files

| File | Audio responsibility |
|---|---|
| `app/src/main/cpp/native_audio_engine.cpp` | Oboe capture/playback streams, callback, crossover, reducer, gate, compressor, EQ, trim, limiter |
| `app/src/main/java/org/echo/livemonitor/NativeAudioEngine.kt` | JNI declarations for the native audio controls |
| `app/src/main/java/org/echo/livemonitor/MainActivity.kt` | Audio-device selectors and controls for levels, DSP, and A/B mode |

