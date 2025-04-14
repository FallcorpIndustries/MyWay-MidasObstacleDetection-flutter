// lib/services/tflite_service.dart

import 'dart:async';
import 'dart:developer';
import 'dart:isolate';
import 'dart:typed_data';

// Import nécessaire pour rootBundle.load()
import 'package:flutter/services.dart' show rootBundle;
// Importe UNIQUEMENT le package principal TFLite pour Flutter
import 'package:tflite_flutter/tflite_flutter.dart';

/// Service responsable du chargement du modèle TensorFlow Lite (MiDaS)
/// et de l'exécution de l'inférence SANS utiliser tflite_flutter_helper_plus.
/// Utilise rootBundle + fromBuffer et entrée Uint8.
/// VERSION FINALE CORRIGÉE : Supprime TOUTE référence explicite à TfLiteType.float32.
class TFLiteService {
  // --- Constantes du Modèle ---
  static const String modelPath = 'midas_small_quant.tflite';
  // Forme de sortie attendue [Batch, Height, Width]
  static const List<int> outputShape = const [1, 256, 256];
  // static final TfLiteType _outputTypeForBuffer = TfLiteType.float32; // <<< LIGNE DÉFINITIVEMENT SUPPRIMÉE

  // --- Variables d'état ---
  Interpreter? _interpreter;
  IsolateInterpreter? _isolateInterpreter;
  bool _isInitialized = false;
  String _confirmedOutputType = "Inconnu"; // Gardé pour info dans les logs

  bool get isInitialized => _isInitialized;

  /// Charge le modèle TFLite (via rootBundle + fromBuffer).
  Future<bool> loadModel() async {
    if (_isInitialized) { /* ... log ... */ return true; }
    log('Chargement modèle TFLite (via rootBundle + fromBuffer): $modelPath ...', name: 'TFLiteService');

    try {
      // 1. Charger les octets de l'asset
      log("Chargement asset 'assets/$modelPath'...", name: "TFLiteService");
      final ByteData assetData = await rootBundle.load('assets/$modelPath');
      log("Asset chargé, taille: ${assetData.lengthInBytes} octets.", name: "TFLiteService");
      if (assetData.lengthInBytes == 0) { throw Exception("Asset '$modelPath' vide."); }
      final Uint8List modelBytes = assetData.buffer.asUint8List(assetData.offsetInBytes, assetData.lengthInBytes);

      // 2. Créer options
      final InterpreterOptions options = InterpreterOptions();

      // 3. Initialiser depuis buffer
      log("Init interpréteur depuis buffer...", name: "TFLiteService");
      _interpreter = await Interpreter.fromBuffer(modelBytes, options: options);
      log('Interpréteur standard chargé (buffer).', name: 'TFLiteService');

      // 4. Allouer tenseurs
      _interpreter!.allocateTensors();
      log('Tenseurs alloués.', name: 'TFLiteService');

      // Inspection type sortie (info)
      try {
          final outputTensors = _interpreter!.getOutputTensors();
          if (outputTensors.isNotEmpty) { _confirmedOutputType = outputTensors[0].type.toString(); log('Type sortie inspecté: ${_confirmedOutputType}', name: 'TFLiteService'); }
      } catch (e) { /* ... log erreur inspection ... */ _confirmedOutputType = "Erreur inspection"; }

      // 5. Créer IsolateInterpreter
      _isolateInterpreter = await IsolateInterpreter.create(address: _interpreter!.address);
      log('IsolateInterpreter créé.', name: 'TFLiteService');

      // 6. Attente (Workaround)
      log('Pause 500ms...', name: 'TFLiteService'); await Future.delayed(const Duration(milliseconds: 500)); log('Pause terminée.', name: 'TFLiteService');

      _isInitialized = true;
      log('TFLiteService initialisé avec succès (via fromBuffer).', name: 'TFLiteService');
       log('  - Sortie attendue (structure Dart): List<List<List<double>>> $outputShape (Type TFLite: ${_confirmedOutputType})');
      return true;

    } catch (e, stacktrace) {
        print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'); print('[TFLiteService] ERREUR FATALE chargement/init (fromBuffer):'); print('Erreur: $e'); print('Stack trace:\n$stacktrace'); print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!');
        _isInitialized = false; _isolateInterpreter?.close(); _interpreter?.close(); _isolateInterpreter = null; _interpreter = null; return false;
    }
  } // Fin loadModel

  /// Exécute l'inférence.
  /// [inputData] : Liste 4D [1, H, W, C] de type INT (0-255).
  /// Retourne une `List<List<List<double>>>` (profondeur) ou `null`.
  Future<List<List<List<double>>>?> runInference(List<List<List<List<int>>>> inputData) async { // Prend INT en entrée
    if (!_isInitialized || _isolateInterpreter == null) { log('ERREUR: TFLiteService non initialisé.', name: 'TFLiteService'); return null; }

    // Crée le buffer de sortie pour recevoir les résultats (double/Float32)
    // La structure de la liste avec 0.0 suffit à définir le type attendu pour le remplissage.
    var outputData = List.generate(
      outputShape[0], // 1
      (_) => List.generate(
        outputShape[1], // 256
        (_) => List.filled(outputShape[2], 0.0, growable: false), // 256 doubles à 0.0
        growable: false
      ),
      growable: false
    );

    log('Exécution inférence TFLite (Isolate)...', name: 'TFLiteService');
    try {
      final startTime = DateTime.now();
      await _isolateInterpreter!.run(inputData, outputData); // TFLite gère les types
      final endTime = DateTime.now();
      log('Inférence terminée en ${endTime.difference(startTime).inMilliseconds} ms.', name: 'TFLiteService');
      return outputData; // Retourne List<List<List<double>>>
    } catch (e, stacktrace) {
      print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'); print('[TFLiteService] ERREUR lors de l\'inférence TFLite:'); print('Erreur: $e'); print('Stack trace:\n$stacktrace'); print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!');
      return null;
    }
  }

  /// Libère les ressources.
  void dispose() {
      // ... (code dispose inchangé) ...
     log('Libération de TFLiteService...', name: 'TFLiteService');
     if (_isInitialized) { try { _isolateInterpreter?.close(); log('IsolateInterpreter fermé.', name: 'TFLiteService'); } catch (e) { log('ERREUR fermeture TFLite: $e', name: 'TFLiteService'); } finally { _isolateInterpreter = null; _interpreter = null; _isInitialized = false; log('TFLiteService libéré.', name: 'TFLiteService'); } }
     else { log('TFLiteService non initialisé, rien à libérer.', name: 'TFLiteService'); }
   }
} // Fin TFLiteService