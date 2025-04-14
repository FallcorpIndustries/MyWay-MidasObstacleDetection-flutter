// android/app/src/main/cpp/image_utils.h

#ifndef IMAGE_UTILS_H
#define IMAGE_UTILS_H

#include <stdint.h> // Pour uint8_t, int32_t etc.

// Définit la structure C pour retourner les résultats RANSAC.
typedef struct {
    float a, b, c, d;
    int32_t inlier_count;
} RansacPlaneResult;


// Définit une macro JNI_EXPORT.
// Si le compilateur est GCC ou Clang (qui définissent __GNUC__),
// la macro sera remplacée par les attributs de visibilité nécessaires pour FFI.
// Sinon (par exemple, pour l'IntelliSense VS Code s'il utilise un mode MSVC),
// la macro sera remplacée par rien du tout, évitant les erreurs de syntaxe.
#ifdef __GNUC__
#define JNI_EXPORT __attribute__((visibility("default"))) __attribute__((used))
#else
#define JNI_EXPORT
#endif

// Bloc extern "C" pour la compatibilité des noms FFI
#ifdef __cplusplus
extern "C" {
#endif

// --- Déclaration de la fonction de conversion YUV -> RGB ---
/**
 * @brief Convertit YUV420 Semi-Planar (NV21/NV12) en RGB888. (Implémentation LENTE !)
 * ... (params) ...
 */
// Applique la macro AVANT le type de retour.
JNI_EXPORT
void convert_yuv420sp_to_rgb(const uint8_t* y_plane,
                             const uint8_t* uv_plane,
                             int width, int height,
                             int y_stride, int uv_stride,
                             uint8_t* out_rgb_buffer);


// --- Déclaration de la fonction de détection de murs RANSAC ---
/**
 * @brief Détecte des plans (murs potentiels) dans une carte de profondeur via RANSAC.
 * ... (params) ...
 * @return Le nombre de plans détectés.
 */
// Applique la macro AVANT le type de retour.
JNI_EXPORT
int detect_walls_ransac(const float* depth_map_data,
                        int width, int height,
                        float fx, float fy, float cx, float cy,
                        float distance_threshold,
                        int min_inliers,
                        int max_iterations,
                        RansacPlaneResult* out_planes_buffer,
                        int max_planes);


#ifdef __cplusplus
} // extern "C"
#endif

#endif // IMAGE_UTILS_H