import 'dart:async';
import 'dart:developer';
import 'dart:isolate';
import 'dart:typed_data';
import 'package:flutter/services.dart' show rootBundle;
import 'package:tflite_flutter/tflite_flutter.dart';

class TFLiteService {
  static const String modelPath = 'midas_small_quant.tflite';
  static const List<int> outputShape = [1, 256, 256, 1];

  Interpreter? _interpreter;
  IsolateInterpreter? _isolateInterpreter;
  bool _isInitialized = false;

  bool get isInitialized => _isInitialized;

  Future<bool> loadModel() async {
    if (_isInitialized) return true;
    log('Chargement modèle TFLite...', name: 'TFLiteService');

    try {
      final ByteData assetData = await rootBundle.load('assets/$modelPath');
      final Uint8List modelBytes = assetData.buffer.asUint8List(assetData.offsetInBytes, assetData.lengthInBytes);
      final InterpreterOptions options = InterpreterOptions();

      _interpreter = await Interpreter.fromBuffer(modelBytes, options: options);
      _interpreter!.allocateTensors();
      _isolateInterpreter = await IsolateInterpreter.create(address: _interpreter!.address);

      _isInitialized = true;
      log('TFLiteService initialisé avec succès.', name: 'TFLiteService');
      return true;
    } catch (e, stacktrace) {
      print('!!! ERREUR INIT TFLITE !!!\nErreur: $e\n$stacktrace');
      _isInitialized = false;
      _interpreter?.close();
      _isolateInterpreter?.close();
      return false;
    }
  }

  /// Adapté pour modèle quantisé : sortie en `int` et non `double`
  Future<List<List<List<List<int>>>>>? runInference(Uint8List inputBytes) async {
    if (!_isInitialized || _isolateInterpreter == null) {
      log('TFLiteService non prêt.', name: 'TFLiteService');
      return Future.value(null);
    }

    // Sortie INT au lieu de DOUBLE (quantized model)
    var outputData = List.generate(
      outputShape[0], (_) => List.generate(
        outputShape[1], (_) => List.generate(
          outputShape[2], (_) => List.filled(outputShape[3], 0),
        ),
      ),
    );

    try {
      await _isolateInterpreter!.run(inputBytes, outputData);
      return outputData;
    } catch (e, stacktrace) {
      print('!!! ERREUR INFÉRENCE TFLITE !!!\nErreur: $e\n$stacktrace');
      return Future.value(null);
    }
  }

  void dispose() {
    log('Libération TFLiteService...', name: 'TFLiteService');
    _isolateInterpreter?.close();
    _interpreter?.close();
    _isolateInterpreter = null;
    _interpreter = null;
    _isInitialized = false;
  }
}
