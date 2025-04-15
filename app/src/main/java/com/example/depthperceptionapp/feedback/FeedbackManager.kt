package com.example.depthperceptionapp.feedback

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.depthperceptionapp.analysis.AnalysisManager // For FreePathResult enum
import com.example.depthperceptionapp.analysis.DetectionState
import java.util.Locale // Required for setting TTS language
import java.util.UUID   // Can be used for utterance IDs

/**
 * Manages Text-To-Speech (TTS) feedback for the application.
 *
 * Handles:
 * - Initializing the Android TTS engine.
 * - Setting the desired language (with fallback).
 * - Generating concise audio messages based on the `DetectionState`.
 * - Speaking the messages using the TTS engine.
 * - Throttling feedback to avoid overwhelming the user.
 * - Releasing TTS resources on shutdown.
 *
 * @param context Application context required for TTS initialization.
 */
class FeedbackManager(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "FeedbackManager"
        // Utterance ID prefix for tracking speech events (optional)
        private const val UTTERANCE_ID_PREFIX = "depth_feedback_"
    }

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var selectedLanguage: Locale = Locale.US // Default to US English

    // State for throttling feedback
    private var lastSpokenStateSummary: String? = null // Store a summary of the last spoken message
    private var lastSpokenTimeMs: Long = 0
    // TUNABLE: Minimum time in milliseconds between consecutive spoken feedbacks,
    // unless the state changes significantly. Prevents chatter. Tune for user comfort.
    private val throttleMillis = 1500L // Speak at most about every 1.5 seconds

    // TUNABLE: Speech parameters (adjust in onInit or dynamically)
    private var speechRate = 1.0f // 1.0 is normal speed
    private var speechPitch = 1.0f // 1.0 is normal pitch

    init {
        Log.d(TAG, "Initializing FeedbackManager...")
        try {
            // Start TTS engine initialization. The result is delivered asynchronously
            // to the onInit callback method.
            tts = TextToSpeech(context, this)
            Log.d(TAG, "TTS instance created, waiting for initialization...")
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating TextToSpeech instance: ${e.message}", e)
            // Handle failure: TTS might not be available on the device.
            tts = null
        }
    }

    /**
     * Callback invoked when the TTS engine finishes initialization.
     *
     * @param status Indicates TTS initialization status (TextToSpeech.SUCCESS or TextToSpeech.ERROR).
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.i(TAG, "TTS Initialization successful.")

            // --- Language Selection ---
            // Try setting the preferred language (e.g., French for Montreal, Canada)
            // Use Locale.CANADA_FRENCH for Canadian French. Locale.FRANCE for French (France).
            // Locale.US for US English (widely supported fallback).
            val preferredLanguage = Locale.CANADA_FRENCH // Target for Montreal
            val fallbackLanguage = Locale.US

            val langResult = tts?.setLanguage(preferredLanguage)

            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS language '${preferredLanguage.displayLanguage}' (e.g., Canadian French) is not supported or missing data. Trying fallback '${fallbackLanguage.displayLanguage}'.")
                // Attempt to set a fallback language (e.g., US English)
                val fallbackResult = tts?.setLanguage(fallbackLanguage)
                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS fallback language '${fallbackLanguage.displayLanguage}' is also not supported or missing data. TTS disabled.")
                    // Handle error: TTS is unusable. Maybe notify the user?
                    isTtsInitialized = false
                    tts = null // Release the potentially unusable instance
                    return
                } else {
                    Log.i(TAG, "TTS language set to fallback: ${fallbackLanguage.displayLanguage}")
                    selectedLanguage = fallbackLanguage
                }
            } else {
                Log.i(TAG, "TTS language set to preferred: ${preferredLanguage.displayLanguage}")
                selectedLanguage = preferredLanguage
            }

            // --- Set Speech Parameters ---
            // Adjust speech rate and pitch (TUNABLE)
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
            Log.i(TAG, "TTS speech rate: $speechRate, pitch: $speechPitch")


            // --- Set Utterance Progress Listener (Optional but recommended) ---
            // Provides callbacks for when speech starts, finishes, or errors.
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Log.d(TAG, "TTS Start: $utteranceId") // Can be verbose
                }

                override fun onDone(utteranceId: String?) {
                    // Log.d(TAG, "TTS Done: $utteranceId") // Can be verbose
                }

                // This might be deprecated; onError(CharSequence, Exception) might be preferred on newer APIs
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS Error (Deprecated Listener): $utteranceId")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "TTS Error: $utteranceId, Code: $errorCode")
                    // Handle errors like network timeout if using network synthesis, output issues, etc.
                }
            })

            isTtsInitialized = true
            Log.i(TAG, "TTS setup complete and ready.")
            // Speak a confirmation message (optional)
            // tts?.speak("Guidance system ready.", TextToSpeech.QUEUE_FLUSH, null, "init_complete")

        } else {
            // TTS Initialization failed at the engine level.
            Log.e(TAG, "TTS Initialization failed! Status code: $status")
            isTtsInitialized = false
            tts = null
        }
    }

    /**
     * Generates and speaks audio feedback based on the current detection state,
     * applying throttling to avoid overwhelming the user with constant updates.
     *
     * @param currentState The latest DetectionState from the AnalysisManager.
     */
    fun provideFeedback(currentState: DetectionState) {
        // Check if TTS is initialized and ready.
        if (!isTtsInitialized || tts == null) {
            // Log.w(TAG, "TTS not ready, skipping feedback.") // Can be noisy
            return
        }

        // --- Generate Message ---
        // Create a concise text message based on the detected state.
        val message = generateFeedbackMessage(currentState)
        // Create a summary of the message for throttling comparison.
        val currentSummary = message // Use the full message for summary in this simple case

        // --- Apply Throttling ---
        val currentTimeMs = System.currentTimeMillis()
        // Determine if the core message has changed significantly from the last spoken one.
        // Or if enough time has passed since the last spoken feedback.
        val shouldSpeak = (currentSummary != lastSpokenStateSummary && currentSummary.isNotBlank()) ||
                (currentTimeMs - lastSpokenTimeMs > throttleMillis)

        if (shouldSpeak && currentSummary.isNotBlank()) {
            // --- Speak Message ---
            // Use QUEUE_FLUSH to interrupt any ongoing speech and speak the new message immediately.
            // Use QUEUE_ADD to queue messages if needed, but flush is better for real-time updates.
            // utteranceId can be used to track progress via UtteranceProgressListener.
            val utteranceId = UTTERANCE_ID_PREFIX + UUID.randomUUID().toString()

            // Pass parameters bundle if needed (e.g., volume, stream type)
            val params = Bundle()
            // params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) // Example: Set volume
            // params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) // Example: Speak on Alarm stream

            Log.i(TAG, "Speaking (ID: $utteranceId): '$message'")
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

            // Update throttling state
            lastSpokenStateSummary = currentSummary
            lastSpokenTimeMs = currentTimeMs
        } else {
            // Feedback throttled or message is blank
            // Log.d(TAG, "Feedback throttled or message blank.") // Can be verbose
        }
    }


    /**
     * Helper function to generate a concise, prioritized feedback message from the DetectionState.
     * Prioritizes immediate obstacles, then walls, then the general free path.
     *
     * @param state The current DetectionState.
     * @return A String containing the message to be spoken, or an empty string if nothing significant is detected.
     */
    private fun generateFeedbackMessage(state: DetectionState): String {
        val messages = mutableListOf<String>()

        // --- 1. Prioritize Immediate Obstacles ---
        // Use the same threshold as defined/used in AnalysisManager for consistency.
        // TODO: Consider getting threshold from AnalysisManager if it becomes dynamic.
        val obstacleThreshold = 0.8f // Needs tuning (higher = closer)
        if (state.maxObstacleDepth > obstacleThreshold) {
            // Use concise language. "Obstacle" is general. Future phases could use object detection results.
            // Consider adding relative direction if available (e.g., "Obstacle close center").
            messages.add(translate("Obstacle close ahead.", "Obstacle proche devant."))
            // If an obstacle is close, often that's the most critical info, so we might stop here.
            return messages.first() // Return immediately for critical obstacle
        }

        // --- 2. Report Detected Walls (If No Close Obstacle) ---
        if (state.wallDetected) {
            // TODO: Future: Could add direction (e.g., "Wall Left", "Wall Right") if analyzed by AnalysisManager.
            messages.add(translate("Wall detected.", "Mur détecté."))
            // We could potentially return here too, as walls are significant.
            // return messages.first() // Optionally return after detecting a wall
        }

        // --- 3. Report Free Path (If No Close Obstacle or Wall info is less critical) ---
        // Only report free path if no immediate obstacle was the primary message.
        // Decide if wall message should also suppress free path message (depends on user need).
        // Current logic: Reports free path even if wall detected, unless obstacle was close.
        when (state.freePathDirection) {
            AnalysisManager.FreePathResult.CENTER -> messages.add(translate("Path clear center.", "Chemin libre au centre."))
            AnalysisManager.FreePathResult.LEFT -> messages.add(translate("Path clear left.", "Chemin libre à gauche."))
            AnalysisManager.FreePathResult.RIGHT -> messages.add(translate("Path clear right.", "Chemin libre à droite."))
            AnalysisManager.FreePathResult.BLOCKED -> messages.add(translate("Path blocked.", "Chemin bloqué."))
            AnalysisManager.FreePathResult.UNKNOWN -> { /* No path info determined */ }
        }

        // Combine messages concisely or just speak the most important one found.
        // Current logic returns the first message added (Obstacle > Wall > Path).
        // Alternative: join messages (e.g., "Wall detected. Path clear center.") - potentially too verbose.
        return messages.firstOrNull() ?: "" // Return the highest priority message, or empty string
    }

    /**
     * Simple placeholder for translation based on selected TTS language.
     * In a real app, use Android String resources with locale qualifiers.
     */
    private fun translate(english: String, french: String): String {
        return if (selectedLanguage.language == Locale.FRENCH.language) {
            french
        } else {
            english
        }
    }


    /**
     * Shuts down the TTS engine and releases its resources.
     * Should be called when the TTS service is no longer needed, typically in `onDestroy`
     * of the Activity/Service that owns this FeedbackManager.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down TTS engine...")
        if (tts != null) {
            try {
                // Stop any ongoing speech immediately.
                tts?.stop()
                // Release the TTS engine resources.
                tts?.shutdown()
                Log.i(TAG, "TTS engine stopped and shut down.")
            } catch (e: Exception) {
                Log.e(TAG, "Exception during TTS shutdown: ${e.message}", e)
            } finally {
                isTtsInitialized = false
                tts = null
            }
        } else {
            Log.d(TAG, "TTS engine was already null.")
        }
    }

} // End of FeedbackManager class