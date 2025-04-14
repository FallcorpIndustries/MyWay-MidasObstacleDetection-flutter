// lib/services/depth_analyzer.dart

import 'dart:ffi';          // Pour FFI (Pointer, Float, Int32)
import 'dart:developer';    // Pour log()
import 'dart:typed_data';   // Pour Float32List
import 'dart:math' as math; // Importe dart:math AVEC un préfixe 'math'

import 'package:ffi/ffi.dart';      // Pour calloc et free (gestion mémoire native)

// Importe nos modèles de données et liaisons FFI
import 'package:assistive_perception_app/models/enums.dart';
import 'package:assistive_perception_app/models/depth_analysis_result.dart';
import 'package:assistive_perception_app/utils/ffi_bindings.dart'; // Adaptez si chemin différent

/// Service responsable de l'analyse de la carte de profondeur générée par TFLite (MiDaS).
/// Détecte les obstacles, estime le chemin libre et appelle la fonction native RANSAC via FFI
/// pour la détection de murs.
class DepthAnalyzer {

  // --- Constantes pour l'Analyse de Profondeur ---
  // Seuils basés sur la sortie de MiDaS (profondeur INVERSE relative).
  // + ÉLEVÉ = + PROCHE ; + BAS = + LOIN. À AJUSTER !
  static const double OBSTACLE_CLOSENESS_THRESHOLD = 0.75;
  static const double OBSTACLE_VERY_CLOSE_THRESHOLD = 0.9;
  static const double FREE_PATH_FARNESS_THRESHOLD = 0.25;

  // --- Constantes pour RANSAC (passées à la fonction FFI) ---
  // À AJUSTER finement par expérimentation !
  static const double RANSAC_DISTANCE_THRESHOLD = 0.08; // Mètres (approx. si intrinsics corrects)
  static const int RANSAC_MIN_INLIERS = 500;
  static const int RANSAC_MAX_ITERATIONS = 50;
  static const int RANSAC_MAX_PLANES_TO_DETECT = 1; // Phase 1: 1 mur max

  // --- PARAMÈTRES INTRINSÈQUES DE LA CAMÉRA (PLACEHOLDERS !) ---
  // IMPORTANTISSIME : Ces valeurs sont des PLACEHOLDERS et INCORRECTES.
  // Elles DOIVENT être remplacées par les valeurs de CALIBRATION de VOTRE appareil
  // pour que RANSAC fonctionne correctement.
  // fx, fy : Longueurs focales (pixels)
  // cx, cy : Point principal (pixels)
  static const double CAMERA_FX = 250.0; // Placeholder ! À CALIBRER !
  static const double CAMERA_FY = 250.0; // Placeholder ! À CALIBRER !
  static const double CAMERA_CX = 128.0; // Placeholder ! (width / 2)
  static const double CAMERA_CY = 128.0; // Placeholder ! (height / 2)
  // --- FIN PARAMÈTRES INTRINSÈQUES ---


  /// Analyse la carte de profondeur (sortie de TFLiteService) pour détecter obstacles,
  /// chemin libre et murs (via FFI/RANSAC).
  ///
  /// [depthMap]: Liste 4D [1, H, W, 1] contenant les valeurs de profondeur inverse (double).
  /// Retourne un objet [DepthAnalysisResult] ou null en cas d'erreur.
  Future<DepthAnalysisResult?> analyzeDepthMap(List<List<List<List<double>>>>? depthMap) async {
    // Vérification de l'entrée
    if (depthMap == null || depthMap.isEmpty || depthMap[0].isEmpty || depthMap[0][0].isEmpty || depthMap[0][0][0].isEmpty) {
      log("Erreur: Carte de profondeur invalide ou vide reçue.", name: "DepthAnalyzer");
      return null;
    }

    // Extraction des dimensions (suppose B=1, C=1)
    final int height = depthMap[0].length;
    final int width = depthMap[0][0].length;
    if (height == 0 || width == 0) {
       log("Erreur: Carte de profondeur vide (dimensions 0).", name: "DepthAnalyzer");
       return null;
    }

    log("Analyse de la carte de profondeur ${width}x${height} (1 canal)", name: "DepthAnalyzer");
    final stopwatch = Stopwatch()..start();

    // Variables pour les résultats
    ObstacleProximity obstacleProximity = ObstacleProximity.None;
    WallDirection wallDirection = WallDirection.None;
    FreePathDirection freePathDirection = FreePathDirection.None;
    double maxCloseness = 0.0;

    // --- 1. Convertir en Float32List plate et trouver maxCloseness ---
    // Float32List est plus facile à passer via FFI Pointer<Float>
    final Float32List depthFloatList = Float32List(width * height);
    int flatIndex = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        // Accéder à la valeur de profondeur dans le canal 0 de la structure 4D
        final double depthValue = depthMap[0][y][x][0];
        depthFloatList[flatIndex++] = depthValue.toFloat(); // Utilise l'extension

        // Calculer maxCloseness en même temps
        if (depthValue > OBSTACLE_CLOSENESS_THRESHOLD && depthValue > maxCloseness) {
          maxCloseness = depthValue;
        }
      }
    }
    // log("Conversion Float32List & maxCloseness OK.", name: "DepthAnalyzer");


    // --- 2. Déterminer la Proximité de l'Obstacle ---
    if (maxCloseness >= OBSTACLE_VERY_CLOSE_THRESHOLD) { obstacleProximity = ObstacleProximity.VeryClose; }
    else if (maxCloseness > OBSTACLE_CLOSENESS_THRESHOLD) { obstacleProximity = ObstacleProximity.Detected; }
    else { obstacleProximity = ObstacleProximity.None; }
    // log("Proximité obstacle: ${obstacleProximity.name}", name: "DepthAnalyzer");


    // --- 3. Estimation Simpliste du Chemin Libre ---
    // (Logique inchangée, utilise depthFloatList)
    int freePathCenterCount = 0; int freePathLeftCount = 0; int freePathRightCount = 0; int totalConsidered = 0;
    final int startY = height ~/ 2; final int thirdWidth = width ~/ 3;
    for (int y = startY; y < height; y++) {
       for (int x = 0; x < width; x++) {
           totalConsidered++; final double depthValue = depthFloatList[y * width + x];
           if (depthValue < FREE_PATH_FARNESS_THRESHOLD) {
               if (x < thirdWidth) freePathLeftCount++;
               else if (x >= width - thirdWidth) freePathRightCount++;
               else freePathCenterCount++;
           }
       }
    }
    if (freePathCenterCount >= freePathLeftCount && freePathCenterCount >= freePathRightCount && freePathCenterCount > totalConsidered * 0.1) { freePathDirection = FreePathDirection.Center;}
    else if (freePathLeftCount > freePathCenterCount && freePathLeftCount >= freePathRightCount && freePathLeftCount > totalConsidered * 0.1) { freePathDirection = FreePathDirection.Left; }
    else if (freePathRightCount > freePathCenterCount && freePathRightCount > freePathLeftCount && freePathRightCount > totalConsidered * 0.1) { freePathDirection = FreePathDirection.Right;}
    else { freePathDirection = FreePathDirection.None; }
    // log("Chemin libre estimé: ${freePathDirection.name}", name: "DepthAnalyzer");


    // --- 4. Détection de Murs via FFI/RANSAC ---
    Pointer<Float>? depthPtr = nullptr; // Pointeur vers copie native de la carte
    Pointer<RansacPlaneResult>? resultsBuffer = nullptr; // Pointeur vers buffer résultats C
    try {
      // Allouer mémoire native pour la carte de profondeur (type C float)
      depthPtr = calloc<Float>(width * height);
      if (depthPtr == nullptr) throw Exception("Allocation échouée pour depthPtr");
      // Copier les données depuis la liste Dart Float32List vers le pointeur natif Float*
      final nativeDepthList = depthPtr.asTypedList(width * height);
      nativeDepthList.setAll(0, depthFloatList);

      // Allouer mémoire native pour recevoir les résultats de RANSAC
      resultsBuffer = calloc<RansacPlaneResult>(RANSAC_MAX_PLANES_TO_DETECT);
       if (resultsBuffer == nullptr) throw Exception("Allocation échouée pour resultsBuffer");

      log("Appel FFI RANSAC...", name: "DepthAnalyzer");
      // Appel de la fonction native C++ via la liaison FFI
      final int planesFound = detectWallsRansac( // Fonction importée de ffi_bindings.dart
        depthPtr, width, height,
        CAMERA_FX, CAMERA_FY, CAMERA_CX, CAMERA_CY, // !! PLACEHOLDERS !!
        RANSAC_DISTANCE_THRESHOLD,
        RANSAC_MIN_INLIERS,
        RANSAC_MAX_ITERATIONS,
        resultsBuffer, RANSAC_MAX_PLANES_TO_DETECT
      );
      log("FFI RANSAC terminé. Plans trouvés: $planesFound", name: "DepthAnalyzer");

      // Traiter les résultats si un plan a été trouvé
      if (planesFound > 0) {
         // Accéder aux données du premier plan via .ref sur le pointeur
         final RansacPlaneResult plane = resultsBuffer.ref;
         log("Plan[0]: A=${plane.a.toStringAsFixed(2)}, B=${plane.b.toStringAsFixed(2)}, C=${plane.c.toStringAsFixed(2)}, D=${plane.d.toStringAsFixed(2)}, Inliers=${plane.inlierCount}", name: "DepthAnalyzer");

         // Analyse simple de la normale (A, B, C) pour mur vertical (B faible)
         double normalMagnitudeXZ = math.sqrt(plane.a * plane.a + plane.c * plane.c);
         if (normalMagnitudeXZ > 0.01) {
             // Utilise .abs() sur les doubles
             if ((plane.b).abs() / normalMagnitudeXZ < 0.20) { // Seuil arbitraire pour verticalité
                 // Logique simpliste pour direction G/D/Front
                 if ((plane.a).abs() > (plane.c).abs() * 1.5) { wallDirection = (plane.a > 0) ? WallDirection.Left : WallDirection.Right; }
                 else if ((plane.c).abs() > (plane.a).abs() * 1.5) { wallDirection = WallDirection.Front; }
                 else { wallDirection = WallDirection.Front; }
                 log("Mur vertical détecté. Direction: ${wallDirection.name}", name: "DepthAnalyzer");
             } else { log("Plan détecté non vertical.", name: "DepthAnalyzer"); }
         } else { log("Plan détecté normal XZ faible.", name: "DepthAnalyzer"); }
      } else {
          log("Aucun mur détecté par RANSAC (placeholder actif).", name: "DepthAnalyzer"); // Log adapté au placeholder
          wallDirection = WallDirection.None;
      }

    } catch (e, stacktrace) {
       log("Erreur FFI RANSAC: $e", name: "DepthAnalyzer", stackTrace: stacktrace);
       wallDirection = WallDirection.None;
    } finally {
       // Libérer IMPÉRATIVEMENT la mémoire native allouée avec calloc !
       log("Libération mémoire native RANSAC...", name: "DepthAnalyzer");
       if (depthPtr != null && depthPtr != nullptr) calloc.free(depthPtr);
       if (resultsBuffer != null && resultsBuffer != nullptr) calloc.free(resultsBuffer);
       log("Mémoire native RANSAC libérée.", name: "DepthAnalyzer");
    }

    // --- 5. Retourner le résultat combiné ---
    stopwatch.stop();
    log("Analyse terminée en ${stopwatch.elapsedMilliseconds} ms.", name: "DepthAnalyzer");

    return DepthAnalysisResult(
      obstacleProximity: obstacleProximity,
      wallDirection: wallDirection, // Sera 'None' tant que RANSAC C++ est vide
      freePathDirection: freePathDirection,
    );
  } // Fin analyzeDepthMap

} // Fin DepthAnalyzer

// Extension (inchangée)
extension FloatExtension on double {
  double toFloat() => this;
}