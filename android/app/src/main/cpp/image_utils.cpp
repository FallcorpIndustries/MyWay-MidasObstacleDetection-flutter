// android/app/src/main/cpp/image_utils.cpp

#include "image_utils.h" // Notre en-tête
#include <stdint.h>     // Pour uint8_t

// Inclut l'en-tête principal de libyuv
// NE COMPILERA PAS si libyuv n'est pas correctement intégré via CMake
#include "libyuv.h" // Assurez-vous que ce chemin est trouvable par CMake après add_subdirectory

// Logging Android
#include <android/log.h>
#define LOG_TAG "NativeLib"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


// --- Implémentation de la conversion YUV -> RGB (UTILISANT LIBYUV) ---

// Cette version appelle libyuv pour une conversion performante.
// Utilise NV12ToRAW (plan Y, puis plan UV entrelacé U, V, U, V...).
extern "C" void convert_yuv420sp_to_rgb(const uint8_t* y_plane,
                                        const uint8_t* uv_plane, // Pointeur vers le début du plan UV
                                        int width, int height,
                                        int y_stride, int uv_stride,
                                        uint8_t* out_rgb_buffer) { // Tampon de sortie RGB888

    // Log d'entrée (peut être commenté si trop verbeux)
    // LOGD("Entree convert_yuv420sp_to_rgb (via libyuv NV12). Dim: %dx%d, Stride Y/UV: %d/%d",
    //      width, height, y_stride, uv_stride);

    int rgb_stride = width * 3; // Stride pour le buffer RGB de sortie

    // Appeler la fonction de conversion NV12 vers RGB (RAW) de libyuv.
    int result = libyuv::NV12ToRAW(
        y_plane,        // Pointeur source Y
        y_stride,       // Stride source Y
        uv_plane,       // Pointeur source UV (NV12 attend UV)
        uv_stride,      // Stride source UV
        out_rgb_buffer, // Pointeur destination RGB (RAW)
        rgb_stride,     // Stride destination RGB
        width,          // Largeur
        height          // Hauteur
    );

    // Vérifier si la conversion libyuv a réussi (retourne 0 si succès)
    if (result != 0) {
        LOGE("Erreur lors de l'appel a libyuv::NV12ToRAW : code d'erreur %d", result);
    }
    // Log de fin (optionnel)
    // else {
    //     LOGD("Fin convert_yuv420sp_to_rgb (via libyuv::NV12ToRAW) : Succès");
    // }
} // Fin de la fonction


// NOTE: L'implémentation de detect_walls_ransac se trouve dans ransac.cpp
// (version minimale qui retourne 0 pour l'instant)