// lib/main.dart

import 'dart:async';
import 'dart:developer';    // Pour log()
import 'dart:typed_data';   // Pour ByteData (dans le test d'asset)

import 'package:flutter/material.dart';
import 'package:flutter/services.dart'; // Pour rootBundle et ByteData
import 'package:camera/camera.dart'; // Pour CameraPreview et CameraImage

// Importe tous nos services et modèles
import 'package:assistive_perception_app/services/camera_service.dart';
import 'package:assistive_perception_app/services/tflite_service.dart';
import 'package:assistive_perception_app/services/preprocessing_service.dart';
import 'package:assistive_perception_app/services/depth_analyzer.dart';
import 'package:assistive_perception_app/services/audio_feedback_service.dart';
import 'package:assistive_perception_app/models/depth_analysis_result.dart';
import 'package:assistive_perception_app/models/enums.dart';


// Point d'entrée principal
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

// Widget racine
class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Assistive Perception App',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const MyHomePage(title: 'Assistive Perception Phase 1'),
    );
  }
}

// Widget principal de la page d'accueil
class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});
  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

// État associé
class _MyHomePageState extends State<MyHomePage> with WidgetsBindingObserver {

  // Instances des Services
  late final CameraService _cameraService;
  late final TFLiteService _tfliteService;
  late final PreprocessingService _preprocessingService;
  late final DepthAnalyzer _depthAnalyzer;
  late final AudioFeedbackService _audioFeedbackService;

  CameraController? _controller;
  bool _isInitializing = true;
  bool _servicesInitialized = false;
  String _statusMessage = "Initialisation...";

  @override
  void initState() {
    super.initState();
    log("MyHomePage: initState", name: "MainUI");
    WidgetsBinding.instance.addObserver(this); // Pour gérer pause/reprise

    // Crée les instances
    _cameraService = CameraService();
    _tfliteService = TFLiteService();
    _preprocessingService = PreprocessingService();
    _depthAnalyzer = DepthAnalyzer();
    _audioFeedbackService = AudioFeedbackService();

    _initializeAsyncServices(); // Lance l'initialisation
  }

  @override
  void dispose() {
    log("MyHomePage: dispose", name: "MainUI");
    WidgetsBinding.instance.removeObserver(this);
    // Libère les ressources proprement
    Future.microtask(() async {
      await _cameraService.dispose();
      _tfliteService.dispose();
      await _audioFeedbackService.dispose();
      log("MyHomePage: Services disposed", name: "MainUI");
    });
    super.dispose();
  }

   @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Gère pause/reprise de la caméra
    final CameraController? cameraController = _controller;
    if (cameraController == null || !cameraController.value.isInitialized) return;

    if (state == AppLifecycleState.inactive || state == AppLifecycleState.paused) {
      log("App Lifecycle: Inactive/Paused - Stopping stream", name: "MainUI");
       _cameraService.stopStreaming();
    } else if (state == AppLifecycleState.resumed) {
       log("App Lifecycle: Resumed - Restarting stream", name: "MainUI");
      if (_servicesInitialized && !_cameraService.isStreaming) { // Vérifie si pas déjà en cours
         _startCameraStream();
      }
    }
  }

  // --- Initialisation Asynchrone AVEC TEST D'ASSET ---
  Future<void> _initializeAsyncServices() async {
    log("Initialisation des services...", name: "MainUI");
    if (!mounted) return;
    setState(() { _isInitializing = true; _statusMessage = "Vérification de l'asset TFLite..."; });

    // --- TEST D'ACCÈS À L'ASSET (AVEC PRINT) ---
    bool assetLoadSuccess = false; String assetError = "";
    try {
      print(">>> TEST ASSET: Tentative de chargement de 'assets/midas_small_quant.tflite' via rootBundle...");
      final ByteData assetData = await rootBundle.load('assets/midas_small_quant.tflite');
      if (assetData.lengthInBytes > 0) { print(">>> TEST ASSET: SUCCÈS ! Asset chargé. Taille: ${assetData.lengthInBytes} octets."); assetLoadSuccess = true; }
      else { assetError = "Asset ... chargé mais VIDE."; print(">>> TEST ASSET: ERREUR ! $assetError"); assetLoadSuccess = false; }
    } catch (e, stacktrace) {
      assetError = "Impossible de charger 'assets/midas_small_quant.tflite'. Vérifiez pubspec.yaml...";
      print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'); print(">>> TEST ASSET: ERREUR FATALE ! $assetError"); print(">>> Erreur AssetBundle: $e"); print(">>> StackTrace AssetBundle:\n$stacktrace"); print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'); assetLoadSuccess = false;
    }
    // --- FIN TEST D'ACCÈS À L'ASSET ---

    if (!mounted) return;
    if (!assetLoadSuccess) { setState(() { _isInitializing = false; _servicesInitialized = false; _statusMessage = "Erreur Asset: $assetError"; }); return; }

    // --- Suite de l'initialisation ---
    setState(() { _statusMessage = "Asset OK. Chargement du modèle TFLite..."; });
    bool tfliteOk = await _tfliteService.loadModel(); // TFLiteService utilise print() dans son catch
    if (!mounted) return;
    if (!tfliteOk) { setState(() { _isInitializing = false; _servicesInitialized = false; _statusMessage = "Erreur TFLite: Modèle non chargé.\n(Vérifiez logs 'ERREUR FATALE')"; }); return; }

    setState(() { _statusMessage = "Asset & Modèle OK. Initialisation audio..."; });
    bool audioOk = await _audioFeedbackService.initialize();
    if (!mounted) return;
    if (!audioOk) { log("Erreur: Service Audio non initialisé.", name: "MainUI"); setState(() { _statusMessage = "Audio non disponible. Init caméra..."; });}
    else { setState(() { _statusMessage = "Initialisation de la caméra..."; });}

    bool cameraOk = await _cameraService.initialize();
    if (!mounted) return;
    if (!cameraOk) { setState(() { _isInitializing = false; _servicesInitialized = false; _statusMessage = "Erreur: Caméra non initialisée."; }); return; }

    _controller = _cameraService.controller;
    setState(() { _isInitializing = false; _servicesInitialized = true; _statusMessage = "Services Prêts.";});
    log("Tous les services sont initialisés.", name: "MainUI");

    _startCameraStream();
  } // Fin _initializeAsyncServices


  // --- Démarrage Flux Caméra ---
  void _startCameraStream() {
      final CameraController? cameraController = _controller;
      if (cameraController != null && cameraController.value.isInitialized && _servicesInitialized) {
        log("Tentative de démarrage du flux caméra...", name: "MainUI");
         _cameraService.startStreaming(_processCameraImage);
         if(mounted) { setState(() { _statusMessage = "Analyse en cours..."; }); }
      } else {
         log("Impossible de démarrer le flux: caméra ou services non prêts.", name: "MainUI");
          if(mounted) { setState(() { _statusMessage = "Erreur au démarrage du flux."; }); }
      }
   }


  // --- Pipeline Traitement Image (TYPES CORRIGÉS pour variables) ---
  // Orchestre l'appel séquentiel des services pour chaque image
  Future<void> _processCameraImage(CameraImage image) async {
    if (!_servicesInitialized || !mounted) return;
    final processingWatch = Stopwatch()..start();
    try {
      print("--- Frame Start ---"); // Marqueur Début

      // 1. Prétraitement -> retourne List<...<int>>?
      // CORRECTION TYPE: inputData est bien List<...<int>>?
      final List<List<List<List<int>>>>? inputData =
          await _preprocessingService.preprocessCameraImage(image);

      if (!mounted) { processingWatch.stop(); return; }
      print("--- Step 1: Preprocessing Done (inputData is ${inputData == null ? 'null' : 'OK'}) ---"); // Marqueur 1
      if (inputData == null) { processingWatch.stop(); return; }

      // 2. Inférence TFLite -> prend List<...<int>>, retourne List<...<double>>?
      // CORRECTION TYPE: outputData est bien List<...<double>>?
      final List<List<List<double>>>? outputData =
          await _tfliteService.runInference(inputData); // Passe la liste d'int

      if (!mounted) { processingWatch.stop(); return; }
      print("--- Step 2: Inference Done (outputData is ${outputData == null ? 'null' : 'OK'}) ---"); // Marqueur 2
      if (outputData == null) { processingWatch.stop(); return; }

      // 3. Analyse de Profondeur -> prend List<...<double>>?
      // Le type de outputData est correct pour cette fonction
      final DepthAnalysisResult? analysisResult =
          await _depthAnalyzer.analyzeDepthMap(outputData); // Passe la liste de double

      if (!mounted) { processingWatch.stop(); return; }
      print("--- Step 3: Analysis Done (analysisResult is ${analysisResult == null ? 'null' : 'OK'}) ---"); // Marqueur 3
      if (analysisResult == null) { processingWatch.stop(); return; }

      // 4. Affichage Résultat Console (Remplace TTS pour l'instant)
      print("-----------------------------------------");
      print("ANALYSE RESULT (Frame ${DateTime.now().millisecondsSinceEpoch}):");
      print(" -> Obstacle: ${analysisResult.obstacleProximity.name}");
      print(" -> Mur: ${analysisResult.wallDirection.name} (Rappel: RANSAC est vide, doit être None)");
      print(" -> Chemin Libre: ${analysisResult.freePathDirection.name}");
      print("-----------------------------------------");
      print("--- Step 4: Result Print Done ---"); // Marqueur Fin

      processingWatch.stop();
      log("Pipeline: ${processingWatch.elapsedMilliseconds} ms", name: "MainUI");

    } catch (e, stacktrace) {
       // Utilise print pour assurer la visibilité de l'erreur du pipeline
       print("!!! ERREUR DANS PIPELINE _processCameraImage: $e\n$stacktrace");
       processingWatch.stop();
    }
  } // Fin _processCameraImage


  // --- Build UI ---
  @override
  Widget build(BuildContext context) {
       return Scaffold(
         appBar: AppBar( title: Text(widget.title), backgroundColor: Theme.of(context).colorScheme.inversePrimary,),
         body: Center( child: _buildBody(), ),
       );
   }

  Widget _buildBody() {
      // (Code _buildBody standard pour gérer affichage chargement/erreur/caméra)
      if (_isInitializing) {
        return Column( mainAxisAlignment: MainAxisAlignment.center, children: <Widget>[ const CircularProgressIndicator(), const SizedBox(height: 20), Text(_statusMessage), ], );
      } else if (!_servicesInitialized || _controller == null || !_controller!.value.isInitialized) {
        return Padding( padding: const EdgeInsets.all(16.0), child: Column( mainAxisAlignment: MainAxisAlignment.center, children: <Widget>[ const Icon(Icons.error_outline, color: Colors.red, size: 60), const SizedBox(height: 20), Text(_statusMessage, textAlign: TextAlign.center, style: TextStyle(fontSize: 16)), const SizedBox(height: 20), ElevatedButton( onPressed: _initializeAsyncServices, child: const Text("Réessayer l'initialisation"), ) ], ), );
      } else {
         final mediaSize = MediaQuery.of(context).size;
         // Ajustement potentiel du scale pour affichage CameraPreview
         final scale = 1 / (_controller!.value.aspectRatio * mediaSize.aspectRatio);
        return ClipRect( child: OverflowBox( alignment: Alignment.center, child: FittedBox( fit: BoxFit.cover, child: SizedBox( width: mediaSize.width, height: mediaSize.width / _controller!.value.aspectRatio, child: CameraPreview(_controller!), ), ), ), );
      }
   }
} // Fin _MyHomePageState

// Extension (inchangée)
extension FloatExtension on double { double toFloat() => this; }