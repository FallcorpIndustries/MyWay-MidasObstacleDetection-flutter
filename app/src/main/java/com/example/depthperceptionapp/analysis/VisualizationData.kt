package com.example.depthperceptionapp.analysis

/**
 * Data class to hold information needed for visualizing analysis results.
 * Coordinates are relative to the depth map grid (e.g., 0-255 for a 256x256 map).
 *
 * @param timestamp Time when the data was generated.
 * @param depthMapWidth The width of the depth map this data corresponds to.
 * @param depthMapHeight The height of the depth map this data corresponds to.
 * @param obstaclePixels List of (x, y) coordinates identified as obstacles.
 * @param freePathPixels List of (x, y) coordinates identified as part of the free path ROI.
 * @param detectionState The original high-level detection state summary.
 */
data class VisualizationData(
    val timestamp: Long = System.currentTimeMillis(),
    val depthMapWidth: Int,
    val depthMapHeight: Int,
    // Lists of coordinates (x, y) for different categories
    val obstaclePixels: List<Pair<Int, Int>> = emptyList(),
    val freePathPixels: List<Pair<Int, Int>> = emptyList(),
    // Keep high-level state too, as overlay might simplify based on it
    val detectionState: DetectionState
)