package com.example.depthperceptionapp
// Adaptez le nom du package si nécessaire

/**
 * Objet singleton pour centraliser les constantes de configuration et les paramètres ajustables (tuning).
 */
object Config {

    // --- Paramètres de Calibration Caméra ---
    // IMPORTANT: REMPLACEZ CES VALEURS par celles obtenues via CalibrationActivity!
    const val CAMERA_FX = 1137.1137f // EXEMPLE (Valeur réelle de l'utilisateur gardée)
    const val CAMERA_FY = 1137.3837f // EXEMPLE (Valeur réelle de l'utilisateur gardée)
    const val CAMERA_CX = 683.7822f  // EXEMPLE (Valeur réelle de l'utilisateur gardée)
    const val CAMERA_CY = 335.02698f  // EXEMPLE (Valeur réelle de l'utilisateur gardée)

    // Coefficients de distorsion (Laisser 0.0f si non fournis ou négligeables)
    const val DIST_K1 = 0.0f
    const val DIST_K2 = 0.0f
    const val DIST_P1 = 0.0f
    const val DIST_P2 = 0.0f
    const val DIST_K3 = 0.0f


    // --- Seuils de Détection de Profondeur (MiDaS UINT8 Déquantifié) ---
    // CRITICAL : À ajuster EXPÉRIMENTALEMENT !
    const val OBSTACLE_CLOSENESS_THRESHOLD = 1200.0f // EXEMPLE (Valeur de l'utilisateur gardée) - Plus haut = plus proche
    const val OBSTACLE_HYSTERESIS_FACTOR = 0.9f      // Pour arrêter l'alerte
    const val OBSTACLE_SIGNIFICANT_DELTA_DEPTH = 200.0f // Augmentation pour redéclencher

    const val FREE_PATH_FARNESS_THRESHOLD = 70.0f  // EXEMPLE (Valeur de l'utilisateur gardée) - Plus bas = plus loin


    // --- Paramètres RANSAC (Détection de Murs/Plans) ---
    // Vos valeurs ajustées - gardées
    const val RANSAC_DISTANCE_THRESHOLD = 0.05f
    const val RANSAC_MIN_INLIERS = 1500
    const val RANSAC_MAX_ITERATIONS = 200


    // --- Paramètres d'Estimation du Chemin Libre ---
    // Vos valeurs ajustées - gardées
    const val FREE_PATH_ROI_WIDTH_FRACTION = 0.4f
    const val FREE_PATH_BLOCKED_THRESHOLD_FRACTION = 0.08f


    // --- Paramètre d'Identification de Mur ---
    // Votre valeur ajustée - gardée
    const val WALL_NORMAL_VERTICALITY_THRESHOLD = 0.6f


    // --- Paramètres de l'Overlay de Débogage ---
    // Votre valeur - gardée
    const val DOWNSAMPLE_FACTOR = 4


    // --- Paramètres du Feedback Audio (FeedbackManager) ---
    const val SOUNDPOOL_MAX_STREAMS = 3
    const val TTS_DEFAULT_SPEECH_RATE = 1.0f
    const val TTS_DEFAULT_PITCH = 1.0f
    // Délais minimum (ms) pour throttling
    const val MIN_DELAY_OBSTACLE_ALERT_MS = 1000L // Votre valeur - gardée
    const val MIN_DELAY_WALL_ALERT_MS = 3000L    // Votre valeur - gardée
    const val MIN_DELAY_PATH_ALERT_MS = 4000L    // Votre valeur - gardée
    // Modulation son obstacle
    const val OBSTACLE_SOUND_RATE_MIN = 0.8f
    const val OBSTACLE_SOUND_RATE_MAX = 1.5f
    const val OBSTACLE_MAX_DEPTH_FOR_MAX_RATE = 9000.0f // EXEMPLE (Doit être > seuil obstacle) - À TUNER!
    // Spatialisation
    const val SPATIALIZATION_LOW_VOLUME = 0.3f
    const val SPATIALIZATION_HIGH_VOLUME = 1.0f
}