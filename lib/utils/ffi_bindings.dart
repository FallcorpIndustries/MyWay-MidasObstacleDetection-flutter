// lib/utils/ffi_bindings.dart

import 'dart:ffi'; // Pour FFI (Pointer, NativeFunction, DynamicLibrary, Struct, Float, Int32 etc.)
import 'dart:io'; // Pour Platform.isAndroid etc.

import 'package:ffi/ffi.dart'; // Pour calloc/free (sera utile pour le buffer de résultats RANSAC)

// === Explication FFI pour Débutants ===
// (Commentaires détaillés sur FFI omis ici pour la brièveté, voir version précédente)
// === Fin Explication FFI ===


// --- Liaison pour la conversion YUV -> RGB ---

// Typedef pour la signature C de notre fonction native `convert_yuv420sp_to_rgb`.
typedef ConvertYUV420SPToRGBNative = Void Function(
    Pointer<Uint8> pY,    // Pointeur C vers le plan Y (const uint8_t*)
    Pointer<Uint8> pUV,   // Pointeur C vers le plan UV entrelacé (const uint8_t*)
    Int32 width,          // Largeur C (int32_t)
    Int32 height,         // Hauteur C (int32_t)
    Int32 yStride,        // Stride Y C (int32_t)
    Int32 uvStride,       // Stride UV C (int32_t)
    Pointer<Uint8> pOut   // Pointeur C vers le tampon RGB de sortie (uint8_t*)
);

// Typedef pour la fonction Dart équivalente.
typedef ConvertYUV420SPToRGBDart = void Function(
    Pointer<Uint8> pY,
    Pointer<Uint8> pUV,
    int width,
    int height,
    int yStride,
    int uvStride,
    Pointer<Uint8> pOut
);


// --- Structure pour les Résultats RANSAC ---

// Définit une structure Dart qui correspondra à la structure C `RansacPlaneResult`.
// Utile pour recevoir les paramètres des plans détectés depuis le code natif.
// La classe étend `Struct` de `dart:ffi`.
// Les annotations `@Float()` et `@Int32()` indiquent le type C correspondant
// pour chaque champ et leur ordre en mémoire.
final class RansacPlaneResult extends Struct {
  /// Coeff A de l'équation du plan Ax + By + Cz + D = 0
  @Float()
  external double a;

  /// Coeff B de l'équation du plan Ax + By + Cz + D = 0
  @Float()
  external double b;

  /// Coeff C de l'équation du plan Ax + By + Cz + D = 0
  @Float()
  external double c;

  /// Coeff D de l'équation du plan Ax + By + Cz + D = 0
  @Float()
  external double d;

  /// Nombre de points considérés comme "inliers" pour ce plan.
  @Int32()
  external int inlierCount;
}


// --- Liaison pour la détection de murs RANSAC ---

// Typedef pour la signature C de notre fonction native `detect_walls_ransac`.
// Prend la carte de profondeur, dimensions, paramètres caméra (intrinsics),
// paramètres RANSAC, un pointeur vers un tampon pour les résultats (de type RansacPlaneResult),
// et la capacité maximale de ce tampon.
// Retourne le nombre de plans effectivement trouvés et écrits dans le tampon (Int32).
typedef DetectWallsRansacNative = Int32 Function(
    Pointer<Float> depthMapData,     // Pointeur vers les données Float32 de la carte de profondeur
    Int32 width,                     // Largeur de la carte
    Int32 height,                    // Hauteur de la carte
    Float fx, Float fy, Float cx, Float cy, // Paramètres intrinsèques caméra (PLACEHOLDERS !)
    Float distanceThreshold,         // Seuil de distance RANSAC
    Int32 minInliers,                // Nombre min d'inliers requis
    Int32 maxIterations,             // Max d'itérations RANSAC
    Pointer<RansacPlaneResult> outPlanesBuffer, // Pointeur vers tampon de sortie (alloué par Dart)
    Int32 maxPlanes                  // Capacité du tampon de sortie
);

// Typedef pour la fonction Dart équivalente.
// Les Float deviennent double, Int32 devient int, Pointer<RansacPlaneResult> reste.
typedef DetectWallsRansacDart = int Function(
    Pointer<Float> depthMapData,
    int width,
    int height,
    double fx, double fy, double cx, double cy,
    double distanceThreshold,
    int minInliers,
    int maxIterations,
    Pointer<RansacPlaneResult> outPlanesBuffer,
    int maxPlanes
);


// --- Chargement de la bibliothèque native ---

const String _libName = "native_processing";
final DynamicLibrary _nativeLib = Platform.isAndroid
    ? DynamicLibrary.open('lib$_libName.so')
    : DynamicLibrary.process();


// --- Recherche des fonctions natives ---

// Recherche de la fonction YUV -> RGB
final ConvertYUV420SPToRGBDart convertYUV420SPToRGB = _nativeLib
    .lookup<NativeFunction<ConvertYUV420SPToRGBNative>>('convert_yuv420sp_to_rgb')
    .asFunction<ConvertYUV420SPToRGBDart>();

// Recherche de la fonction RANSAC
// Note : L'appel à lookup réussira maintenant, mais la fonction ne sera
// utilisable qu'une fois que detect_walls_ransac sera implémentée en C++
// et compilée dans la bibliothèque libnative_processing.so.
final DetectWallsRansacDart detectWallsRansac = _nativeLib
    .lookup<NativeFunction<DetectWallsRansacNative>>('detect_walls_ransac')
    .asFunction<DetectWallsRansacDart>();