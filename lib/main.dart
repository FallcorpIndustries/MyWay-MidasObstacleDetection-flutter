// lib/main.dart

// --- IMPORTS ESSENTIELS ---
import 'dart:async';
import 'dart:developer';    // Pour log()
import 'dart:typed_data';   // Pour ByteData et Uint8List

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
// --- FIN IMPORTS ---


// --- FONCTION MAIN ---
// Point d'entrée principal de l'application Flutter
void main() {
  // Assure que les bindings Flutter sont initialisés
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}
// --- FIN FONCTION MAIN ---


// --- WIDGET RACINE MYAPP ---
// Widget racine de l'application (généralement StatelessWidget)
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
      // La page d'accueil de notre application
      home: const MyHomePage(title: 'Assistive Perception Phase 1'),
    );
  }
}
// --- FIN WIDGET RACINE MYAPP ---


// --- WIDGET MYHOMEPAGE (Stateful) ---
// Widget principal de la page d'accueil (Stateful car l'état change)
class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});
  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}
// --- FIN WIDGET MYHOMEPAGE ---


// --- CLASSE D'ÉTAT _MyHomePageState ---
// État associé à MyHomePage
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
    WidgetsBinding.instance.addObserver(this);

    _cameraService = CameraService();
    _tfliteService = TFLiteService();
    _preprocessingService = PreprocessingService();
    _depthAnalyzer = DepthAnalyzer();
    _audioFeedbackService = AudioFeedbackService();

    _initializeAsyncServices();
  }

   @override
  void dispose() {
     log("MyHomePage: dispose", name: "MainUI");
     WidgetsBinding.instance.removeObserver(this);
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
      final CameraController? cameraController = _controller;
      if (cameraController == null || !cameraController.value.isInitialized) return;
      if (state == AppLifecycleState.inactive || state == AppLifecycleState.paused) {
        log("App Lifecycle: Inactive/Paused - Stopping stream", name: "MainUI");
         _cameraService.stopStreaming();
      } else if (state == AppLifecycleState.resumed) {
         log("App Lifecycle: Resumed - Restarting stream", name: "MainUI");
        if (_servicesInitialized && !_cameraService.isStreaming) {
           _startCameraStream();
        }
      }
   }

  // Initialisation Asynchrone (avec test asset)
  Future<void> _initializeAsyncServices() async {
    log("Initialisation des services...", name: "MainUI");
    if (!mounted) return;
    setState(() { _isInitializing = true; _statusMessage = "Vérification de l'asset TFLite..."; });

    // Test accès asset
    bool assetLoadSuccess = false; String assetError = "";
    try {
      print(">>> TEST ASSET: Tentative de chargement...");
      final ByteData assetData = await rootBundle.load('assets/midas_small_quant.tflite');
      if (assetData.lengthInBytes > 0) { print(">>> TEST ASSET: SUCCÈS ! Taille: ${assetData.lengthInBytes} octets."); assetLoadSuccess = true; }
      else { assetError = "Asset chargé mais VIDE."; print(">>> TEST ASSET: ERREUR ! $assetError"); assetLoadSuccess = false; }
    } catch (e, stacktrace) {
      assetError = "Impossible de charger asset. Vérifiez pubspec.yaml..."; print('!!! TEST ASSET: ERREUR FATALE ! $assetError'); print(">>> Erreur: $e\n$stacktrace"); print('!!!'); assetLoadSuccess = false;
    }
    if (!mounted) return;
    if (!assetLoadSuccess) { setState(() { _isInitializing = false; _servicesInitialized = false; _statusMessage = "Erreur Asset: $assetError"; }); return; }

    // Init TFLite
    setState(() { _statusMessage = "Asset OK. Chargement modèle TFLite..."; });
    bool tfliteOk = await _tfliteService.loadModel();
    if (!mounted) return;
    if (!tfliteOk) { setState(() { _isInitializing = false; _servicesInitialized = false; _statusMessage = "Erreur TFLite: Modèle non chargé."; }); return; }

    // Init Audio
    setState(() { _statusMessage = "Modèle OK. Initialisation audio..."; });
    bool audioOk = await _audioFeedbackService.initialize();
    if (!mounted) return;
    if (!audioOk) { log("Erreur: Service Audio non initialisé.", name: "MainUI"); setState(() { _statusMessage = "Audio non disponible. Init caméra..."; });}
    else { setState(() { _statusMessage = "Initialisation de la caméra..."; });}

    // Init Caméra
    bool cameraOk = await _cameraService.initialize();
    if (!mounted) return;
    if (!cameraOk) { setState(() { _isInitializing = false; _servicesInitialized = false; _statusMessage = "Erreur: Caméra non initialisée."; }); return; }

    // Tout est prêt
    _controller = _cameraService.controller;
    setState(() { _isInitializing = false; _servicesInitialized = true; _statusMessage = "Services Prêts.";});
    log("Tous les services sont initialisés.", name: "MainUI");
    _startCameraStream();
  }

  // Démarrage Flux Caméra
  void _startCameraStream() {
      final CameraController? cameraController = _controller;
      if (cameraController != null && cameraController.value.isInitialized && _servicesInitialized) {
        log("Démarrage du flux caméra...", name: "MainUI");
         _cameraService.startStreaming(_processCameraImage);
         if(mounted) { setState(() { _statusMessage = "Analyse en cours..."; }); }
      } else {
         log("Impossible de démarrer le flux.", name: "MainUI");
          if(mounted) { setState(() { _statusMessage = "Erreur démarrage flux."; }); }
      }
   }


  // Pipeline Traitement Image (Types Corrigés pour Buffers Plats)
  Future<void> _processCameraImage(CameraImage image) async {
  if (!_servicesInitialized || !mounted) return;
  final processingWatch = Stopwatch()..start();

  try {
    print("--- Frame Start ---");

    final Uint8List? inputData = await _preprocessingService.preprocessCameraImage(image);
    if (!mounted || inputData == null) return;
    print("--- Step 1: Preprocessing Done (inputData is OK, size=${inputData.length}) ---");

    // INFÉRENCE : sortie maintenant List<List<List<List<int>>>>
    final List<List<List<List<int>>>>? rawOutput = await _tfliteService.runInference(inputData);
    if (!mounted || rawOutput == null) return;
    print("--- Step 2: Inference Done (outputData is OK) ---");

    // CONVERSION : vers List<List<List<List<double>>>> pour compatibilité
    final outputData = rawOutput.map((batch) =>
      batch.map((row) =>
        row.map((col) =>
          col.map((v) => v.toDouble()).toList()
        ).toList()
      ).toList()
    ).toList();

    final analysisResult = await _depthAnalyzer.analyzeDepthMap(outputData);
    if (!mounted || analysisResult == null) return;
    print("--- Step 3: Analysis Done (analysisResult is OK) ---");

    print("-----------------------------------------");
    print("ANALYSE RESULT:");
    print(" -> Obstacle: ${analysisResult.obstacleProximity.name}");
    print(" -> Mur: ${analysisResult.wallDirection.name}");
    print(" -> Chemin Libre: ${analysisResult.freePathDirection.name}");
    print("-----------------------------------------");

    processingWatch.stop();
    log("Pipeline: ${processingWatch.elapsedMilliseconds} ms", name: "MainUI");
  } catch (e, stacktrace) {
    print("!!! ERREUR _processCameraImage: $e\n$stacktrace");
    processingWatch.stop();
  }
}



  // --- Build UI ---
  @override
  Widget build(BuildContext context) {
     return Scaffold(
       appBar: AppBar( title: Text(widget.title), backgroundColor: Theme.of(context).colorScheme.inversePrimary,),
       body: Center( child: _buildBody(), ),
     );
  }

  Widget _buildBody() {
    // Gère l'affichage : chargement, erreur, ou caméra
    if (_isInitializing) {
      return Column( mainAxisAlignment: MainAxisAlignment.center, children: <Widget>[ const CircularProgressIndicator(), const SizedBox(height: 20), Text(_statusMessage), ], );
    } else if (!_servicesInitialized || _controller == null || !_controller!.value.isInitialized) {
      return Padding( padding: const EdgeInsets.all(16.0), child: Column( mainAxisAlignment: MainAxisAlignment.center, children: <Widget>[ const Icon(Icons.error_outline, color: Colors.red, size: 60), const SizedBox(height: 20), Text(_statusMessage, textAlign: TextAlign.center, style: TextStyle(fontSize: 16)), const SizedBox(height: 20), ElevatedButton( onPressed: _initializeAsyncServices, child: const Text("Réessayer l'initialisation"), ) ], ), );
    } else {
       // Affichage de la caméra
       final mediaSize = MediaQuery.of(context).size;
       final scale = 1 / (_controller!.value.aspectRatio * mediaSize.aspectRatio);
      return ClipRect( child: OverflowBox( alignment: Alignment.center, child: FittedBox( fit: BoxFit.cover, child: SizedBox( width: mediaSize.width, height: mediaSize.width / _controller!.value.aspectRatio, child: CameraPreview(_controller!), ), ), ), );
    }
  }
} // Fin _MyHomePageState

// Extension (inchangée)
extension FloatExtension on double { double toFloat() => this; }