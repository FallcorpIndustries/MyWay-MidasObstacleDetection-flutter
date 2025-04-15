package com.example.depthperceptionapp.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
// Importer AnalysisManager pour accéder à l'enum interne FreePathResult
import com.example.depthperceptionapp.analysis.AnalysisManager
// PAS d'import pour FreePathResult directement
// import com.example.depthperceptionapp.analysis.FreePathResult
import com.example.depthperceptionapp.analysis.VisualizationData
import com.example.depthperceptionapp.Config // Pour lire DOWNSAMPLE_FACTOR si déplacé ici
import kotlin.math.max
import kotlin.math.min

/**
 * Vue personnalisée pour dessiner les overlays de visualisation.
 * Utilise maintenant AnalysisManager.FreePathResult.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "OverlayView"
        // Lire depuis Config maintenant
        // private const val DOWNSAMPLE_FACTOR = 4
    }

    private var visualizationData: VisualizationData? = null
    private val obstaclePaint = Paint().apply { /* ... */ color = Color.argb(100, 255, 0, 0); style = Paint.Style.FILL }
    private val freePathPaint = Paint().apply { /* ... */ color = Color.argb(80, 0, 255, 0); style = Paint.Style.FILL }
    private val wallPaint = Paint().apply { /* ... */ color = Color.argb(150, 0, 0, 255); style = Paint.Style.STROKE; strokeWidth = 8f }
    private val textPaint = Paint().apply { /* ... */ color = Color.YELLOW; textSize = 40f; style = Paint.Style.FILL; setShadowLayer(2f, 1f, 1f, Color.BLACK) }

    private var viewWidth = 0; private var viewHeight = 0
    private var scaleX = 1.0f; private var scaleY = 1.0f
    private var lastDepthMapWidth = 0; private var lastDepthMapHeight = 0
    private val pointRect = RectF()

    fun updateOverlay(data: VisualizationData?) {
        this.visualizationData = data
        if (data != null && (data.depthMapWidth != lastDepthMapWidth || data.depthMapHeight != lastDepthMapHeight)) {
            updateCoordinateMapping()
            lastDepthMapWidth = data.depthMapWidth
            lastDepthMapHeight = data.depthMapHeight
        }
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            viewWidth = w; viewHeight = h
            Log.d(TAG, "OverlayView size: $viewWidth x $viewHeight")
            updateCoordinateMapping()
        }
    }

    private fun updateCoordinateMapping() {
        val data = visualizationData ?: return
        if (viewWidth <= 0 || viewHeight <= 0 || data.depthMapWidth <= 0 || data.depthMapHeight <= 0) {
            scaleX = 1.0f; scaleY = 1.0f; return
        }
        scaleX = viewWidth.toFloat() / data.depthMapWidth.toFloat()
        scaleY = viewHeight.toFloat() / data.depthMapHeight.toFloat()
    }

    private fun mapToViewCoordinates(depthX: Int, depthY: Int): Pair<Float, Float> {
        val viewX = depthX.toFloat() * scaleX + scaleX / 2f
        val viewY = depthY.toFloat() * scaleY + scaleY / 2f
        return Pair(viewX, viewY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentData = visualizationData
        if (currentData == null || viewWidth <= 0 || viewHeight <= 0 || scaleX <= 0f || scaleY <= 0f) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); return
        }
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Choisir une méthode de dessin
        // drawDetailedOverlay(canvas, currentData) // Optionnel: points détaillés
        drawIndicativeOverlay(canvas, currentData) // Recommandé: zones simples
    }

    /** Dessine des points/rectangles détaillés (coûteux) */
    private fun drawDetailedOverlay(canvas: Canvas, data: VisualizationData) {
        val obstacleSize = max(2f, min(scaleX, scaleY) * 1.5f)
        data.obstaclePixels.forEachIndexed { index, coord ->
            // Utiliser Config.DOWNSAMPLE_FACTOR
            if (index % (Config.DOWNSAMPLE_FACTOR * Config.DOWNSAMPLE_FACTOR) == 0) {
                val (viewX, viewY) = mapToViewCoordinates(coord.first, coord.second)
                pointRect.set(viewX - obstacleSize / 2, viewY - obstacleSize / 2, viewX + obstacleSize / 2, viewY + obstacleSize / 2)
                canvas.drawRect(pointRect, obstaclePaint)
            }
        }
        // Optionnel: Dessiner freePathPixels de manière similaire mais moins dense
    }

    /** Dessine des indicateurs simples pour l'état global */
    private fun drawIndicativeOverlay(canvas: Canvas, data: VisualizationData) {
        val roiYStartView = viewHeight / 2f
        val indicatorHeight = viewHeight * 0.1f
        val indicatorY = viewHeight - indicatorHeight - 20f

        // Utiliser AnalysisManager.FreePathResult pour la comparaison
        when (data.detectionState.freePathDirection) {
            AnalysisManager.FreePathResult.LEFT -> canvas.drawRect(20f, indicatorY, viewWidth / 3f, indicatorY + indicatorHeight, freePathPaint)
            AnalysisManager.FreePathResult.CENTER -> canvas.drawRect(viewWidth / 3f, indicatorY, 2 * viewWidth / 3f, indicatorY + indicatorHeight, freePathPaint)
            AnalysisManager.FreePathResult.RIGHT -> canvas.drawRect(2 * viewWidth / 3f, indicatorY, viewWidth.toFloat() - 20f, indicatorY + indicatorHeight, freePathPaint)
            AnalysisManager.FreePathResult.BLOCKED -> {
                canvas.drawRect(20f, indicatorY, viewWidth.toFloat() - 20f, indicatorY + indicatorHeight, obstaclePaint)
            }
            else -> {} // UNKNOWN
        }

        // Mur détecté
        if (data.detectionState.wallDetected) {
            canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), wallPaint)
            // TODO: Affiner dessin du mur basé sur wallDirection ? (ex: ligne verticale gauche/droite)
        }

        // Obstacle proche
        // Utiliser Config.OBSTACLE_CLOSENESS_THRESHOLD pour la logique d'affichage
        if (data.detectionState.maxObstacleDepth > Config.OBSTACLE_CLOSENESS_THRESHOLD) {
            textPaint.color = Color.RED; textPaint.textSize = 80f; textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("OBSTACLE", viewWidth / 2f, viewHeight / 2f, textPaint)
            textPaint.color = Color.YELLOW; textPaint.textSize = 40f; textPaint.textAlign = Paint.Align.LEFT
        }
    }
}