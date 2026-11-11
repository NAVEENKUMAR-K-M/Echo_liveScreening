package org.echo.livemonitor

internal object NativeAudioEngine {
    init {
        System.loadLibrary("echoaudio")
    }

    external fun nativeStart(inputDeviceId: Int, outputDeviceId: Int): Boolean
    external fun nativeStop()
    external fun nativeSetMonitorLevel(level: Float)
    external fun nativeSetEqualizerBand(ear: Int, index: Int, gainDb: Float)
    external fun nativeSetSpeechCompressionEnabled(enabled: Boolean)
    external fun nativeSetSpeechCompressionRatio(ratio: Float)
    external fun nativeSetNoiseGateEnabled(enabled: Boolean)
    external fun nativeSetNoiseGateThresholdDb(thresholdDb: Float)
    external fun nativeSetAdaptiveDenoiserEnabled(enabled: Boolean)
    external fun nativeSetAdaptiveDenoiserStrengthDb(strengthDb: Float)
    external fun nativeSetDspBypassed(bypassed: Boolean)
    external fun nativeIsRunning(): Boolean
    external fun nativeGetStreamInfo(): String
    external fun nativeGetInputPeak(): Float
}
