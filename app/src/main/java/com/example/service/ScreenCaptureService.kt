package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.ScreenSightApp
import com.example.domain.model.ScreenSightError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent Foreground Service for Background Voice + Screen Sharing (Section 6 & Priorities 3, 4, 5, 9).
 *
 * Ensures that when the user leaves/minimizes ScreenSight AI to use any other app on their phone:
 * 1. Android 14+ does NOT mute the microphone (`FOREGROUND_SERVICE_TYPE_MICROPHONE`).
 * 2. Android 14+ allows continuous screen capture (`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`).
 * 3. Minimizes to the phone's Home screen ONLY AFTER `startForeground`, `getMediaProjection`, and
 *    `createVirtualDisplay` have completed while the Activity is still in the foreground, avoiding
 *    Android 14+ `SecurityException` / `ForegroundServiceStartNotAllowedException`.
 * 4. Dynamically resizes `VirtualDisplay` and `ImageReader` on portrait/landscape rotation without
 *    requiring a new `MediaProjection` token.
 * 5. Uses zero-Bitmap duplicate frame skipping via `FrameProcessor.processImage(image)`.
 */
class ScreenCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var frameLoopJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null
    private var imageReaderThread: HandlerThread? = null
    private var imageReaderHandler: Handler? = null
    private var currentCaptureWidth: Int = 0
    private var currentCaptureHeight: Int = 0

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val isCapturing = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var floatingOverlayController: FloatingOverlayController? = null

    private val sessionManager: SessionManager
        get() = (application as ScreenSightApp).container.sessionManager

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            val wasActive = isCapturing.get()
            releaseCapture(keepOverlayIfSessionActive = true)
            if (wasActive) {
                sessionManager.onScreenProjectionRevokedBySystem()
            }
            if (!sessionManager.sessionState.value.isActive) {
                releaseWakeLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                updateForegroundNotification()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        floatingOverlayController = FloatingOverlayController(
            context = this,
            onToggleMic = { sessionManager.toggleMicrophoneMute() },
            onTogglePauseCapture = {
                if (isPaused.get()) resumeCapture() else pauseCapture()
            },
            onEndSession = {
                releaseCapture(keepOverlayIfSessionActive = false)
                releaseWakeLocks()
                sessionManager.stopSession(fromService = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VOICE_SESSION -> {
                // If already capturing screen, do not downgrade foreground service type
                if (isCapturing.get()) {
                    floatingOverlayController?.showOverlay(sessionManager)
                    return START_STICKY
                }
                try {
                    acquireWakeLocks()
                    val notification = buildForegroundNotification(
                        isSharingScreen = false,
                        isCapturePaused = false
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val hasMicPerm = ContextCompat.checkSelfPermission(
                            this,
                            android.Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMicPerm) {
                            startForeground(
                                NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            )
                        } else {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    floatingOverlayController?.showOverlay(sessionManager)
                } catch (_: Exception) {
                    // Ignore if microphone foreground promotion is blocked while app is in background
                }
            }

            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val autoMinimize = intent.getBooleanExtra(EXTRA_AUTO_MINIMIZE, true)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    sessionManager.onScreenCaptureError(ScreenSightError.ScreenCapturePermissionDenied)
                    return START_STICKY
                }

                try {
                    acquireWakeLocks()
                    val notification = buildForegroundNotification(
                        isSharingScreen = true,
                        isCapturePaused = false
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val hasMicPerm = ContextCompat.checkSelfPermission(
                                this,
                                android.Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMicPerm) {
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            } else {
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                            }
                        } else {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        }
                        startForeground(NOTIFICATION_ID, notification, fgType)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }

                    val started = startCapture(resultCode, resultData)
                    if (started && autoMinimize) {
                        minimizeToPhoneHomeScreen()
                    }
                } catch (e: Exception) {
                    sessionManager.onScreenCaptureError(
                        ScreenSightError.ForegroundServiceFailure(e.message ?: "Could not start foreground service")
                    )
                }
            }

            ACTION_PAUSE_CAPTURE -> pauseCapture()
            ACTION_RESUME_CAPTURE -> resumeCapture()

            ACTION_STOP_CAPTURE -> {
                stopCapture()
                if (!sessionManager.sessionState.value.isActive) {
                    releaseWakeLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateForegroundNotification()
                }
            }

            ACTION_END_SESSION -> {
                releaseCapture(keepOverlayIfSessionActive = false)
                releaseWakeLocks()
                sessionManager.stopSession(fromService = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingOverlayController?.onConfigurationChanged()
        if (isCapturing.get() && virtualDisplay != null) {
            resizeVirtualDisplayIfNeeded()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the live voice & screen-sharing foreground service running when the user leaves or swipes the Activity
        super.onTaskRemoved(rootIntent)
    }

    fun startCapture(resultCode: Int, resultData: Intent): Boolean {
        releaseCapture(keepOverlayIfSessionActive = true)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            sessionManager.onScreenCaptureError(ScreenSightError.ScreenCapturePermissionDenied)
            return false
        }

        // Android 14+ MANDATES registering a callback BEFORE calling createVirtualDisplay
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        val metrics = resources.displayMetrics
        val maxDim = sessionManager.currentMaxFrameDimensionPx()
        val (captureWidth, captureHeight) = FrameProcessor.calculateTargetDimensions(
            srcWidth = metrics.widthPixels.coerceAtLeast(360),
            srcHeight = metrics.heightPixels.coerceAtLeast(640),
            maxDimension = maxDim
        )
        currentCaptureWidth = captureWidth
        currentCaptureHeight = captureHeight
        val densityDpi = metrics.densityDpi

        val handlerThread = HandlerThread("ScreenSightImageReader").apply { start() }
        val handler = Handler(handlerThread.looper)
        imageReaderThread = handlerThread
        imageReaderHandler = handler

        val reader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        val vDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            captureWidth,
            captureHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        virtualDisplay = vDisplay

        isCapturing.set(true)
        isPaused.set(false)
        sessionManager.onScreenSharingStateChanged(isSharing = true)
        sessionManager.onScreenCapturePausedChanged(isPaused = false)
        // Ensure microphone capture is active if permission was already granted
        sessionManager.startAudioCaptureIfPermitted()
        floatingOverlayController?.showOverlay(sessionManager)

        startFrameLoop()
        return true
    }

    /**
     * Adjusts VirtualDisplay and ImageReader surface when the user rotates their phone
     * between portrait and landscape without requiring a new MediaProjection token.
     */
    @Synchronized
    private fun resizeVirtualDisplayIfNeeded() {
        val vd = virtualDisplay ?: return
        val metrics = resources.displayMetrics
        val maxDim = sessionManager.currentMaxFrameDimensionPx()
        val (newWidth, newHeight) = FrameProcessor.calculateTargetDimensions(
            srcWidth = metrics.widthPixels.coerceAtLeast(360),
            srcHeight = metrics.heightPixels.coerceAtLeast(640),
            maxDimension = maxDim
        )
        if (newWidth == currentCaptureWidth && newHeight == currentCaptureHeight) return

        val oldReader = imageReader
        val newReader = ImageReader.newInstance(
            newWidth,
            newHeight,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = newReader
        currentCaptureWidth = newWidth
        currentCaptureHeight = newHeight

        runCatching {
            vd.resize(newWidth, newHeight, metrics.densityDpi)
            vd.surface = newReader.surface
        }
        runCatching { oldReader?.close() }
        sessionManager.frameProcessor.resetDuplicateFilter()
    }

    private fun minimizeToPhoneHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(homeIntent) }
    }

    private fun startFrameLoop() {
        frameLoopJob?.cancel()
        frameLoopJob = serviceScope.launch {
            while (isActive && isCapturing.get()) {
                val fps = sessionManager.currentFrameRateFps().coerceIn(1, 2)
                val intervalMs = (1000L / fps).coerceAtLeast(500L)

                if (!isPaused.get() && sessionManager.isLiveSocketReady()) {
                    captureAndSendSingleLatestFrame()
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * Acquires ONLY the latest frame, immediately closes the ImageReader image in `finally`,
     * and processes it via FrameProcessor.processImage() (which skips static duplicate screens
     * with zero Bitmap allocations).
     */
    @Synchronized
    private fun captureAndSendSingleLatestFrame() {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        try {
            sessionManager.processAndSendScreenImage(image)
        } catch (_: Exception) {
            // Ignore transient plane read error during orientation resize
        } finally {
            runCatching { image.close() }
        }
    }

    fun pauseCapture() {
        if (!isCapturing.get()) return
        isPaused.set(true)
        sessionManager.onScreenCapturePausedChanged(isPaused = true)
        updateForegroundNotification()
    }

    fun resumeCapture() {
        if (!isCapturing.get()) return
        isPaused.set(false)
        sessionManager.frameProcessor.resetDuplicateFilter()
        sessionManager.onScreenCapturePausedChanged(isPaused = false)
        updateForegroundNotification()
    }

    fun stopCapture() {
        releaseCapture(keepOverlayIfSessionActive = true)
        sessionManager.onScreenSharingStateChanged(isSharing = false)
    }

    @Synchronized
    fun releaseCapture(keepOverlayIfSessionActive: Boolean = false) {
        isCapturing.set(false)
        isPaused.set(false)
        currentCaptureWidth = 0
        currentCaptureHeight = 0

        if (!keepOverlayIfSessionActive || !sessionManager.sessionState.value.isActive) {
            floatingOverlayController?.hideOverlay()
        }

        frameLoopJob?.cancel()
        frameLoopJob = null

        virtualDisplay?.let { vd ->
            runCatching { vd.release() }
        }
        virtualDisplay = null

        imageReader?.let { reader ->
            runCatching { reader.close() }
        }
        imageReader = null

        imageReaderThread?.let { thread ->
            runCatching { thread.quitSafely() }
        }
        imageReaderThread = null
        imageReaderHandler = null

        mediaProjection?.let { mp ->
            runCatching { mp.unregisterCallback(projectionCallback) }
            runCatching { mp.stop() }
        }
        mediaProjection = null
    }

    private fun acquireWakeLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ScreenSightAI::LiveSessionWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(MAX_WAKE_LOCK_TIMEOUT_MS)
                }
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(MAX_WAKE_LOCK_TIMEOUT_MS)
            }

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockType, "ScreenSightAI::LiveWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseWakeLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        }
        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        }
    }

    override fun onDestroy() {
        releaseCapture(keepOverlayIfSessionActive = false)
        releaseWakeLocks()
        sessionManager.onScreenSharingStateChanged(isSharing = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateForegroundNotification() {
        val notification = buildForegroundNotification(
            isSharingScreen = isCapturing.get(),
            isCapturePaused = isPaused.get()
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private fun buildForegroundNotification(
        isSharingScreen: Boolean = true,
        isCapturePaused: Boolean = false
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = if (isCapturePaused) ACTION_RESUME_CAPTURE else ACTION_PAUSE_CAPTURE
        }
        val pauseResumePending = PendingIntent.getService(
            this,
            1,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endSessionIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_END_SESSION
        }
        val endSessionPending = PendingIntent.getService(
            this,
            2,
            endSessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when {
            isSharingScreen && isCapturePaused -> "Screen sharing paused • Voice active in background"
            isSharingScreen -> getString(R.string.notification_sharing_text)
            else -> "Live voice session running in background"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.notification_sharing_title))
            .setContentText(statusText)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isSharingScreen) {
            builder.addAction(
                if (isCapturePaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isCapturePaused) "Resume Screen" else "Pause Screen",
                pauseResumePending
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.action_end_conversation),
            endSessionPending
        )
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "screensight_live_projection_channel"
        private const val NOTIFICATION_ID = 4102
        private const val VIRTUAL_DISPLAY_NAME = "ScreenSightLiveVirtualDisplay"
        private const val MAX_WAKE_LOCK_TIMEOUT_MS = 45 * 60 * 1000L // 45-minute safety bound

        const val ACTION_START_VOICE_SESSION = "com.example.service.action.START_VOICE_SESSION"
        const val ACTION_START_CAPTURE = "com.example.service.action.START_CAPTURE"
        const val ACTION_PAUSE_CAPTURE = "com.example.service.action.PAUSE_CAPTURE"
        const val ACTION_RESUME_CAPTURE = "com.example.service.action.RESUME_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.example.service.action.STOP_CAPTURE"
        const val ACTION_END_SESSION = "com.example.service.action.END_SESSION"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_AUTO_MINIMIZE = "extra_auto_minimize"

        fun startVoiceSessionService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_VOICE_SESSION
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun startScreenCaptureService(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            autoMinimizeToHome: Boolean = true
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_AUTO_MINIMIZE, autoMinimizeToHome)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun pauseScreenCaptureService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_PAUSE_CAPTURE
            }
            runCatching { context.startService(intent) }
        }

        fun resumeScreenCaptureService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_RESUME_CAPTURE
            }
            runCatching { context.startService(intent) }
        }

        fun stopScreenCaptureService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            runCatching { context.startService(intent) }
        }

        /**
         * Uses [Context.stopService] rather than `startService(ACTION_END_SESSION)` so stopping
         * from the background never throws `BackgroundServiceStartNotAllowedException` on Android 8+.
         */
        fun stopEntireSessionService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            runCatching { context.stopService(intent) }
        }
    }
}
