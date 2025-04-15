package com.example.depthperceptionapp.ui
// Adaptez le nom du package si nécessaire

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size // Android Size pour CameraX
import android.view.View // Pour ProgressBar visibility
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.depthperceptionapp.databinding.ActivityCalibrationBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.* // Importe Mat, CvType, Point3, TermCriteria etc.
// Importer OpenCV Size avec alias
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Activité dédiée à la calibration de la caméra en utilisant une mire damier et OpenCV.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var cameraExecutor: ExecutorService

    // --- Paramètres de la mire damier (À AJUSTER) ---
    private val chessboardWidth = 9
    private val chessboardHeight = 6
    private val squareSizeMeters = 0.025f // EXEMPLE
    private val calibrationPatternSize = CvSize(chessboardWidth.toDouble(), chessboardHeight.toDouble())
    private val minFramesForCalibration = 15

    // --- Stockage des points ---
    private val objectPointsList = mutableListOf<Mat>()
    private val imagePointsList = mutableListOf<Mat>()
    private var imageSizeForCalibration: CvSize? = null

    // --- Drapeaux et état ---
    private var captureFrameRequested = false
    private var framesCaptured = 0

    // --- Variables OpenCV réutilisables ---
    // !! CORRECTION: Utiliser lateinit et initialiser dans onCreate APRES OpenCVLoader !!
    private lateinit var matGray: Mat
    private lateinit var matRgba: Mat
    private lateinit var cornersMat: MatOfPoint2f

    companion object {
        private const val TAG = "CalibrationActivity"
        private const val PREFS_NAME = "CalibrationPrefs"
        // Clés SharedPreferences
        private const val PREF_FX = "camera_fx"
        private const val PREF_FY = "camera_fy"
        private const val PREF_CX = "camera_cx"
        private const val PREF_CY = "camera_cy"
        private const val PREF_K1 = "dist_k1"
        private const val PREF_K2 = "dist_k2"
        private const val PREF_P1 = "dist_p1"
        private const val PREF_P2 = "dist_p2"
        private const val PREF_K3 = "dist_k3"
        private const val PREF_REPROJECTION_ERROR = "reprojection_error"
    }

    // Gestionnaire de permission
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i(TAG,"Permission Caméra accordée via launcher.")
                setupCamera()
            } else {
                Toast.makeText(this, "Permission Caméra refusée. Impossible de calibrer.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate")

        // 1. Initialiser OpenCV
        if (OpenCVLoader.initDebug()) {
            Log.i(TAG, "OpenCV chargé avec succès.")

            // --- CORRECTION: Initialisation des Mats APRÈS le chargement réussi ---
            matGray = Mat()
            matRgba = Mat()
            cornersMat = MatOfPoint2f()
            Log.d(TAG, "Matrices OpenCV initialisées.")
            // --------------------------------------------------------------------

        } else {
            Log.e(TAG, "Erreur critique lors du chargement d'OpenCV!")
            Toast.makeText(this, "Impossible de charger OpenCV.", Toast.LENGTH_LONG).show()
            finish() // Quitter si OpenCV ne charge pas
            return
        }

        // Initialiser l'executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configurer les listeners des boutons
        setupButtonClickListeners()

        // Vérifier permission et démarrer caméra
        checkCameraPermission()
    }

    /** Configure les actions des boutons */
    private fun setupButtonClickListeners() {
        binding.buttonCaptureFrame.setOnClickListener {
            Log.d(TAG, "Clic sur 'Capturer Cadre'")
            if (!captureFrameRequested) {
                captureFrameRequested = true
                updateStatus("Demande de capture... Visez la mire.")
            }
        }

        binding.buttonCalibrate.setOnClickListener {
            Log.d(TAG, "Clic sur 'Calculer Calibration' (framesCaptured=$framesCaptured)")
            if (framesCaptured >= minFramesForCalibration) {
                runCalibration() // Lancer la calibration
            } else {
                val remaining = minFramesForCalibration - framesCaptured
                Log.w(TAG, "Clic sur Calibrer mais seulement $framesCaptured/$minFramesForCalibration cadres capturés.")
                Toast.makeText(this, "Encore $remaining cadre(s) valide(s) à capturer.", Toast.LENGTH_SHORT).show()
                updateStatus("Il manque $remaining cadre(s). Utilisez 'Capturer Cadre'. (${framesCaptured}/${minFramesForCalibration})")
            }
        }
        // Désactivé par défaut dans le XML
        binding.buttonCalibrate.isEnabled = false
    }

    /** Vérifie la permission caméra */
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Permission caméra déjà OK.")
            setupCamera()
        } else {
            Log.i(TAG, "Demande de permission caméra...")
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Crée les points 3D de référence pour la mire */
    private fun createObjectPointsForSingleImage(): MatOfPoint3f {
        val cornerPoints = mutableListOf<Point3>()
        for (i in 0 until chessboardHeight) {
            for (j in 0 until chessboardWidth) {
                cornerPoints.add(Point3(j * squareSizeMeters.toDouble(), i * squareSizeMeters.toDouble(), 0.0))
            }
        }
        val objPointsMat = MatOfPoint3f()
        objPointsMat.fromList(cornerPoints)
        return objPointsMat
    }

    /** Configure et démarre CameraX */
    private fun setupCamera() {
        Log.d(TAG, "Configuration CameraX...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val targetResolution = Size(1280, 720) // Résolution CameraX

                val preview = Preview.Builder()
                    .setTargetResolution(targetResolution)
                    .build()
                    .also { it.setSurfaceProvider(binding.previewViewCalibration.surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(targetResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor, ChessboardAnalyzer())

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                Log.i(TAG, "CameraX lié avec succès.")
                updateStatus("Prêt. Visez la mire et utilisez 'Capturer Cadre'. (${framesCaptured}/${minFramesForCalibration})")

            } catch (exc: Exception) {
                Log.e(TAG, "Echec config/liaison CameraX", exc)
                updateStatus("Erreur Caméra: ${exc.message}")
                Toast.makeText(this, "Impossible de démarrer caméra: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Classe interne pour analyser les images CameraX */
    private inner class ChessboardAnalyzer : ImageAnalysis.Analyzer {

        private val termCriteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 30, 0.001)
        private var processing = false

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            if (processing) { imageProxy.close(); return }
            processing = true

            var statusMessage = "Analyse en cours..." // Message par défaut

            try {
                // 1. Conversion ImageProxy (RGBA) -> Mat
                val plane = imageProxy.planes[0]
                val buffer = plane.buffer
                val width = imageProxy.width
                val height = imageProxy.height
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride

                // S'assurer que matRgba est initialisée (devrait l'être par onCreate) et a la bonne taille
                if (!::matRgba.isInitialized || matRgba.width() != width || matRgba.height() != height) {
                    matRgba.release() // Libérer l'ancienne si taille différente
                    matRgba = Mat(height, width, CvType.CV_8UC4)
                    Log.w(TAG, "matRgba réinitialisée car taille/état invalide.")
                }

                // Copier buffer vers Mat
                buffer.rewind()
                val bufferBytes = ByteArray(buffer.remaining())
                buffer.get(bufferBytes)
                if (rowStride == width * pixelStride) {
                    matRgba.put(0, 0, bufferBytes)
                } else {
                    val rowData = ByteArray(width * pixelStride)
                    var bufferOffset = 0
                    for (row in 0 until height) {
                        if (bufferOffset + width * pixelStride <= bufferBytes.size) {
                            System.arraycopy(bufferBytes, bufferOffset, rowData, 0, width * pixelStride)
                            matRgba.put(row, 0, rowData)
                        } else {
                            Log.w(TAG,"Anomalie stride/buffer. Arrêt copie.")
                            throw RuntimeException("Buffer/stride mismatch")
                        }
                        bufferOffset += rowStride
                    }
                }

                // Stocker taille OpenCV
                if (imageSizeForCalibration == null) {
                    imageSizeForCalibration = CvSize(width.toDouble(), height.toDouble())
                    Log.i(TAG, "Taille image: ${imageSizeForCalibration?.width}x${imageSizeForCalibration?.height}")
                }

                // 2. Convertir en Gris
                Imgproc.cvtColor(matRgba, matGray, Imgproc.COLOR_RGBA2GRAY)

                // 3. Détecter Coins
                val found = Calib3d.findChessboardCorners(
                    matGray, calibrationPatternSize, cornersMat,
                    Calib3d.CALIB_CB_ADAPTIVE_THRESH or Calib3d.CALIB_CB_NORMALIZE_IMAGE or Calib3d.CALIB_CB_FAST_CHECK
                )
                Log.d(TAG, "findChessboardCorners result: $found") // Log de vérification

                if (found) {
                    Imgproc.cornerSubPix(matGray, cornersMat, CvSize(11.0, 11.0), CvSize(-1.0, -1.0), termCriteria)

                    if (captureFrameRequested) {
                        captureFrameRequested = false

                        val currentImagePoints = MatOfPoint2f()
                        cornersMat.copyTo(currentImagePoints)
                        imagePointsList.add(currentImagePoints)
                        objectPointsList.add(createObjectPointsForSingleImage())
                        framesCaptured++
                        Log.i(TAG, "Cadre $framesCaptured/$minFramesForCalibration capturé !")

                        // Mise à jour UI
                        runOnUiThread {
                            binding.buttonCalibrate.isEnabled = framesCaptured >= minFramesForCalibration
                            updateStatus("Cadre $framesCaptured/$minFramesForCalibration capturé !")
                        }
                    } else {
                        // Seulement mettre à jour le statut si pas de capture demandée
                        statusMessage = "Coins détectés ! (${framesCaptured}/${minFramesForCalibration})"
                        runOnUiThread { updateStatus(statusMessage) }
                    }
                } else {
                    statusMessage = "Aucun coin détecté. (${framesCaptured}/${minFramesForCalibration})"
                    // Mettre à jour si pas de capture demandée OU si capture échouée
                    if (!captureFrameRequested) {
                        runOnUiThread { updateStatus(statusMessage) }
                    } else {
                        runOnUiThread { updateStatus("Capture échouée (coins non trouvés). Réessayez.") }
                        captureFrameRequested = false // Reset flag
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erreur dans analyze()", e)
                runOnUiThread { updateStatus("Erreur analyse: ${e.message}") }
            } finally {
                imageProxy.close() // Toujours fermer l'image proxy
                processing = false // Déverrouiller
            }
        }
    }

    /** Exécute la calibration OpenCV et sauvegarde les résultats */
    private fun runCalibration() {
        val currentImageSize = imageSizeForCalibration
        if (imagePointsList.size < minFramesForCalibration || currentImageSize == null) {
            Log.e(TAG, "Conditions non remplies pour calibration: ${imagePointsList.size} cadres, taille image ${if (currentImageSize == null) "inconnue" else "connue"}")
            updateStatus("Échec : Données/Taille image insuffisantes.")
            // Réactiver les boutons si l'utilisateur clique sur calibrer trop tôt
            runOnUiThread {
                binding.buttonCaptureFrame.isEnabled = true
                binding.buttonCalibrate.isEnabled = framesCaptured >= minFramesForCalibration
            }
            return
        }

        // Mise à jour UI avant de lancer la coroutine
        runOnUiThread {
            updateStatus("Calibration en cours (peut prendre du temps)...")
            binding.buttonCalibrate.isEnabled = false
            binding.buttonCaptureFrame.isEnabled = false
            binding.progressBarCalibration.visibility = View.VISIBLE
        }

        // Copier les listes pour la coroutine
        val objectPointsCopy = ArrayList(objectPointsList)
        val imagePointsCopy = ArrayList(imagePointsList)

        // Lancer la calibration en arrière-plan
        CoroutineScope(Dispatchers.Default).launch {
            var resultText = "Erreur inconnue pendant la calibration."
            var calibrationSuccess = false
            val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
            val distCoeffs = Mat.zeros(5, 1, CvType.CV_64F)
            val rvecs = mutableListOf<Mat>()
            val tvecs = mutableListOf<Mat>()

            try {
                Log.i(TAG, "Lancement Calib3d.calibrateCamera avec ${objectPointsCopy.size} vues.")
                if (objectPointsCopy.isEmpty() || imagePointsCopy.isEmpty()) {
                    throw IllegalStateException("Les listes de points copiées sont vides.")
                }

                val reprojectionError = Calib3d.calibrateCamera(
                    objectPointsCopy, imagePointsCopy, currentImageSize,
                    cameraMatrix, distCoeffs, rvecs, tvecs
                )

                val fx = cameraMatrix.get(0, 0)?.get(0) ?: Double.NaN
                val fy = cameraMatrix.get(1, 1)?.get(0) ?: Double.NaN
                val cx = cameraMatrix.get(0, 2)?.get(0) ?: Double.NaN
                val cy = cameraMatrix.get(1, 2)?.get(0) ?: Double.NaN
                val k1 = distCoeffs.get(0, 0)?.get(0) ?: 0.0
                val k2 = distCoeffs.get(1, 0)?.get(0) ?: 0.0
                val p1 = distCoeffs.get(2, 0)?.get(0) ?: 0.0
                val p2 = distCoeffs.get(3, 0)?.get(0) ?: 0.0
                val k3 = distCoeffs.get(4, 0)?.get(0) ?: 0.0

                if (fx.isNaN() || fy.isNaN() || cx.isNaN() || cy.isNaN()) {
                    throw IllegalStateException("Paramètres intrinsèques invalides (NaN).")
                }

                saveCalibrationResults(
                    fx.toFloat(), fy.toFloat(), cx.toFloat(), cy.toFloat(),
                    k1.toFloat(), k2.toFloat(), p1.toFloat(), p2.toFloat(), k3.toFloat(),
                    reprojectionError.toFloat()
                )
                calibrationSuccess = true

                resultText = """
                    Calibration SAUVEGARDÉE ! Erreur=%.4f
                    fx=%.2f, fy=%.2f, cx=%.2f, cy=%.2f
                    k1=%.4f, k2=%.4f, p1=%.4f, p2=%.4f, k3=%.4f
                    NOTE: Copiez ces valeurs dans Config.kt !
                """.trimIndent().format(reprojectionError, fx, fy, cx, cy, k1, k2, p1, p2, k3)

            } catch (e: Exception) {
                Log.e(TAG, "Erreur pendant calibrateCamera ou sauvegarde", e)
                resultText = "Erreur de calibration:\n${e.message}"
                calibrationSuccess = false
            } finally {
                // Libérer les matrices locales à la coroutine
                cameraMatrix.release()
                distCoeffs.release()
                rvecs.forEach { it.release() }
                tvecs.forEach { it.release() }
                // Mettre à jour l'UI depuis le thread principal
                withContext(Dispatchers.Main) {
                    updateStatus(resultText)
                    binding.progressBarCalibration.visibility = View.GONE
                    binding.buttonCaptureFrame.isEnabled = true
                    binding.buttonCalibrate.isEnabled = framesCaptured >= minFramesForCalibration
                }
            }
        } // Fin CoroutineScope
    }

    /** Sauvegarde les paramètres dans SharedPreferences */
    private fun saveCalibrationResults(fx: Float, fy: Float, cx: Float, cy: Float,
                                       k1: Float, k2: Float, p1: Float, p2: Float, k3: Float,
                                       error: Float) {
        try {
            val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            Log.d(TAG, "Sauvegarde SharedPreferences: fx=$fx, fy=$fy, cx=$cx, cy=$cy...") // Log abrégé
            editor.putFloat(PREF_FX, fx)
            editor.putFloat(PREF_FY, fy)
            editor.putFloat(PREF_CX, cx)
            editor.putFloat(PREF_CY, cy)
            editor.putFloat(PREF_K1, k1)
            editor.putFloat(PREF_K2, k2)
            editor.putFloat(PREF_P1, p1)
            editor.putFloat(PREF_P2, p2)
            editor.putFloat(PREF_K3, k3)
            editor.putFloat(PREF_REPROJECTION_ERROR, error)
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la sauvegarde SharedPreferences", e)
            runOnUiThread { Toast.makeText(this, "Erreur sauvegarde calibration", Toast.LENGTH_SHORT).show() }
        }
    }

    /** Met à jour le TextView de statut sur le thread UI */
    private fun updateStatus(message: String) {
        if (!isFinishing && !isDestroyed) {
            runOnUiThread {
                binding.textViewStatus.text = "Status:\n$message"
            }
        } else {
            Log.w(TAG, "Ignore mise à jour statut sur activité détruite: $message")
        }
    }

    /** Gère la destruction de l'activité et libère les ressources */
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        // Libérer les Mats OpenCV
        // Utiliser runCatching pour éviter crash si release est appelée sur un Mat déjà libéré
        runCatching { matGray.release() }.onFailure { Log.e(TAG, "Erreur release matGray", it) }
        runCatching { matRgba.release() }.onFailure { Log.e(TAG, "Erreur release matRgba", it) }
        runCatching { cornersMat.release() }.onFailure { Log.e(TAG, "Erreur release cornersMat", it) }
        imagePointsList.forEach { runCatching { it.release() } }
        objectPointsList.forEach { runCatching { it.release() } }
        imagePointsList.clear()
        objectPointsList.clear()
        Log.d(TAG, "Activité Calibration détruite, ressources libérées.")
    }
}