package com.example.depthperceptionapp.analysis

import android.util.Log
import com.example.depthperceptionapp.ndk.NdkBridge
import com.example.depthperceptionapp.tflite.DepthPredictor // For accessing output dims
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Data class to hold the combined results of the analysis for a single frame.
 * Represents the perceived state of the environment at a specific moment.
 */
data class DetectionState(
    val timestamp: Long = System.currentTimeMillis(), // Time when the state was generated
    val maxObstacleDepth: Float = 0.0f, // Highest relative inverse depth of detected obstacles (higher = closer)
    val wallDetected: Boolean = false,   // Flag indicating if a significant plane likely representing a wall was found
    // TODO: Add wall details (e.g., direction, plane equation) if needed later
    val freePathDirection: AnalysisManager.FreePathResult = AnalysisManager.FreePathResult.UNKNOWN // Enum indicating the estimated clearest path
)

/**
 * Manages the analysis of the depth map produced by the TFLite model.
 * This class orchestrates the different analysis steps:
 * - Obstacle detection (basic thresholding) + Coord Collection
 * - Wall detection (using NDK RANSAC)
 * - Free path estimation (simple ROI analysis) + Coord Collection
 * Returns VisualizationData containing detailed results.
 *
 * IMPORTANT: All analysis methods operating on the depth map should ideally be called
 * from a background thread/coroutine.
 */
class AnalysisManager {

    companion object {
        private const val TAG = "AnalysisManager"
    }

    /*
     * ============================================================
     * == Key Configurable Parameters, Defaults, and Tuning Notes ==
     * ============================================================
     * | Parameter                  | Default Value | Tuning Notes                                                                    | Location      |
     * |----------------------------|---------------|---------------------------------------------------------------------------------|---------------|
     * | Camera Intrinsics (fx)     | 525.0f        | !! PLACEHOLDER !! Calibrate per device/camera. Focal length X (pixels).        | Analysis Mgr. |
     * | Camera Intrinsics (fy)     | 525.0f        | !! PLACEHOLDER !! Calibrate per device/camera. Focal length Y (pixels).        | Analysis Mgr. |
     * | Camera Intrinsics (cx)     | 128.0f        | !! PLACEHOLDER !! Calibrate per device/camera. Principal point X (pixels, w=256).| Analysis Mgr. |
     * | Camera Intrinsics (cy)     | 128.0f        | !! PLACEHOLDER !! Calibrate per device/camera. Principal point Y (pixels, h=256).| Analysis Mgr. |
     * | RANSAC Dist. Threshold     | 0.05f         | !! TUNE !! Max distance for inliers (approx meters if depth was metric). Affects sensitivity. | Analysis Mgr. |
     * | RANSAC Min Inliers         | 500           | !! TUNE !! Min points to form a plane. Depends on point cloud density/wall size. | Analysis Mgr. |
     * | RANSAC Max Iterations      | 100           | !! TUNE !! Trade-off: performance vs. finding optimal plane robustness.           | Analysis Mgr. |
     * | Obstacle Closeness Thresh. | 1500.0f       | !! TUNE !! Relative inverse depth threshold (higher = closer). ADJUST FOR DEQUANTIZED SCALE! | Analysis Mgr. |
     * | Free Path Farness Thresh.  | 0.2f          | !! TUNE !! Relative inverse depth threshold (lower = farther). ADJUST FOR DEQUANTIZED SCALE! | Analysis Mgr. |
     * | Free Path ROI % Width      | 0.6f          | !! TUNE !! Horizontal portion of view for free path check (60% of width).       | Analysis Mgr. |
     * | Free Path Blocked Thresh.  | 0.1f          | !! TUNE !! Min percentage of ROI pixels needed to be 'far' to not be 'blocked'. | Analysis Mgr. |
     * | Wall Normal Vert Thresh.   | 0.8f          | !! TUNE !! Ratio (Z / XY) of normal vector components for wall check (lower = more vertical). | Analysis Mgr. |
     * | Input Norm. Mean           | N/A (UINT8)   | !! VERIFY MODEL REQS !! Usually 0 for UINT8 models.                               | DepthAnalyzer |
     * | Input Norm. Std            | N/A (UINT8)   | !! VERIFY MODEL REQS !! Usually 1 (or 255 if scaling) for UINT8. Raw pixels used now. | DepthAnalyzer |
     * | TFLite Num Threads         | 4             | !! TUNE !! Based on device CPU cores and performance testing.                     | DepthPredictor|
     * | TTS Throttle Millis        | 1500L         | !! TUNE !! Desired feedback frequency vs. user overload.                          | Feedback Mgr. |
     * | TTS Speech Rate            | 1.0f          | !! TUNE !! For user preference (1.0 = normal).                                  | Feedback Mgr. |
     * | TTS Pitch                  | 1.0f          | !! TUNE !! For user preference (1.0 = normal).                                  | Feedback Mgr. |
     */

    // --- Placeholder Camera Intrinsics ---
    private val DEFAULT_FX = 525.0f // Needs Calibration!
    private val DEFAULT_FY = 525.0f // Needs Calibration!
    private val DEFAULT_CX = 128.0f // Needs Calibration! (Relative to 256 width)
    private val DEFAULT_CY = 128.0f // Needs Calibration! (Relative to 256 height)

    // --- RANSAC Parameters ---
    private val RANSAC_DISTANCE_THRESHOLD = 0.05f // TUNE
    private val RANSAC_MIN_INLIERS = 500 // TUNE
    private val RANSAC_MAX_ITERATIONS = 100 // TUNE

    // --- Obstacle Detection Parameter ---
    // CRITICAL: Tune this based on observing dequantized maxObstacleDepth logs!
    val obstacleClosenessThreshold = 1500.0f // EXAMPLE - TUNE THIS VALUE!

    // --- Free Path Estimation Parameters ---
    // CRITICAL: Tune this based on observing typical dequantized depth values for far areas.
    private val FREE_PATH_FARNESS_THRESHOLD = 50.0f // EXAMPLE - TUNE THIS VALUE! Start low, maybe increase.
    private val FREE_PATH_ROI_WIDTH_FRACTION = 0.6f // TUNE
    private val FREE_PATH_BLOCKED_THRESHOLD_FRACTION = 0.1f // TUNE

    // --- Wall Identification Parameter ---
    private val WALL_NORMAL_VERTICALITY_THRESHOLD = 0.8f // TUNE


    /**
     * Orchestrates the analysis pipeline, returning detailed VisualizationData.
     *
     * @param depthMapFloatBuffer Dequantized FloatBuffer output from DepthPredictor.
     * @param width Width of the depth map (e.g., 256).
     * @param height Height of the depth map (e.g., 256).
     * @return VisualizationData summarizing findings and pixel coordinates.
     */
    fun analyzeDepthMap(depthMapFloatBuffer: FloatBuffer, width: Int, height: Int): VisualizationData {
        val analysisStartTime = System.currentTimeMillis()
        depthMapFloatBuffer.rewind()

        /*
        *****************************************************
        ** FUTURE EXPANSION POINT: OBJECT DETECTION        **
        *****************************************************
         ... (Keep comment block) ...
        *****************************************************
        */

        // --- Prepare lists to hold coordinates ---
        val obstaclePixels = mutableListOf<Pair<Int, Int>>()
        val freePathPixels = mutableListOf<Pair<Int, Int>>()

        // --- Perform Analysis and Collect Coordinates ---

        // 1. Detect Obstacles & Collect Coords
        val maxObstacleDepth = detectObstaclesAndGetCoords(depthMapFloatBuffer, width, height, obstaclePixels)
        Log.d(TAG, "Obstacle Detection - Max Depth: $maxObstacleDepth (Threshold: $obstacleClosenessThreshold), Pixels: ${obstaclePixels.size}")
        depthMapFloatBuffer.rewind()

        // 2. Detect Walls (RANSAC via NDK)
        val detectedPlanes = detectWalls(depthMapFloatBuffer, width, height)
        depthMapFloatBuffer.rewind()

        // 3. Identify if a wall-like plane was found
        val wallDetected = identifyWalls(detectedPlanes)
        Log.d(TAG, "Wall Identification - Detected: $wallDetected")

        // 4. Estimate Free Path & Collect Coords
        val freePathResult = estimateFreePathAndGetCoords(depthMapFloatBuffer, width, height, freePathPixels)
        Log.d(TAG, "Free Path Estimation - Result: $freePathResult, Pixels: ${freePathPixels.size}")
        depthMapFloatBuffer.rewind()


        val analysisEndTime = System.currentTimeMillis()
        // Log.d(TAG, "Full Depth Analysis Time: ${analysisEndTime - analysisStartTime} ms") // Can be verbose

        // 5. Aggregate Results into DetectionState and VisualizationData
        val detectionState = DetectionState(
            timestamp = System.currentTimeMillis(),
            maxObstacleDepth = maxObstacleDepth,
            wallDetected = wallDetected,
            freePathDirection = freePathResult
        )

        return VisualizationData(
            timestamp = detectionState.timestamp,
            depthMapWidth = width,
            depthMapHeight = height,
            obstaclePixels = obstaclePixels,
            freePathPixels = freePathPixels,
            detectionState = detectionState
        )
    }


    /**
     * Detects obstacles based on threshold AND collects their coordinates.
     * @param depthMap Dequantized depth map FloatBuffer (position 0).
     * @param width Depth map width.
     * @param height Depth map height.
     * @param outObstacleCoords Mutable list to be populated with (x, y) coords of obstacle pixels.
     * @return The maximum relative inverse depth value found for obstacles.
     */
    fun detectObstaclesAndGetCoords(
        depthMap: FloatBuffer,
        width: Int,
        height: Int,
        outObstacleCoords: MutableList<Pair<Int, Int>> // Output list
    ): Float {
        var maxObstacleDepth = 0.0f
        outObstacleCoords.clear()
        depthMap.rewind()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                // Ensure index is within buffer limits before getting
                if (index >= depthMap.limit()) continue
                val depthValue = depthMap.get(index)

                if (depthValue > obstacleClosenessThreshold) {
                    if (depthValue > maxObstacleDepth) {
                        maxObstacleDepth = depthValue
                    }
                    outObstacleCoords.add(Pair(x, y))
                }
            }
        }
        return maxObstacleDepth
    }


    /** Calls the native RANSAC implementation. */
    fun detectWalls(depthMap: FloatBuffer, width: Int, height: Int): Array<FloatArray>? {
        // Log.d(TAG, "Calling NDK RANSAC with Intrinsics (PLACEHOLDERS!) and Params...") // Can be verbose
        val planes = NdkBridge.detectWallsRansac(
            depthMap, width, height,
            DEFAULT_FX, DEFAULT_FY, DEFAULT_CX, DEFAULT_CY, // Use PLACEHOLDER intrinsics
            RANSAC_DISTANCE_THRESHOLD, RANSAC_MIN_INLIERS, RANSAC_MAX_ITERATIONS
        )
        // Log results for debugging if needed
        // if (planes != null && planes.isNotEmpty()) {
        //     Log.d(TAG, "NDK RANSAC returned ${planes.size} plane(s).")
        //     // Log plane details...
        // } else {
        //     Log.d(TAG, "NDK RANSAC returned null or empty.")
        // }
        return planes
    }

    /** Interprets RANSAC results to identify walls based on normal vector orientation. */
    fun identifyWalls(detectedPlanes: Array<FloatArray>?): Boolean {
        if (detectedPlanes == null || detectedPlanes.isEmpty()) {
            return false
        }
        val plane = detectedPlanes[0]
        if (plane.size < 5) {
            Log.w(TAG, "Detected plane array has unexpected size: ${plane.size}")
            return false
        }
        val a = plane[0]; val b = plane[1]; val c = plane[2]
        val normalLengthXY = sqrt(a * a + b * b)
        if (normalLengthXY < 1e-6) { // Avoid division by zero, normal is mostly vertical
            return false
        }
        val normalLengthZ = abs(c)
        return (normalLengthZ / normalLengthXY) < WALL_NORMAL_VERTICALITY_THRESHOLD
    }

    // Enum for free path result
    enum class FreePathResult { LEFT, CENTER, RIGHT, BLOCKED, UNKNOWN }

    /**
     * Estimates free path direction based on ROI analysis AND collects "far" pixel coordinates.
     * @param depthMap Dequantized depth map FloatBuffer (position 0).
     * @param width Depth map width.
     * @param height Depth map height.
     * @param outFreePathCoords Mutable list to be populated with (x, y) coords of free path pixels in ROI.
     * @return FreePathResult enum indicating estimated direction.
     */
    fun estimateFreePathAndGetCoords(
        depthMap: FloatBuffer,
        width: Int,
        height: Int,
        outFreePathCoords: MutableList<Pair<Int, Int>> // Output list
    ): FreePathResult {
        outFreePathCoords.clear()
        depthMap.rewind()

        // --- ROI Definition ---
        val roiYStart = height / 2
        val roiYEnd = height
        val roiXWidth = (width * FREE_PATH_ROI_WIDTH_FRACTION).toInt()
        val roiXStart = (width - roiXWidth) / 2
        val roiXEnd = roiXStart + roiXWidth

        // --- Column Analysis ---
        val leftColEnd = roiXStart + roiXWidth / 3
        val centerColEnd = roiXStart + 2 * roiXWidth / 3
        var farPixelsLeft = 0
        var farPixelsCenter = 0
        var farPixelsRight = 0
        var totalPixelsInRoi = 0

        for (y in roiYStart until roiYEnd) {
            for (x in roiXStart until roiXEnd) {
                totalPixelsInRoi++
                val index = y * width + x
                if (index >= depthMap.limit()) continue
                val depthValue = depthMap.get(index)

                // Lower inverse depth = farther away
                if (depthValue < FREE_PATH_FARNESS_THRESHOLD) {
                    outFreePathCoords.add(Pair(x, y))
                    when {
                        x < leftColEnd -> farPixelsLeft++
                        x < centerColEnd -> farPixelsCenter++
                        else -> farPixelsRight++
                    }
                }
            }
        }

        // --- Determine Result ---
        val totalFarPixels = farPixelsLeft + farPixelsCenter + farPixelsRight
        if (totalPixelsInRoi == 0 || totalFarPixels < totalPixelsInRoi * FREE_PATH_BLOCKED_THRESHOLD_FRACTION) {
            return FreePathResult.BLOCKED
        }

        return when {
            farPixelsCenter >= farPixelsLeft && farPixelsCenter >= farPixelsRight -> FreePathResult.CENTER
            farPixelsLeft >= farPixelsCenter && farPixelsLeft >= farPixelsRight -> FreePathResult.LEFT
            else -> FreePathResult.RIGHT
        }
    }

} // End of AnalysisManager class