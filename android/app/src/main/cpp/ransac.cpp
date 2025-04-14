/// android/app/src/main/cpp/ransac.cpp

#include "image_utils.h" // Contient la déclaration de la fonction et RansacPlaneResult
#include <vector>        // Pour std::vector (stocker les points 3D)
#include <cmath>         // Pour sqrt, fabs (valeur absolue float)
#include <random>        // Pour la génération de nombres aléatoires (mt19937, uniform_int_distribution)
#include <limits>        // Pour std::numeric_limits
#include <stdexcept>     // Pour std::runtime_error (gestion d'erreurs potentielles)

// Pour le logging Android
#include <android/log.h>
#define LOG_TAG "NativeLib"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__) // Warning
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


// Structure simple pour représenter un point 3D
struct Point3D {
    float x, y, z;
};

// --- Implémentation de la fonction de détection de murs RANSAC ---

extern "C" int detect_walls_ransac(const float* depth_map_data,
                                   int width, int height,
                                   float fx, float fy, float cx, float cy, // Placeholders !
                                   float distance_threshold,
                                   int min_inliers,
                                   int max_iterations,
                                   RansacPlaneResult* out_planes_buffer,
                                   int max_planes) {

    LOGD("Entree detect_walls_ransac. Dim: %dx%d, Thresh: %.3f, MinInl: %d, MaxIter: %d",
         width, height, distance_threshold, min_inliers, max_iterations);
    LOGD("Intrinsics (PLACEHOLDERS!): fx=%.1f, fy=%.1f, cx=%.1f, cy=%.1f", fx, fy, cx, cy);


    // --- Étape 1: Génération du Nuage de Points 3D ---
    // Convertit la carte de profondeur 2D en une liste de points 3D (X, Y, Z).
    std::vector<Point3D> point_cloud;
    point_cloud.reserve(width * height / 4); // Pré-allouer un peu de mémoire (estimation)

    for (int v = 0; v < height; ++v) { // v = coordonnée y de l'image (row)
        for (int u = 0; u < width; ++u) { // u = coordonnée x de l'image (col)
            // depth_map_data est la profondeur INVERSE relative (plus haut = plus proche)
            float inv_d = depth_map_data[v * width + u];

            // Ignorer les pixels invalides ou trop lointains/proches selon le modèle MiDaS
            // (le seuil 0.01f est arbitraire, à ajuster si nécessaire)
            if (inv_d > 0.01f) {
                // Convertir la profondeur inverse en profondeur Z (distance)
                float Z = 1.0f / inv_d;

                // Déprojection 2D -> 3D en utilisant les paramètres intrinsèques
                // IMPORTANT: Utilise fx, fy, cx, cy qui sont des PLACEHOLDERS !
                // La précision de X et Y dépend CRUCIALEMENT de la calibration !
                // Convention de coordonnées caméra fréquente : X vers la droite, Y vers le BAS, Z vers l'avant.
                // Si votre analyse Dart suppose Y vers le HAUT, il faudra ajuster le signe de Y ici ou dans l'analyse.
                // Pour correspondre à l'analyse Dart (Y normal faible = mur vertical), on suppose Y vers le haut.
                float X = (static_cast<float>(u) - cx) * Z / fx;
                float Y = (static_cast<float>(v) - cy) * Z / fy; // Y positif = vers le BAS dans l'image
                // Pour obtenir Y vers le HAUT dans le repère 3D, on peut inverser :
                 Y = -Y;


                // Ajouter le point 3D au nuage
                point_cloud.push_back({X, Y, Z});
            }
        }
    }

    LOGD("Nuage de points généré avec %zu points.", point_cloud.size());

    // Vérification : A-t-on assez de points pour RANSAC ?
    if (point_cloud.size() < 3 || point_cloud.size() < static_cast<size_t>(min_inliers)) {
        LOGW("Pas assez de points valides (%zu) pour RANSAC.", point_cloud.size());
        return 0; // Retourne 0 plans trouvés
    }


    // --- Étape 2: Algorithme RANSAC pour trouver le meilleur plan ---

    int best_inlier_count = -1;
    float best_plane_A = 0, best_plane_B = 0, best_plane_C = 0, best_plane_D = 0;

    // Initialiser le générateur de nombres aléatoires
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<size_t> distrib(0, point_cloud.size() - 1);

    for (int iter = 0; iter < max_iterations; ++iter) {
        // 2a. Sélectionner 3 points aléatoires distincts
        size_t idx1 = distrib(gen);
        size_t idx2 = distrib(gen);
        size_t idx3 = distrib(gen);
        // S'assurer qu'ils sont distincts (méthode simple, peut être optimisée)
        while (idx2 == idx1) { idx2 = distrib(gen); }
        while (idx3 == idx1 || idx3 == idx2) { idx3 = distrib(gen); }

        const Point3D& p1 = point_cloud[idx1];
        const Point3D& p2 = point_cloud[idx2];
        const Point3D& p3 = point_cloud[idx3];

        // 2b. Calculer l'équation du plan Ax + By + Cz + D = 0 passant par p1, p2, p3
        // Vecteur v1 = p2 - p1
        float v1x = p2.x - p1.x;
        float v1y = p2.y - p1.y;
        float v1z = p2.z - p1.z;
        // Vecteur v2 = p3 - p1
        float v2x = p3.x - p1.x;
        float v2y = p3.y - p1.y;
        float v2z = p3.z - p1.z;

        // Calculer la normale N = v1 x v2 (produit vectoriel)
        float A = v1y * v2z - v1z * v2y;
        float B = v1z * v2x - v1x * v2z;
        float C = v1x * v2y - v1y * v2x;

        // Normaliser le vecteur normal (A, B, C) pour que les calculs de distance soient corrects
        float magnitude = sqrt(A * A + B * B + C * C);
        if (magnitude < 1e-6) { // Éviter division par zéro / points colinéaires
            continue; // Passe à l'itération suivante si les points sont dégénérés
        }
        A /= magnitude;
        B /= magnitude;
        C /= magnitude;

        // Calculer D: D = -(A*p1.x + B*p1.y + C*p1.z)
        float D = -(A * p1.x + B * p1.y + C * p1.z);

        // 2c. Compter les inliers pour ce plan candidat
        int current_inlier_count = 0;
        for (const auto& pt : point_cloud) {
            // Calculer la distance perpendiculaire du point au plan
            // distance = |Ax + By + Cz + D| / sqrt(A^2+B^2+C^2)
            // Comme le vecteur normal (A,B,C) est déjà normalisé (magnitude=1),
            // la distance est juste |Ax + By + Cz + D|
            float distance = std::fabs(A * pt.x + B * pt.y + C * pt.z + D);

            if (distance < distance_threshold) {
                current_inlier_count++;
            }
        }

        // 2d. Mettre à jour le meilleur plan si celui-ci est meilleur
        if (current_inlier_count > best_inlier_count) {
            best_inlier_count = current_inlier_count;
            best_plane_A = A;
            best_plane_B = B;
            best_plane_C = C;
            best_plane_D = D;
        }
    } // Fin de la boucle RANSAC

    LOGD("RANSAC terminé. Meilleur plan trouvé avec %d inliers.", best_inlier_count);

    // --- Étape 3: Retourner le résultat ---

    // Vérifier si le meilleur plan trouvé est suffisamment bon (assez d'inliers)
    // et si l'appelant a fourni un tampon de sortie capable de recevoir au moins 1 plan.
    if (best_inlier_count >= min_inliers && max_planes >= 1) {
        LOGD("Plan valide trouvé ! A=%.2f, B=%.2f, C=%.2f, D=%.2f",
             best_plane_A, best_plane_B, best_plane_C, best_plane_D);

        // Remplir la première structure dans le tampon de sortie fourni par Dart
        out_planes_buffer[0].a = best_plane_A;
        out_planes_buffer[0].b = best_plane_B;
        out_planes_buffer[0].c = best_plane_C;
        out_planes_buffer[0].d = best_plane_D;
        out_planes_buffer[0].inlier_count = static_cast<int32_t>(best_inlier_count); // Cast en int32_t

        return 1; // Retourne 1 (nombre de plans trouvés et écrits)
    } else {
        if (best_inlier_count < min_inliers) {
           LOGD("Meilleur plan n'a pas assez d'inliers (%d < %d).", best_inlier_count, min_inliers);
        }
         if (max_planes < 1) {
             LOGW("Le tampon de sortie fourni ne peut contenir aucun plan (max_planes=%d).", max_planes);
         }
        return 0; // Retourne 0 (aucun plan valide écrit dans le tampon)
    }
}