package com.example.depthperceptionapp.analysis
// Adaptez le nom du package si nécessaire

// Importer DetectionState qui contient maintenant la bonne référence à FreePathResult
import com.example.depthperceptionapp.analysis.DetectionState

/**
 * Data class pour contenir les informations nécessaires à la visualisation.
 */
data class VisualizationData(
    val timestamp: Long = System.currentTimeMillis(),
    val depthMapWidth: Int,
    val depthMapHeight: Int,
    val obstaclePixels: List<Pair<Int, Int>> = emptyList(),
    val freePathPixels: List<Pair<Int, Int>> = emptyList(),
    val detectionState: DetectionState // Contient la version à jour de DetectionState
    // Optionnel: Ajouter les inliers du mur si RANSAC NDK est modifié pour les retourner
    // val wallInlierPixels: List<Pair<Int, Int>> = emptyList()
)