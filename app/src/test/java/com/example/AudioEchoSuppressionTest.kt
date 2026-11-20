package com.example

import com.example.service.AcousticEchoGate
import com.example.service.AudioRoutingProfile
import com.example.service.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Full-Duplex Acoustic Echo Suppression, Adaptive Double-Talk Barge-In,
 * Calling-Agent Duplex Profile, and Transcript Self-Echo Filtering.
 */
class AudioEchoSuppressionTest {

    @Test
    fun `when AI is silent all user and caller speech passes through at full sensitivity`() {
        val gate = AcousticEchoGate(AudioRoutingProfile.ASSISTANT_SPEAKER)

        val quietResult = gate.evaluate(
            micRms = 0.03f,
            aiOutputRms = 0f,
            isEchoGuardActive = false,
            isHeadsetConnected = false,
            bargeInEnabled = true
        )
        assertEquals(AcousticEchoGate.Decision.PASS_THROUGH, quietResult)

        val speechResult1 = gate.evaluate(
            micRms = 0.12f,
            aiOutputRms = 0f,
            isEchoGuardActive = false,
            isHeadsetConnected = false,
            bargeInEnabled = true
        )
        val speechResult2 = gate.evaluate(
            micRms = 0.14f,
            aiOutputRms = 0f,
            isEchoGuardActive = false,
            isHeadsetConnected = false,
            bargeInEnabled = true
        )
        assertEquals(AcousticEchoGate.Decision.PASS_THROUGH, speechResult1)
        assertEquals(AcousticEchoGate.Decision.PASS_THROUGH, speechResult2)
        assertTrue(gate.isNormalVoiceActive())
    }

    @Test
    fun `loudspeaker AI self-echo is suppressed and never triggers false barge-in`() {
        val gate = AcousticEchoGate(AudioRoutingProfile.ASSISTANT_SPEAKER)

        // Simulate 12 chunks (~1.2 seconds) of AI speaking loudly on phone speaker with residual mic echo
        repeat(12) {
            val decision = gate.evaluate(
                micRms = 0.35f,
                aiOutputRms = 0.60f,
                isEchoGuardActive = true,
                isHeadsetConnected = false,
                bargeInEnabled = true
            )
            assertEquals(
                "Residual speaker echo must be suppressed so Gemini does not self-interrupt or answer itself",
                AcousticEchoGate.Decision.SUPPRESS_SELF_ECHO,
                decision
            )
        }
    }

    @Test
    fun `sustained loud human speech above AI reference triggers clean barge-in`() {
        val gate = AcousticEchoGate(AudioRoutingProfile.ASSISTANT_SPEAKER)

        // First 2 onset convergence chunks are always suppressed
        repeat(2) {
            assertEquals(
                AcousticEchoGate.Decision.SUPPRESS_SELF_ECHO,
                gate.evaluate(0.85f, 0.30f, isEchoGuardActive = true, isHeadsetConnected = false, bargeInEnabled = true)
            )
        }

        // Next 3 sustained loud human chunks are buffered as candidate pre-roll
        repeat(3) {
            val d = gate.evaluate(0.88f, 0.30f, isEchoGuardActive = true, isHeadsetConnected = false, bargeInEnabled = true)
            assertEquals(AcousticEchoGate.Decision.SUPPRESS_SELF_ECHO, d)
            assertTrue(gate.isCandidateDoubleTalkChunk())
        }

        // 4th sustained chunk confirms genuine human barge-in!
        val bargeIn = gate.evaluate(0.90f, 0.30f, isEchoGuardActive = true, isHeadsetConnected = false, bargeInEnabled = true)
        assertEquals(AcousticEchoGate.Decision.TRIGGER_BARGE_IN, bargeIn)
    }

    @Test
    fun `when barge-in is disabled loudspeaker audio is strictly suppressed during AI speech`() {
        val gate = AcousticEchoGate(AudioRoutingProfile.ASSISTANT_SPEAKER)

        repeat(10) {
            val decision = gate.evaluate(
                micRms = 0.95f,
                aiOutputRms = 0.20f,
                isEchoGuardActive = true,
                isHeadsetConnected = false,
                bargeInEnabled = false
            )
            assertEquals(AcousticEchoGate.Decision.SUPPRESS_SELF_ECHO, decision)
        }
    }

    @Test
    fun `headset mode keeps full-duplex passthrough and responsive barge-in`() {
        val gate = AcousticEchoGate(AudioRoutingProfile.ASSISTANT_SPEAKER)

        // Quiet background passes through cleanly on isolated headset
        assertEquals(
            AcousticEchoGate.Decision.PASS_THROUGH,
            gate.evaluate(0.04f, 0.70f, isEchoGuardActive = true, isHeadsetConnected = true, bargeInEnabled = true)
        )

        // Two normal spoken chunks on headset trigger immediate clean barge-in
        assertEquals(
            AcousticEchoGate.Decision.PASS_THROUGH,
            gate.evaluate(0.18f, 0.70f, isEchoGuardActive = true, isHeadsetConnected = true, bargeInEnabled = true)
        )
        assertEquals(
            AcousticEchoGate.Decision.TRIGGER_BARGE_IN,
            gate.evaluate(0.20f, 0.70f, isEchoGuardActive = true, isHeadsetConnected = true, bargeInEnabled = true)
        )
    }

    @Test
    fun `calling agent voice_call_duplex profile hears remote caller double-talk responsively`() {
        val callGate = AcousticEchoGate(AudioRoutingProfile.VOICE_CALL_DUPLEX)

        // Onset convergence (2 chunks)
        repeat(2) {
            assertEquals(
                AcousticEchoGate.Decision.SUPPRESS_SELF_ECHO,
                callGate.evaluate(0.55f, 0.40f, isEchoGuardActive = true, isHeadsetConnected = false, bargeInEnabled = true)
            )
        }
        // Moderate caller speech (0.55 > adaptive threshold 0.40) triggers after 3 chunks
        repeat(2) {
            assertEquals(
                AcousticEchoGate.Decision.SUPPRESS_SELF_ECHO,
                callGate.evaluate(0.55f, 0.40f, isEchoGuardActive = true, isHeadsetConnected = false, bargeInEnabled = true)
            )
        }
        assertEquals(
            AcousticEchoGate.Decision.TRIGGER_BARGE_IN,
            callGate.evaluate(0.55f, 0.40f, isEchoGuardActive = true, isHeadsetConnected = false, bargeInEnabled = true)
        )
    }

    @Test
    fun `transcript self-echo guard filters AI self-echo but preserves real user and caller replies`() {
        val aiSaid = "আমি এখন আপনার জন্য ইউটিউব খুলে দিচ্ছি এবং স্ক্রিন দেখে সাহায্য করছি।"

        // Direct echo of AI's own words right after speaking -> filtered
        assertTrue(
            SessionManager.isLikelySelfEchoTranscript(
                userTranscript = "আপনার জন্য ইউটিউব খুলে দিচ্ছি",
                recentAiTranscript = aiSaid,
                elapsedSinceAiSpeechMs = 600L
            )
        )

        // Genuine different user/caller response right after AI spoke -> preserved!
        assertFalse(
            SessionManager.isLikelySelfEchoTranscript(
                userTranscript = "না থামো, আগে আমার আব্বুকে কল করো",
                recentAiTranscript = aiSaid,
                elapsedSinceAiSpeechMs = 500L
            )
        )

        // Same phrase spoken much later (> 2.5s window) -> preserved!
        assertFalse(
            SessionManager.isLikelySelfEchoTranscript(
                userTranscript = "আপনার জন্য ইউটিউব খুলে দিচ্ছি",
                recentAiTranscript = aiSaid,
                elapsedSinceAiSpeechMs = 4_000L
            )
        )
    }
}
