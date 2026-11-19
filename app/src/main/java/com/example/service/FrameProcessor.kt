package com.example.service

import android.graphics.Bitmap
import android.media.Image
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

data class ProcessedFrameResult(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val perceptualSignature: Long,
    val wasSkippedDuplicate: Boolean = false
)

/**
 * FrameProcessor (Section 6.3 & Priority 9 Optimization).
 * Responsibilities:
 * - Zero-Bitmap fast path: computes the 64-bit 8x8 perceptual luminance hash directly from the
 *   [Image.Plane] RGBA ByteBuffer before allocating any [Bitmap], eliminating ~3-6 MB/sec of
 *   heap allocations and GC churn when the user's screen is static.
 * - Converts RGBA_8888 Image planes to Bitmap only when the frame has changed (or heartbeat interval elapsed).
 * - Scales down images preserving aspect ratio so the longest edge <= maxDimensionPx (default 1280px).
 * - Reuses an internal [ByteArrayOutputStream] buffer to minimize byte-array allocations during JPEG encoding.
 */
class FrameProcessor(
    @Volatile var maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    @Volatile var jpegQuality: Int = DEFAULT_JPEG_QUALITY
) {
    @Volatile
    private var lastFrameSignature: Long? = null
    @Volatile
    private var lastSentTimestampMs: Long = 0L

    private val reusableJpegStream = ByteArrayOutputStream(48 * 1024)

    fun updateConfiguration(maxDimension: Int, quality: Int) {
        maxDimensionPx = maxDimension.coerceIn(320, 1920)
        jpegQuality = quality.coerceIn(30, 95)
    }

    @Synchronized
    fun resetDuplicateFilter() {
        lastFrameSignature = null
        lastSentTimestampMs = 0L
    }

    /**
     * Optimized end-to-end [Image] processing path used by [ScreenCaptureService].
     * Checks the 8x8 perceptual hash directly on the native plane buffer BEFORE allocating a Bitmap.
     */
    @Synchronized
    fun processImage(
        image: Image,
        forceSendEvenIfUnchanged: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis()
    ): ProcessedFrameResult? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val plane = planes[0]
        val buffer = plane.buffer ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || pixelStride <= 0) return null

        val signature = computeLuminanceSignatureFromBuffer(
            buffer = buffer,
            width = width,
            height = height,
            pixelStride = pixelStride,
            rowStride = rowStride
        )

        val (targetWidth, targetHeight) = calculateTargetDimensions(
            srcWidth = width,
            srcHeight = height,
            maxDimension = maxDimensionPx
        )

        val previousSig = lastFrameSignature
        val timeSinceLastSend = currentTimeMs - lastSentTimestampMs

        // Fast-path duplicate skip: ZERO Bitmap allocations when screen is unchanged
        if (!forceSendEvenIfUnchanged &&
            previousSig != null &&
            isSignatureNearlyIdentical(previousSig, signature) &&
            timeSinceLastSend < HEARTBEAT_REFRESH_INTERVAL_MS
        ) {
            return ProcessedFrameResult(
                jpegBytes = ByteArray(0),
                width = targetWidth,
                height = targetHeight,
                perceptualSignature = signature,
                wasSkippedDuplicate = true
            )
        }

        val bitmap = extractBitmapFromImage(image) ?: return null
        return try {
            encodeChangedBitmap(
                sourceBitmap = bitmap,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                signature = signature,
                currentTimeMs = currentTimeMs
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /**
     * Extracts RGBA pixels from [Image] into a [Bitmap].
     * Caller MUST still close [Image] in a finally block.
     */
    fun extractBitmapFromImage(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val buffer = planes[0].buffer ?: return null
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || pixelStride <= 0) return null

        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + (rowPadding / pixelStride).coerceAtLeast(0)

        val paddedBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)

        return if (bitmapWidth == width) {
            paddedBitmap
        } else {
            val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            paddedBitmap.recycle()
            cropped
        }
    }

    /**
     * Resizes [sourceBitmap] while preserving aspect ratio, checks for perceptual duplicates,
     * and encodes to JPEG bytes. Always recycles temporary bitmaps to prevent memory leaks.
     */
    @Synchronized
    fun processBitmap(
        sourceBitmap: Bitmap,
        forceSendEvenIfUnchanged: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis()
    ): ProcessedFrameResult? {
        val srcWidth = sourceBitmap.width
        val srcHeight = sourceBitmap.height
        if (srcWidth <= 0 || srcHeight <= 0) return null

        val (targetWidth, targetHeight) = calculateTargetDimensions(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            maxDimension = maxDimensionPx
        )

        val signature = computeLuminanceSignature(sourceBitmap)
        val previousSig = lastFrameSignature
        val timeSinceLastSend = currentTimeMs - lastSentTimestampMs

        if (!forceSendEvenIfUnchanged &&
            previousSig != null &&
            isSignatureNearlyIdentical(previousSig, signature) &&
            timeSinceLastSend < HEARTBEAT_REFRESH_INTERVAL_MS
        ) {
            return ProcessedFrameResult(
                jpegBytes = ByteArray(0),
                width = targetWidth,
                height = targetHeight,
                perceptualSignature = signature,
                wasSkippedDuplicate = true
            )
        }

        return encodeChangedBitmap(
            sourceBitmap = sourceBitmap,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            signature = signature,
            currentTimeMs = currentTimeMs
        )
    }

    private fun encodeChangedBitmap(
        sourceBitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        signature: Long,
        currentTimeMs: Long
    ): ProcessedFrameResult? {
        val scaledBitmap = if (targetWidth != sourceBitmap.width || targetHeight != sourceBitmap.height) {
            Bitmap.createScaledBitmap(sourceBitmap, targetWidth, targetHeight, true)
        } else {
            sourceBitmap
        }

        try {
            reusableJpegStream.reset()
            val compressed = scaledBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                jpegQuality.coerceIn(30, 95),
                reusableJpegStream
            )
            if (!compressed) return null

            val jpegBytes = reusableJpegStream.toByteArray()
            lastFrameSignature = signature
            lastSentTimestampMs = currentTimeMs

            return ProcessedFrameResult(
                jpegBytes = jpegBytes,
                width = targetWidth,
                height = targetHeight,
                perceptualSignature = signature,
                wasSkippedDuplicate = false
            )
        } finally {
            if (scaledBitmap !== sourceBitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION_PX = 1280
        const val DEFAULT_JPEG_QUALITY = 70
        private const val HEARTBEAT_REFRESH_INTERVAL_MS = 8_000L

        /**
         * Pure function calculating aspect-ratio-preserving dimensions within [maxDimension].
         */
        fun calculateTargetDimensions(
            srcWidth: Int,
            srcHeight: Int,
            maxDimension: Int
        ): Pair<Int, Int> {
            require(srcWidth > 0 && srcHeight > 0 && maxDimension > 0) {
                "Dimensions must be positive"
            }
            val longestEdge = max(srcWidth, srcHeight)
            if (longestEdge <= maxDimension) {
                return srcWidth to srcHeight
            }
            val scale = maxDimension.toFloat() / longestEdge.toFloat()
            val targetWidth = (srcWidth * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (srcHeight * scale).roundToInt().coerceAtLeast(1)
            return targetWidth to targetHeight
        }

        /**
         * Zero-allocation 64-bit average hash (8x8 grid luminance comparison) directly on an RGBA_8888 ByteBuffer.
         */
        fun computeLuminanceSignatureFromBuffer(
            buffer: ByteBuffer,
            width: Int,
            height: Int,
            pixelStride: Int,
            rowStride: Int
        ): Long {
            if (width <= 0 || height <= 0 || pixelStride < 3) return 0L
            val capacity = buffer.limit()
            val luminances = IntArray(64)
            var sum = 0
            var idx = 0

            for (gy in 0 until 8) {
                val y = ((gy + 0.5f) * height / 8f).toInt().coerceIn(0, height - 1)
                val rowOffset = y * rowStride
                for (gx in 0 until 8) {
                    val x = ((gx + 0.5f) * width / 8f).toInt().coerceIn(0, width - 1)
                    val offset = rowOffset + x * pixelStride
                    val luma = if (offset + 2 < capacity) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        (r * 299 + g * 587 + b * 114) / 1000
                    } else {
                        0
                    }
                    luminances[idx++] = luma
                    sum += luma
                }
            }

            val avg = sum / 64
            var hash = 0L
            for (i in 0 until 64) {
                if (luminances[i] >= avg) {
                    hash = hash or (1L shl i)
                }
            }
            return hash
        }

        /**
         * Computes a 64-bit average hash (8x8 grid luminance comparison) for fast duplicate frame detection.
         */
        fun computeLuminanceSignature(bitmap: Bitmap): Long {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return 0L

            val luminances = IntArray(64)
            var sum = 0
            var idx = 0
            for (gy in 0 until 8) {
                val y = ((gy + 0.5f) * h / 8f).toInt().coerceIn(0, h - 1)
                for (gx in 0 until 8) {
                    val x = ((gx + 0.5f) * w / 8f).toInt().coerceIn(0, w - 1)
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val luma = (r * 299 + g * 587 + b * 114) / 1000
                    luminances[idx++] = luma
                    sum += luma
                }
            }
            val avg = sum / 64
            var hash = 0L
            for (i in 0 until 64) {
                if (luminances[i] >= avg) {
                    hash = hash or (1L shl i)
                }
            }
            return hash
        }

        fun isSignatureNearlyIdentical(sigA: Long, sigB: Long): Boolean {
            val hammingDistance = java.lang.Long.bitCount(sigA xor sigB)
            return hammingDistance <= 2
        }
    }
}
