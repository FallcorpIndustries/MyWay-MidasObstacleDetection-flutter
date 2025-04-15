package com.example.depthperceptionapp.analysis
// Adaptez le nom du package si nécessaire

import android.util.Log
import com.example.depthperceptionapp.Config // Importer les constantes
import com.example.depthperceptionapp.ndk.NdkBridge
import com.example.depthperceptionapp.analysis.DetectionState // Importer data class externe
import com.example.depthperceptionapp.analysis.VisualizationData // Importer data class externe
import com.example.depthperceptionapp.analysis.WallDirection // Importer enum externe
// PAS d'import pour FreePathResult car défini DANS la classe
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Gère l'analyse de la carte de profondeur. Définit FreePathResult en interne.
 */
class AnalysisManager {

    // --- Enum FreePathResult défini à l'intérieur ---
    enum class FreePathResult { LEFT, CENTER, RIGHT, BLOCKED, UNKNOWN }
    // ---------------------------------------------

    companion object {
        private const val TAG = "AnalysisManager"
        private const val DEBUG_TAG = "MyWayDebug"
        // Zones pour logging
        private val NEAR_ZONE_Y_RANGE = 0.8f..1.0f
        private val NEAR_ZONE_X_RANGE = 0.25f..0.75f
        private val FAR_ZONE_Y_RANGE = 0.4f..0.6f
        private val FAR_ZONE_X_RANGE = 0.4f..0.6f
    }

    // Pas de constantes définies ici, utilise Config

    /** Fonction principale d'analyse */
    fun analyzeDepthMap(depthMapFloatBuffer: FloatBuffer, width: Int, height: Int): VisualizationData {
        val analysisStartTime = System.currentTimeMillis()
        depthMapFloatBuffer.rewind()

        logDepthStatsInZones(depthMapFloatBuffer, width, height)
        depthMapFloatBuffer.rewind()

        val obstaclePixels = mutableListOf<Pair<Int, Int>>()
        val freePathPixels = mutableListOf<Pair<Int, Int>>()

        // 1. Obstacles
        val maxObstacleDepth = detectObstaclesAndGetCoords(depthMapFloatBuffer, width, height, obstaclePixels)
        Log.d(DEBUG_TAG, "Obstacle Check - Max Depth: %.2f (Thresh: %.2f)".format(maxObstacleDepth, Config.OBSTACLE_CLOSENESS_THRESHOLD))
        depthMapFloatBuffer.rewind()

        // 2. Murs (RANSAC)
        val detectedPlanes = detectWalls(depthMapFloatBuffer, width, height)
        depthMapFloatBuffer.rewind()

        // 3. Identification Mur/Direction
        var wallDetected = false
        var wallDirection = WallDirection.NONE
        var wallNormalStr = "N/A"
        if (detectedPlanes != null && detectedPlanes.isNotEmpty()) {
            val plane = detectedPlanes[0]
            if (plane.size >= 5) {
                val nx = plane[0]; val ny = plane[1]; val nz = plane[2]; val inliers = plane[4].toInt()
                wallNormalStr = "Norm(%.2f, %.2f, %.2f), Inliers:%d".format(nx, ny, nz, inliers)
                wallDetected = identifyWallFromNormal(nx, ny, nz)
                if (wallDetected) {
                    wallDirection = estimateWallDirection(nx, ny)
                }
                Log.d(DEBUG_TAG, "RANSAC Result - ${wallNormalStr} -> WallDetected: $wallDetected, WallDirection: $wallDirection")
            } else {
                Log.w(TAG, "RANSAC plane array size unexpected: ${plane.size}")
                Log.d(DEBUG_TAG, "RANSAC Result - Plane data format error.")
            }
        } else {
            Log.d(DEBUG_TAG, "RANSAC Result - No significant plane found.")
        }

        // 4. Chemin Libre
        // Appelle la fonction qui retourne le type interne AnalysisManager.FreePathResult
        val freePathResult = estimateFreePathAndGetCoords(depthMapFloatBuffer, width, height, freePathPixels)
        Log.d(DEBUG_TAG, "Free Path Check - Direction: $freePathResult (Thresh: ${Config.FREE_PATH_FARNESS_THRESHOLD}), Far Pixels: ${freePathPixels.size}")
        depthMapFloatBuffer.rewind()

        // 5. Agrégation
        val finalDetectionState = DetectionState(
            timestamp = System.currentTimeMillis(),
            maxObstacleDepth = maxObstacleDepth,
            wallDetected = wallDetected,
            wallDirection = wallDirection,
            freePathDirection = freePathResult // Assigner la valeur de type interne
        )
        val finalVisualizationData = VisualizationData(
            timestamp = finalDetectionState.timestamp, depthMapWidth = width, depthMapHeight = height,
            obstaclePixels = obstaclePixels, freePathPixels = freePathPixels,
            detectionState = finalDetectionState
        )
        return finalVisualizationData
    }


    /** Loggue les stats de profondeur dans des zones */
    private fun logDepthStatsInZones(depthMap: FloatBuffer, width: Int, height: Int) {
        logDepthStatsForZone("NearZone (Lower Center)", depthMap, width, height, NEAR_ZONE_X_RANGE, NEAR_ZONE_Y_RANGE)
        logDepthStatsForZone("FarZone (Center Middle)", depthMap, width, height, FAR_ZONE_X_RANGE, FAR_ZONE_Y_RANGE)
    }

    /** Calcule et loggue les stats pour une zone */
    private fun logDepthStatsForZone(zoneName: String, depthMap: FloatBuffer, width: Int, height: Int, xRange: ClosedFloatingPointRange<Float>, yRange: ClosedFloatingPointRange<Float>) {
        var minVal = Float.MAX_VALUE; var maxVal = Float.MIN_VALUE
        var sumVal = 0.0; var count = 0
        val xStart = (width * xRange.start).toInt().coerceIn(0, width - 1)
        val xEnd = (width * xRange.endInclusive).toInt().coerceIn(xStart, width)
        val yStart = (height * yRange.start).toInt().coerceIn(0, height - 1)
        val yEnd = (height * yRange.endInclusive).toInt().coerceIn(yStart, height)

        depthMap.rewind()
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val index = y * width + x
                if (index >= depthMap.limit()) continue
                val depthValue = depthMap.get(index)
                if (depthValue.isFinite() && depthValue > 0) {
                    if (depthValue < minVal) minVal = depthValue
                    if (depthValue > maxVal) maxVal = depthValue
                    sumVal += depthValue
                    count++
                }
            }
        }
        if (count > 0) {
            val avgVal = sumVal / count
            Log.d(DEBUG_TAG, "$zoneName Stats - Min: %.2f, Max: %.2f, Avg: %.2f (#Pts: $count)".format(minVal, maxVal, avgVal))
        } else {
            Log.d(DEBUG_TAG, "$zoneName Stats - No valid points.")
        }
    }

    /** Détecte obstacles et collecte coordonnées */
    fun detectObstaclesAndGetCoords(
        depthMap: FloatBuffer, width: Int, height: Int,
        outObstacleCoords: MutableList<Pair<Int, Int>>
    ): Float {
        var maxObstacleDepth = 0.0f
        outObstacleCoords.clear(); depthMap.rewind()
        val threshold = Config.OBSTACLE_CLOSENESS_THRESHOLD
        val numPixels = width * height
        for (i in 0 until numPixels) {
            val depthValue = depthMap.get(i)
            if (depthValue > threshold) {
                if (depthValue > maxObstacleDepth) maxObstacleDepth = depthValue
                outObstacleCoords.add(Pair(i % width, i / width))
            }
        }
        return maxObstacleDepth
    }

    /** Appelle NDK RANSAC */
    fun detectWalls(depthMap: FloatBuffer, width: Int, height: Int): Array<FloatArray>? {
        return try {
            NdkBridge.detectWallsRansac(
                depthMap, width, height, Config.CAMERA_FX, Config.CAMERA_FY, Config.CAMERA_CX, Config.CAMERA_CY,
                Config.RANSAC_DISTANCE_THRESHOLD, Config.RANSAC_MIN_INLIERS, Config.RANSAC_MAX_ITERATIONS
            )
        } catch (e: Exception) { Log.e(TAG, "Erreur NDK detectWallsRansac", e); null }
    }

    /** Identifie mur depuis normale */
    private fun identifyWallFromNormal(nx: Float, ny: Float, nz: Float): Boolean {
        val normalLengthXY = sqrt(nx * nx + ny * ny); if (normalLengthXY < 1e-6) return false
        return (abs(nz) / normalLengthXY) < Config.WALL_NORMAL_VERTICALITY_THRESHOLD
    }

    /** Estime direction mur */
    private fun estimateWallDirection(nx: Float, ny: Float): WallDirection {
        val angleThreshold = 0.3f
        return when {
            nx < -angleThreshold -> WallDirection.RIGHT
            nx > angleThreshold -> WallDirection.LEFT
            else -> WallDirection.FRONT
        }
    }

    /** Estime chemin libre et collecte coordonnées */
    // La signature de retour utilise maintenant le type interne AnalysisManager.FreePathResult
    fun estimateFreePathAndGetCoords(
        depthMap: FloatBuffer, width: Int, height: Int,
        outFreePathCoords: MutableList<Pair<Int, Int>>
    ): AnalysisManager.FreePathResult { // <<< TYPE DE RETOUR COMPLET
        outFreePathCoords.clear(); depthMap.rewind()

        val roiYStart = height / 2; val roiYEnd = height
        val roiXWidth = (width * Config.FREE_PATH_ROI_WIDTH_FRACTION).toInt()
        val roiXStart = (width - roiXWidth) / 2; val roiXEnd = roiXStart + roiXWidth
        val leftColEnd = roiXStart + roiXWidth / 3; val centerColEnd = roiXStart + 2 * roiXWidth / 3
        var farPixelsLeft = 0; var farPixelsCenter = 0; var farPixelsRight = 0
        var totalPixelsInRoi = 0
        val threshold = Config.FREE_PATH_FARNESS_THRESHOLD

        // --- CORRIGER LA LIGNE SUIVANTE ---
        for (y in roiYStart until roiYEnd) {
            // Utiliser les variables définies : roiXStart et roiXEnd
            for (x in roiXStart until roiXEnd) {
                // --- FIN CORRECTION ---
                totalPixelsInRoi++
                val index = y * width + x
                if (index >= depthMap.limit()) continue
                val depthValue = depthMap.get(index)
                if (depthValue < threshold) {
                    outFreePathCoords.add(Pair(x, y))
                    when {
                        x < leftColEnd -> farPixelsLeft++
                        x < centerColEnd -> farPixelsCenter++
                        else -> farPixelsRight++
                    }
                }
            }
        }

        val totalFarPixels = farPixelsLeft + farPixelsCenter + farPixelsRight
        if (totalPixelsInRoi == 0 || totalFarPixels < totalPixelsInRoi * Config.FREE_PATH_BLOCKED_THRESHOLD_FRACTION) {
            // Utiliser le nom complet car on est DANS la classe AnalysisManager
            return AnalysisManager.FreePathResult.BLOCKED
        }

        return when {
            farPixelsCenter >= farPixelsLeft && farPixelsCenter >= farPixelsRight -> AnalysisManager.FreePathResult.CENTER
            farPixelsLeft >= farPixelsCenter && farPixelsLeft >= farPixelsRight -> AnalysisManager.FreePathResult.LEFT
            else -> AnalysisManager.FreePathResult.RIGHT
        }
    }

} // Fin de la classe AnalysisManager