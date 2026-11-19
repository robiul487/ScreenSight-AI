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
 * Real-time microphone audio capture using Android AudioRecord (Section 7.1 & Priority 6).
 * Captures 16-bit PCM little-endian mono audio at 16 kHz, attaches hardware AcousticEchoCanceler,
 * NoiseSuppressor, and AutomaticGainControl when available, uses echo-safe dual-threshold VAD
 * so loudspeaker playback does not falsely self-interrupt Gemini, and releases native resources
 * deterministically inside the capture coroutine's `finally` block.
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
                var consecutiveVoiceChunks = 0
                var consecutiveBargeInChunks = 0

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
                                consecutiveVoiceChunks = 0
                                consecutiveBargeInChunks = 0
                                continue
                            }

                            val chunk = if (bytesRead == buffer.size) {
                                buffer.copyOf()
                            } else {
                                buffer.copyOfRange(0, bytesRead)
                            }

                            val rmsNormalized = calculateNormalizedRms(chunk)
                            _micLevel.value = rmsNormalized

                            val aiSpeakingNow = isAiCurrentlySpeaking()
                            val activeThreshold = if (aiSpeakingNow) {
                                BARGE_IN_WHILE_AI_SPEAKING_THRESHOLD
                            } else {
                                VOICE_ACTIVITY_THRESHOLD
                            }

                            if (rmsNormalized > activeThreshold) {
                                consecutiveVoiceChunks++
                                if (aiSpeakingNow) {
                                    consecutiveBargeInChunks++
                                } else {
                                    consecutiveBargeInChunks = 0
                                }

                                val requiredChunks = if (aiSpeakingNow) 4 else 2
                                val chunksCount = if (aiSpeakingNow) consecutiveBargeInChunks else consecutiveVoiceChunks
                                if (chunksCount >= requiredChunks) {
                                    if (!_isUserSpeaking.value) {
                                        _isUserSpeaking.value = true
                                        onUserVoiceDetected(aiSpeakingNow)
                                    }
                                }
                            } else {
                                consecutiveVoiceChunks = 0
                                consecutiveBargeInChunks = 0
                                _isUserSpeaking.value = false
                            }

                            onAudioChunk(chunk, sampleRateHz)
                        } else if (bytesRead < 0) {
                            break
                        }
                    }
                } finally {
                    _micLevel.value = 0f
                    _isUserSpeaking.value = false
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
        private const val VOICE_ACTIVITY_THRESHOLD = 0.055f
        // Higher threshold while loudspeaker is playing AI voice to prevent speaker bleed from self-interrupting
        private const val BARGE_IN_WHILE_AI_SPEAKING_THRESHOLD = 0.36f

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
