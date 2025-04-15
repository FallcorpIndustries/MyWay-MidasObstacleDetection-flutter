package com.example.depthperceptionapp.analysis
// Adaptez le nom du package si nécessaire

// PAS d'import pour FreePathResult car on utilise le nom complet ci-dessous

/**
 * Enumération représentant la direction estimée d'un mur détecté par rapport à l'utilisateur.
 */
enum class WallDirection {
    LEFT, RIGHT, FRONT, NONE
}

/**
 * Data class représentant l'état agrégé de l'environnement perçu à un instant T.
 */
data class DetectionState(
    val timestamp: Long = System.currentTimeMillis(),
    val maxObstacleDepth: Float = 0.0f,
    val wallDetected: Boolean = false,
    val wallDirection: WallDirection = WallDirection.NONE,
    // Utiliser le nom complet car l'enum est défini DANS AnalysisManager
    val freePathDirection: AnalysisManager.FreePathResult = AnalysisManager.FreePathResult.UNKNOWN
)