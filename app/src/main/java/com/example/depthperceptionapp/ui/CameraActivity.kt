package com.example.depthperceptionapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.depthperceptionapp.analysis.AnalysisManager
import com.example.depthperceptionapp.analysis.DetectionState
// Import VisualizationData
import com.example.depthperceptionapp.analysis.VisualizationData
import com.example.depthperceptionapp.camera.DepthAnalyzer
import com.example.depthperceptionapp.databinding.ActivityCameraBinding
import com.example.depthperceptionapp.feedback.FeedbackManager
import com.example.depthperceptionapp.tflite.DepthPredictor
// Import OverlayView
import com.example.depthperceptionapp.ui.OverlayView
import kotlinx.coroutines.* // Import CoroutineScope
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main activity hosting the camera preview, analysis pipeline, and visualization overlay.
 */
class CameraActivity : AppCompatActivity() {

    // View Binding instance for accessing layout views
    private lateinit var binding: ActivityCameraBinding
    // Reference for the custom overlay view
    private lateinit var overlayView: OverlayView

    // CameraX related variables
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Executor for running ImageAnalysis on a separate background thread
    private lateinit var cameraExecutor: ExecutorService

    // Components for the depth perception pipeline
    private var depthPredictor: DepthPredictor? = null
    private var analysisManager: AnalysisManager? = null
    private var feedbackManager: FeedbackManager? = null

    // Coroutine scope for managing background tasks related to analysis/feedback
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "CameraActivity" // Tag for logging
    }

    // Activity Result Launcher for Camera Permission Request
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i(TAG, "Camera permission granted.")
                setupCamera()
            } else {
                Log.e(TAG, "Camera permission denied.")
                Toast.makeText(
                    this,
                    "Camera permission is required for this app to function.",
                    Toast.LENGTH_LONG
                ).show()
                // finish() // Optionally close the app if permission denied
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflate the layout using View Binding
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "onCreate: Initializing components...")

        // Get reference to the OverlayView from the binding
        overlayView = binding.overlayView

        // Initialize core components
        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            // Initialize components (ensure DepthPredictor handles init errors)
            depthPredictor = DepthPredictor(this)
            analysisManager = AnalysisManager()
            feedbackManager = FeedbackManager(this)

        } catch (e: Exception) {
            Log.e(TAG, "Initialization Error during onCreate: ${e.message}", e)
            Toast.makeText(this, "Failed to initialize core components: ${e.message}", Toast.LENGTH_LONG).show()
            // Consider disabling functionality or closing the app
            return // Exit onCreate if essential components fail
        }

        // Check for camera permission on startup
        checkCameraPermission()

        Log.d(TAG, "onCreate: Initialization complete.")
    }

    // Checks if camera permission is granted. If not, requests it.
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.i(TAG, "Camera permission already granted.")
                setupCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.w(TAG, "Showing permission rationale.")
                Toast.makeText(this, "Camera access is needed to analyze the surroundings.", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                Log.i(TAG, "Requesting camera permission.")
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Sets up the camera provider and binds use cases
    private fun setupCamera() {
        // Check if predictor initialized successfully before setting up camera dependent on it
        if (depthPredictor == null || !depthPredictor!!.isInitialized) {
            Log.e(TAG, "Depth predictor not initialized. Cannot setup camera.")
            Toast.makeText(this, "Model loading failed. Camera disabled.", Toast.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "Setting up camera...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Build Preview Use Case
                previewUseCase = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                        Log.d(TAG, "Preview use case built and surface provider set.")
                    }

                // Build ImageAnalysis Use Case
                imageAnalysisUseCase = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480)) // Consider device capabilities
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        // Set the analyzer, passing the callback to handle results
                        it.setAnalyzer(cameraExecutor, DepthAnalyzer(
                            depthPredictor = depthPredictor!!,
                            analysisManager = analysisManager!!,
                            feedbackManager = feedbackManager!!,
                            analysisScope = analysisScope,
                            // --- Updated onResult Lambda ---
                            onResult = { visData: VisualizationData -> // Receives VisualizationData
                                // The DepthAnalyzer invokes this callback directly.
                                // We need to switch to the UI thread to update UI elements.
                                runOnUiThread {
                                    // Update debug text view using the DetectionState within VisualizationData
                                    binding.debugText.text = formatDebugText(visData.detectionState)
                                    // Update the overlay view with the visualization data
                                    overlayView.updateOverlay(visData)
                                }
                            }
                        ))
                        Log.d(TAG, "ImageAnalysis use case built and analyzer set.")
                    }

                // Bind the use cases to the camera lifecycle
                bindCameraUseCases()

            } catch (exc: Exception) {
                Log.e(TAG, "Error setting up camera provider or use cases: ${exc.message}", exc)
                Toast.makeText(this, "Failed to initialize camera: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this)) // Run listener on main thread
    }

    // Unbinds existing use cases and binds the new ones
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: run { Log.e(TAG, "CameraProvider not available."); return }
        val preview = previewUseCase ?: run { Log.e(TAG, "PreviewUseCase not available."); return }
        val analysis = imageAnalysisUseCase ?: run { Log.e(TAG, "ImageAnalysisUseCase not available."); return }

        try {
            provider.unbindAll()
            Log.d(TAG, "Unbound all previous use cases.")

            // Bind required use cases
            provider.bindToLifecycle(
                this, // LifecycleOwner
                cameraSelector, // Back camera
                preview, // Show preview
                analysis // Analyze frames
            )
            Log.i(TAG, "Camera use cases bound successfully.")

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed: ${exc.message}", exc)
            Toast.makeText(this, "Failed to bind camera: ${exc.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Formats debug text based on DetectionState */
    private fun formatDebugText(state: DetectionState): String {
        // Use the actual threshold from AnalysisManager if possible, or keep it consistent.
        // Make sure this threshold matches the one used for decision making.
        val obstacleThreshold = analysisManager?.obstacleClosenessThreshold ?: 1500.0f // Use tuned threshold
        val obstacleStr = if (state.maxObstacleDepth > obstacleThreshold)
            "Obstacle Close (%.1f)".format(state.maxObstacleDepth) else "Obstacle Far/None"
        val wallStr = if (state.wallDetected) "Wall Detected" else "No Wall"
        val pathStr = "Path: ${state.freePathDirection}"
        // Add timestamp for debugging timing issues
        // val timeStr = "T: ${state.timestamp % 100000}" // Show last few digits of timestamp
        return "$obstacleStr\n$wallStr\n$pathStr" //\n$timeStr"
    }


    override fun onResume() {
        super.onResume()
        // Potentially re-check permissions and setup camera if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && cameraProvider == null) {
            // Only setup if permission granted AND provider isn't already setup
            if (depthPredictor != null && depthPredictor!!.isInitialized) {
                setupCamera()
            } else {
                Log.w(TAG, "onResume: Predictor not ready, cannot setup camera yet.")
                // Maybe re-attempt initialization or show error
            }
        }
        Log.d(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        // Consider pausing analysis explicitly if needed, though lifecycle binding handles camera
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Shutting down...")

        // Cancel background tasks
        analysisScope.cancel("Activity Destroyed")

        // Shutdown camera executor
        cameraExecutor.shutdown()
        Log.d(TAG, "Camera executor shut down.")

        // Release TFLite interpreter
        depthPredictor?.close()
        Log.d(TAG, "DepthPredictor closed.")

        // Shutdown TTS engine
        feedbackManager?.shutdown()
        Log.d(TAG, "FeedbackManager shut down.")

        // Clear overlay on destroy (optional, called from UI thread)
        runOnUiThread { overlayView.updateOverlay(null) }

        // Unbind use cases (CameraX lifecycle should handle this, but explicit call is safe)
        cameraProvider?.unbindAll()
        Log.d(TAG, "Camera use cases unbound.")

        Log.d(TAG, "onDestroy: Shutdown complete.")
    }
}