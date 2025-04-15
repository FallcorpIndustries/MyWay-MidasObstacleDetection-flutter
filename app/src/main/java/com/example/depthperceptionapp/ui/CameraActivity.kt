package com.example.depthperceptionapp.ui
// Adaptez le nom du package si nécessaire

import android.Manifest
import android.content.Intent // <<< IMPORT AJOUTÉ
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
// import android.widget.Button // Pas nécessaire si on utilise ViewBinding directement
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.depthperceptionapp.Config // Import nécessaire pour lire seuil dans formatDebugText
import com.example.depthperceptionapp.R // Import pour ID (si on utilisait findViewById)
import com.example.depthperceptionapp.analysis.AnalysisManager
import com.example.depthperceptionapp.analysis.DetectionState
import com.example.depthperceptionapp.analysis.VisualizationData
import com.example.depthperceptionapp.analysis.WallDirection // Import pour formatDebugText
import com.example.depthperceptionapp.camera.DepthAnalyzer
import com.example.depthperceptionapp.databinding.ActivityCameraBinding // Assurez-vous que le nom correspond
import com.example.depthperceptionapp.feedback.FeedbackManager
import com.example.depthperceptionapp.tflite.DepthPredictor
import com.example.depthperceptionapp.ui.OverlayView
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Activité principale hébergeant l'aperçu caméra, le pipeline d'analyse,
 * le retour audio/TTS, et l'overlay de visualisation.
 * Inclut maintenant un listener pour lancer CalibrationActivity.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var overlayView: OverlayView
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private lateinit var cameraExecutor: ExecutorService
    private var depthPredictor: DepthPredictor? = null
    private var analysisManager: AnalysisManager? = null
    private var feedbackManager: FeedbackManager? = null
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "CameraActivity"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i(TAG, "Permission Caméra accordée.")
                setupCamera()
            } else {
                Log.e(TAG, "Permission Caméra refusée.")
                Toast.makeText(this, "La permission caméra est nécessaire.", Toast.LENGTH_LONG).show()
                // finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate: Initialisation...")

        overlayView = binding.overlayView
        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            depthPredictor = DepthPredictor(this)
            analysisManager = AnalysisManager()
            feedbackManager = FeedbackManager(this) // Assurez-vous que FeedbackManager est prêt pour Phase 2
        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'initialisation (probable modèle TFLite): ${e.message}", e)
            Toast.makeText(this, "Erreur initialisation: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // --- GESTION DU CLIC SUR LE BOUTON CALIBRER ---
        // Assurez-vous que l'ID de votre bouton dans activity_camera.xml est bien "buttonGoToCalibration"
        // Si vous l'avez ajouté et qu'il a cet ID, ce code devrait fonctionner.
        // Si le bouton n'existe pas dans le XML, l'accès via binding lèvera une exception (attrapée ci-dessous).
        try {
            // Utilisez l'ID exact défini dans votre fichier activity_camera.xml
            // Si vous n'avez pas ajouté le bouton, cette ligne échouera (le catch le signalera).
            binding.buttonGoToCalibration.setOnClickListener {
                Log.i(TAG, "Clic sur le bouton Calibrer -> Lancement de CalibrationActivity...")
                Toast.makeText(this, "Lancement Calibration...", Toast.LENGTH_SHORT).show() // Feedback immédiat

                try {
                    // Créer une Intention pour démarrer CalibrationActivity
                    val intent = Intent(this, CalibrationActivity::class.java)
                    // Démarrer la nouvelle activité
                    startActivity(intent)
                } catch (e: Exception) {
                    // Capturer une erreur si l'activité ne peut pas être démarrée (ex: non déclarée dans Manifest)
                    Log.e(TAG, "Erreur lors du lancement explicite de CalibrationActivity", e)
                    Toast.makeText(this, "Impossible de lancer l'écran de calibration: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            Log.d(TAG, "Listener ajouté pour buttonGoToCalibration.")
        } catch (e: NullPointerException) {
            // Ceci arrivera si 'buttonGoToCalibration' n'existe pas dans votre layout XML lié par le binding
            Log.e(TAG, "Le bouton avec l'ID 'buttonGoToCalibration' n'a pas été trouvé dans le layout activity_camera.xml. Listener non ajouté.", e)
            // Optionnel : Afficher un message si le bouton est censé exister
            // Toast.makeText(this, "Erreur interne: Bouton Calibrer non trouvé dans layout.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // Autres erreurs possibles lors de l'ajout du listener
            Log.e(TAG, "Erreur lors de l'ajout du listener au bouton de calibration", e)
            Toast.makeText(this, "Erreur interne: Listener bouton calibration.", Toast.LENGTH_LONG).show()
        }
        // --- Fin gestion clic ---

        // Vérifier la permission caméra au démarrage
        checkCameraPermission()

        Log.d(TAG, "onCreate: Initialisation terminée.")
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                Log.i(TAG, "Permission Caméra déjà accordée.")
                setupCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.w(TAG, "Affichage de la justification pour la permission.")
                Toast.makeText(this, "L'accès caméra est requis pour analyser l'environnement.", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                Log.i(TAG, "Demande de la permission caméra.")
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun setupCamera() {
        if (depthPredictor == null || !depthPredictor!!.isInitialized) {
            Log.e(TAG, "DepthPredictor non initialisé. Impossible de configurer la caméra.")
            Toast.makeText(this, "Échec chargement modèle. Caméra désactivée.", Toast.LENGTH_LONG).show()
            return
        }

        Log.d(TAG, "Configuration de la caméra...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                imageAnalysisUseCase = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, DepthAnalyzer(
                            depthPredictor = depthPredictor!!,
                            analysisManager = analysisManager!!,
                            feedbackManager = feedbackManager!!,
                            analysisScope = analysisScope,
                            onResult = { visData: VisualizationData -> // Reçoit VisualizationData
                                runOnUiThread { // Mettre à jour UI sur le Main Thread
                                    if (!isFinishing && !isDestroyed) {
                                        binding.debugText.text = formatDebugText(visData.detectionState)
                                        overlayView.updateOverlay(visData) // Mettre à jour l'overlay
                                    }
                                }
                            }
                        ))
                    }

                bindCameraUseCases()

            } catch (exc: Exception) {
                Log.e(TAG, "Erreur lors de la configuration CameraProvider ou UseCases", exc)
                Toast.makeText(this, "Impossible d'initialiser la caméra: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: run { Log.e(TAG, "CameraProvider non dispo pour binding."); return }
        val preview = previewUseCase ?: run { Log.e(TAG, "PreviewUseCase non dispo pour binding."); return }
        val analysis = imageAnalysisUseCase ?: run { Log.e(TAG, "ImageAnalysisUseCase non dispo pour binding."); return }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, preview, analysis)
            Log.i(TAG, "Use cases caméra liés avec succès.")
        } catch (exc: Exception) {
            Log.e(TAG, "Échec liaison use case: ${exc.message}", exc)
            Toast.makeText(this, "Impossible de lier la caméra: ${exc.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Formate le texte de débogage à partir de DetectionState */
    private fun formatDebugText(state: DetectionState): String {
        // Utiliser le seuil depuis Config pour la comparaison ici aussi
        val obstacleThreshold = Config.OBSTACLE_CLOSENESS_THRESHOLD
        val obstacleStr = if (state.maxObstacleDepth > obstacleThreshold)
        // Afficher la valeur de profondeur pour aider au tuning
            "Obstacle Proche (D=%.1f)".format(state.maxObstacleDepth)
        else
            "Obstacle Loin/Non"

        // Afficher la direction du mur si détecté
        val wallStr = if (state.wallDetected) "Mur (${state.wallDirection})" else "Pas de Mur"
        val pathStr = "Chemin: ${state.freePathDirection}"
        // Optionnel: ajouter le timestamp pour voir la fraîcheur des données
        // val timeStr = "T: ${state.timestamp % 10000}"
        return "$obstacleStr\n$wallStr\n$pathStr" // \n$timeStr
    }


    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        // Assurer que la caméra redémarre si nécessaire après une pause/permission accordée
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && cameraProvider == null) {
            if (depthPredictor != null && depthPredictor!!.isInitialized) {
                Log.i(TAG, "Configuration caméra depuis onResume (était en attente).")
                setupCamera()
            } else {
                Log.w(TAG, "onResume: Predictor non prêt, impossible de configurer caméra.")
                // Afficher un message d'erreur persistant si le modèle n'a pas chargé ?
                // Toast.makeText(this,"Modèle non chargé!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Nettoyage et arrêt...")
        analysisScope.cancel("Activité détruite")
        cameraExecutor.shutdown()
        depthPredictor?.close()
        feedbackManager?.shutdown()
        runOnUiThread { overlayView.updateOverlay(null) } // Effacer l'overlay
        Log.d(TAG, "onDestroy: Nettoyage terminé.")
    }
}