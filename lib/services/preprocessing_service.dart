// lib/services/preprocessing_service.dart

import 'dart:async';
import 'dart:developer';
import 'dart:ffi';
import 'dart:typed_data'; // Pour Uint8List, ByteBuffer, Float32List

import 'package:camera/camera.dart';
import 'package:ffi/ffi.dart';
import 'package:image/image.dart' as img_lib;

import 'package:assistive_perception_app/utils/ffi_bindings.dart';

/// Service responsable du pré-traitement des images de la caméra avant l'inférence.
/// VERSION CORRIGÉE: Output Uint8 pour modèle quantifié.
class PreprocessingService {

  final ConvertYUV420SPToRGBDart _convertYUV = convertYUV420SPToRGB;
  static const int modelInputWidth = 256;
  static const int modelInputHeight = 256;

  /// Prétraite une image CameraImage pour l'inférence MiDaS (modèle quantifié Uint8).
  ///
  /// Retourne une Liste 4D [1, 256, 256, 3] de type INT (0-255),
  /// prête à être envoyée au TFLiteService, ou null en cas d'erreur.
  Future<List<List<List<List<int>>>>?> preprocessCameraImage(CameraImage image) async { // <<< CHANGEMENT TYPE RETOUR
    Pointer<Uint8>? pY_native = nullptr;
    Pointer<Uint8>? pUV_native = nullptr;
    Pointer<Uint8>? pRGB = nullptr;
    final stopwatch = Stopwatch()..start();

    try {
      print("Preproc START - Image: ${image.width}x${image.height}");

      // --- 1. Validation et Récupération des Données Dart ---
      if (image.planes.length < 2) { print("Preproc FAIL: Moins de 2 plans"); return null; }
      final planeY = image.planes[0]; final planeUV = image.planes[1];
      final int yStride = planeY.bytesPerRow; final int uvStride = planeUV.bytesPerRow;
      final int width = image.width; final int height = image.height;
      final Uint8List yBytes = planeY.bytes; final Uint8List uvBytes = planeUV.bytes;
      print("Preproc 1.1 - Données YUV Dart OK");

      // --- 2. Allocation Mémoire Native et Copie YUV ---
      print("Preproc 2.1 - Allocation pY_native..."); pY_native = calloc<Uint8>(yBytes.lengthInBytes);
      if (pY_native == nullptr) { print("Preproc FAIL: Allocation pY_native"); return null; }
      print("Preproc 2.2 - Copie Y..."); pY_native.asTypedList(yBytes.lengthInBytes).setAll(0, yBytes);
      print("Preproc 2.3 - Allocation pUV_native..."); pUV_native = calloc<Uint8>(uvBytes.lengthInBytes);
       if (pUV_native == nullptr) { print("Preproc FAIL: Allocation pUV_native"); return null; }
       print("Preproc 2.4 - Copie UV..."); pUV_native.asTypedList(uvBytes.lengthInBytes).setAll(0, uvBytes);
      print("Preproc 2.5 - Allocation/Copie YUV Natif OK");

      // --- 3. Allocation Mémoire Native pour RGB ---
      final int rgbBufferSize = width * height * 3;
      print("Preproc 3.1 - Allocation pRGB..."); pRGB = calloc<Uint8>(rgbBufferSize);
      if (pRGB == nullptr) { print("Preproc FAIL: Allocation pRGB"); return null; }
       print("Preproc 3.2 - Allocation RGB OK");

      // --- 4. Appel FFI pour YUV -> RGB ---
      print("Preproc 4.1 - Appel FFI _convertYUV (libyuv)...");
      _convertYUV(pY_native!, pUV_native!, width, height, yStride, uvStride, pRGB);
      print("Preproc 4.2 - Retour Appel FFI _convertYUV");

      // --- 5. Copier les données RGB vers Dart ---
      print("Preproc 5.1 - Copie RGB Natif -> Dart...");
      final Uint8List rgbBytes = Uint8List.fromList(pRGB.asTypedList(rgbBufferSize));
      print("Preproc 5.2 - Copie RGB OK");

      // --- 6. Libération mémoire native RGB --- (Faite dans finally)

      // --- 7. Créer objet Image Dart ---
      print("Preproc 7.1 - Création Image explicite...");
      img_lib.Image rgbImage = img_lib.Image(width, height);
      int byteIndex = 0;
      for (int y = 0; y < height; ++y) {
          for (int x = 0; x < width; ++x) {
              if (byteIndex + 2 < rgbBytes.length) {
                   rgbImage.setPixelRgba(x, y, rgbBytes[byteIndex], rgbBytes[byteIndex + 1], rgbBytes[byteIndex + 2], 255);
              } else { log("Erreur d'indice remplissage rgbImage!", name: "PreprocessingService"); }
              byteIndex += 3;
          }
      }
      print("Preproc 7.2 - Création/Remplissage Image explicite OK");

      // --- 8. Redimensionner l'image ---
      print("Preproc 8.1 - Redimensionnement copyResize...");
      img_lib.Image resizedImage = img_lib.copyResize(rgbImage, width: modelInputWidth, height: modelInputHeight, interpolation: img_lib.Interpolation.linear);
      print("Preproc 8.2 - Redimensionnement OK");

      // --- 9. Formater pour TFLite [1, H, W, C] (Uint8 - PAS de normalisation) ---
      print("Preproc 9.1 - Formatage boucle (Uint8)...");
      // CORRECTION: Crée une liste d'entiers (0-255)
      var imageMatrix = List.generate( 1, // Batch size
        (_) => List.generate( modelInputHeight, // Hauteur
          (y) => List.generate( modelInputWidth, // Largeur
            (x) {
              int pixelValue = resizedImage.getPixel(x, y);
              // Retourne directement les entiers R, G, B
              return [
                img_lib.getRed(pixelValue),
                img_lib.getGreen(pixelValue),
                img_lib.getBlue(pixelValue)
              ]; // Liste [R, G, B] de type int
            },
            growable: false),
          growable: false),
        growable: false);
      print("Preproc 9.2 - Formatage Uint8 OK");

      stopwatch.stop();
      print("Preprocessing: FIN OK. Temps total: ${stopwatch.elapsedMilliseconds} ms.");
      return imageMatrix; // <<< CHANGEMENT TYPE RETOUR

    } catch (e, stacktrace) {
       print("!!! ERREUR FATALE dans preprocessCameraImage: $e\n$stacktrace");
       return null;
    } finally {
       print("Preprocessing FINALLY: Libération mémoire...");
       if (pY_native != null && pY_native != nullptr) calloc.free(pY_native);
       if (pUV_native != null && pUV_native != nullptr) calloc.free(pUV_native);
       if (pRGB != null && pRGB != nullptr) calloc.free(pRGB);
       print("Preprocessing FINALLY: Mémoire libérée.");
    }
  }
}

extension FloatExtension on double { double toFloat() => this; } // Gardé si utile ailleurs