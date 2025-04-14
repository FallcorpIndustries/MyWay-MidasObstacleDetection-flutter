// lib/services/tflite_service.dart

import 'dart:async';
import 'dart:developer';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:flutter/services.dart' show rootBundle;
import 'package:tflite_flutter/tflite_flutter.dart';

/// Service TFLite utilisant Interpreter.fromBuffer et entrée Uint8.
class TFLiteService {
  static const String modelPath = 'midas_small_quant.tflite';
  static const List<int> outputShape = const [1, 256, 256];
  // Le type de sortie conceptuel est Float32 (profondeur), même si le modèle est quantifié.
  // TFLite runtime gère souvent la déquantification de sortie implicitement pour certains ops.
  // Nous gardons la création du buffer de sortie en double.
  static final TfLiteType _outputTypeForBuffer = TfLiteType.float32; // Type pour créer le buffer de sortie

  Interpreter? _interpreter;
  IsolateInterpreter? _isolateInterpreter;
  bool _isInitialized = false;
  String _confirmedOutputType = "Inconnu";

  bool get isInitialized => _isInitialized;

  Future<bool> loadModel() async {
    // ... (Code loadModel utilisant fromBuffer, inchangé par rapport à la version précédente) ...
     if (_isInitialized) { log('TFLiteService déjà initialisé.', name: 'TFLiteService'); return true; }
     log('Chargement du modèle TFLite (via rootBundle + fromBuffer): $modelPath ...', name: 'TFLiteService');
     try {
       log("Chargement des octets de l'asset 'assets/$modelPath'...", name: "TFLiteService");
       final ByteData assetData = await rootBundle.load('assets/$modelPath');
       log("Asset chargé, taille: ${assetData.lengthInBytes} octets.", name: "TFLiteService");
       if (assetData.lengthInBytes == 0) { throw Exception("Asset '$modelPath' chargé mais vide."); }
       final Uint8List modelBytes = assetData.buffer.asUint8List(assetData.offsetInBytes, assetData.lengthInBytes);
       final InterpreterOptions options = InterpreterOptions();
       log("Initialisation de l'interpréteur depuis le buffer...", name: "TFLiteService");
       _interpreter = await Interpreter.fromBuffer(modelBytes, options: options);
       log('Interpréteur standard chargé depuis buffer.', name: 'TFLiteService');
       _interpreter!.allocateTensors();
       log('Tenseurs alloués.', name: 'TFLiteService');
       try {
           final outputTensors = _interpreter!.getOutputTensors();
           if (outputTensors.isNotEmpty) { _confirmedOutputType = outputTensors[0].type.toString(); log('Type de sortie inspecté: ${_confirmedOutputType}', name: 'TFLiteService'); }
       } catch (e) { log('Erreur inspection tenseurs sortie: $e', name: 'TFLiteService'); _confirmedOutputType = "Erreur inspection"; }
       _isolateInterpreter = await IsolateInterpreter.create(address: _interpreter!.address);
       log('IsolateInterpreter créé.', name: 'TFLiteService');
       log('Pause de 500ms...', name: 'TFLiteService'); await Future.delayed(const Duration(milliseconds: 500)); log('Pause terminée.', name: 'TFLiteService');
       _isInitialized = true;
       log('TFLiteService initialisé avec succès (via fromBuffer).', name: 'TFLiteService');
       return true;
     } catch (e, stacktrace) {
         print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'); print('[TFLiteService] ERREUR FATALE chargement/init (fromBuffer):'); print('Erreur: $e'); print('Stack trace:\n$stacktrace'); print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!');
         _isInitialized = false; _isolateInterpreter?.close(); _interpreter?.close(); _isolateInterpreter = null; _interpreter = null; return false;
     }
  }

  /// Exécute l'inférence.
  ///
  /// [inputData] : Liste 4D [1, H, W, C] de type INT (0-255). CORRIGÉ.
  /// Retourne une `List<List<List<double>>>` (profondeur) ou `null`.
  Future<List<List<List<double>>>?> runInference(List<List<List<List<int>>>> inputData) async { // <<< CHANGEMENT TYPE ENTRÉE
    if (!_isInitialized || _isolateInterpreter == null) { /* ... log erreur ... */ return null; }

    // Crée le buffer de sortie pour recevoir les résultats Float32 (profondeur)
    // TFLite gère la conversion si le tenseur de sortie du modèle est Uint8 mais que nous lisons comme Float32.
    var outputData = List.generate(
      outputShape[0], (_) => List.generate(
        outputShape[1], (_) => List.filled(outputShape[2], 0.0, growable: false), growable: false), growable: false );

    log('Exécution inférence TFLite (Isolate)...', name: 'TFLiteService');
    try {
      final startTime = DateTime.now();
      // Passe inputData (List<...<int>>) et outputData (List<...<double>>)
      await _isolateInterpreter!.run(inputData, outputData);
      final endTime = DateTime.now();
      log('Inférence terminée en ${endTime.difference(startTime).inMilliseconds} ms.', name: 'TFLiteService');
      return outputData; // Retourne List<List<List<double>>>
    } catch (e, stacktrace) {
      print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'); print('[TFLiteService] ERREUR lors de l\'inférence TFLite:'); print('Erreur: $e'); print('Stack trace:\n$stacktrace'); print('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!');
      return null;
    }
  }

  void dispose() {
      // ... (code dispose inchangé) ...
     log('Libération de TFLiteService...', name: 'TFLiteService');
     if (_isInitialized) { try { _isolateInterpreter?.close(); log('IsolateInterpreter fermé.', name: 'TFLiteService'); } catch (e) { log('ERREUR fermeture TFLite: $e', name: 'TFLiteService'); } finally { _isolateInterpreter = null; _interpreter = null; _isInitialized = false; log('TFLiteService libéré.', name: 'TFLiteService'); } }
     else { log('TFLiteService non initialisé, rien à libérer.', name: 'TFLiteService'); }
   }
}