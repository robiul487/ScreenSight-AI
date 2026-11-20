package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.example.domain.model.ScreenSightError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-time AI voice audio output using Android AudioTrack routed through the full-duplex
 * communication audio path (USAGE_VOICE_COMMUNICATION + MODE_IN_COMMUNICATION).
 *
 * Why USAGE_VOICE_COMMUNICATION + MODE_IN_COMMUNICATION matters:
 * - Android's hardware AcousticEchoCanceler (AEC) on AudioRecord(VOICE_COMMUNICATION) ONLY
 *   receives the playback reference signal when AudioTrack plays on the communication path.
 * - Playing on media/navigation streams bypasses hardware AEC and causes self-echo loops.
 * - Automatically routes to wired/Bluetooth/USB headsets when connected (full-duplex isolation),
 *   or to the built-in speakerphone on the communication bus when hands-free.
 * - Tracks live output RMS and a post-speech reverberation guard window so residual room echo
 *   never self-interrupts Gemini Live while preserving full sensitivity for users & future voice calls.
 */
class AudioPlaybackManager(
    context: Context
) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var speakingHoldJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val isInitialized = AtomicBoolean(false)
    private val turnCompletedFromServer = AtomicBoolean(true)
    private val lastChunkWrittenAtMs = AtomicLong(0L)

    @Volatile
    private var previousAudioMode: Int? = null
    @Volatile
    private var previousSpeakerphoneOn: Boolean? = null

    private var playbackChannel = Channel<ByteArray>(
        capacity = MAX_PENDING_AUDIO_CHUNKS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _isAiSpeaking = MutableStateFlow(false)
    val isAiSpeaking: StateFlow<Boolean> = _isAiSpeaking.asStateFlow()

    /**
     * True while AI audio is playing AND during the short post-speech hardware drain + room
     * reverberation tail (~600ms total). Immediately cleared on genuine user barge-in.
     */
    private val _isEchoGuardActive = MutableStateFlow(false)
    val isEchoGuardActive: StateFlow<Boolean> = _isEchoGuardActive.asStateFlow()

    /**
     * Live normalized RMS [0f..1f] of the AI speech chunk currently being written to AudioTrack,
     * used as a reference level by [AudioCaptureManager.AcousticEchoGate] for adaptive double-talk.
     */
    private val _currentOutputRms = MutableStateFlow(0f)
    val currentOutputRms: StateFlow<Float> = _currentOutputRms.asStateFlow()

    private val _isAiMuted = MutableStateFlow(false)
    val isAiMuted: StateFlow<Boolean> = _isAiMuted.asStateFlow()

    @Volatile
    private var outputVolume: Float = 1.0f

    fun setOutputVolume(volume: Float) {
        outputVolume = volume.coerceIn(0.1f, 1.0f)
        runCatching { activeTrack?.setVolume(outputVolume) }
    }

    /**
     * Returns true when a wired headset, Bluetooth headset, BLE audio device, or USB headset is
     * connected — meaning playback is acoustically isolated from the phone microphone and full-duplex
     * listening/barge-in can run without loudspeaker echo gating.
     */
    fun isExternalAudioDeviceConnected(): Boolean {
        return runCatching {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_HEARING_AID -> true
                    else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            }
        }.getOrDefault(false)
    }

    @Synchronized
    fun initialize(
        sampleRateHz: Int = DEFAULT_OUTPUT_SAMPLE_RATE_HZ,
        onError: (ScreenSightError) -> Unit
    ) {
        if (isInitialized.get() && activeTrack != null) return

        release()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecordErrorOrBadValue(minBufferSize)) {
            onError(ScreenSightError.AudioTrackFailure)
            return
        }

        try {
            configureCommunicationAudioRouting()
            requestAudioFocus()

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 3)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                restoreAudioRouting()
                onError(ScreenSightError.AudioTrackFailure)
                return
            }

            runCatching { track.setVolume(outputVolume) }
            track.play()
            activeTrack = track
            isInitialized.set(true)
            turnCompletedFromServer.set(true)

            val channel = Channel<ByteArray>(
                capacity = MAX_PENDING_AUDIO_CHUNKS,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            playbackChannel = channel

            playbackJob = scope.launch {
                try {
                    for (pcmChunk in channel) {
                        if (!isActive || !isInitialized.get()) break
                        if (_isAiMuted.value) {
                            _isAiSpeaking.value = false
                            _isEchoGuardActive.value = false
                            _currentOutputRms.value = 0f
                            continue
                        }
                        turnCompletedFromServer.set(false)
                        _isAiSpeaking.value = true
                        _isEchoGuardActive.value = true
                        _currentOutputRms.value = AudioCaptureManager.calculateNormalizedRms(pcmChunk)
                        lastChunkWrittenAtMs.set(System.currentTimeMillis())

                        runCatching {
                            track.write(pcmChunk, 0, pcmChunk.size)
                        }

                        if (channel.isEmpty && turnCompletedFromServer.get()) {
                            scheduleSpeakingTailCompletion()
                        }
                    }
                } finally {
                    _isAiSpeaking.value = false
                    _isEchoGuardActive.value = false
                    _currentOutputRms.value = 0f
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    if (activeTrack === track) {
                        activeTrack = null
                    }
                }
            }
        } catch (_: Exception) {
            release()
            onError(ScreenSightError.AudioTrackFailure)
        }
    }

    private fun AudioRecordErrorOrBadValue(size: Int): Int {
        return if (size == AudioTrack.ERROR || size == AudioTrack.ERROR_BAD_VALUE) size else Int.MIN_VALUE
    }

    fun enqueueAudioChunk(pcm24kBytes: ByteArray) {
        if (!isInitialized.get() || _isAiMuted.value || pcm24kBytes.isEmpty()) return
        speakingHoldJob?.cancel()
        turnCompletedFromServer.set(false)
        _isAiSpeaking.value = true
        _isEchoGuardActive.value = true
        _currentOutputRms.value = AudioCaptureManager.calculateNormalizedRms(pcm24kBytes)
        lastChunkWrittenAtMs.set(System.currentTimeMillis())
        playbackChannel.trySend(pcm24kBytes)
    }

    /**
     * Immediately flushes queued AI audio when the user barges in / interrupts Gemini.
     * Drops the echo guard immediately so the user's/caller's interrupting speech flows unimpeded.
     */
    fun interruptPlayback() {
        speakingHoldJob?.cancel()
        turnCompletedFromServer.set(true)
        while (playbackChannel.tryReceive().isSuccess) {
            // Drain pending audio chunks
        }
        _isAiSpeaking.value = false
        _isEchoGuardActive.value = false
        _currentOutputRms.value = 0f
        activeTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        }
    }

    fun setAiMuted(muted: Boolean) {
        _isAiMuted.value = muted
        if (muted) {
            interruptPlayback()
        }
    }

    fun toggleAiMute(): Boolean {
        val next = !_isAiMuted.value
        setAiMuted(next)
        return next
    }

    fun onTurnComplete() {
        turnCompletedFromServer.set(true)
        if (playbackChannel.isEmpty) {
            scheduleSpeakingTailCompletion()
        }
    }

    /**
     * Prevents `_isAiSpeaking` from flickering false between WebSocket audio chunks, allows the
     * hardware AudioTrack pipeline to finish draining (~220ms), and holds `_isEchoGuardActive`
     * through the room reverberation tail (~380ms) so the AI's final syllable never bounces back
     * into the microphone and triggers a self-reply.
     */
    private fun scheduleSpeakingTailCompletion() {
        speakingHoldJob?.cancel()
        speakingHoldJob = scope.launch {
            delay(HARDWARE_PLAYBACK_DRAIN_MS)
            if (playbackChannel.isEmpty && turnCompletedFromServer.get()) {
                _isAiSpeaking.value = false
                _currentOutputRms.value = 0f
                delay(POST_SPEECH_REVERB_TAIL_MS)
                if (playbackChannel.isEmpty && turnCompletedFromServer.get() && !_isAiSpeaking.value) {
                    _isEchoGuardActive.value = false
                }
            }
        }
    }

    @Synchronized
    fun release() {
        isInitialized.set(false)
        turnCompletedFromServer.set(true)
        speakingHoldJob?.cancel()
        speakingHoldJob = null
        _isAiSpeaking.value = false
        _isEchoGuardActive.value = false
        _currentOutputRms.value = 0f

        playbackChannel.close()
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
        }
        playbackJob?.cancel()
        playbackJob = null
        abandonAudioFocus()
        restoreAudioRouting()
    }

    private fun configureCommunicationAudioRouting() {
        runCatching {
            if (previousAudioMode == null) {
                previousAudioMode = audioManager.mode
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            val externalConnected = isExternalAudioDeviceConnected()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = audioManager.availableCommunicationDevices
                val preferredDevice = if (externalConnected) {
                    commDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                } else {
                    commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                }
                if (preferredDevice != null) {
                    audioManager.setCommunicationDevice(preferredDevice)
                }
            } else {
                @Suppress("DEPRECATION")
                if (previousSpeakerphoneOn == null) {
                    previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
                }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = !externalConnected
            }
        }
    }

    private fun restoreAudioRouting() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                previousSpeakerphoneOn?.let { prev ->
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = prev
                }
                previousSpeakerphoneOn = null
            }
            previousAudioMode?.let { prevMode ->
                audioManager.mode = prevMode
            }
            previousAudioMode = null
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { /* Ducking handled by OS */ }
                .build()
            audioFocusRequest = focusReq
            runCatching { audioManager.requestAudioFocus(focusReq) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { req ->
                runCatching { audioManager.abandonAudioFocusRequest(req) }
            }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(null) }
        }
    }

    companion object {
        const val DEFAULT_OUTPUT_SAMPLE_RATE_HZ = 24000
        private const val MAX_PENDING_AUDIO_CHUNKS = 128
        private const val HARDWARE_PLAYBACK_DRAIN_MS = 220L
        private const val POST_SPEECH_REVERB_TAIL_MS = 380L
    }
}
