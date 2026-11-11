package org.echo.livemonitor

import android.Manifest
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var routeView: TextView
    private lateinit var levelView: TextView
    private lateinit var monitorButton: Button
    private lateinit var stopButton: Button
    private lateinit var monitorLevel: SeekBar
    private lateinit var inputDeviceSpinner: Spinner
    private lateinit var outputDeviceSpinner: Spinner
    private lateinit var refreshDevicesButton: Button
    private lateinit var inputDeviceAdapter: ArrayAdapter<String>
    private lateinit var outputDeviceAdapter: ArrayAdapter<String>
    private val gainSeekBars = Array(2) { arrayOfNulls<SeekBar>(6) }
    private var speechCompressionToggle: CheckBox? = null
    private var compressionRatioSlider: SeekBar? = null

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
    private var monitorLevelValue = INITIAL_MONITOR_LEVEL
    private val earGainsDb = Array(2) { FloatArray(EQ_CENTERS_HZ.size) }
    private var speechCompressionEnabled = true
    private var speechCompressionRatio = INITIAL_COMPRESSION_RATIO
    private var noiseGateEnabled = true
    private var noiseGateThresholdDb = INITIAL_NOISE_GATE_THRESHOLD_DB
    private var noiseGateToggle: CheckBox? = null
    private var noiseGateThresholdSlider: SeekBar? = null
    private var adaptiveDenoiserEnabled = false
    private var adaptiveDenoiserStrengthDb = INITIAL_DENOISER_STRENGTH_DB
    private var adaptiveDenoiserToggle: CheckBox? = null
    private var adaptiveDenoiserStrengthSlider: SeekBar? = null
    private var clarityProcessed = true
    private var clarityEnvironment = "Quiet"
    private var clarityModeToggle: CheckBox? = null
    private var clarityEnvironmentSpinner: Spinner? = null
    private var clarityCorrectInput: EditText? = null
    private var clarityTotalInput: EditText? = null
    private var clarityResultView: TextView? = null
    private var audiogramThresholdInputs = Array(2) { arrayOfNulls<EditText>(EQ_CENTERS_HZ.size) }
    private var realEarVerified = false
    private var realEarNotes = ""
    private var availableInputDevices: List<AudioDeviceInfo> = emptyList()
    private var availableOutputDevices: List<AudioDeviceInfo> = emptyList()
    private var selectedInputDeviceId: Int? = null
    private var selectedOutputDeviceId: Int? = null
    private var updatingDeviceMenus = false
    private var askedForPermission = false
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            handler.post { refreshDeviceSelections() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handler.post { refreshDeviceSelections() }
        }
    }

    private val refreshMonitorStatus = object : Runnable {
        override fun run() {
            if (!::statusView.isInitialized) return
            if (!NativeAudioEngine.nativeIsRunning()) {
                val message = NativeAudioEngine.nativeGetStreamInfo()
                stopMonitor(if (message == "Audio stream stopped") "Audio monitor stopped" else message)
                return
            }

            val peak = NativeAudioEngine.nativeGetInputPeak().coerceIn(0f, 1f)
            statusView.text = "Monitoring · relative mic level ${(peak * 100).toInt()}%\n" +
                    NativeAudioEngine.nativeGetStreamInfo()
            handler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreSettings()
        applyDspSettings()
        buildInterface()
        refreshDeviceSelections()
        updateRouteLabel()
    }

    override fun onStart() {
        super.onStart()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        refreshDeviceSelections()
    }

    private fun buildInterface() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val scroll = ScrollView(this).apply {
            addView(page)
        }

        val title = TextView(this).apply {
            text = "ECHO Live Monitor"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        page.addView(title, fullWidth())

        val description = TextView(this).apply {
            text = "Selected microphone → on-phone audio loop → selected output device"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        }
        page.addView(description, fullWidth())

        val notice = TextView(this).apply {
            text = "Prototype only. This is a live audio monitor, not a fitted hearing aid. " +
                    "Start with low phone and earbud volume. Digital limits are not calibrated to dB SPL."
            textSize = 14f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFFEAEAEA.toInt())
        }
        page.addView(notice, fullWidth())

        addAudioDeviceSelectors(page)

        routeView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(18), 0, dp(8))
        }
        page.addView(routeView, fullWidth())

        statusView = TextView(this).apply {
            text = "Stopped"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(16))
        }
        page.addView(statusView, fullWidth())

        levelView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(4))
        }
        page.addView(levelView, fullWidth())

        monitorLevel = SeekBar(this).apply {
            max = MAX_MONITOR_LEVEL_PERCENT
            progress = (monitorLevelValue * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    monitorLevelValue = progress / 100f
                    levelView.text = "Monitor level: $progress% digital trim (0–50% range)"
                    NativeAudioEngine.nativeSetMonitorLevel(monitorLevelValue)
                    preferences.edit().putFloat(KEY_MONITOR_LEVEL, monitorLevelValue).apply()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        page.addView(monitorLevel, fullWidth())
        levelView.text = "Monitor level: ${(monitorLevelValue * 100).toInt()}% digital trim (0–50% range)"

        addNoiseGateControls(page)
        addAdaptiveNoiseReductionControls(page)
        addSpeechClarityControls(page)
        addAudiogramProfileControls(page)
        addSpeechCompressionControls(page)

        val resetButton = Button(this).apply {
            text = "Reset to cautious defaults"
            setOnClickListener { resetToCautiousDefaults() }
        }
        page.addView(resetButton, fullWidth())

        monitorButton = Button(this).apply {
            text = "Start monitor"
            setOnClickListener { requestPermissionAndStart() }
        }
        page.addView(monitorButton, fullWidth())

        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { stopMonitor("Stopped") }
        }
        page.addView(stopButton, fullWidth())

        val measurementNote = TextView(this).apply {
            text = "End-to-end acoustic latency is not measured by this screen. We will measure " +
                    "the selected microphone-to-output path separately."
            textSize = 14f
            setPadding(0, dp(18), 0, 0)
        }
        page.addView(measurementNote, fullWidth())

        setContentView(scroll)
    }

    private fun addAudioDeviceSelectors(page: LinearLayout) {
        val heading = TextView(this).apply {
            text = "Audio devices"
            textSize = 18f
            setPadding(0, dp(18), 0, dp(4))
        }
        page.addView(heading, fullWidth())

        val inputLabel = TextView(this).apply {
            text = "Microphone input"
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        page.addView(inputLabel, fullWidth())

        inputDeviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf("Choose microphone…")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        inputDeviceSpinner = Spinner(this).apply {
            adapter = inputDeviceAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (updatingDeviceMenus) return
                    selectedInputDeviceId = availableInputDevices.getOrNull(position - 1)?.id
                    updateRouteLabel()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        page.addView(inputDeviceSpinner, fullWidth())

        val outputLabel = TextView(this).apply {
            text = "Playback output"
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        page.addView(outputLabel, fullWidth())

        outputDeviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf("Choose output device…")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        outputDeviceSpinner = Spinner(this).apply {
            adapter = outputDeviceAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (updatingDeviceMenus) return
                    selectedOutputDeviceId = availableOutputDevices.getOrNull(position - 1)?.id
                    updateRouteLabel()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        page.addView(outputDeviceSpinner, fullWidth())

        val deviceNote = TextView(this).apply {
            text = "Bluetooth earbuds are preferred when connected. Phone-speaker output can feed " +
                    "back into the microphone, so keep volume low. Device choices may change when " +
                    "Bluetooth or wired accessories connect or disconnect."
            textSize = 13f
            setPadding(0, dp(2), 0, dp(4))
        }
        page.addView(deviceNote, fullWidth())

        refreshDevicesButton = Button(this).apply {
            text = "Refresh audio devices"
            setOnClickListener { refreshDeviceSelections() }
        }
        page.addView(refreshDevicesButton, fullWidth())
    }

    private fun addNoiseGateControls(page: LinearLayout) {
        val heading = TextView(this).apply {
            text = "Microphone noise suppression"
            textSize = 18f
            setPadding(0, dp(20), 0, dp(4))
        }
        page.addView(heading, fullWidth())

        val toggle = CheckBox(this).apply {
            text = "Reduce steady mic hiss during quiet periods"
            isChecked = noiseGateEnabled
            setOnCheckedChangeListener { _, enabled ->
                noiseGateEnabled = enabled
                NativeAudioEngine.nativeSetNoiseGateEnabled(enabled)
                preferences.edit().putBoolean(KEY_NOISE_GATE_ENABLED, enabled).apply()
            }
        }
        noiseGateToggle = toggle
        page.addView(toggle, fullWidth())

        val thresholdLabel = TextView(this).apply {
            text = formatNoiseGateThreshold(noiseGateThresholdDb)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(2))
        }
        page.addView(thresholdLabel, fullWidth())

        val thresholdSlider = SeekBar(this).apply {
            max = NOISE_GATE_THRESHOLD_STEPS
            progress = noiseThresholdToSliderProgress(noiseGateThresholdDb)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    noiseGateThresholdDb = MIN_NOISE_GATE_THRESHOLD_DB.toFloat() + progress
                    thresholdLabel.text = formatNoiseGateThreshold(noiseGateThresholdDb)
                    NativeAudioEngine.nativeSetNoiseGateThresholdDb(noiseGateThresholdDb)
                    preferences.edit().putFloat(KEY_NOISE_GATE_THRESHOLD, noiseGateThresholdDb).apply()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        noiseGateThresholdSlider = thresholdSlider
        page.addView(thresholdSlider, fullWidth())

        val note = TextView(this).apply {
            text = "Four-band gate with a 6 dB open/close hysteresis. Closed bands are reduced " +
                    "by up to 24 dB; detector attack/release are 3/60 ms and gain transitions " +
                    "are 4/100 ms. Raise the threshold to reduce more hiss between words; lower " +
                    "it if quiet speech is cut. This cannot remove noise that overlaps speech."
            textSize = 13f
            setPadding(0, dp(2), 0, dp(10))
        }
        page.addView(note, fullWidth())
    }

    private fun addAdaptiveNoiseReductionControls(page: LinearLayout) {
        val heading = TextView(this).apply {
            text = "Adaptive noise reduction"
            textSize = 18f
            setPadding(0, dp(20), 0, dp(4))
        }
        page.addView(heading, fullWidth())

        val toggle = CheckBox(this).apply {
            text = "Reduce steady background hiss during speech"
            isChecked = adaptiveDenoiserEnabled
            setOnCheckedChangeListener { _, enabled ->
                adaptiveDenoiserEnabled = enabled
                NativeAudioEngine.nativeSetAdaptiveDenoiserEnabled(enabled)
                preferences.edit().putBoolean(KEY_DENOISER_ENABLED, enabled).apply()
            }
        }
        adaptiveDenoiserToggle = toggle
        page.addView(toggle, fullWidth())

        val strengthLabel = TextView(this).apply {
            text = formatDenoiserStrength(adaptiveDenoiserStrengthDb)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(2))
        }
        page.addView(strengthLabel, fullWidth())

        val strengthSlider = SeekBar(this).apply {
            max = DENOISER_STRENGTH_MAX_DB.toInt()
            progress = adaptiveDenoiserStrengthDb.toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    adaptiveDenoiserStrengthDb = progress.toFloat()
                    strengthLabel.text = formatDenoiserStrength(adaptiveDenoiserStrengthDb)
                    NativeAudioEngine.nativeSetAdaptiveDenoiserStrengthDb(adaptiveDenoiserStrengthDb)
                    preferences.edit().putFloat(KEY_DENOISER_STRENGTH, adaptiveDenoiserStrengthDb).apply()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        adaptiveDenoiserStrengthSlider = strengthSlider
        page.addView(strengthSlider, fullWidth())

        val note = TextView(this).apply {
            text = "Four-band adaptive expander with a per-band noise-floor estimate (fast 250 ms " +
                    "downward tracking, slow 8 s upward tracking). The selected strength is the " +
                    "maximum attenuation near the estimated floor; signals about 15 dB above it " +
                    "return to full level. It uses sample-by-sample filtering without frame buffering. " +
                    "Try 6–9 dB first: stronger reduction can soften quiet consonants. This is a " +
                    "prototype denoiser, not a neural or clinical noise-reduction system."
            textSize = 13f
            setPadding(0, dp(2), 0, dp(10))
        }
        page.addView(note, fullWidth())
    }

    private fun addSpeechClarityControls(page: LinearLayout) {
        val heading = TextView(this).apply {
            text = "Speech clarity A/B log"
            textSize = 18f
            setPadding(0, dp(20), 0, dp(4))
        }
        page.addView(heading, fullWidth())

        val explanation = TextView(this).apply {
            text = "Compare the same short, unseen word list with processed audio and baseline " +
                    "audio under the same conditions. A helper can read the words while you enter " +
                    "how many you understood. This is a manual prototype comparison, not a validated " +
                    "speech-in-noise or hearing test."
            textSize = 14f
            setPadding(0, 0, 0, dp(6))
        }
        page.addView(explanation, fullWidth())

        val modeToggle = CheckBox(this).apply {
            text = "Use processed audio (off = baseline)"
            isChecked = clarityProcessed
            setOnCheckedChangeListener { _, processed ->
                clarityProcessed = processed
                NativeAudioEngine.nativeSetDspBypassed(!processed)
                preferences.edit().putBoolean(KEY_CLARITY_PROCESSED, processed).apply()
            }
        }
        clarityModeToggle = modeToggle
        page.addView(modeToggle, fullWidth())

        val environmentLabel = TextView(this).apply {
            text = "Listening environment"
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }
        page.addView(environmentLabel, fullWidth())
        val environments = listOf("Quiet", "Background noise")
        val environmentAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, environments).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val environmentSpinner = Spinner(this).apply {
            adapter = environmentAdapter
            setSelection(environments.indexOf(clarityEnvironment).takeIf { it >= 0 } ?: 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    clarityEnvironment = environments[position]
                    preferences.edit().putString(KEY_CLARITY_ENVIRONMENT, clarityEnvironment).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        clarityEnvironmentSpinner = environmentSpinner
        page.addView(environmentSpinner, fullWidth())

        val scoreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val correctInput = EditText(this).apply {
            hint = "Correct"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        clarityCorrectInput = correctInput
        scoreRow.addView(correctInput, LinearLayout.LayoutParams(0, dp(56), 1f))
        val totalInput = EditText(this).apply {
            hint = "Words in list"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("10")
        }
        clarityTotalInput = totalInput
        scoreRow.addView(totalInput, LinearLayout.LayoutParams(0, dp(56), 1f))
        page.addView(scoreRow, fullWidth())

        val saveTrialButton = Button(this).apply {
            text = "Save trial score"
            setOnClickListener { saveClarityTrial() }
        }
        page.addView(saveTrialButton, fullWidth())

        val resultView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        clarityResultView = resultView
        page.addView(resultView, fullWidth())
        refreshClarityTrialLog()
    }

    private fun refreshDeviceSelections() {
        if (!::inputDeviceSpinner.isInitialized || !::outputDeviceSpinner.isInitialized) return

        val oldInputId = selectedInputDeviceId
        val oldOutputId = selectedOutputDeviceId
        val wasMonitoring = ::stopButton.isInitialized && stopButton.isEnabled
        availableInputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .toList()
            .sortedBy { deviceLabel(it) }
        availableOutputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .toList()
            .sortedBy { deviceLabel(it) }

        selectedInputDeviceId = oldInputId?.takeIf { id -> availableInputDevices.any { it.id == id } }
            ?: availableInputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.id
            ?: availableInputDevices.firstOrNull()?.id
        selectedOutputDeviceId = oldOutputId?.takeIf { id -> availableOutputDevices.any { it.id == id } }
            ?: availableOutputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.id

        updatingDeviceMenus = true
        inputDeviceAdapter.clear()
        inputDeviceAdapter.add("Choose microphone…")
        inputDeviceAdapter.addAll(availableInputDevices.map(::deviceLabel))
        inputDeviceAdapter.notifyDataSetChanged()
        outputDeviceAdapter.clear()
        outputDeviceAdapter.add("Choose output device…")
        outputDeviceAdapter.addAll(availableOutputDevices.map(::deviceLabel))
        outputDeviceAdapter.notifyDataSetChanged()
        inputDeviceSpinner.setSelection(
            availableInputDevices.indexOfFirst { it.id == selectedInputDeviceId }.let { if (it < 0) 0 else it + 1 }
        )
        outputDeviceSpinner.setSelection(
            availableOutputDevices.indexOfFirst { it.id == selectedOutputDeviceId }.let { if (it < 0) 0 else it + 1 }
        )
        setDeviceControlsEnabled(!wasMonitoring)
        updatingDeviceMenus = false

        if (wasMonitoring &&
            (availableInputDevices.none { it.id == oldInputId } ||
             availableOutputDevices.none { it.id == oldOutputId })) {
            stopMonitor("Selected audio device disconnected. Choose devices and start again.")
        } else {
            updateRouteLabel()
        }
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio device"
        return "$name · ${deviceTypeName(device.type)} · id ${device.id}"
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth microphone (SCO)"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset microphone"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset microphone"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio input"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio output"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI output"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line output"
        AudioDeviceInfo.TYPE_DOCK -> "Dock output"
        else -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            "Bluetooth LE headset"
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
            "Bluetooth LE audio output"
        } else {
            "Audio device"
        }
    }

    private fun setDeviceControlsEnabled(enabled: Boolean) {
        if (!::inputDeviceSpinner.isInitialized) return
        inputDeviceSpinner.isEnabled = enabled && availableInputDevices.isNotEmpty()
        outputDeviceSpinner.isEnabled = enabled && availableOutputDevices.isNotEmpty()
        refreshDevicesButton.isEnabled = enabled
    }

    private fun addAudiogramProfileControls(page: LinearLayout) {
        val heading = TextView(this).apply {
            text = "Audiogram and fitting profile"
            textSize = 18f
            setPadding(0, dp(24), 0, dp(4))
        }
        page.addView(heading, fullWidth())

        val explanation = TextView(this).apply {
            text = "Record measured hearing thresholds in dB HL, then record the separate per-ear " +
                    "fitting gain offsets. Thresholds are stored for reference only: this prototype " +
                    "does not calculate a prescription from them. Gain controls are limited to ±6 dB " +
                    "and are not calibrated to sound pressure at the eardrum."
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }
        page.addView(explanation, fullWidth())

        listOf("Left ear", "Right ear").forEachIndexed { earIndex, earName ->
            val earHeading = TextView(this).apply {
                text = earName
                textSize = 16f
                setPadding(0, dp(10), 0, dp(2))
            }
            page.addView(earHeading, fullWidth())

            val thresholdHeading = TextView(this).apply {
                text = "Audiogram thresholds (dB HL)"
                textSize = 14f
                setPadding(0, dp(4), 0, dp(2))
            }
            page.addView(thresholdHeading, fullWidth())

            EQ_CENTERS_HZ.forEachIndexed { index, centerHz ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val frequency = TextView(this).apply {
                    text = formatFrequency(centerHz)
                    textSize = 14f
                }
                row.addView(frequency, LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT))
                val thresholdInput = EditText(this).apply {
                    hint = "dB HL"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                            InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setSingleLine(true)
                    setText(preferences.getString(audiogramThresholdKey(earIndex, index), ""))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val raw = s?.toString()?.trim().orEmpty()
                            val value = raw.toFloatOrNull()
                            if (raw.isEmpty() || (value != null && value in MIN_AUDIOMETRY_DB_HL..MAX_AUDIOMETRY_DB_HL)) {
                                preferences.edit().putString(audiogramThresholdKey(earIndex, index), raw).apply()
                            }
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                }
                audiogramThresholdInputs[earIndex][index] = thresholdInput
                row.addView(thresholdInput, LinearLayout.LayoutParams(0, dp(52), 1f))
                page.addView(row, fullWidth())
            }

            val gainHeading = TextView(this).apply {
                text = "Fitting gain offsets (dB)"
                textSize = 14f
                setPadding(0, dp(6), 0, dp(2))
            }
            page.addView(gainHeading, fullWidth())

            EQ_CENTERS_HZ.forEachIndexed { index, centerHz ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val bandLabel = TextView(this).apply {
                    text = formatFrequency(centerHz)
                    textSize = 14f
                }
                row.addView(bandLabel, LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT))

                val gainLabel = TextView(this).apply {
                    text = formatGain(earGainsDb[earIndex][index])
                    textSize = 13f
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                val seekBar = SeekBar(this).apply {
                    max = EQ_SLIDER_STEPS
                    progress = gainToSliderProgress(earGainsDb[earIndex][index])
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val gainDb = (progress - EQ_SLIDER_CENTER) / 2f
                            earGainsDb[earIndex][index] = gainDb
                            gainLabel.text = formatGain(gainDb)
                            NativeAudioEngine.nativeSetEqualizerBand(earIndex, index, gainDb)
                            preferences.edit()
                                .putFloat(profileGainKey(earIndex, index), gainDb)
                                .apply()
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                }
                gainSeekBars[earIndex][index] = seekBar
                row.addView(seekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(gainLabel, LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT))
                page.addView(row, fullWidth())
            }
        }

        val verificationHeading = TextView(this).apply {
            text = "Fitting verification record"
            textSize = 16f
            setPadding(0, dp(14), 0, dp(2))
        }
        page.addView(verificationHeading, fullWidth())

        val verificationToggle = CheckBox(this).apply {
            text = "External real-ear measurement reviewed"
            isChecked = realEarVerified
            setOnCheckedChangeListener { _, checked ->
                realEarVerified = checked
                preferences.edit().putBoolean(KEY_REAL_EAR_VERIFIED, checked).apply()
            }
        }
        page.addView(verificationToggle, fullWidth())

        val verificationNotes = EditText(this).apply {
            hint = "Optional record: date, equipment, input level, target and measured output (dB SPL)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            setText(realEarNotes)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    realEarNotes = s?.toString().orEmpty()
                    preferences.edit().putString(KEY_REAL_EAR_NOTES, realEarNotes).apply()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        page.addView(verificationNotes, fullWidth())

        val verificationNote = TextView(this).apply {
            text = "The phone and Bluetooth earbuds cannot perform real-ear measurement here. " +
                    "Mark this only when an external calibrated measurement has been completed; " +
                    "the record does not certify the fit."
            textSize = 13f
            setPadding(0, dp(2), 0, dp(8))
        }
        page.addView(verificationNote, fullWidth())

        val dspNote = TextView(this).apply {
            text = "Six peaking filters per ear (Q=1.0): 250, 500, 1k, 2k, 4k and 6k Hz. " +
                    "Use only fitting gain recommendations. Stored thresholds are reference data " +
                    "and do not set these gains."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        page.addView(dspNote, fullWidth())
    }

    private fun addSpeechCompressionControls(page: LinearLayout) {
        val heading = TextView(this).apply {
            text = "Speech compression"
            textSize = 18f
            setPadding(0, dp(20), 0, dp(4))
        }
        page.addView(heading, fullWidth())

        val compressionToggle = CheckBox(this).apply {
            text = "Enable 4-band speech compression"
            isChecked = speechCompressionEnabled
            setOnCheckedChangeListener { _, enabled ->
                speechCompressionEnabled = enabled
                NativeAudioEngine.nativeSetSpeechCompressionEnabled(enabled)
                preferences.edit().putBoolean(KEY_COMPRESSION_ENABLED, enabled).apply()
            }
        }
        speechCompressionToggle = compressionToggle
        page.addView(compressionToggle, fullWidth())

        val ratioLabel = TextView(this).apply {
            text = formatCompressionRatio(speechCompressionRatio)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(2))
        }
        page.addView(ratioLabel, fullWidth())

        val ratioSlider = SeekBar(this).apply {
            max = COMPRESSION_RATIO_STEPS
            progress = ratioToSliderProgress(speechCompressionRatio)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speechCompressionRatio = 1.0f + progress / 10f
                    ratioLabel.text = formatCompressionRatio(speechCompressionRatio)
                    NativeAudioEngine.nativeSetSpeechCompressionRatio(speechCompressionRatio)
                    preferences.edit().putFloat(KEY_COMPRESSION_RATIO, speechCompressionRatio).apply()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        compressionRatioSlider = ratioSlider
        page.addView(ratioSlider, fullWidth())

        val compressionNote = TextView(this).apply {
            text = "Four Linkwitz–Riley bands split at 500, 1,500 and 3,500 Hz. " +
                    "Downward compression starts above −36 dBFS; attack 8 ms, release 120 ms, " +
                    "no makeup gain or look-ahead. This changes loud-band dynamics; it does not " +
                    "automatically make quiet speech audible."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        }
        page.addView(compressionNote, fullWidth())
    }

    private fun saveClarityTrial() {
        val correct = clarityCorrectInput?.text?.toString()?.toIntOrNull()
        val total = clarityTotalInput?.text?.toString()?.toIntOrNull()
        val result = clarityResultView ?: return
        if (total == null || total !in 1..MAX_CLARITY_TRIAL_WORDS || correct == null || correct !in 0..total) {
            result.text = "Enter a correct count from 0 to the list size (1–$MAX_CLARITY_TRIAL_WORDS words)."
            return
        }

        val mode = if (clarityProcessed) "Processed" else "Baseline"
        val entry = listOf(
            System.currentTimeMillis().toString(),
            clarityEnvironment,
            mode,
            correct.toString(),
            total.toString()
        ).joinToString("|")
        val previous = preferences.getString(KEY_CLARITY_TRIALS, "").orEmpty()
            .lineSequence().filter { it.isNotBlank() }.toList()
        preferences.edit()
            .putString(KEY_CLARITY_TRIALS, (listOf(entry) + previous).take(MAX_CLARITY_TRIALS).joinToString("\n"))
            .apply()
        clarityCorrectInput?.text?.clear()
        refreshClarityTrialLog()
    }

    private fun refreshClarityTrialLog() {
        val result = clarityResultView ?: return
        val trials = preferences.getString(KEY_CLARITY_TRIALS, "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val fields = line.split('|')
                if (fields.size != 5) return@mapNotNull null
                val timestamp = fields[0].toLongOrNull() ?: return@mapNotNull null
                val correct = fields[3].toIntOrNull() ?: return@mapNotNull null
                val total = fields[4].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                if (correct !in 0..total) return@mapNotNull null
                "${fields[1]} · ${fields[2]} · $correct/$total (${correct * 100 / total}%) · " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
            }
            .take(MAX_CLARITY_TRIALS)
            .toList()
        result.text = if (trials.isEmpty()) {
            "No trial scores saved yet. Baseline bypass retains monitor trim and the safety limiter."
        } else {
            "Saved trials (newest first)\n" + trials.joinToString("\n")
        }
    }

    private fun restoreSettings() {
        monitorLevelValue = preferences.getFloat(KEY_MONITOR_LEVEL, INITIAL_MONITOR_LEVEL)
            .coerceIn(0f, MAX_MONITOR_LEVEL_PERCENT / 100f)
        for (ear in 0..1) {
            for (band in EQ_CENTERS_HZ.indices) {
                earGainsDb[ear][band] = preferences.getFloat(profileGainKey(ear, band), 0f)
                    .coerceIn(-6f, 6f)
            }
        }
        speechCompressionEnabled = preferences.getBoolean(KEY_COMPRESSION_ENABLED, true)
        speechCompressionRatio = preferences.getFloat(KEY_COMPRESSION_RATIO, INITIAL_COMPRESSION_RATIO)
            .coerceIn(1f, 3f)
        noiseGateEnabled = preferences.getBoolean(KEY_NOISE_GATE_ENABLED, true)
        noiseGateThresholdDb = preferences.getFloat(
            KEY_NOISE_GATE_THRESHOLD,
            INITIAL_NOISE_GATE_THRESHOLD_DB
        ).coerceIn(MIN_NOISE_GATE_THRESHOLD_DB.toFloat(), MAX_NOISE_GATE_THRESHOLD_DB.toFloat())
        adaptiveDenoiserEnabled = preferences.getBoolean(KEY_DENOISER_ENABLED, false)
        adaptiveDenoiserStrengthDb = preferences.getFloat(KEY_DENOISER_STRENGTH, INITIAL_DENOISER_STRENGTH_DB)
            .coerceIn(0f, DENOISER_STRENGTH_MAX_DB)
        clarityProcessed = preferences.getBoolean(KEY_CLARITY_PROCESSED, true)
        clarityEnvironment = preferences.getString(KEY_CLARITY_ENVIRONMENT, "Quiet")
            ?.takeIf { it == "Quiet" || it == "Background noise" } ?: "Quiet"
        realEarVerified = preferences.getBoolean(KEY_REAL_EAR_VERIFIED, false)
        realEarNotes = preferences.getString(KEY_REAL_EAR_NOTES, "").orEmpty()
    }

    private fun applyDspSettings() {
        NativeAudioEngine.nativeSetMonitorLevel(monitorLevelValue)
        NativeAudioEngine.nativeSetSpeechCompressionEnabled(speechCompressionEnabled)
        NativeAudioEngine.nativeSetSpeechCompressionRatio(speechCompressionRatio)
        NativeAudioEngine.nativeSetNoiseGateEnabled(noiseGateEnabled)
        NativeAudioEngine.nativeSetNoiseGateThresholdDb(noiseGateThresholdDb)
        NativeAudioEngine.nativeSetAdaptiveDenoiserEnabled(adaptiveDenoiserEnabled)
        NativeAudioEngine.nativeSetAdaptiveDenoiserStrengthDb(adaptiveDenoiserStrengthDb)
        NativeAudioEngine.nativeSetDspBypassed(!clarityProcessed)
        for (ear in 0..1) {
            for (band in EQ_CENTERS_HZ.indices) {
                NativeAudioEngine.nativeSetEqualizerBand(ear, band, earGainsDb[ear][band])
            }
        }

    }

    private fun resetToCautiousDefaults() {
        monitorLevelValue = INITIAL_MONITOR_LEVEL
        speechCompressionEnabled = true
        speechCompressionRatio = INITIAL_COMPRESSION_RATIO
        noiseGateEnabled = true
        noiseGateThresholdDb = INITIAL_NOISE_GATE_THRESHOLD_DB
        adaptiveDenoiserEnabled = false
        adaptiveDenoiserStrengthDb = INITIAL_DENOISER_STRENGTH_DB
        clarityProcessed = true
        earGainsDb.forEach { it.fill(0f) }
        monitorLevel.progress = (INITIAL_MONITOR_LEVEL * 100).toInt()
        for (ear in 0..1) {
            for (band in EQ_CENTERS_HZ.indices) {
                gainSeekBars[ear][band]?.progress = EQ_SLIDER_CENTER
            }
        }
        speechCompressionToggle?.isChecked = true
        compressionRatioSlider?.progress = ratioToSliderProgress(INITIAL_COMPRESSION_RATIO)
        noiseGateToggle?.isChecked = true
        noiseGateThresholdSlider?.progress = noiseThresholdToSliderProgress(INITIAL_NOISE_GATE_THRESHOLD_DB)
        adaptiveDenoiserToggle?.isChecked = false
        adaptiveDenoiserStrengthSlider?.progress = INITIAL_DENOISER_STRENGTH_DB.toInt()
        clarityModeToggle?.isChecked = true
        applyDspSettings()
        saveSettings()
        levelView.text = "Monitor level: ${(monitorLevelValue * 100).toInt()}% digital trim (0–50% range)"
    }

    private fun saveSettings() {
        val editor = preferences.edit()
            .putFloat(KEY_MONITOR_LEVEL, monitorLevelValue)
            .putBoolean(KEY_COMPRESSION_ENABLED, speechCompressionEnabled)
            .putFloat(KEY_COMPRESSION_RATIO, speechCompressionRatio)
            .putBoolean(KEY_NOISE_GATE_ENABLED, noiseGateEnabled)
            .putFloat(KEY_NOISE_GATE_THRESHOLD, noiseGateThresholdDb)
            .putBoolean(KEY_DENOISER_ENABLED, adaptiveDenoiserEnabled)
            .putFloat(KEY_DENOISER_STRENGTH, adaptiveDenoiserStrengthDb)
            .putBoolean(KEY_CLARITY_PROCESSED, clarityProcessed)
        for (ear in 0..1) {
            for (band in EQ_CENTERS_HZ.indices) {
                editor.putFloat(profileGainKey(ear, band), earGainsDb[ear][band])
            }
        }
        editor.apply()
    }

    private fun formatGain(gainDb: Float): String = String.format(Locale.US, "%+.1f dB", gainDb)

    private fun formatCompressionRatio(ratio: Float): String =
        String.format(Locale.US, "Compression ratio: %.1f:1", ratio)

    private fun formatNoiseGateThreshold(thresholdDb: Float): String =
        String.format(Locale.US, "Noise gate threshold: %.0f dBFS", thresholdDb)

    private fun formatDenoiserStrength(strengthDb: Float): String =
        String.format(Locale.US, "Maximum adaptive attenuation: %.0f dB", strengthDb)

    private fun gainToSliderProgress(gainDb: Float): Int =
        ((gainDb * 2f) + EQ_SLIDER_CENTER).toInt().coerceIn(0, EQ_SLIDER_STEPS)

    private fun ratioToSliderProgress(ratio: Float): Int =
        ((ratio - 1f) * 10f).toInt().coerceIn(0, COMPRESSION_RATIO_STEPS)

    private fun noiseThresholdToSliderProgress(thresholdDb: Float): Int =
        (thresholdDb - MIN_NOISE_GATE_THRESHOLD_DB.toFloat()).toInt()
            .coerceIn(0, NOISE_GATE_THRESHOLD_STEPS)

    private fun profileGainKey(ear: Int, band: Int): String = "gain_${ear}_$band"

    private fun audiogramThresholdKey(ear: Int, band: Int): String = "audiogram_${ear}_$band"

    private fun formatFrequency(frequencyHz: Int): String =
        if (frequencyHz >= 1000) "${frequencyHz / 1000} kHz" else "$frequencyHz Hz"

    private fun requestPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMonitor()
        } else {
            askedForPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }
    }

    @Deprecated("Use the Activity Result API in a future UI cleanup")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE || !askedForPermission) return
        askedForPermission = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startMonitor()
        } else {
            statusView.text = "Microphone permission is required for live monitoring."
        }
    }

    private fun startMonitor() {
        refreshDeviceSelections()
        updateRouteLabel()
        val selectedOutput = selectedOutputDevice()
        if (selectedOutput == null) {
            statusView.text = "Choose a playback output device before starting the monitor."
            return
        }
        val selectedInput = selectedInputDevice()
        if (selectedInput == null) {
            statusView.text = "Choose an available microphone before starting the monitor."
            return
        }

        if (!requestAudioFocus()) {
            releaseAudioFocus()
            statusView.text = "Could not get audio focus. Pause other audio and try again."
            return
        }

        NativeAudioEngine.nativeSetMonitorLevel(monitorLevelValue)
        if (!NativeAudioEngine.nativeStart(selectedInput.id, selectedOutput.id)) {
            releaseAudioFocus()
            statusView.text = NativeAudioEngine.nativeGetStreamInfo()
            return
        }

        monitorButton.isEnabled = false
        stopButton.isEnabled = true
        setDeviceControlsEnabled(false)
        routeView.text = "Input: ${deviceLabel(selectedInput)}\nOutput: ${deviceLabel(selectedOutput)}"
        statusView.text = "Starting audio monitor…"
        handler.removeCallbacks(refreshMonitorStatus)
        handler.post(refreshMonitorStatus)
    }

    private fun stopMonitor(message: String) {
        handler.removeCallbacks(refreshMonitorStatus)
        NativeAudioEngine.nativeStop()
        releaseAudioFocus()
        monitorButton.isEnabled = true
        stopButton.isEnabled = false
        setDeviceControlsEnabled(true)
        statusView.text = message
        updateRouteLabel()
    }

    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    runOnUiThread {
                        if (::stopButton.isInitialized && stopButton.isEnabled) {
                            stopMonitor("Stopped because audio focus was lost")
                        }
                    }
                }
            }
            .build()
        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
    }

    private fun selectedInputDevice(): AudioDeviceInfo? =
        availableInputDevices.firstOrNull { it.id == selectedInputDeviceId }

    private fun selectedOutputDevice(): AudioDeviceInfo? =
        availableOutputDevices.firstOrNull { it.id == selectedOutputDeviceId }

    private fun updateRouteLabel() {
        if (!::routeView.isInitialized) return
        val input = selectedInputDevice()?.let(::deviceLabel) ?: "Choose a microphone"
        val output = selectedOutputDevice()?.let(::deviceLabel) ?: "Choose an output device"
        routeView.text = "Input: $input\nOutput: $output"
    }

    override fun onStop() {
        if (::stopButton.isInitialized && stopButton.isEnabled) {
            stopMonitor("Stopped when the app left the foreground")
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        super.onStop()
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_MICROPHONE = 17
        private const val STATUS_REFRESH_MS = 250L
        private const val PREFERENCES_NAME = "echo_profile"
        private const val KEY_MONITOR_LEVEL = "monitor_level"
        private const val KEY_COMPRESSION_ENABLED = "speech_compression_enabled"
        private const val KEY_COMPRESSION_RATIO = "speech_compression_ratio"
        private const val KEY_NOISE_GATE_ENABLED = "noise_gate_enabled"
        private const val KEY_NOISE_GATE_THRESHOLD = "noise_gate_threshold_dbfs"
        private const val KEY_DENOISER_ENABLED = "adaptive_denoiser_enabled"
        private const val KEY_DENOISER_STRENGTH = "adaptive_denoiser_strength_db"
        private const val KEY_CLARITY_PROCESSED = "clarity_processed_audio"
        private const val KEY_CLARITY_ENVIRONMENT = "clarity_environment"
        private const val KEY_CLARITY_TRIALS = "clarity_trials"
        private const val KEY_REAL_EAR_VERIFIED = "real_ear_verified"
        private const val KEY_REAL_EAR_NOTES = "real_ear_notes"
        private const val INITIAL_MONITOR_LEVEL = 0.15f
        private const val MAX_MONITOR_LEVEL_PERCENT = 50
        private const val INITIAL_COMPRESSION_RATIO = 2.0f
        private const val INITIAL_NOISE_GATE_THRESHOLD_DB = -54f
        private const val MIN_NOISE_GATE_THRESHOLD_DB = -72
        private const val MAX_NOISE_GATE_THRESHOLD_DB = -36
        private const val INITIAL_DENOISER_STRENGTH_DB = 9f
        private const val DENOISER_STRENGTH_MAX_DB = 18f
        private const val MIN_AUDIOMETRY_DB_HL = -10f
        private const val MAX_AUDIOMETRY_DB_HL = 120f
        private const val MAX_CLARITY_TRIAL_WORDS = 200
        private const val MAX_CLARITY_TRIALS = 40
        private const val EQ_SLIDER_CENTER = 12
        private const val EQ_SLIDER_STEPS = 24
        private const val COMPRESSION_RATIO_STEPS = 20
        private const val NOISE_GATE_THRESHOLD_STEPS =
            MAX_NOISE_GATE_THRESHOLD_DB - MIN_NOISE_GATE_THRESHOLD_DB
        private val EQ_CENTERS_HZ = intArrayOf(250, 500, 1000, 2000, 4000, 6000)
    }
}
