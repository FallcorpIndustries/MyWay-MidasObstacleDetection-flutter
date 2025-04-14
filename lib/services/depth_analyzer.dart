// lib/services/depth_analyzer.dart

import 'dart:ffi';          // Pour FFI (Pointer, Float, Int32)
import 'dart:developer';    // Pour log()
import 'dart:typed_data';   // Pour Float32List
import 'dart:math' as math; // Garde l'import préfixé pour math.sqrt

import 'package:ffi/ffi.dart';      // Pour calloc et free (gestion mémoire native)

// Importe nos modèles de données et liaisons FFI
import 'package:assistive_perception_app/models/enums.dart';
import 'package:assistive_perception_app/models/depth_analysis_result.dart';
import 'package:assistive_perception_app/utils/ffi_bindings.dart'; // Adaptez si chemin différent

/// Service responsable de l'analyse de la carte de profondeur générée par TFLite (MiDaS).
class DepthAnalyzer {

  // --- Constantes pour l'Analyse de Profondeur ---
  static const double OBSTACLE_CLOSENESS_THRESHOLD = 0.75;
  static const double OBSTACLE_VERY_CLOSE_THRESHOLD = 0.9;
  static const double FREE_PATH_FARNESS_THRESHOLD = 0.25;

  // --- Constantes pour RANSAC ---
  static const double RANSAC_DISTANCE_THRESHOLD = 0.08;
  static const int RANSAC_MIN_INLIERS = 500;
  static const int RANSAC_MAX_ITERATIONS = 50;
  static const int RANSAC_MAX_PLANES_TO_DETECT = 1;

  // --- PARAMÈTRES INTRINSÈQUES DE LA CAMÉRA (PLACEHOLDERS !) ---
  // (Commentaires importants sur la calibration omis ici pour la brièveté)
  static const double CAMERA_FX = 250.0; // Placeholder !
  static const double CAMERA_FY = 250.0; // Placeholder !
  static const double CAMERA_CX = 128.0; // Placeholder !
  static const double CAMERA_CY = 128.0; // Placeholder !
  // --- FIN PARAMÈTRES INTRINSÈQUES ---


  /// Analyse la carte de profondeur (sortie de TFLiteService) pour détecter obstacles,
  /// chemin libre et murs (via FFI/RANSAC).
  ///
  /// [depthMap]: Liste 3D [1, H, W] contenant les valeurs de profondeur inverse relative (type double).
  /// Retourne un objet [DepthAnalysisResult] ou null en cas d'erreur.
  Future<DepthAnalysisResult?> analyzeDepthMap(List<List<List<double>>>? depthMap) async {
    if (depthMap == null || depthMap.isEmpty || depthMap[0].isEmpty) {
      log("Erreur: Carte de profondeur invalide reçue.", name: "DepthAnalyzer");
      return null;
    }

    final List<List<double>> map2D = depthMap[0];
    final int height = map2D.length;
    final int width = map2D[0].length;

    if (height == 0 || width == 0) {
       log("Erreur: Carte de profondeur vide (dimensions 0).", name: "DepthAnalyzer");
       return null;
    }

    log("Analyse de la carte de profondeur ${width}x${height}", name: "DepthAnalyzer");
    final stopwatch = Stopwatch()..start();

    ObstacleProximity obstacleProximity = ObstacleProximity.None;
    WallDirection wallDirection = WallDirection.None;
    FreePathDirection freePathDirection = FreePathDirection.None;
    double maxCloseness = 0.0;

    // --- 1. Convertir la carte en Float32List et trouver maxCloseness ---
    final Float32List depthFloatList = Float32List(width * height);
    int flatIndex = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final double depthValue = map2D[y][x];
        depthFloatList[flatIndex++] = depthValue.toFloat();
        if (depthValue > OBSTACLE_CLOSENESS_THRESHOLD && depthValue > maxCloseness) {
          maxCloseness = depthValue;
        }
      }
    }
    log("Conversion en Float32List et calcul maxCloseness terminés.", name: "DepthAnalyzer");

    // --- 2. Déterminer la Proximité de l'Obstacle ---
    // (Logique inchangée)
    if (maxCloseness >= OBSTACLE_VERY_CLOSE_THRESHOLD) {
      obstacleProximity = ObstacleProximity.VeryClose;
    } else if (maxCloseness > OBSTACLE_CLOSENESS_THRESHOLD) {
      obstacleProximity = ObstacleProximity.Detected;
    } else {
      obstacleProximity = ObstacleProximity.None;
    }
    log("Proximité obstacle déterminée: ${obstacleProximity.name} (maxCloseness: ${maxCloseness.toStringAsFixed(3)})", name: "DepthAnalyzer");

    // --- 3. Estimation Simpliste du Chemin Libre ---
    // (Logique inchangée)
    int freePathCenterCount = 0;
    int freePathLeftCount = 0;
    int freePathRightCount = 0;
    int totalConsidered = 0;
    final int startY = height ~/ 2;
    final int thirdWidth = width ~/ 3;
    for (int y = startY; y < height; y++) {
       for (int x = 0; x < width; x++) {
           totalConsidered++;
           final double depthValue = depthFloatList[y * width + x];
           if (depthValue < FREE_PATH_FARNESS_THRESHOLD) {
               if (x < thirdWidth) freePathLeftCount++;
               else if (x >= width - thirdWidth) freePathRightCount++;
               else freePathCenterCount++;
           }
       }
    }
    if (freePathCenterCount >= freePathLeftCount && freePathCenterCount >= freePathRightCount && freePathCenterCount > totalConsidered * 0.1) {
        freePathDirection = FreePathDirection.Center;
    } else if (freePathLeftCount > freePathCenterCount && freePathLeftCount >= freePathRightCount && freePathLeftCount > totalConsidered * 0.1) {
        freePathDirection = FreePathDirection.Left;
    } else if (freePathRightCount > freePathCenterCount && freePathRightCount > freePathLeftCount && freePathRightCount > totalConsidered * 0.1) {
        freePathDirection = FreePathDirection.Right;
    } else {
        freePathDirection = FreePathDirection.None;
    }
    log("Direction chemin libre estimée: ${freePathDirection.name}", name: "DepthAnalyzer");

    // --- 4. Détection de Murs via FFI/RANSAC ---
    Pointer<Float>? depthPtr = nullptr;
    Pointer<RansacPlaneResult>? resultsBuffer = nullptr;
    try {
      depthPtr = calloc<Float>(width * height);
      if (depthPtr == nullptr) throw Exception("Allocation échouée pour depthPtr");
      final nativeDepthList = depthPtr.asTypedList(width * height);
      nativeDepthList.setAll(0, depthFloatList);

      resultsBuffer = calloc<RansacPlaneResult>(RANSAC_MAX_PLANES_TO_DETECT);
       if (resultsBuffer == nullptr) throw Exception("Allocation échouée pour resultsBuffer");

      log("Appel FFI RANSAC...", name: "DepthAnalyzer");
      final int planesFound = detectWallsRansac(
        depthPtr, width, height,
        CAMERA_FX, CAMERA_FY, CAMERA_CX, CAMERA_CY,
        RANSAC_DISTANCE_THRESHOLD, RANSAC_MIN_INLIERS, RANSAC_MAX_ITERATIONS,
        resultsBuffer, RANSAC_MAX_PLANES_TO_DETECT
      );
      log("FFI RANSAC terminé. Plans trouvés: $planesFound", name: "DepthAnalyzer");

      if (planesFound > 0) {
         final RansacPlaneResult plane = resultsBuffer.ref;
         log("Plan[0]: A=${plane.a.toStringAsFixed(2)}, B=${plane.b.toStringAsFixed(2)}, C=${plane.c.toStringAsFixed(2)}, D=${plane.d.toStringAsFixed(2)}, Inliers=${plane.inlierCount}", name: "DepthAnalyzer");

         // CORRECTION FINALE: Utilise la méthode .abs() intégrée
         double normalMagnitudeXZ = math.sqrt(plane.a * plane.a + plane.c * plane.c); // Garde math.sqrt
         if (normalMagnitudeXZ > 0.01) {
             // Utilise (valeur).abs() au lieu de math.abs(valeur)
             if ((plane.b).abs() / normalMagnitudeXZ < 0.20) {
                 if ((plane.a).abs() > (plane.c).abs() * 1.5) {
                     wallDirection = (plane.a > 0) ? WallDirection.Left : WallDirection.Right;
                 } else if ((plane.c).abs() > (plane.a).abs() * 1.5) {
                      wallDirection = WallDirection.Front;
                 } else {
                      wallDirection = WallDirection.Front;
                 }
                 log("Mur vertical détecté. Direction estimée: ${wallDirection.name}", name: "DepthAnalyzer");
             } else { log("Plan détecté mais probablement pas vertical.", name: "DepthAnalyzer");}
         } else { log("Plan détecté mais normal trop faible en XZ.", name: "DepthAnalyzer");}
      } else { log("Aucun mur détecté par RANSAC.", name: "DepthAnalyzer"); wallDirection = WallDirection.None; }

    } catch (e, stacktrace) {
       log("Erreur pendant l'appel FFI RANSAC ou traitement: $e", name: "DepthAnalyzer", stackTrace: stacktrace);
       wallDirection = WallDirection.None;
    } finally {
       if (depthPtr != null && depthPtr != nullptr) calloc.free(depthPtr);
       if (resultsBuffer != null && resultsBuffer != nullptr) calloc.free(resultsBuffer);
       log("Mémoire native pour RANSAC libérée.", name: "DepthAnalyzer");
    }

    // --- 5. Retourner le résultat combiné ---
    stopwatch.stop();
    log("Analyse terminée en ${stopwatch.elapsedMilliseconds} ms.", name: "DepthAnalyzer");

    return DepthAnalysisResult(
      obstacleProximity: obstacleProximity,
      wallDirection: wallDirection,
      freePathDirection: freePathDirection,
    );
  }
}

// Extension pour clarifier la conversion double -> float pour FFI Pointer<Float>
extension FloatExtension on double {
  double toFloat() => this; // Retourne simplement le double.
}