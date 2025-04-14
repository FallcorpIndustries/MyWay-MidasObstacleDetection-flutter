# android/app/src/main/cpp/CMakeLists.txt

# Définit la version minimale de CMake requise.
# Doit correspondre (ou être <=) à celle dans android/app/build.gradle.kts
cmake_minimum_required(VERSION 3.18.1) # Ou 3.22.1, 3.31.6 etc.

# Définit le nom du projet CMake.
project("native_processing")

# --- INTÉGRATION DE LIBYUV ---
# Ajoute le sous-répertoire contenant le code source et le CMakeLists.txt de libyuv.
# REQUIERT que le code source de libyuv soit dans le dossier: cpp/libyuv/
# Crée la cible 'yuv' (généralement statique).
add_subdirectory(libyuv)
# -----------------------------


# --- AJOUT DE VOTRE BIBLIOTHÈQUE NATIVE ---
# Définit votre bibliothèque 'native_processing' (partagée - .so)
add_library(
        native_processing
        SHARED
        image_utils.cpp   # Doit inclure et appeler libyuv
        ransac.cpp        # Code RANSAC (minimal ou complet)
)

# --- AJOUT DES CHEMINS D'INCLUSION ---
# Indique au compilateur où trouver les fichiers .h de libyuv
# lorsque l'on compile la cible 'native_processing'.
target_include_directories(native_processing
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libyuv/include # Chemin vers les .h de libyuv
)
# --- FIN AJOUT CHEMINS D'INCLUSION ---


# --- RECHERCHE DES BIBLIOTHÈQUES NDK ---
# Recherche la bibliothèque de log Android
find_library(
        log-lib
        log
)

# --- LIAISON DES BIBLIOTHÈQUES ---
# Lie ${log-lib} ET la cible 'yuv' (de libyuv) à votre bibliothèque native.
target_link_libraries(
        native_processing
        PRIVATE
        ${log-lib}  # Bibliothèque de log NDK
        yuv         # Bibliothèque libyuv
)