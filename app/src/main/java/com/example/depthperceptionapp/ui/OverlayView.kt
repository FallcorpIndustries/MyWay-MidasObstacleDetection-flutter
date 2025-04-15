package com.example.depthperceptionapp.ui

import android.content.Context
import android.graphics.* // Import Canvas, Paint, Color, RectF, PorterDuff
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.depthperceptionapp.analysis.AnalysisManager // For enum access
import com.example.depthperceptionapp.analysis.VisualizationData
import kotlin.math.max
import kotlin.math.min

/**
 * A custom view that draws overlays based on depth analysis results
 * on top of the camera preview.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "OverlayView"
        // Downsampling factor: Process 1 out of every N*N pixels for detailed visualization
        // Increase for better performance, decrease for more detail. Start with 4 or higher.
        private const val DOWNSAMPLE_FACTOR = 4
    }

    // Data to be visualized (updated from Activity/ViewModel)
    private var visualizationData: VisualizationData? = null

    // Reusable Paint objects for different categories
    private val obstaclePaint = Paint().apply {
        color = Color.argb(100, 255, 0, 0) // Semi-transparent Red
        style = Paint.Style.FILL
    }

    private val freePathPaint = Paint().apply {
        color = Color.argb(80, 0, 255, 0) // Semi-transparent Green
        style = Paint.Style.FILL
    }

    private val wallPaint = Paint().apply { // Simple border for now
        color = Color.argb(150, 0, 0, 255) // Semi-transparent Blue
        style = Paint.Style.STROKE
        strokeWidth = 8f // Make border thicker
    }

    private val textPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 40f // Adjust size as needed
        style = Paint.Style.FILL
        setShadowLayer(2f, 1f, 1f, Color.BLACK) // Add shadow for better visibility
    }

    // Variables for coordinate mapping
    private var viewWidth = 0
    private var viewHeight = 0
    private var scaleX = 1.0f
    private var scaleY = 1.0f
    private var lastDepthMapWidth = 0
    private var lastDepthMapHeight = 0

    // RectF for drawing points/rects efficiently
    private val pointRect = RectF()

    /**
     * Updates the data used for drawing the overlay.
     * Must be called on the UI thread. Triggers a redraw.
     * @param data The latest visualization data, or null to clear.
     */
    fun updateOverlay(data: VisualizationData?) {
        // Check if data has actually changed significantly enough to warrant update
        // (Simple check for now, could compare timestamps or content hash)
        // if (this.visualizationData == data) return // Basic check

        this.visualizationData = data
        // Check if mapping needs recalculation (e.g., first data or depth map size changed)
        if (data != null && (data.depthMapWidth != lastDepthMapWidth || data.depthMapHeight != lastDepthMapHeight)) {
            updateCoordinateMapping()
            lastDepthMapWidth = data.depthMapWidth
            lastDepthMapHeight = data.depthMapHeight
        }
        // Trigger a redraw
        postInvalidate() // Use postInvalidate as updateOverlay might be called from bg thread via runOnUiThread
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            viewWidth = w
            viewHeight = h
            Log.d(TAG, "OverlayView size changed: $viewWidth x $viewHeight")
            // Update scaling factors when view size changes
            updateCoordinateMapping()
        }
    }

    /**
     * Calculates the scaling factors to map depth map coordinates to view coordinates.
     */
    private fun updateCoordinateMapping() {
        val data = visualizationData ?: return // Need data to know depth map size
        if (viewWidth == 0 || viewHeight == 0 || data.depthMapWidth <= 0 || data.depthMapHeight <= 0) {
            scaleX = 1.0f
            scaleY = 1.0f
            return
        }
        scaleX = viewWidth.toFloat() / data.depthMapWidth.toFloat()
        scaleY = viewHeight.toFloat() / data.depthMapHeight.toFloat()
        // Log.d(TAG, "Updated scale factors: scaleX=$scaleX, scaleY=$scaleY for depth map ${data.depthMapWidth}x${data.depthMapHeight}")
    }

    /**
     * Maps a coordinate from the depth map grid to the view's coordinate system.
     * IMPORTANT CAVEAT in documentation.
     */
    private fun mapToViewCoordinates(depthX: Int, depthY: Int): Pair<Float, Float> {
        val viewX = depthX.toFloat() * scaleX + scaleX / 2f // Center the point/rect within the scaled cell
        val viewY = depthY.toFloat() * scaleY + scaleY / 2f
        return Pair(viewX, viewY)
    }


    /**
     * The main drawing method. Called by the system when the view needs to be redrawn.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Get current data - if null or view not ready, clear and exit
        val currentData = visualizationData
        if (currentData == null || viewWidth <= 0 || viewHeight <= 0 || scaleX <= 0f || scaleY <= 0f) {
            // Clear the canvas if no data
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            return
        }

        // Clear previous drawings for this frame
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)


        // --- Choose Drawing Method ---
        // Option A: Draw Downsampled Points/Rects
        // drawDetailedOverlay(canvas, currentData)

        // Option B: Draw Simplified Indicative Regions (Less computation, often clearer)
        drawIndicativeOverlay(canvas, currentData)

    }

    /** Draws detailed pixel overlays (downsampled) */
    private fun drawDetailedOverlay(canvas: Canvas, data: VisualizationData) {
        // 1. Draw Obstacle Pixels (Downsampled)
        val obstacleSize = max(2f, min(scaleX, scaleY) * 1.5f) // Adjust point size based on scaling
        data.obstaclePixels.forEachIndexed { index, coord ->
            if (index % (DOWNSAMPLE_FACTOR * DOWNSAMPLE_FACTOR) == 0) { // Draw 1 out of N*N points
                val (viewX, viewY) = mapToViewCoordinates(coord.first, coord.second)
                pointRect.set(viewX - obstacleSize / 2, viewY - obstacleSize / 2, viewX + obstacleSize / 2, viewY + obstacleSize / 2)
                canvas.drawRect(pointRect, obstaclePaint)
            }
        }

        // 2. Draw Free Path Pixels (Downsampled) - Optional, can be visually noisy
        val freePathSize = max(2f, min(scaleX, scaleY) * 1.5f)
        data.freePathPixels.forEachIndexed { index, coord ->
            if (index % (DOWNSAMPLE_FACTOR * DOWNSAMPLE_FACTOR * 2) == 0) { // Sample even less for free path
                val (viewX, viewY) = mapToViewCoordinates(coord.first, coord.second)
                pointRect.set(viewX - freePathSize / 2, viewY - freePathSize / 2, viewX + freePathSize / 2, viewY + freePathSize / 2)
                canvas.drawRect(pointRect, freePathPaint)
            }
        }
    }

    /** Draws simplified regions indicating overall state */
    private fun drawIndicativeOverlay(canvas: Canvas, data: VisualizationData) {
        // 1. Indicate free path direction at the bottom-center
        val roiYStartView = viewHeight / 2f // Draw indicators below midpoint
        val indicatorHeight = viewHeight * 0.1f // Height of indicator region
        val indicatorY = viewHeight - indicatorHeight - 20f // Position near bottom

        when (data.detectionState.freePathDirection) {
            AnalysisManager.FreePathResult.LEFT -> canvas.drawRect(20f, indicatorY, viewWidth / 3f, indicatorY + indicatorHeight, freePathPaint)
            AnalysisManager.FreePathResult.CENTER -> canvas.drawRect(viewWidth / 3f, indicatorY, 2 * viewWidth / 3f, indicatorY + indicatorHeight, freePathPaint)
            AnalysisManager.FreePathResult.RIGHT -> canvas.drawRect(2 * viewWidth / 3f, indicatorY, viewWidth.toFloat() - 20f, indicatorY + indicatorHeight, freePathPaint)
            AnalysisManager.FreePathResult.BLOCKED -> {
                // Draw red indicator across bottom for blocked
                canvas.drawRect(20f, indicatorY, viewWidth.toFloat() - 20f, indicatorY + indicatorHeight, obstaclePaint)
            }
            else -> {} // Unknown or no specific path
        }

        // 2. Draw border if wall detected
        if (data.detectionState.wallDetected) {
            canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), wallPaint)
        }

        // 3. Indicate if obstacle is close (e.g., flashing red overlay or text)
        // Use the threshold from AnalysisManager if possible, otherwise use a reasonable default
        // Note: We don't have direct access to analysisManager.obstacleClosenessThreshold here.
        // We rely on the DetectionState passed within VisualizationData.
        // Let's just check the maxObstacleDepth itself for simplicity, assuming higher means closer.
        // We need a threshold relative to expected dequantized values.
        val obstacleVizThreshold = 1000.0f // EXAMPLE threshold - TUNE BASED ON LOGS!
        if (data.detectionState.maxObstacleDepth > obstacleVizThreshold) {
            // Example: Draw large red 'X' or text
            textPaint.color = Color.RED
            textPaint.textSize = 80f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("OBSTACLE", viewWidth / 2f, viewHeight / 2f, textPaint)
            textPaint.color = Color.YELLOW // Reset color
            textPaint.textSize = 40f
            textPaint.textAlign = Paint.Align.LEFT

        }
    }

}