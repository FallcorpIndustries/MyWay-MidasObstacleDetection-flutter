// lib/services/preprocessing_service.dart
import 'dart:async';
import 'dart:developer';
import 'dart:ffi';
import 'dart:typed_data';
import 'package:camera/camera.dart';
import 'package:ffi/ffi.dart';
import 'package:image/image.dart' as img_lib;
import 'package:assistive_perception_app/utils/ffi_bindings.dart';

class PreprocessingService {
  final ConvertYUV420SPToRGBDart _convertYUV = convertYUV420SPToRGB;
  static const int modelInputWidth = 256;
  static const int modelInputHeight = 256;
  static const int modelInputChannels = 3; // RGB

  Future<Uint8List?> preprocessCameraImage(CameraImage image) async {
    Pointer<Uint8>? pY_native = nullptr; Pointer<Uint8>? pUV_native = nullptr; Pointer<Uint8>? pRGB = nullptr;
    final stopwatch = Stopwatch()..start();
    try {
      // print("Preproc START - Image: ${image.width}x${image.height}");
      if (image.planes.length < 2) { print("Preproc FAIL: Moins de 2 plans"); return null; }
      final planeY = image.planes[0]; final planeUV = image.planes[1];
      final int yStride = planeY.bytesPerRow; final int uvStride = planeUV.bytesPerRow;
      final int width = image.width; final int height = image.height;
      final Uint8List yBytes = planeY.bytes; final Uint8List uvBytes = planeUV.bytes;
      // print("Preproc 1.1 - Données YUV Dart OK");

      // Allocation et Copie YUV
      pY_native = calloc<Uint8>(yBytes.lengthInBytes); if (pY_native == nullptr) throw Exception("Alloc Y échouée");
      pY_native.asTypedList(yBytes.lengthInBytes).setAll(0, yBytes);
      pUV_native = calloc<Uint8>(uvBytes.lengthInBytes); if (pUV_native == nullptr) throw Exception("Alloc UV échouée");
      pUV_native.asTypedList(uvBytes.lengthInBytes).setAll(0, uvBytes);
      // print("Preproc 2.5 - Allocation/Copie YUV Natif OK");

      // Allocation RGB Natif
      final int rgbBufferSize = width * height * 3; pRGB = calloc<Uint8>(rgbBufferSize);
      if (pRGB == nullptr) throw Exception("Alloc RGB échouée");
      // print("Preproc 3.2 - Allocation RGB OK");

      // Appel FFI (libyuv)
      // print("Preproc 4.1 - Appel FFI _convertYUV (libyuv)...");
      _convertYUV(pY_native!, pUV_native!, width, height, yStride, uvStride, pRGB);
      // print("Preproc 4.2 - Retour Appel FFI _convertYUV");

      // Copie RGB Natif -> Dart
      final Uint8List rgbBytes = Uint8List.fromList(pRGB.asTypedList(rgbBufferSize));
      // print("Preproc 5.2 - Copie RGB OK");

      // Création Image Dart Explicite
      // print("Preproc 7.1 - Création Image explicite...");
      img_lib.Image rgbImage = img_lib.Image(width, height);
      int byteIndex = 0;
      for (int y = 0; y < height; ++y) { for (int x = 0; x < width; ++x) { if (byteIndex + 2 < rgbBytes.length) {
            rgbImage.setPixelRgba(x, y, rgbBytes[byteIndex], rgbBytes[byteIndex + 1], rgbBytes[byteIndex + 2], 255);
      } byteIndex += 3; } }
      // print("Preproc 7.2 - Création/Remplissage Image explicite OK");

      // Redimensionnement
      // print("Preproc 8.1 - Redimensionnement copyResize...");
      img_lib.Image resizedImage = img_lib.copyResize(rgbImage, width: modelInputWidth, height: modelInputHeight, interpolation: img_lib.Interpolation.linear);
      // print("Preproc 8.2 - Redimensionnement OK");

      // Formatage en Uint8List plate [H, W, C]
      // print("Preproc 9.1 - Formatage en Uint8List plate...");
      final inputBytes = Uint8List(modelInputHeight * modelInputWidth * modelInputChannels);
      int bufferIndex = 0;
      for (int y = 0; y < modelInputHeight; y++) { for (int x = 0; x < modelInputWidth; x++) {
          int pixelValue = resizedImage.getPixel(x, y);
          inputBytes[bufferIndex++] = img_lib.getRed(pixelValue);
          inputBytes[bufferIndex++] = img_lib.getGreen(pixelValue);
          inputBytes[bufferIndex++] = img_lib.getBlue(pixelValue);
      }}
      // print("Preproc 9.2 - Formatage Uint8List OK");

      stopwatch.stop(); print("Preproc OK: ${stopwatch.elapsedMilliseconds} ms");
      return inputBytes; // Retourne la liste plate Uint8

    } catch (e, stacktrace) {
       print("!!! ERREUR FATALE dans preprocessCameraImage: $e\n$stacktrace");
       return null;
    } finally {
       // print("Preprocessing FINALLY: Libération mémoire...");
       if (pY_native != null && pY_native != nullptr) calloc.free(pY_native);
       if (pUV_native != null && pUV_native != nullptr) calloc.free(pUV_native);
       if (pRGB != null && pRGB != nullptr) calloc.free(pRGB);
       // print("Preprocessing FINALLY: Mémoire libérée.");
    }
  }
}
extension FloatExtension on double { double toFloat() => this; }