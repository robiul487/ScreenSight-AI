package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.example.domain.model.ScreenSightError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Audio duplex routing profile so the same capture engine cleanly supports both:
 * - [ASSISTANT_SPEAKER]: Hands-free loudspeaker assistant (stronger residual echo gate while AI speaks)
 * - [VOICE_CALL_DUPLEX]: Future full-duplex Calling Agent mode (preserves caller + system voice
 *   sensitivity and lower double-talk interruption threshold while keeping hardware AEC enabled)
 */
enum class AudioRoutingProfile {
    ASSISTANT_SPEAKER,
    VOICE_CALL_DUPLEX
}

/**
 * Pure, deterministic acoustic echo & double-talk gate used on top of Android's hardware
 * [AcousticEchoCanceler].
 *
 * Design guarantees:
 * 1. When AI is NOT speaking (and post-speech reverb tail has finished): 100% open full-fidelity
 *    passthrough — hears quiet user speech, external voices, caller audio, and system prompts.
 * 2. When a wired/Bluetooth/USB headset is connected: 100% open full-duplex passthrough + responsive
 *    human barge-in.
 * 3. When on phone loudspeaker AND AI is speaking (or within reverb tail): suppresses residual
 *    speaker bleed from reaching Gemini Live's server-side VAD (stopping self-interruption and
 *    self-replies), while still allowing genuine sustained human/caller double-talk barge-in when
 *    [bargeInEnabled] is true.
 */
class AcousticEchoGate(
    private val profile: AudioRoutingProfile = AudioRoutingProfile.ASSISTANT_SPEAKER
) {
    enum class Decision {
        PASS_THROUGH,
        SUPPRESS_SELF_ECHO,
        TRIGGER_BARGE_IN
    }

    private var guardActiveChunks: Int = 0
    private var consecutiveVoiceChunks: Int = 0
    private var consecutiveBargeInChunks: Int = 0

    fun reset() {
        guardActiveChunks = 0
        consecutiveVoiceChunks = 0
        consecutiveBargeInChunks = 0
    }

    fun evaluate(
        micRms: Float,
        aiOutputRms: Float,
        isEchoGuardActive: Boolean,
        isHeadsetConnected: Boolean,
        bargeInEnabled: Boolean
    ): Decision {
        // 1. AI is silent & reverb tail cleared -> 100% open passthrough (never deafen normal/caller audio)
        if (!isEchoGuardActive) {
            guardActiveChunks = 0
            consecutiveBargeInChunks = 0
            if (micRms > AudioCaptureManager.VOICE_ACTIVITY_THRESHOLD) {
                consecutiveVoiceChunks++
            } else {
                consecutiveVoiceChunks = 0
            }
            return Decision.PASS_THROUGH
        }

        // 2. External headset / Bluetooth / USB audio -> acoustically isolated from loudspeaker
        if (isHeadsetConnected) {
            guardActiveChunks = 0
            if (bargeInEnabled && micRms > HEADSET_BARGE_IN_THRESHOLD) {
                consecutiveBargeInChunks++
                if (consecutiveBargeInChunks >= HEADSET_BARGE_IN_CHUNKS) {
                    consecutiveBargeInChunks = 0
                    return Decision.TRIGGER_BARGE_IN
                }
            } else {
                consecutiveBargeInChunks = 0
            }
            return Decision.PASS_THROUGH
        }

        // 3. Phone loudspeaker while AI is speaking or within post-speech reverberation tail
        guardActiveChunks++
        consecutiveVoiceChunks = 0

        // If user disabled barge-in, strictly block loudspeaker echo from ever hitting server VAD
        if (!bargeInEnabled) {
            consecutiveBargeInChunks = 0
            return Decision.SUPPRESS_SELF_ECHO
        }

        // Ignore the initial speaker onset transient (~200ms) while hardware AEC converges
        if (guardActiveChunks <= ONSET_CONVERGENCE_CHUNKS) {
            consecutiveBargeInChunks = 0
            return Decision.SUPPRESS_SELF_ECHO
        }

        val adaptiveThreshold = computeAdaptiveBargeInThreshold(aiOutputRms)
        val requiredChunks = when (profile) {
            AudioRoutingProfile.ASSISTANT_SPEAKER -> SPEAKER_BARGE_IN_CHUNKS
            AudioRoutingProfile.VOICE_CALL_DUPLEX -> CALL_DUPLEX_BARGE_IN_CHUNKS
        }

        return if (micRms >= adaptiveThreshold) {
            consecutiveBargeInChunks++
            if (consecutiveBargeInChunks >= requiredChunks) {
                consecutiveBargeInChunks = 0
                guardActiveChunks = 0
                Decision.TRIGGER_BARGE_IN
            } else {
                // Candidate double-talk chunk: hold back until sustained speech confirms it isn't a click
                Decision.SUPPRESS_SELF_ECHO
            }
        } else {
            consecutiveBargeInChunks = 0
            Decision.SUPPRESS_SELF_ECHO
        }
    }

    fun isCandidateDoubleTalkChunk(): Boolean = consecutiveBargeInChunks > 0

    fun isNormalVoiceActive(): Boolean = consecutiveVoiceChunks >= NORMAL_VOICE_CHUNKS

    fun computeAdaptiveBargeInThreshold(aiOutputRms: Float): Float {
        val clampedAi = aiOutputRms.coerceIn(0f, 1f)
        return when (profile) {
            AudioRoutingProfile.ASSISTANT_SPEAKER ->
                (SPEAKER_BARGE_IN_BASE_THRESHOLD + clampedAi * SPEAKER_ECHO_REFERENCE_SCALE)
                    .coerceAtMost(SPEAKER_BARGE_IN_MAX_THRESHOLD)

            AudioRoutingProfile.VOICE_CALL_DUPLEX ->
                (CALL_DUPLEX_BARGE_IN_BASE_THRESHOLD + clampedAi * CALL_DUPLEX_ECHO_REFERENCE_SCALE)
                    .coerceAtMost(CALL_DUPLEX_BARGE_IN_MAX_THRESHOLD)
        }
    }

    companion object {
        private const val NORMAL_VOICE_CHUNKS = 2
        private const val ONSET_CONVERGENCE_CHUNKS = 2

        private const val HEADSET_BARGE_IN_THRESHOLD = 0.10f
        private const val HEADSET_BARGE_IN_CHUNKS = 2

        // Loudspeaker adaptive double-talk threshold (above typical residual AEC bleed)
        private const val SPEAKER_BARGE_IN_BASE_THRESHOLD = 0.44f
        private const val SPEAKER_ECHO_REFERENCE_SCALE = 0.42f
        private const val SPEAKER_BARGE_IN_MAX_THRESHOLD = 0.80f
        private const val SPEAKER_BARGE_IN_CHUNKS = 4

        // Calling-agent full-duplex profile (more responsive to remote caller speech)
        private const val CALL_DUPLEX_BARGE_IN_BASE_THRESHOLD = 0.28f
        private const val CALL_DUPLEX_ECHO_REFERENCE_SCALE = 0.30f
        private const val CALL_DUPLEX_BARGE_IN_MAX_THRESHOLD = 0.64f
        private const val CALL_DUPLEX_BARGE_IN_CHUNKS = 3
    }
}

/**
 * Real-time microphone audio capture using Android AudioRecord (Section 7.1 & Full-Duplex AEC).
 * Captures 16-bit PCM little-endian mono audio at 16 kHz, attaches hardware AcousticEchoCanceler,
 * NoiseSuppressor, and AutomaticGainControl, and pairs hardware AEC with [AcousticEchoGate] +
 * double-talk pre-roll buffering so the AI never self-interrupts or answers its own voice while
 * keeping 100% sensitivity for human speech and future calling-agent audio.
 */
class AudioCaptureManager(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    @Volatile
    private var activeRecorder: AudioRecord? = null

    private val isCapturing = AtomicBoolean(false)
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isUserSpeaking = MutableStateFlow(false)
    val isUserSpeaking: StateFlow<Boolean> = _isUserSpeaking.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    @Volatile
    var routingProfile: AudioRoutingProfile = AudioRoutingProfile.ASSISTANT_SPEAKER

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(
        sampleRateHz: Int = DEFAULT_INPUT_SAMPLE_RATE_HZ,
        isAiCurrentlySpeaking: () -> Boolean = { false },
        aiOutputRmsProvider: () -> Float = { 0f },
        isHeadsetConnected: () -> Boolean = { false },
        isBargeInEnabled: () -> Boolean = { true },
        onAudioChunk: (ByteArray, Int) -> Unit,
        onUserVoiceDetected: (Boolean) -> Unit,
        onError: (ScreenSightError) -> Unit
    ) {
        if (!hasRecordAudioPermission()) {
            onError(ScreenSightError.MicrophonePermissionDenied)
            return
        }
        if (isCapturing.get()) return

        stopRecording()

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onError(ScreenSightError.AudioRecordFailure)
            return
        }

        // Chunk size ~100ms of 16-bit mono audio at 16kHz = 3200 bytes
        val chunkSizeBytes = (sampleRateHz * 2) / 10
        val recordBufferSize = maxOf(minBufferSize * 2, chunkSizeBytes * 2)

        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                onError(ScreenSightError.AudioRecordFailure)
                return
            }

            var echoCanceler: AcousticEchoCanceler? = null
            var noiseSuppressor: NoiseSuppressor? = null
            var gainControl: AutomaticGainControl? = null

            runCatching {
                val sessionId = recorder.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = true
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                        enabled = true
                    }
                }
                if (AutomaticGainControl.isAvailable()) {
                    gainControl = AutomaticGainControl.create(sessionId)?.apply {
                        enabled = true
                    }
                }
            }

            recorder.startRecording()
            activeRecorder = recorder
            isCapturing.set(true)

            captureJob = scope.launch {
                val buffer = ByteArray(chunkSizeBytes)
                val echoGate = AcousticEchoGate(routingProfile)
                val bargeInPreRoll = ArrayDeque<ByteArray>(PRE_ROLL_MAX_CHUNKS + 1)

                try {
                    while (isActive && isCapturing.get()) {
                        val bytesRead = try {
                            recorder.read(buffer, 0, buffer.size)
                        } catch (_: Exception) {
                            -1
                        }

                        if (bytesRead > 0) {
                            if (_isMuted.value) {
                                _micLevel.value = 0f
                                _isUserSpeaking.value = false
                                echoGate.reset()
                                bargeInPreRoll.clear()
                                continue
                            }

                            val chunk = if (bytesRead == buffer.size) {
                                buffer.copyOf()
                            } else {
                                buffer.copyOfRange(0, bytesRead)
                            }

                            val rmsNormalized = calculateNormalizedRms(chunk)
                            val echoGuardActive = isAiCurrentlySpeaking()
                            val headsetActive = isHeadsetConnected()
                            val bargeInAllowed = isBargeInEnabled()
                            val aiRms = if (echoGuardActive) aiOutputRmsProvider() else 0f

                            when (
                                echoGate.evaluate(
                                    micRms = rmsNormalized,
                                    aiOutputRms = aiRms,
                                    isEchoGuardActive = echoGuardActive,
                                    isHeadsetConnected = headsetActive,
                                    bargeInEnabled = bargeInAllowed
                                )
                            ) {
                                AcousticEchoGate.Decision.SUPPRESS_SELF_ECHO -> {
                                    // Keep candidate double-talk chunks in a small pre-roll so real barge-in
                                    // never loses its opening syllables, but DO NOT stream loudspeaker echo
                                    // to Gemini Live's server-side VAD.
                                    if (echoGate.isCandidateDoubleTalkChunk()) {
                                        if (bargeInPreRoll.size >= PRE_ROLL_MAX_CHUNKS) {
                                            bargeInPreRoll.removeFirst()
                                        }
                                        bargeInPreRoll.addLast(chunk)
                                    } else {
                                        bargeInPreRoll.clear()
                                    }
                                    _micLevel.value = 0f
                                    _isUserSpeaking.value = false
                                }

                                AcousticEchoGate.Decision.TRIGGER_BARGE_IN -> {
                                    _micLevel.value = rmsNormalized
                                    _isUserSpeaking.value = true
                                    // Interrupt AI playback FIRST so loudspeaker stops immediately
                                    onUserVoiceDetected(true)
                                    // Flush preserved opening syllables + current chunk to Gemini
                                    while (bargeInPreRoll.isNotEmpty()) {
                                        onAudioChunk(bargeInPreRoll.removeFirst(), sampleRateHz)
                                    }
                                    onAudioChunk(chunk, sampleRateHz)
                                }

                                AcousticEchoGate.Decision.PASS_THROUGH -> {
                                    bargeInPreRoll.clear()
                                    _micLevel.value = rmsNormalized
                                    if (echoGate.isNormalVoiceActive()) {
                                        if (!_isUserSpeaking.value) {
                                            _isUserSpeaking.value = true
                                            onUserVoiceDetected(false)
                                        }
                                    } else if (rmsNormalized <= VOICE_ACTIVITY_THRESHOLD) {
                                        _isUserSpeaking.value = false
                                    }
                                    onAudioChunk(chunk, sampleRateHz)
                                }
                            }
                        } else if (bytesRead < 0) {
                            break
                        }
                    }
                } finally {
                    _micLevel.value = 0f
                    _isUserSpeaking.value = false
                    bargeInPreRoll.clear()
                    runCatching { echoCanceler?.release() }
                    runCatching { noiseSuppressor?.release() }
                    runCatching { gainControl?.release() }
                    runCatching {
                        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            recorder.stop()
                        }
                    }
                    runCatching { recorder.release() }
                    if (activeRecorder === recorder) {
                        activeRecorder = null
                    }
                }
            }
        } catch (_: Exception) {
            stopRecording()
            onError(ScreenSightError.AudioRecordFailure)
        }
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted) {
            _micLevel.value = 0f
            _isUserSpeaking.value = false
        }
    }

    fun toggleMute(): Boolean {
        val next = !_isMuted.value
        setMuted(next)
        return next
    }

    @Synchronized
    fun stopRecording() {
        isCapturing.set(false)
        _micLevel.value = 0f
        _isUserSpeaking.value = false

        // Unblock any in-flight blocking read() on the IO thread; final release happens inside captureJob's finally block
        val recorder = activeRecorder
        if (recorder != null) {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
        }
        captureJob?.cancel()
        captureJob = null
    }

    companion object {
        const val DEFAULT_INPUT_SAMPLE_RATE_HZ = 16000
        const val VOICE_ACTIVITY_THRESHOLD = 0.055f
        private const val PRE_ROLL_MAX_CHUNKS = 3

        /**
         * Computes normalized RMS amplitude [0.0f .. 1.0f] from 16-bit little-endian PCM bytes.
         */
        fun calculateNormalizedRms(pcm16LeBytes: ByteArray): Float {
            if (pcm16LeBytes.size < 2) return 0f
            val shortCount = pcm16LeBytes.size / 2
            val shortBuffer = ByteBuffer.wrap(pcm16LeBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()

            var sumSquares = 0.0
            for (i in 0 until shortCount) {
                val sample = shortBuffer.get(i).toDouble() / Short.MAX_VALUE
                sumSquares += sample * sample
            }
            val rms = sqrt(sumSquares / shortCount).toFloat()
            return (rms * 3.2f).coerceIn(0f, 1f)
        }
    }
}
