package com.example.service

import android.content.Context
import android.media.AudioAttributes
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
 * Real-time AI voice audio output using Android AudioTrack (Section 7.2 & Priority 6).
 * Streams 24 kHz 16-bit PCM mono audio chunks from Gemini Live API with bounded backpressure queue,
 * AudioFocus management, AI voice mute toggle, flicker-free speaking state detection,
 * immediate interruption flushing, and thread-safe AudioTrack release.
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

    private var playbackChannel = Channel<ByteArray>(
        capacity = MAX_PENDING_AUDIO_CHUNKS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _isAiSpeaking = MutableStateFlow(false)
    val isAiSpeaking: StateFlow<Boolean> = _isAiSpeaking.asStateFlow()

    private val _isAiMuted = MutableStateFlow(false)
    val isAiMuted: StateFlow<Boolean> = _isAiMuted.asStateFlow()

    @Volatile
    private var outputVolume: Float = 1.0f

    fun setOutputVolume(volume: Float) {
        outputVolume = volume.coerceIn(0.1f, 1.0f)
        runCatching { activeTrack?.setVolume(outputVolume) }
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
        if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            onError(ScreenSightError.AudioTrackFailure)
            return
        }

        try {
            requestAudioFocus()

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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
                            continue
                        }
                        turnCompletedFromServer.set(false)
                        _isAiSpeaking.value = true
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

    fun enqueueAudioChunk(pcm24kBytes: ByteArray) {
        if (!isInitialized.get() || _isAiMuted.value || pcm24kBytes.isEmpty()) return
        speakingHoldJob?.cancel()
        turnCompletedFromServer.set(false)
        _isAiSpeaking.value = true
        lastChunkWrittenAtMs.set(System.currentTimeMillis())
        playbackChannel.trySend(pcm24kBytes)
    }

    /**
     * Immediately flushes queued AI audio when the user barges in / interrupts Gemini.
     */
    fun interruptPlayback() {
        speakingHoldJob?.cancel()
        turnCompletedFromServer.set(true)
        while (playbackChannel.tryReceive().isSuccess) {
            // Drain pending audio chunks
        }
        _isAiSpeaking.value = false
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
     * Prevents `_isAiSpeaking` from flickering false between WebSocket audio chunks and allows
     * the hardware AudioTrack buffer tail (~180ms) to finish playing out before transitioning to LISTENING.
     */
    private fun scheduleSpeakingTailCompletion() {
        speakingHoldJob?.cancel()
        speakingHoldJob = scope.launch {
            delay(180L)
            if (playbackChannel.isEmpty && turnCompletedFromServer.get()) {
                _isAiSpeaking.value = false
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

        playbackChannel.close()
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
        }
        playbackJob?.cancel()
        playbackJob = null
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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
                    AudioManager.STREAM_MUSIC,
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
    }
}
