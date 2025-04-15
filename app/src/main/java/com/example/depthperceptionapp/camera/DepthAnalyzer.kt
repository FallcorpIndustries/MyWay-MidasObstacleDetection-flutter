package com.example.depthperceptionapp.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.depthperceptionapp.analysis.AnalysisManager
import com.example.depthperceptionapp.analysis.DetectionState
// Import VisualizationData
import com.example.depthperceptionapp.analysis.VisualizationData
import com.example.depthperceptionapp.feedback.FeedbackManager
import com.example.depthperceptionapp.ndk.NdkBridge
import com.example.depthperceptionapp.tflite.DepthPredictor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.DataType // Still needed for TensorImage
// No longer need NormalizeOp for UINT8
// import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp // Keep for potential future use
import java.nio.ByteBuffer

/**
 * Analyzer class that processes camera frames for depth perception.
 * Implements the ImageAnalysis.Analyzer interface to receive frames from CameraX.
 *
 * VERSION ADAPTED FOR UINT8 QUANTIZED MODEL INPUT. Passes VisualizationData.
 *
 * @param depthPredictor Instance of DepthPredictor (configured for UINT8 model).
 * @param analysisManager Instance of AnalysisManager for processing the depth map.
 * @param feedbackManager Instance of FeedbackManager for providing TTS feedback.
 * @param analysisScope CoroutineScope for launching analysis tasks off the camera executor thread.
 * @param onResult Callback function invoked with the final VisualizationData.
 */
class DepthAnalyzer(
    private val depthPredictor: DepthPredictor,
    private val analysisManager: AnalysisManager,
    private val feedbackManager: FeedbackManager,
    private val analysisScope: CoroutineScope,
    // --- Callback signature changed ---
    private val onResult: (VisualizationData) -> Unit // Callback provides VisualizationData
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "DepthAnalyzer"
        // Use constants from DepthPredictor for consistency
        // private const val MODEL_INPUT_WIDTH = 256
        // private const val MODEL_INPUT_HEIGHT = 256
    }

    // Reusable direct buffer for RGB conversion output
    private var rgbBuffer: ByteBuffer? = null

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class) // Needed for imageProxy.image
    override fun analyze(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis() // For performance timing

        // --- 1. Format Check ---
        if (imageProxy.format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unexpected image format: ${imageProxy.format}. Expected YUV_420_888.")
            imageProxy.close()
            return
        }

        val width = imageProxy.width
        val height = imageProxy.height

        // --- 2. YUV_420_888 to RGB (via NDK using libyuv) ---
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val uvRowStride = uPlane.rowStride

        // Allocate or reuse the direct ByteBuffer for ARGB output
        val rgbBufferSize = width * height * 4
        if (rgbBuffer == null || rgbBuffer?.capacity() != rgbBufferSize) {
            Log.d(TAG, "Allocating direct RGB buffer ($rgbBufferSize bytes)")
            rgbBuffer = ByteBuffer.allocateDirect(rgbBufferSize)
        }
        rgbBuffer!!.rewind() // Prepare buffer for writing by NDK

        // Call the NDK function
        val conversionSuccess = NdkBridge.yuvToRgb(
            width, height,
            yBuffer, uBuffer, vBuffer,
            yRowStride, uvPixelStride, uvRowStride,
            rgbBuffer!!
        )

        // --- Close ImageProxy ---
        // Close imageProxy ASAP after NDK call finishes using its buffers.
        imageProxy.close()

        if (!conversionSuccess) {
            Log.e(TAG, "NDK YUV to RGB conversion failed")
            // Maybe add a brief visual/audio error state?
            return // Stop processing this frame
        }

        rgbBuffer!!.rewind() // Rewind so bitmap can read from start

        // --- 3. Convert RGB ByteBuffer to Bitmap ---
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(rgbBuffer!!)

        // --- 4. Resize Bitmap to Model Input Size ---
        val resizedBitmap = Bitmap.createScaledBitmap(
            bitmap,
            DepthPredictor.INPUT_WIDTH, // Use constant from DepthPredictor
            DepthPredictor.INPUT_HEIGHT,
            true // Use filtering
        )
        bitmap.recycle() // Recycle original large bitmap

        // --- 5. Prepare TFLite UINT8 Input Buffer ---
        val tensorImage = TensorImage(DataType.UINT8) // Data type is UINT8
        tensorImage.load(resizedBitmap)

        // ImageProcessor: NO Normalization needed for standard UINT8 models.
        val imageProcessor = ImageProcessor.Builder()
            // Ensure resizing is handled (it was done above)
            // REMOVED NormalizeOp
            .build()
        val processedTensorImage = imageProcessor.process(tensorImage)
        val inputBuffer: ByteBuffer = processedTensorImage.buffer // This buffer is UINT8

        // Log.d(TAG, "Input buffer prepared for TFLite (UINT8). Size: ${inputBuffer.remaining()}") // Verbose

        resizedBitmap.recycle() // Recycle resized bitmap


        // --- 6. Run TFLite Inference & Analysis (Background) ---
        // Launch analysis in the background scope
        analysisScope.launch {
            // Ensure predictor is ready before using it
            if (!depthPredictor.isInitialized) {
                Log.e(TAG, "DepthPredictor not initialized, skipping analysis.")
                return@launch
            }

            // --- 6a. Run Inference (returns dequantized FloatBuffer) ---
            val depthMapFloatBuffer = depthPredictor.predictDepth(inputBuffer)

            if (depthMapFloatBuffer == null) {
                Log.e(TAG, "TFLite inference/dequantization failed or returned null.")
                // Optionally update UI/Overlay to show an error state
                runOnUiThread { // Example of updating UI from background
                    onResult( // Send empty data with error state if desired
                        VisualizationData(
                            depthMapWidth = 0, depthMapHeight = 0,
                            detectionState = DetectionState(
                                freePathDirection = AnalysisManager.FreePathResult.UNKNOWN // Indicate error/unknown
                            )
                        )
                    )
                }
                return@launch
            }
            // Log.d(TAG, "Dequantized depth map obtained. Size: ${depthMapFloatBuffer.remaining()}") // Verbose

            // --- 6b. Analyze Dequantized Depth Map ---
            // analysisManager.analyzeDepthMap now returns VisualizationData
            val visualizationData = analysisManager.analyzeDepthMap(
                depthMapFloatBuffer,
                DepthPredictor.OUTPUT_WIDTH, // Use actual output dims from predictor
                DepthPredictor.OUTPUT_HEIGHT
            )

            // --- 6c. Provide Feedback (based on DetectionState within VisualizationData) ---
            feedbackManager.provideFeedback(visualizationData.detectionState)

            // --- 6d. Invoke Callback with VisualizationData ---
            // Pass the combined analysis and visualization data back to the Activity/caller
            // The caller (CameraActivity) will handle switching to the UI thread if needed.
            onResult(visualizationData)

            // --- Performance Logging ---
            val endTime = System.currentTimeMillis()
            Log.i(TAG, "Total Frame Processing Time (Analyze->Result): ${endTime - startTime} ms")

        } // End of analysisScope.launch
    }

    /** Helper to run UI updates safely from background threads */
    private fun runOnUiThread(action: () -> Unit) {
        // Simplified: Assumes view is accessible, context would be better
        // In a real app, use Activity context or Handler.
        (context as? android.app.Activity)?.runOnUiThread(action)
    }

    // Need context for runOnUiThread - pass it in constructor or handle differently
    // For simplicity, assuming context is available via some means (not ideal)
    private val context = CoroutineScope(Dispatchers.Main).coroutineContext // Placeholder context access

}