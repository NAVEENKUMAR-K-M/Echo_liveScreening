#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <iomanip>
#include <memory>
#include <sstream>
#include <string>

#include <oboe/Oboe.h>

namespace {

constexpr std::size_t kEarCount = 2;
constexpr std::size_t kEqBandCount = 6;
constexpr std::size_t kSpeechBandCount = 4;
constexpr std::array<float, kEqBandCount> kEqCenterFrequenciesHz{
        250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 6000.0f};
constexpr std::array<float, 3> kSpeechCrossoversHz{500.0f, 1500.0f, 3500.0f};
constexpr float kSpeechThresholdDb = -36.0f;
constexpr float kNoiseGateMinimumGain = 0.06309573f; // -24 dB attenuation while closed
constexpr float kLimiterCeiling = 0.25118864f; // -12 dBFS
constexpr float kDenoiserQuietSnrRatio = 1.4f;
constexpr float kDenoiserOpenSnrRatio = 5.62341325f; // 15 dB above estimated floor

class EchoMonitor final : public oboe::FullDuplexStream,
                          public oboe::AudioStreamErrorCallback {
public:
    EchoMonitor() {
        for (auto &earGains : mEqTargetGainsDb) {
            for (auto &gain : earGains) gain.store(0.0f, std::memory_order_relaxed);
        }
    }

    bool startMonitor(int32_t inputDeviceId, int32_t outputDeviceId) {
        stopMonitor();
        mDisconnected.store(false, std::memory_order_relaxed);

        oboe::AudioStreamBuilder outputBuilder;
        outputBuilder.setDirection(oboe::Direction::Output)
                ->setDeviceId(outputDeviceId)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                // Bluetooth A2DP generally cannot provide an exclusive low-latency stream.
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setChannelConversionAllowed(true)
                ->setFormatConversionAllowed(true)
                ->setUsage(oboe::Usage::AssistanceAccessibility)
                ->setContentType(oboe::ContentType::Speech)
                ->setPackageName("org.echo.livemonitor")
                ->setDataCallback(this)
                ->setErrorCallback(this);

        oboe::Result result = outputBuilder.openStream(mOutputStream);
        if (result != oboe::Result::OK) {
            mLastError = "Could not open the selected playback output stream";
            return false;
        }

        oboe::AudioStreamBuilder inputBuilder;
        inputBuilder.setDirection(oboe::Direction::Input)
                ->setDeviceId(inputDeviceId)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setChannelConversionAllowed(true)
                ->setFormatConversionAllowed(true)
                ->setSampleRate(mOutputStream->getSampleRate())
                ->setInputPreset(oboe::InputPreset::VoicePerformance)
                ->setPackageName("org.echo.livemonitor")
                ->setBufferCapacityInFrames(mOutputStream->getBufferCapacityInFrames() * 2);

        result = inputBuilder.openStream(mInputStream);
        if (result != oboe::Result::OK) {
            mLastError = "Could not open the phone microphone stream";
            closeStreams();
            return false;
        }

        resetDsp(mOutputStream->getSampleRate());
        setSharedInputStream(mInputStream);
        setSharedOutputStream(mOutputStream);
        result = oboe::FullDuplexStream::start();
        if (result != oboe::Result::OK) {
            mLastError = "Could not start full-duplex audio";
            closeStreams();
            return false;
        }

        mLastError.clear();
        mRunning.store(true, std::memory_order_release);
        return true;
    }

    void stopMonitor() {
        mRunning.store(false, std::memory_order_release);
        if (mInputStream || mOutputStream) {
            oboe::FullDuplexStream::stop();
        }
        closeOne(mOutputStream);
        closeOne(mInputStream);
        mPeak.store(0.0f, std::memory_order_relaxed);
    }

    void setLevel(float level) {
        mMonitorLevel.store(std::clamp(level, 0.0f, 0.5f), std::memory_order_relaxed);
    }

    void setEqualizerBand(int32_t ear, int32_t index, float gainDb) {
        if (ear < 0 || static_cast<std::size_t>(ear) >= kEarCount ||
            index < 0 || static_cast<std::size_t>(index) >= kEqBandCount) return;
        mEqTargetGainsDb[static_cast<std::size_t>(ear)][static_cast<std::size_t>(index)].store(
                std::clamp(gainDb, -6.0f, 6.0f), std::memory_order_relaxed);
    }

    void setSpeechCompressionEnabled(bool enabled) {
        mSpeechCompressionEnabled.store(enabled, std::memory_order_relaxed);
    }

    void setSpeechCompressionRatio(float ratio) {
        mSpeechCompressionRatio.store(std::clamp(ratio, 1.0f, 3.0f),
                                      std::memory_order_relaxed);
    }

    void setNoiseGateEnabled(bool enabled) {
        mNoiseGateEnabled.store(enabled, std::memory_order_relaxed);
    }

    void setNoiseGateThresholdDb(float thresholdDb) {
        const float clampedDb = std::clamp(thresholdDb, -72.0f, -36.0f);
        mNoiseGateOpenThreshold.store(std::pow(10.0f, clampedDb / 20.0f),
                                      std::memory_order_relaxed);
    }

    void setAdaptiveDenoiserEnabled(bool enabled) {
        mAdaptiveDenoiserEnabled.store(enabled, std::memory_order_relaxed);
    }

    void setAdaptiveDenoiserStrengthDb(float strengthDb) {
        const float clampedDb = std::clamp(strengthDb, 0.0f, 18.0f);
        mAdaptiveDenoiserMinimumGain.store(std::pow(10.0f, -clampedDb / 20.0f),
                                           std::memory_order_relaxed);
    }

    void setDspBypassed(bool bypassed) {
        mDspBypassed.store(bypassed, std::memory_order_relaxed);
    }

    bool isRunning() const {
        return mRunning.load(std::memory_order_acquire) &&
               !mDisconnected.load(std::memory_order_relaxed);
    }

    float inputPeak() const {
        return mPeak.load(std::memory_order_relaxed);
    }

    std::string streamInfo() const {
        if (mDisconnected.load(std::memory_order_relaxed)) {
            return "Audio route disconnected; reconnect or choose an available device";
        }
        if (!mOutputStream || !isRunning()) {
            return mLastError.empty() ? "Audio stream stopped" : mLastError;
        }
        std::ostringstream info;
        info << mOutputStream->getSampleRate() << " Hz · "
             << mOutputStream->getChannelCount() << " output channel(s) · "
             << (mOutputStream->getPerformanceMode() == oboe::PerformanceMode::LowLatency
                     ? "low-latency mode requested"
                     : "low-latency mode unavailable");
        info << "\nAndroid stream estimates: input " << latencyText(mInputStream)
             << ", output " << latencyText(mOutputStream)
             << "\nThese estimates may omit Bluetooth earbud delay.";
        return info.str();
    }

    oboe::DataCallbackResult onBothStreamsReady(const void *inputData,
                                                 int numInputFrames,
                                                 void *outputData,
                                                 int numOutputFrames) override {
        auto *output = static_cast<float *>(outputData);
        const auto *input = static_cast<const float *>(inputData);
        if (output == nullptr || numOutputFrames <= 0) {
            return oboe::DataCallbackResult::Continue;
        }

        const int outputChannels = std::max(1, getOutputStream()->getChannelCount());
        const int inputChannels = getInputStream() == nullptr
                ? 1
                : std::max(1, getInputStream()->getChannelCount());
        const float targetLevel = mMonitorLevel.load(std::memory_order_relaxed);
        const bool compressionEnabled = mSpeechCompressionEnabled.load(std::memory_order_relaxed);
        const float compressionRatio = mSpeechCompressionRatio.load(std::memory_order_relaxed);
        const bool noiseGateEnabled = mNoiseGateEnabled.load(std::memory_order_relaxed);
        const float noiseGateThreshold = mNoiseGateOpenThreshold.load(std::memory_order_relaxed);
        const bool adaptiveDenoiserEnabled = mAdaptiveDenoiserEnabled.load(std::memory_order_relaxed);
        const float denoiserMinimumGain = mAdaptiveDenoiserMinimumGain.load(std::memory_order_relaxed);
        const bool dspBypassed = mDspBypassed.load(std::memory_order_relaxed);
        updateEqCoefficients();
        float peak = 0.0f;

        for (int frame = 0; frame < numOutputFrames; ++frame) {
            float monoSample = 0.0f;
            if (input != nullptr && frame < numInputFrames) {
                for (int channel = 0; channel < inputChannels; ++channel) {
                    monoSample += input[frame * inputChannels + channel];
                }
                monoSample /= static_cast<float>(inputChannels);
            }
            peak = std::max(peak, std::abs(monoSample));

            std::array<float, kSpeechBandCount> bands{};
            splitSpeechBands(monoSample, bands);
            for (std::size_t band = 0; band < kSpeechBandCount; ++band) {
                bands[band] = reduceBandNoise(bands[band], band, adaptiveDenoiserEnabled,
                                             denoiserMinimumGain);
                bands[band] = applyNoiseGate(bands[band], band, noiseGateEnabled,
                                             noiseGateThreshold);
            }
            if (compressionEnabled) {
                for (std::size_t band = 0; band < kSpeechBandCount; ++band) {
                    bands[band] = compressBand(bands[band], band, compressionRatio);
                }
            }
            const float speechProcessed = bands[0] + bands[1] + bands[2] + bands[3];

            mAppliedMonitorLevel = mMonitorLevelSmoothingCoefficient * mAppliedMonitorLevel +
                    (1.0f - mMonitorLevelSmoothingCoefficient) * targetLevel;

            for (int channel = 0; channel < outputChannels; ++channel) {
                const std::size_t ear = static_cast<std::size_t>(channel) % kEarCount;
                float sample = speechProcessed;
                float processedSample = sample;
                for (auto &filter : mEqFilters[ear]) processedSample = filter.process(processedSample);
                if (dspBypassed) sample = monoSample;
                else sample = processedSample;
                sample = limitPeak(sample * mAppliedMonitorLevel, ear);
                output[frame * outputChannels + channel] = sample;
            }
        }

        mPeak.store(peak, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorBeforeClose(oboe::AudioStream *, oboe::Result) override {}

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result error) override {
        mRunning.store(false, std::memory_order_release);
        if (error == oboe::Result::ErrorDisconnected) {
            mDisconnected.store(true, std::memory_order_relaxed);
        }
    }

private:
    struct Biquad {
        float b0 = 1.0f;
        float b1 = 0.0f;
        float b2 = 0.0f;
        float a1 = 0.0f;
        float a2 = 0.0f;
        float state1 = 0.0f;
        float state2 = 0.0f;

        void configurePeaking(float sampleRate, float centerHz, float q, float gainDb) {
            constexpr float kPi = 3.14159265358979323846f;
            const float amplitude = std::pow(10.0f, gainDb / 40.0f);
            const float omega = 2.0f * kPi * centerHz / sampleRate;
            const float cosine = std::cos(omega);
            const float alpha = std::sin(omega) / (2.0f * q);
            const float a0 = 1.0f + alpha / amplitude;
            b0 = (1.0f + alpha * amplitude) / a0;
            b1 = (-2.0f * cosine) / a0;
            b2 = (1.0f - alpha * amplitude) / a0;
            a1 = (-2.0f * cosine) / a0;
            a2 = (1.0f - alpha / amplitude) / a0;
        }

        void configureButterworth(float sampleRate, float cutoffHz, bool lowPass) {
            constexpr float kPi = 3.14159265358979323846f;
            constexpr float kButterworthQ = 0.70710678f;
            const float omega = 2.0f * kPi * cutoffHz / sampleRate;
            const float cosine = std::cos(omega);
            const float alpha = std::sin(omega) / (2.0f * kButterworthQ);
            const float a0 = 1.0f + alpha;
            if (lowPass) {
                b0 = ((1.0f - cosine) * 0.5f) / a0;
                b1 = (1.0f - cosine) / a0;
                b2 = b0;
            } else {
                b0 = ((1.0f + cosine) * 0.5f) / a0;
                b1 = -(1.0f + cosine) / a0;
                b2 = b0;
            }
            a1 = (-2.0f * cosine) / a0;
            a2 = (1.0f - alpha) / a0;
        }

        float process(float input) {
            const float output = b0 * input + state1;
            state1 = b1 * input - a1 * output + state2;
            state2 = b2 * input - a2 * output;
            return output;
        }
    };

    struct LinkwitzRileyCrossover {
        Biquad lowFirst;
        Biquad lowSecond;
        Biquad highFirst;
        Biquad highSecond;

        void configure(float sampleRate, float cutoffHz) {
            lowFirst.configureButterworth(sampleRate, cutoffHz, true);
            lowSecond.configureButterworth(sampleRate, cutoffHz, true);
            highFirst.configureButterworth(sampleRate, cutoffHz, false);
            highSecond.configureButterworth(sampleRate, cutoffHz, false);
        }

        float low(float sample) {
            return lowSecond.process(lowFirst.process(sample));
        }

        float high(float sample) {
            return highSecond.process(highFirst.process(sample));
        }
    };

    void resetDsp(int32_t sampleRate) {
        mSampleRate = static_cast<float>(std::max(sampleRate, 1));
        for (auto &earFilters : mEqFilters) earFilters = {};
        for (auto &earGains : mAppliedEqGainsDb) earGains.fill(0.0f);
        mCrossovers = {};
        for (std::size_t index = 0; index < mCrossovers.size(); ++index) {
            mCrossovers[index].configure(mSampleRate, kSpeechCrossoversHz[index]);
        }
        mCompressorEnvelopes.fill(0.0f);
        mNoiseGateEnvelopes.fill(0.0f);
        mDenoiserEnvelopes.fill(0.0f);
        mDenoiserNoiseFloors.fill(0.0001f);
        mDenoiserGains.fill(1.0f);
        mNoiseGateGains.fill(1.0f);
        mNoiseGateOpen.fill(true);
        mLimiterGains.fill(1.0f);
        mAppliedMonitorLevel = mMonitorLevel.load(std::memory_order_relaxed);
        mMonitorLevelSmoothingCoefficient = envelopeCoefficient(0.010f);
        mCompressorAttackCoefficient = envelopeCoefficient(0.008f);
        mCompressorReleaseCoefficient = envelopeCoefficient(0.120f);
        mNoiseGateDetectorAttackCoefficient = envelopeCoefficient(0.003f);
        mNoiseGateDetectorReleaseCoefficient = envelopeCoefficient(0.060f);
        mNoiseGateOpenCoefficient = envelopeCoefficient(0.004f);
        mNoiseGateCloseCoefficient = envelopeCoefficient(0.100f);
        mDenoiserEnvelopeAttackCoefficient = envelopeCoefficient(0.003f);
        mDenoiserEnvelopeReleaseCoefficient = envelopeCoefficient(0.030f);
        mDenoiserFloorDownCoefficient = envelopeCoefficient(0.250f);
        mDenoiserFloorUpCoefficient = envelopeCoefficient(8.0f);
        mDenoiserOpenCoefficient = envelopeCoefficient(0.005f);
        mDenoiserCloseCoefficient = envelopeCoefficient(0.080f);
        mLimiterReleaseCoefficient = envelopeCoefficient(0.050f);
    }

    float envelopeCoefficient(float timeSeconds) const {
        return std::exp(-1.0f / (timeSeconds * mSampleRate));
    }

    void splitSpeechBands(float sample, std::array<float, kSpeechBandCount> &bands) {
        float remaining = sample;
        bands[0] = mCrossovers[0].low(remaining);
        remaining = mCrossovers[0].high(remaining);
        bands[1] = mCrossovers[1].low(remaining);
        remaining = mCrossovers[1].high(remaining);
        bands[2] = mCrossovers[2].low(remaining);
        bands[3] = mCrossovers[2].high(remaining);
    }

    float compressBand(float sample, std::size_t band, float ratio) {
        const float magnitude = std::abs(sample);
        float &envelope = mCompressorEnvelopes[band];
        const float coefficient = magnitude > envelope
                ? mCompressorAttackCoefficient
                : mCompressorReleaseCoefficient;
        envelope = coefficient * envelope + (1.0f - coefficient) * magnitude;
        if (envelope <= 0.000001f) return sample;

        const float envelopeDb = 20.0f * std::log10(envelope);
        if (envelopeDb <= kSpeechThresholdDb) return sample;
        // Downward compression above the band threshold; there is no makeup gain.
        const float compressedDb = kSpeechThresholdDb +
                (envelopeDb - kSpeechThresholdDb) / std::max(ratio, 1.0f);
        const float gain = std::pow(10.0f, (compressedDb - envelopeDb) / 20.0f);
        return sample * gain;
    }

    float applyNoiseGate(float sample, std::size_t band, bool enabled, float openThreshold) {
        float &envelope = mNoiseGateEnvelopes[band];
        const float magnitude = std::abs(sample);
        const float detectorCoefficient = magnitude > envelope
                ? mNoiseGateDetectorAttackCoefficient
                : mNoiseGateDetectorReleaseCoefficient;
        envelope = detectorCoefficient * envelope + (1.0f - detectorCoefficient) * magnitude;

        bool &isOpen = mNoiseGateOpen[band];
        const float closeThreshold = openThreshold * 0.50118723f; // 6 dB hysteresis
        if (!enabled || envelope >= openThreshold) {
            isOpen = true;
        } else if (envelope <= closeThreshold) {
            isOpen = false;
        }

        const float targetGain = (!enabled || isOpen) ? 1.0f : kNoiseGateMinimumGain;
        float &gain = mNoiseGateGains[band];
        const float smoothingCoefficient = targetGain > gain
                ? mNoiseGateOpenCoefficient
                : mNoiseGateCloseCoefficient;
        gain = smoothingCoefficient * gain + (1.0f - smoothingCoefficient) * targetGain;
        return sample * gain;
    }

    float reduceBandNoise(float sample, std::size_t band, bool enabled, float minimumGain) {
        const float magnitude = std::abs(sample);
        float &envelope = mDenoiserEnvelopes[band];
        const float envelopeCoefficient = magnitude > envelope
                ? mDenoiserEnvelopeAttackCoefficient
                : mDenoiserEnvelopeReleaseCoefficient;
        envelope = envelopeCoefficient * envelope + (1.0f - envelopeCoefficient) * magnitude;

        // Let the estimated background level fall promptly and rise slowly so speech does not
        // quickly become part of the noise estimate.
        float &noiseFloor = mDenoiserNoiseFloors[band];
        const float floorCoefficient = envelope < noiseFloor
                ? mDenoiserFloorDownCoefficient
                : mDenoiserFloorUpCoefficient;
        noiseFloor = floorCoefficient * noiseFloor + (1.0f - floorCoefficient) * envelope;

        const float ratio = envelope / std::max(noiseFloor, 0.000001f);
        const float position = std::clamp(
                (ratio - kDenoiserQuietSnrRatio) /
                        (kDenoiserOpenSnrRatio - kDenoiserQuietSnrRatio),
                0.0f, 1.0f);
        const float shapedPosition = position * position * (3.0f - 2.0f * position);
        const float targetGain = enabled
                ? minimumGain + (1.0f - minimumGain) * shapedPosition
                : 1.0f;
        float &gain = mDenoiserGains[band];
        const float smoothingCoefficient = targetGain > gain
                ? mDenoiserOpenCoefficient
                : mDenoiserCloseCoefficient;
        gain = smoothingCoefficient * gain + (1.0f - smoothingCoefficient) * targetGain;
        return sample * gain;
    }

    void updateEqCoefficients() {
        for (std::size_t ear = 0; ear < kEarCount; ++ear) {
            for (std::size_t band = 0; band < kEqBandCount; ++band) {
                const float gainDb = mEqTargetGainsDb[ear][band].load(std::memory_order_relaxed);
                if (std::abs(gainDb - mAppliedEqGainsDb[ear][band]) >= 0.01f) {
                    mEqFilters[ear][band].configurePeaking(
                            mSampleRate, kEqCenterFrequenciesHz[band], 1.0f, gainDb);
                    mAppliedEqGainsDb[ear][band] = gainDb;
                }
            }
        }
    }

    float limitPeak(float sample, std::size_t ear) {
        const float magnitude = std::abs(sample);
        const float requestedGain = magnitude > kLimiterCeiling
                ? kLimiterCeiling / magnitude
                : 1.0f;
        float &limiterGain = mLimiterGains[ear];
        if (requestedGain < limiterGain) {
            limiterGain = requestedGain;
        } else {
            limiterGain = mLimiterReleaseCoefficient * limiterGain +
                    (1.0f - mLimiterReleaseCoefficient) * requestedGain;
        }
        return std::clamp(sample * limiterGain, -kLimiterCeiling, kLimiterCeiling);
    }

    static std::string latencyText(const std::shared_ptr<oboe::AudioStream> &stream) {
        if (!stream) return "unavailable";
        const auto latency = stream->calculateLatencyMillis();
        if (!latency) return "unavailable";
        std::ostringstream value;
        value << std::fixed << std::setprecision(1) << latency.value() << " ms";
        return value.str();
    }

    static void closeOne(std::shared_ptr<oboe::AudioStream> &stream) {
        if (!stream) return;
        stream->stop();
        stream->close();
        stream.reset();
    }

    void closeStreams() {
        closeOne(mOutputStream);
        closeOne(mInputStream);
    }

    std::shared_ptr<oboe::AudioStream> mInputStream;
    std::shared_ptr<oboe::AudioStream> mOutputStream;
    std::atomic<bool> mRunning{false};
    std::atomic<bool> mDisconnected{false};
    std::atomic<float> mMonitorLevel{0.15f};
    std::atomic<float> mSpeechCompressionRatio{2.0f};
    std::atomic<bool> mSpeechCompressionEnabled{true};
    std::atomic<float> mNoiseGateOpenThreshold{0.00199526f}; // -54 dBFS
    std::atomic<bool> mNoiseGateEnabled{true};
    std::atomic<bool> mAdaptiveDenoiserEnabled{false};
    std::atomic<float> mAdaptiveDenoiserMinimumGain{0.35481339f}; // 9 dB maximum reduction
    std::atomic<bool> mDspBypassed{false};
    std::atomic<float> mPeak{0.0f};
    std::array<std::array<std::atomic<float>, kEqBandCount>, kEarCount> mEqTargetGainsDb{};
    std::array<std::array<float, kEqBandCount>, kEarCount> mAppliedEqGainsDb{};
    std::array<std::array<Biquad, kEqBandCount>, kEarCount> mEqFilters{};
    std::array<LinkwitzRileyCrossover, 3> mCrossovers{};
    std::array<float, kSpeechBandCount> mCompressorEnvelopes{};
    std::array<float, kSpeechBandCount> mNoiseGateEnvelopes{};
    std::array<float, kSpeechBandCount> mDenoiserEnvelopes{};
    std::array<float, kSpeechBandCount> mDenoiserNoiseFloors{};
    std::array<float, kSpeechBandCount> mDenoiserGains{1.0f, 1.0f, 1.0f, 1.0f};
    std::array<float, kSpeechBandCount> mNoiseGateGains{1.0f, 1.0f, 1.0f, 1.0f};
    std::array<bool, kSpeechBandCount> mNoiseGateOpen{true, true, true, true};
    std::array<float, kEarCount> mLimiterGains{1.0f, 1.0f};
    float mSampleRate = 48000.0f;
    float mAppliedMonitorLevel = 0.15f;
    float mMonitorLevelSmoothingCoefficient = 0.0f;
    float mCompressorAttackCoefficient = 0.0f;
    float mCompressorReleaseCoefficient = 0.0f;
    float mNoiseGateDetectorAttackCoefficient = 0.0f;
    float mNoiseGateDetectorReleaseCoefficient = 0.0f;
    float mNoiseGateOpenCoefficient = 0.0f;
    float mNoiseGateCloseCoefficient = 0.0f;
    float mDenoiserEnvelopeAttackCoefficient = 0.0f;
    float mDenoiserEnvelopeReleaseCoefficient = 0.0f;
    float mDenoiserFloorDownCoefficient = 0.0f;
    float mDenoiserFloorUpCoefficient = 0.0f;
    float mDenoiserOpenCoefficient = 0.0f;
    float mDenoiserCloseCoefficient = 0.0f;
    float mLimiterReleaseCoefficient = 0.0f;
    std::string mLastError;
};

EchoMonitor gMonitor;

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeStart(JNIEnv *, jobject, jint inputDeviceId,
                                                        jint outputDeviceId) {
    return gMonitor.startMonitor(inputDeviceId, outputDeviceId) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeStop(JNIEnv *, jobject) {
    gMonitor.stopMonitor();
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeSetMonitorLevel(JNIEnv *, jobject, jfloat level) {
    gMonitor.setLevel(level);
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeSetEqualizerBand(JNIEnv *, jobject, jint ear,
                                                                   jint index, jfloat gainDb) {
    gMonitor.setEqualizerBand(ear, index, gainDb);
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeSetSpeechCompressionEnabled(JNIEnv *, jobject,
                                                                              jboolean enabled) {
    gMonitor.setSpeechCompressionEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeSetSpeechCompressionRatio(JNIEnv *, jobject,
                                                                            jfloat ratio) {
    gMonitor.setSpeechCompressionRatio(ratio);
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeSetNoiseGateEnabled(JNIEnv *, jobject,
                                                                      jboolean enabled) {
    gMonitor.setNoiseGateEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeSetNoiseGateThresholdDb(JNIEnv *, jobject,
                                                                          jfloat thresholdDb) {
    gMonitor.setNoiseGateThresholdDb(thresholdDb);
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeSetAdaptiveDenoiserEnabled(JNIEnv *, jobject,
                                                                             jboolean enabled) {
    gMonitor.setAdaptiveDenoiserEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeSetAdaptiveDenoiserStrengthDb(JNIEnv *, jobject,
                                                                               jfloat strengthDb) {
    gMonitor.setAdaptiveDenoiserStrengthDb(strengthDb);
}

extern "C" JNIEXPORT void JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeSetDspBypassed(JNIEnv *, jobject,
                                                                 jboolean bypassed) {
    gMonitor.setDspBypassed(bypassed == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeIsRunning(JNIEnv *, jobject) {
    return gMonitor.isRunning() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeGetStreamInfo(JNIEnv *env, jobject) {
    const std::string info = gMonitor.streamInfo();
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jfloat JNICALL
Java_org_echo_livemonitor_NativeAudioEngine_nativeGetInputPeak(JNIEnv *, jobject) {
    return gMonitor.inputPeak();
}
