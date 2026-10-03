package com.cyclone.mobile.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Alpha.72: Drive heard nothing because its recording started before the microphone service held the microphone
 * (Android silences a background app's recording), and the system recognizer it could fall back to was hidden by
 * package visibility. These pin the fix.
 */
class DriveVoiceFallbackContractTest {
    private fun source(relative: String): String = listOf(
        File("src/main/$relative"), File("app/src/main/$relative"), File("apps/mobile/app/src/main/$relative"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Could not locate $relative")

    private fun voice(name: String) = source("java/com/cyclone/mobile/voice/$name")

    @Test fun recordingWaitsForTheMicrophoneService() {
        val session = voice("VoiceSession.kt")
        val listen = session.substringAfter("private fun listen(waitMs: Int? = null) {").substringBefore("private fun heard()")
        assertTrue(listen.indexOf("VoiceService.startAndWait(app)") in 0 until listen.indexOf("capture.record("))
        val service = voice("VoiceService.kt")
        assertTrue(service.contains(".onSuccess { _foreground.value = true }"))
        assertTrue(service.contains("withTimeoutOrNull(FOREGROUND_WAIT_MS) { foreground.first { it } }"))
    }

    @Test fun aSilencedRecordingIsCaughtAndTheSystemRecognizerTakesOver() {
        assertTrue(voice("VoiceCapture.kt").contains("if (silenced.feed(chunk, n)) return Outcome.Failed(VoiceFailure.MIC_SILENCED)"))
        val session = voice("VoiceSession.kt")
        assertTrue(session.contains("private val RECOGNIZER_TAKES_OVER = setOf(VoiceFailure.MIC_SILENCED, VoiceFailure.MIC_BUSY)"))
        // Alpha.78: the ear policy decides, in the same turn: a deaf recording hands to the recognizer, a broken
        // recognizer hands back to the recording. Never one-way for the rest of the session.
        assertTrue(session.contains("if (result.failure in RECOGNIZER_TAKES_OVER) ears.recorderDeaf() else null"))
        assertTrue(session.contains("ears.recognizerBroken()"))
        assertTrue(!session.contains("systemRecognizer"))
        // A network or model error in transcription never switches ears.
        assertTrue(session.contains("a network or model error never switches ears"))
    }

    @Test fun theRecognizerIsVisibleAndFallsBackToTheStandardOne() {
        assertTrue(source("AndroidManifest.xml").contains("<action android:name=\"android.speech.RecognitionService\" />"))
        val stt = voice("SpeechToText.kt")
        assertTrue(stt.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer(context)"))
        assertTrue(stt.contains("if (!first.tryStandard) return@withContext first.result"))
        assertTrue(stt.contains("attempt(SpeechRecognizer.createSpeechRecognizer(context), language, onLevel).result"))
    }

    @Test fun nothingOfTheVoiceIsKept() {
        // The recognizer's transcript goes straight into the turn; no audio or text is written anywhere.
        listOf("VoiceSession.kt", "SpeechToText.kt", "MicSilence.kt", "VoiceCapture.kt").forEach { name ->
            val text = voice(name)
            listOf("getSharedPreferences", "writeText(", "FileOutputStream").forEach {
                assertTrue("$name uses $it", !text.contains(it))
            }
            // No logcat. (Alpha.78: the voice run log goes to the run history through VoiceRunSink, with the owner's
            // words like any run's goal, never audio.)
            assertTrue("$name uses Android's Log", !Regex("\\bLog\\.").containsMatchIn(text))
        }
    }
}
