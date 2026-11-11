# ECHO Live Monitor prototype

This Android prototype targets the Nothing Phone (3a) and OnePlus Nord Buds 3R.
It captures a selected Android audio input and routes a live monitor to a selected
audio output using Oboe. The callback includes independent six-band left/right
fitting-gain controls, a four-band noise gate, optional four-band downward speech
compression, optional adaptive four-band noise reduction, a conservative digital
output ceiling, a manual processed/baseline speech-score log, and an audiogram and
external fitting-verification record. The app does not convert audiogram thresholds
into a prescription, measure ear-canal sound pressure, or report acoustic end-to-end
latency.

## Open and run

1. Open this folder in Android Studio.
2. Install Android SDK 35, Android NDK 28.2.13676358, and CMake 3.22.1 if Android Studio asks.
3. Let Gradle sync, then connect the Nothing Phone (3a) with USB debugging enabled.
4. Pair the Nord Buds 3R, enable Gaming Mode in HeyMelody, and run the app on the phone.
5. Choose a microphone and output device in the app. Bluetooth earbuds are selected
   automatically when available; otherwise choose an output explicitly. Start with
   low phone and earbud media volume, then grant microphone permission when asked.

The app lists audio devices currently exposed by Android and refreshes the list when
devices connect or disconnect. It selects the built-in microphone and prefers a
Bluetooth A2DP output by default when available. If no Bluetooth output is present,
select an output explicitly. Phone-speaker output can feed back into the microphone.
The app stops monitoring when it leaves the foreground.

## Per-ear profile and speech DSP

The app records separate left/right audiogram thresholds in dB HL at 250, 500, 1k,
2k, 4k, and 6k Hz, and saves independent fitting gain offsets at the same
frequencies. Each peaking filter has Q=1.0 and a range of −6 to +6 dB in 0.5 dB
steps. Thresholds are reference data only; the app does not calculate a prescription
or use threshold entries to change gain. Use only gain recommendations from an
audiogram-based fitting.

The fitting verification section can hold notes about external real-ear measurement,
including date, equipment, input level, target, and measured output in dB SPL. Its
checkbox is a user-entered record only. The phone and Bluetooth earbuds do not
perform calibrated real-ear measurement or verify an in-ear fit. Resetting DSP
defaults preserves the audiogram, verification notes, and speech-score history.

Optional speech compression splits the mono microphone signal into four bands
with Linkwitz–Riley crossovers at 500, 1,500, and 3,500 Hz. Each band uses an
independent peak envelope and downward compression above −36 dBFS. The ratio is
adjustable from 1:1 to 3:1; the initial ratio is 2:1. Attack is 8 ms, release is
120 ms, and there is no makeup gain or look-ahead. It can reduce louder portions
of speech but does not automatically amplify quiet speech. The filters add no
look-ahead delay; their phase response and sound quality still need listening and
measurement checks.

## Microphone noise gate

The optional-by-control four-band gate is enabled by default and operates on the
same 500, 1,500, and 3,500 Hz crossover bands. Its initial open threshold is
−54 dBFS and can be adjusted from −72 to −36 dBFS. It uses 6 dB hysteresis and
attenuates closed bands by up to 24 dB, with a 3/60 ms detector attack/release and
4/100 ms gain opening/closing. This reduces low-level steady hiss during pauses;
it is not a full denoiser and cannot remove noise that overlaps speech. Lower the
threshold if quiet speech is cut. The threshold is a digital level, not a
calibrated acoustic noise measurement.

## Adaptive noise reduction

The optional adaptive reducer operates sample-by-sample in the same four crossover
bands; it does not collect audio frames or add frame buffering. Each band tracks an
envelope with 3 ms attack and 30 ms release and estimates its background floor with
250 ms downward and 8 s upward time constants. Gain is reduced near the estimated
floor and returns to unity about 15 dB above it. Maximum attenuation is adjustable
from 0 to 18 dB, with the UI initially set to 9 dB; the feature starts disabled.
The gain opens over 5 ms and closes over 80 ms. These are experimental settings:
the slow floor tracker may be influenced by sustained speech, and quiet consonants
can be softened. The existing gate still handles low-level pauses. The adaptive
reducer has no frame-buffer latency, although crossover and smoothing filters have
their own time and phase response.

## Speech clarity comparison

The A/B control switches between processed audio and a baseline that bypasses the
gate, adaptive reducer, compressor, and fitting EQ. Monitor trim and the digital
peak limiter stay active in both modes. Choose Quiet or Background noise, listen to
the same short unseen word list in both modes under matching conditions, and enter
the number of words understood and list size. Up to 40 scores are stored locally
with condition, mode, and timestamp. The score is a manual prototype comparison;
it is not a standardized speech-in-noise or hearing test.

## Output controls and digital ceiling

The output trim starts at 15% amplitude and is capped at 50%. Gain changes are
smoothed over 10 ms. A peak limiter reduces digital peaks to −12 dBFS with a
50 ms release and no look-ahead. These digital limits do not establish a safe
sound pressure level at the ear: Android media volume, earbud sensitivity, fit,
and acoustic output are not calibrated by this app. Start with low phone and
earbud volume. The reset button returns the app to flat profiles, 15% trim, and
2:1 speech compression enabled.

## What this prototype can tell us

- Whether simultaneous phone-microphone input and Bluetooth-earbud output work on
  this device pair.
- Which microphone and playback device Android exposes and whether Oboe can open
  the selected pair.
- Whether the stream opens in Android's requested low-latency mode.
- Android's input and output stream latency estimates, when the driver reports them.
- Relative microphone activity and whether the stream disconnects.
- How separate left/right fitting offsets and experimental four-band processing
  behave in a real-time callback. Audiogram thresholds can be recorded as reference
  data but are not automatically fitted.

## Current DSP tuning

| Stage | Initial setting | User adjustment |
|---|---|---|
| Four-band noise gate | Enabled; threshold −54 dBFS; 24 dB maximum attenuation; 6 dB hysteresis | Enable/disable; threshold −72 to −36 dBFS |
| Adaptive four-band noise reduction | Disabled; 9 dB maximum attenuation; 3/30 ms envelope; 250 ms down/8 s up noise-floor tracking; 5/80 ms gain opening/closing | Enable/disable; 0–18 dB maximum attenuation |
| Per-ear peaking EQ | 250, 500, 1k, 2k, 4k, 6k Hz; Q = 1.0; flat by default | Left/right independently: −6 to +6 dB in 0.5 dB steps |
| Four-band speech compression | Enabled; crossovers 500, 1.5k, 3.5k Hz; threshold −36 dBFS; ratio 2:1 | Enable/disable; ratio 1:1 to 3:1 in 0.1 steps |
| Compression envelope | 8 ms attack; 120 ms release; no makeup gain or look-ahead | Fixed time constants |
| Monitor output trim | 15% amplitude; 10 ms gain smoothing | App slider: 0–50% |
| Digital peak ceiling | −12 dBFS; immediate gain reduction; 50 ms release; no look-ahead | Fixed; this does not cap acoustic SPL |

The gate, EQ, and compressor values are experimental DSP settings, not a prescription.
dBFS describes digital signal level, not sound pressure at the eardrum. The peak
ceiling cannot guarantee safe listening volume without acoustic calibration.

Bluetooth A2DP generally cannot provide Android's exclusive low-latency stream, so
the prototype requests shared playback and asks for low-latency performance where
the route supports it. The app reports the mode Android actually grants. Gaming
Mode can reduce the earbud's own delay, but does not remove phone buffering, codec,
radio, and earbud processing delay.

The Android estimates are not an end-to-end latency measurement. Bluetooth codec,
buffering, and earbud processing may add delay beyond what Android reports. Measure
end-to-end acoustic latency separately with a calibrated external recording setup
or OboeTester before making claims about responsiveness.

## Next milestones

1. Measure end-to-end delay and dropouts for the Nothing Phone (3a) + Nord Buds 3R
   with Gaming Mode enabled and disabled.
2. Confirm capture level, distortion, and output level on a coupler before using
   positive fitting gains.
3. Compare processed and baseline scores with repeated, matched word lists in quiet
   and background noise, then adjust the adaptive reducer while watching for lost
   consonants.
4. Verify any audiogram-based fitting with appropriate professional measurements;
   the stored thresholds, notes, and manual sliders do not replace that step.

See [TECHNICAL_SHEET.md](TECHNICAL_SHEET.md) for the full project specification and
[AUDIO_TECHNICAL_SHEET.md](AUDIO_TECHNICAL_SHEET.md) for the audio path, stream
configuration, DSP signal flow and tuning, and latency limits.

This is an engineering demonstration and is not a hearing aid or a substitute for
an audiologist's assessment and fitting.
