package com.example.agent

/**
 * Phase 3 Grounding & Coordinate Boundary Validator (Section 3).
 *
 * Ensures every bounding-box or coordinate interaction:
 * - Confirms valid screen dimensions before dispatching gestures.
 * - Validates pixel (0..width, 0..height) and normalized (0.0..1.0) coordinate boundaries.
 * - Verifies that element_id / observation_id references belong to the latest non-stale observation.
 * - Rejects stale, disabled, or invalid target references cleanly without blind taps.
 */
object ElementInteractionValidator {

    const val DEFAULT_MAX_OBSERVATION_AGE_MS = 25_000L

    sealed class ValidationOutcome {
        data class ValidElement(
            val element: GroundedUiElement,
            val tapX: Float,
            val tapY: Float
        ) : ValidationOutcome()

        data class ValidCoordinate(
            val tapX: Float,
            val tapY: Float,
            val isNormalizedFallback: Boolean
        ) : ValidationOutcome()

        data class Rejected(
            val errorCode: String,
            val reasonEn: String,
            val reasonBn: String,
            val recoverable: Boolean = true
        ) : ValidationOutcome()
    }

    /**
     * Validates that a [UiBounds] rectangle has positive area and lies within valid screen dimensions.
     */
    fun validateBounds(bounds: UiBounds, screenWidthPx: Int, screenHeightPx: Int): Boolean {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) return false
        if (bounds.x1 < 0 || bounds.y1 < 0) return false
        if (bounds.x2 <= bounds.x1 || bounds.y2 <= bounds.y1) return false
        if (bounds.x2 > screenWidthPx || bounds.y2 > screenHeightPx) return false
        if (bounds.normX1 < 0f || bounds.normY1 < 0f || bounds.normX2 > 1f || bounds.normY2 > 1f) return false
        if (bounds.normX2 < bounds.normX1 || bounds.normY2 < bounds.normY1) return false
        return true
    }

    /**
     * Validates a grounded [elementId] against the [latestSnapshot] and optional [requestedObservationId].
     */
    fun validateElementTarget(
        elementId: String,
        requestedObservationId: String?,
        latestSnapshot: ScreenUiSnapshot?,
        nowMillis: Long = System.currentTimeMillis(),
        maxObservationAgeMs: Long = DEFAULT_MAX_OBSERVATION_AGE_MS
    ): ValidationOutcome {
        if (latestSnapshot == null) {
            return ValidationOutcome.Rejected(
                errorCode = "NO_OBSERVATION",
                reasonEn = "No recent screen observation is available. Call get_screen_state first.",
                reasonBn = "স্ক্রিনের সর্বশেষ তথ্য পাওয়া যায়নি। আবার স্ক্রিন দেখছি।"
            )
        }
        val freshnessCheck = checkObservationFreshness(
            requestedObservationId = requestedObservationId,
            latestSnapshot = latestSnapshot,
            nowMillis = nowMillis,
            maxObservationAgeMs = maxObservationAgeMs
        )
        if (freshnessCheck != null) return freshnessCheck

        if (latestSnapshot.screenWidthPx <= 0 || latestSnapshot.screenHeightPx <= 0) {
            return ValidationOutcome.Rejected(
                errorCode = "INVALID_SCREEN_DIMENSIONS",
                reasonEn = "Cannot verify element coordinates because screen dimensions are unavailable.",
                reasonBn = "স্ক্রিনের মাপ সঠিক না থাকায় ট্যাপ করা যায়নি।"
            )
        }

        val element = latestSnapshot.findElementById(elementId)
            ?: return ValidationOutcome.Rejected(
                errorCode = "ELEMENT_ID_NOT_FOUND",
                reasonEn = "Element \"$elementId\" does not exist in latest observation (${latestSnapshot.observationId}). Call get_screen_state to refresh.",
                reasonBn = "নির্দিষ্ট বাটনটি বর্তমান স্ক্রিনে নেই। আবার স্ক্রিন যাচাই করছি।"
            )

        if (!element.enabled) {
            return ValidationOutcome.Rejected(
                errorCode = "ELEMENT_DISABLED",
                reasonEn = "Element \"$elementId\" (\"${element.bestLabel}\") is currently disabled.",
                reasonBn = "\"${element.bestLabel}\" বাটনটি এখন নিষ্ক্রিয় আছে।",
                recoverable = false
            )
        }

        if (!validateBounds(element.bounds, latestSnapshot.screenWidthPx, latestSnapshot.screenHeightPx)) {
            return ValidationOutcome.Rejected(
                errorCode = "INVALID_BOUNDS",
                reasonEn = "Element \"$elementId\" has out-of-bounds screen coordinates.",
                reasonBn = "বাটনটির অবস্থান স্ক্রিনের সীমার বাইরে রয়েছে।"
            )
        }

        return ValidationOutcome.ValidElement(
            element = element,
            tapX = element.bounds.centerX,
            tapY = element.bounds.centerY
        )
    }

    /**
     * Validates pixel (x, y) or normalized (normX, normY in 0..1) fallback coordinates against
     * confirmed screen dimensions and observation freshness.
     */
    fun validateCoordinateTarget(
        x: Float?,
        y: Float?,
        normX: Float?,
        normY: Float?,
        requestedObservationId: String?,
        latestSnapshot: ScreenUiSnapshot?,
        screenWidthPx: Int,
        screenHeightPx: Int,
        nowMillis: Long = System.currentTimeMillis(),
        maxObservationAgeMs: Long = DEFAULT_MAX_OBSERVATION_AGE_MS
    ): ValidationOutcome {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) {
            return ValidationOutcome.Rejected(
                errorCode = "INVALID_SCREEN_DIMENSIONS",
                reasonEn = "Screen dimensions ($screenWidthPx x $screenHeightPx) are invalid for coordinate interaction.",
                reasonBn = "স্ক্রিনের মাপ নিশ্চিত করা যায়নি।"
            )
        }

        if (!requestedObservationId.isNullOrBlank() && latestSnapshot != null) {
            val freshnessCheck = checkObservationFreshness(
                requestedObservationId = requestedObservationId,
                latestSnapshot = latestSnapshot,
                nowMillis = nowMillis,
                maxObservationAgeMs = maxObservationAgeMs
            )
            if (freshnessCheck != null) return freshnessCheck
        } else if (latestSnapshot != null && (nowMillis - latestSnapshot.timestampMillis) > maxObservationAgeMs) {
            return ValidationOutcome.Rejected(
                errorCode = "STALE_OBSERVATION",
                reasonEn = "Latest screen observation is stale (${(nowMillis - latestSnapshot.timestampMillis) / 1000}s old). Refresh screen state before tapping coordinates.",
                reasonBn = "স্ক্রিনের তথ্য পুরনো হয়ে গেছে। আবার স্ক্রিন দেখে চেষ্টা করছি।"
            )
        }

        if (normX != null && normY != null) {
            if (normX.isNaN() || normY.isNaN() || normX !in 0f..1f || normY !in 0f..1f) {
                return ValidationOutcome.Rejected(
                    errorCode = "INVALID_COORDINATES",
                    reasonEn = "Normalized coordinates ($normX, $normY) must be within 0.0..1.0.",
                    reasonBn = "ট্যাপের অবস্থান সঠিক সীমার মধ্যে নেই।"
                )
            }
            val px = (normX * screenWidthPx).coerceIn(1f, (screenWidthPx - 1).coerceAtLeast(1).toFloat())
            val py = (normY * screenHeightPx).coerceIn(1f, (screenHeightPx - 1).coerceAtLeast(1).toFloat())
            return ValidationOutcome.ValidCoordinate(tapX = px, tapY = py, isNormalizedFallback = true)
        }

        if (x != null && y != null) {
            if (x.isNaN() || y.isNaN() || x < 0f || y < 0f || x > screenWidthPx.toFloat() || y > screenHeightPx.toFloat()) {
                return ValidationOutcome.Rejected(
                    errorCode = "INVALID_COORDINATES",
                    reasonEn = "Pixel coordinates (${x.toInt()}, ${y.toInt()}) exceed screen boundaries (${screenWidthPx}x${screenHeightPx}).",
                    reasonBn = "ট্যাপের অবস্থান স্ক্রিনের সীমার বাইরে রয়েছে।"
                )
            }
            return ValidationOutcome.ValidCoordinate(tapX = x, tapY = y, isNormalizedFallback = false)
        }

        return ValidationOutcome.Rejected(
            errorCode = "INVALID_COORDINATES",
            reasonEn = "No valid pixel (x,y) or normalized (norm_x,norm_y) coordinates provided.",
            reasonBn = "সঠিক অবস্থান পাওয়া যায়নি।"
        )
    }

    private fun checkObservationFreshness(
        requestedObservationId: String?,
        latestSnapshot: ScreenUiSnapshot,
        nowMillis: Long,
        maxObservationAgeMs: Long
    ): ValidationOutcome.Rejected? {
        if (!requestedObservationId.isNullOrBlank() &&
            !latestSnapshot.observationId.equals(requestedObservationId.trim(), ignoreCase = true)
        ) {
            return ValidationOutcome.Rejected(
                errorCode = "STALE_OBSERVATION",
                reasonEn = "Target belongs to stale observation \"$requestedObservationId\" (current is \"${latestSnapshot.observationId}\"). Re-observe screen before interacting.",
                reasonBn = "স্ক্রিন পরিবর্তিত হয়েছে। নতুন স্ক্রিন দেখে আবার চেষ্টা করছি।"
            )
        }
        val ageMs = nowMillis - latestSnapshot.timestampMillis
        if (ageMs > maxObservationAgeMs) {
            return ValidationOutcome.Rejected(
                errorCode = "STALE_OBSERVATION",
                reasonEn = "Screen observation \"${latestSnapshot.observationId}\" is stale (${ageMs / 1000}s old). Call get_screen_state before tapping.",
                reasonBn = "স্ক্রিনের তথ্য পুরনো হয়ে গেছে। আবার স্ক্রিন দেখছি।"
            )
        }
        return null
    }
}
