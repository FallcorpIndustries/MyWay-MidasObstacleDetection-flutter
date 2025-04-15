package com.example.depthperceptionapp.feedback
// Adaptez si nécessaire

import android.content.Context
import android.content.res.Resources // Import pour catch Resources.NotFoundException
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.depthperceptionapp.Config // Import Config OK
import com.example.depthperceptionapp.R     // Import R OK
import com.example.depthperceptionapp.analysis.DetectionState
// Importer les enums depuis leur définition unique
import com.example.depthperceptionapp.analysis.AnalysisManager // Pour accéder à FreePathResult interne
import com.example.depthperceptionapp.analysis.WallDirection
import java.util.Locale
import java.util.UUID

/**
 * Gère les retours audio multimodaux (Earcons via SoundPool, TTS) pour l'utilisateur.
 * VERSION AMÉLIORÉE : Détecte les changements significatifs et applique hystérésis/throttling.
 */
class FeedbackManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "FeedbackManager"
        private const val TTS_UTTERANCE_ID_PREFIX = "myway_tts_"
    }

    // --- TTS ---
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var selectedLanguage: Locale = Locale.CANADA_FRENCH
    private var fallbackLanguage: Locale = Locale.FRENCH
    private var finalFallbackLanguage: Locale = Locale.US

    // --- SoundPool ---
    private var soundPool: SoundPool? = null
    private var isSoundPoolLoaded = false
    private var obstacleSoundId: Int = -1
    private var wallSoundId: Int = -1
    private var pathClearSoundId: Int = -1
    private var errorSoundId: Int = -1
    // Streams actifs
    private var currentObstacleStreamId: Int? = null
    private var currentWallStreamId: Int? = null
    private var currentPathStreamId: Int? = null

    // --- Throttling & State ---
    private var lastObstacleReportTime: Long = 0
    private var lastWallReportTime: Long = 0
    private var lastPathReportTime: Long = 0
    private var lastProcessedState: DetectionState? = null
    private var isObstacleAlertActive: Boolean = false

    init {
        Log.d(TAG, "Initialisation FeedbackManager v2...")
        try {
            tts = TextToSpeech(context, this)
        } catch (e: Exception) { Log.e(TAG, "Erreur création instance TTS", e) }
        setupSoundPool()
    }

    // --- Configuration SoundPool ---
    private fun setupSoundPool() {
        Log.d(TAG, "Configuration SoundPool...")
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setAudioAttributes(audioAttributes)
            .setMaxStreams(Config.SOUNDPOOL_MAX_STREAMS) // Utilise Config
            .build()

        soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                Log.i(TAG, "Son chargé: ID $sampleId")
                checkSoundPoolLoaded()
            } else {
                Log.e(TAG, "Erreur chargement son ID: $sampleId, Status: $status")
                isSoundPoolLoaded = false
            }
        }

        try {
            // Charger les sons depuis res/raw en utilisant les bons identifiants R.raw.*
            // (qui doivent correspondre aux noms de fichiers renommés SANS extension)
            obstacleSoundId = soundPool?.load(context, R.raw.obstaclenear, 1) ?: -1
            wallSoundId = soundPool?.load(context, R.raw.walldetected, 1) ?: -1
            pathClearSoundId = soundPool?.load(context, R.raw.pathclear, 1) ?: -1
            errorSoundId = soundPool?.load(context, R.raw.errorbeep, 1) ?: -1

            if (listOf(obstacleSoundId, wallSoundId, pathClearSoundId, errorSoundId).any { it <= 0 }) {
                Log.e(TAG, "Au moins un son n'a pas pu être initié au chargement (ID invalide retourné par load).")
                isSoundPoolLoaded = false
            } else {
                Log.d(TAG, "Chargement des sons initié (IDs: Obs=$obstacleSoundId, Wall=$wallSoundId, Path=$pathClearSoundId, Err=$errorSoundId).")
            }
        } catch (e: Resources.NotFoundException) {
            // Cette erreur se produira si R.raw.* n'est pas généré (fichier manquant OU build non nettoyé)
            Log.e(TAG, "Erreur Ressources.NotFoundException lors de soundPool.load(). Vérifiez que les fichiers existent dans res/raw avec les bons noms (ex: obstacle_near.wav) ET que le projet est nettoyé/reconstruit.", e)
            isSoundPoolLoaded = false; soundPool?.release(); soundPool = null
        } catch (e: Exception) {
            Log.e(TAG, "Erreur générique lors de soundPool.load()", e)
            isSoundPoolLoaded = false; soundPool?.release(); soundPool = null
        }
    }

    private fun checkSoundPoolLoaded() {
        if (obstacleSoundId > 0 && wallSoundId > 0 && pathClearSoundId > 0 && errorSoundId > 0) {
            if (!isSoundPoolLoaded) {
                isSoundPoolLoaded = true
                Log.i(TAG, "SoundPool prêt !")
            }
        }
    }

    // --- Callback Initialisation TTS ---
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.i(TAG, "Initialisation TTS réussie.")
            val langResult = tts?.setLanguage(selectedLanguage) // FR_CA
            if (isLanguageNotAvailable(langResult)) {
                Log.w(TAG, "Langue TTS '${selectedLanguage.displayLanguage}' non supportée. Essai '${fallbackLanguage.displayLanguage}'.")
                val fallbackResult = tts?.setLanguage(fallbackLanguage) // FR
                if (isLanguageNotAvailable(fallbackResult)) {
                    Log.w(TAG, "Langue TTS '${fallbackLanguage.displayLanguage}' non supportée. Essai '${finalFallbackLanguage.displayLanguage}'.")
                    val finalFallbackResult = tts?.setLanguage(finalFallbackLanguage) // US
                    if (isLanguageNotAvailable(finalFallbackResult)) {
                        Log.e(TAG, "Langue TTS finale fallback '${finalFallbackLanguage.displayLanguage}' non supportée ! TTS désactivé.")
                        isTtsInitialized = false; tts?.shutdown(); tts = null; return
                    } else { Log.i(TAG,"Langue TTS réglée sur fallback final: ${finalFallbackLanguage.displayLanguage}") }
                } else { Log.i(TAG,"Langue TTS réglée sur fallback: ${fallbackLanguage.displayLanguage}") }
            } else { Log.i(TAG, "Langue TTS réglée sur : ${selectedLanguage.displayLanguage}") }

            tts?.setSpeechRate(Config.TTS_DEFAULT_SPEECH_RATE) // Utilise Config
            tts?.setPitch(Config.TTS_DEFAULT_PITCH) // Utilise Config
            tts?.setOnUtteranceProgressListener(ttsProgressListener)
            isTtsInitialized = true
            Log.i(TAG, "TTS prêt.")
        } else {
            Log.e(TAG, "Échec de l'initialisation TTS ! Status: $status")
            isTtsInitialized = false; tts = null
        }
    }

    /** Listener pour les événements TTS */
    private val ttsProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {}
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { Log.e(TAG, "TTS Error (Deprecated): $utteranceId") }
        override fun onError(utteranceId: String?, errorCode: Int) { Log.e(TAG, "TTS Error: $utteranceId, Code=$errorCode") }
    }

    /** Vérifie si le code de retour de setLanguage indique une erreur */
    private fun isLanguageNotAvailable(result: Int?): Boolean {
        return result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED
    }

    // --- Méthode Principale appelée par DepthAnalyzer ---
    fun provideFeedback(currentState: DetectionState) {
        if (!isTtsInitialized || !isSoundPoolLoaded || soundPool == null) {
            return // Ne rien faire si audio pas prêt
        }
        determineAndPlayFeedback(currentState)
        lastProcessedState = currentState // Mémoriser pour prochaine comparaison
    }

    // --- Logique de Décision et de Jeu ---
    private fun determineAndPlayFeedback(currentState: DetectionState) {
        val currentTime = System.currentTimeMillis()
        val lastState = lastProcessedState
        var feedbackPlayedThisFrame = false // Pour gérer la priorité

        // --- Priorité 1 : Obstacle Proche ---
        val isNowAboveThreshold = currentState.maxObstacleDepth > Config.OBSTACLE_CLOSENESS_THRESHOLD
        val wasAlreadyAlerting = isObstacleAlertActive
        val isNowBelowHysteresis = currentState.maxObstacleDepth < (Config.OBSTACLE_CLOSENESS_THRESHOLD * Config.OBSTACLE_HYSTERESIS_FACTOR)
        val significantIncrease = lastState != null &&
                (currentState.maxObstacleDepth - lastState.maxObstacleDepth > Config.OBSTACLE_SIGNIFICANT_DELTA_DEPTH)

        // Déclencher / Redéclencher alerte obstacle
        if (isNowAboveThreshold && (!wasAlreadyAlerting || significantIncrease)) {
            isObstacleAlertActive = true
            if (currentTime - lastObstacleReportTime > Config.MIN_DELAY_OBSTACLE_ALERT_MS) {
                stopLowerPrioritySounds()
                val rate = calculateObstacleRate(currentState.maxObstacleDepth)
                currentObstacleStreamId = playEarcon(obstacleSoundId, rate = rate)
                speakTTS(translateForTTS("Obstacle proche !"), TextToSpeech.QUEUE_FLUSH, "obstacle")
                lastObstacleReportTime = currentTime
                feedbackPlayedThisFrame = true
            }
        }
        // Arrêter l'alerte obstacle
        else if (wasAlreadyAlerting && isNowBelowHysteresis) {
            Log.d(TAG, "Obstacle cleared")
            isObstacleAlertActive = false
            stopSound(currentObstacleStreamId); currentObstacleStreamId = null
        }

        if (feedbackPlayedThisFrame) return // Priorité haute, on s'arrête là pour cette frame

        // --- Priorité 2 : Mur Détecté ---
        val isNowWall = currentState.wallDetected
        val wasWall = lastState?.wallDetected ?: false
        val wallDirectionChanged = wasWall && isNowWall && currentState.wallDirection != lastState?.wallDirection && currentState.wallDirection != WallDirection.NONE

        if (isNowWall && (!wasWall || wallDirectionChanged)) {
            if (currentTime - lastWallReportTime > Config.MIN_DELAY_WALL_ALERT_MS) {
                stopSound(currentPathStreamId); currentPathStreamId = null

                var leftVol = Config.SPATIALIZATION_HIGH_VOLUME; var rightVol = Config.SPATIALIZATION_HIGH_VOLUME
                val ttsMessage = when (currentState.wallDirection) {
                    WallDirection.LEFT -> { leftVol = Config.SPATIALIZATION_HIGH_VOLUME; rightVol = Config.SPATIALIZATION_LOW_VOLUME; "Mur à gauche." }
                    WallDirection.RIGHT -> { leftVol = Config.SPATIALIZATION_LOW_VOLUME; rightVol = Config.SPATIALIZATION_HIGH_VOLUME; "Mur à droite." }
                    WallDirection.FRONT -> "Mur devant."
                    WallDirection.NONE -> "Mur détecté."
                }

                currentWallStreamId = playEarcon(wallSoundId, leftVolume = leftVol, rightVolume = rightVol)
                speakTTS(translateForTTS(ttsMessage), TextToSpeech.QUEUE_ADD, "wall_${currentState.wallDirection}")
                lastWallReportTime = currentTime
                feedbackPlayedThisFrame = true
            }
        }

        // --- Priorité 3 : Changement État Chemin Libre ---
        // Utiliser AnalysisManager.FreePathResult car enum est défini dans cette classe
        val currentPath = currentState.freePathDirection
        val lastPath = lastState?.freePathDirection ?: AnalysisManager.FreePathResult.UNKNOWN
        val pathStateChanged = currentPath != lastPath
        val isNowClearPath = currentPath == AnalysisManager.FreePathResult.LEFT ||
                currentPath == AnalysisManager.FreePathResult.CENTER ||
                currentPath == AnalysisManager.FreePathResult.RIGHT

        // Alerter seulement si l'état change ET devient "clair"
        if (pathStateChanged && isNowClearPath) {
            if (currentTime - lastPathReportTime > Config.MIN_DELAY_PATH_ALERT_MS) {
                val ttsMessage = when(currentPath) {
                    AnalysisManager.FreePathResult.CENTER -> "Chemin libre centre."
                    AnalysisManager.FreePathResult.LEFT -> "Chemin libre à gauche."
                    AnalysisManager.FreePathResult.RIGHT -> "Chemin libre à droite."
                    else -> ""
                }
                if (ttsMessage.isNotEmpty()){
                    currentPathStreamId = playEarcon(pathClearSoundId)
                    speakTTS(translateForTTS(ttsMessage), TextToSpeech.QUEUE_ADD, "path_clear_${currentPath}")
                    lastPathReportTime = currentTime
                }
            }
        }
        // Gérer le cas BLOCKED si changement
        else if (pathStateChanged && currentPath == AnalysisManager.FreePathResult.BLOCKED) {
            Log.d(TAG, "Path became BLOCKED.")
            // Optionnel: jouer son error_beep ou TTS "Chemin bloqué" (avec throttling)
            // if (currentTime - lastPathReportTime > Config.MIN_DELAY_PATH_ALERT_MS) { ... playEarcon(errorSoundId)... }
        }

        // TODO: Placeholder pour future intégration IMU
    }

    /** Calcule le taux de lecture/pitch pour le son d'obstacle */
    private fun calculateObstacleRate(depthValue: Float) : Float {
        val threshold = Config.OBSTACLE_CLOSENESS_THRESHOLD
        val maxDepthForMaxRate = Config.OBSTACLE_MAX_DEPTH_FOR_MAX_RATE
        val minRate = Config.OBSTACLE_SOUND_RATE_MIN
        val maxRate = Config.OBSTACLE_SOUND_RATE_MAX
        if (depthValue <= threshold) return minRate.coerceIn(0.5f, 2.0f) // Retourner min mais dans la plage valide
        val range = maxDepthForMaxRate - threshold
        val depthInRange = (depthValue - threshold).coerceIn(0f, range)
        val ratio = if (range > 0) depthInRange / range else 1f
        return (minRate + ratio * (maxRate - minRate)).coerceIn(0.5f, 2.0f) // Assurer plage valide
    }

    /** Joue un son chargé dans le SoundPool */
    private fun playEarcon(soundId: Int, rate: Float = 1.0f, leftVolume: Float = 1.0f, rightVolume: Float = 1.0f): Int? {
        if (!isSoundPoolLoaded || soundPool == null || soundId <= 0) {
            Log.w(TAG, "SoundPool non prêt ou ID invalide ($soundId) pour playEarcon")
            return null
        }
        val streamId = try {
            soundPool?.play(soundId, leftVolume.coerceIn(0.0f, 1.0f), rightVolume.coerceIn(0.0f, 1.0f), 1, 0, rate.coerceIn(0.5f, 2.0f))
        } catch (e: Exception) { Log.e(TAG, "Erreur SoundPool.play()", e); null }
        if (streamId == null || streamId == 0) { Log.e(TAG, "SoundPool.play a échoué (streamId=$streamId)") }
        return streamId
    }

    /** Arrête un stream SoundPool */
    private fun stopSound(streamId: Int?) {
        if (streamId != null && streamId > 0 && soundPool != null) {
            try { soundPool?.stop(streamId) }
            catch (e: Exception) { Log.e(TAG, "Erreur SoundPool.stop(streamId=$streamId)", e) }
        }
    }

    /** Arrête les sons de priorité inférieure */
    private fun stopLowerPrioritySounds() {
        stopSound(currentWallStreamId); currentWallStreamId = null
        stopSound(currentPathStreamId); currentPathStreamId = null
    }

    /** Fait parler le moteur TTS */
    private fun speakTTS(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD, utteranceIdSuffix: String? = null) {
        if (!isTtsInitialized || tts == null || text.isBlank()) return
        val utteranceId = TTS_UTTERANCE_ID_PREFIX + (utteranceIdSuffix ?: System.currentTimeMillis())
        val params = Bundle()
        try { tts?.speak(text, queueMode, params, utteranceId) }
        catch (e: Exception) { Log.e(TAG, "Erreur lors de tts.speak() pour '$text'", e) }
    }

    /** "Traduit" les messages pour TTS */
    private fun translateForTTS(englishDefault: String, frenchMsg: String? = null): String {
        val useFrench = tts?.language?.language == Locale.FRENCH.language
        val effectiveMsg = if (useFrench && frenchMsg != null) frenchMsg else englishDefault
        // TODO: Remplacer par ressources strings.xml
        return effectiveMsg
    }

    /** Libère les ressources */
    fun shutdown() {
        Log.d(TAG, "Arrêt de FeedbackManager...")
        if (tts != null) {
            try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { Log.e(TAG, "Erreur arrêt TTS", e) }
            finally { tts = null; isTtsInitialized = false }
        }
        if (soundPool != null) {
            try { soundPool?.release() } catch (e: Exception) { Log.e(TAG, "Erreur release SoundPool", e) }
            finally { soundPool = null; isSoundPoolLoaded = false }
        }
        Log.i(TAG, "FeedbackManager arrêté.")
    }
}