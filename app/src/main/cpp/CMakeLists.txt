# Minimum CMake version required. Should match or be lower than the version
# specified in build.gradle.kts (android.externalNativeBuild.cmake.version).
cmake_minimum_required(VERSION 3.18.1) # Use a version available in Android Studio SDK

# Project name for this native library build.
project("depth_perception_native")

# --- libyuv Integration ---
# Provides highly optimized YUV conversion functions.
# Option 1 (Recommended for beginners): Add libyuv source code directly to the project.
#   1. Download or clone the libyuv source code from its repository:
#      https://chromium.googlesource.com/libyuv/libyuv/
#   2. Place the entire 'libyuv' source directory inside this 'src/main/cpp/' directory.
#      So you'll have: src/main/cpp/libyuv/include, src/main/cpp/libyuv/source, etc.
#   3. Uncomment the following lines:
#
 add_subdirectory(libyuv) # Tells CMake to build libyuv from its source
 set(LIBYUV_INCLUDE_DIRS ${CMAKE_CURRENT_SOURCE_DIR}/libyuv/include) # Path to libyuv headers
 set(LIBYUV_LIBRARIES yuv) # The target name defined in libyuv's CMakeLists.txt

# Option 2: Find prebuilt libyuv libraries (More complex setup).
#   Requires you to manually build or download prebuilt libyuv static (.a) or shared (.so)
#   libraries for the target ABIs (e.g., arm64-v8a) and place them somewhere accessible.
#   You'll also need the libyuv header files.
#   Example (adjust paths as needed):
#   find_library(libyuv-lib yuv HINTS path/to/your/prebuilt/libs/${ANDROID_ABI})
#   set(LIBYUV_INCLUDE_DIRS path/to/your/libyuv/include)
#   set(LIBYUV_LIBRARIES ${libyuv-lib})

# --- Placeholder comment for integration ---
# TODO: Integrate libyuv library using Option 1 (preferred) or Option 2 above.
#       If using Option 1, download libyuv source into src/main/cpp/libyuv and uncomment
#       the add_subdirectory, set(LIBYUV_INCLUDE_DIRS...), and set(LIBYUV_LIBRARIES...) lines.
#       Also uncomment LIBYUV_INCLUDE_DIRS and LIBYUV_LIBRARIES in target_link_libraries
#       and target_include_directories below.
#       THE CODE WILL NOT COMPILE WITHOUT LIBYUV INTEGRATED.

# --- Add Native Source Files ---
# Define the native library target.
# - 'depth_perception_native': This is the name of the library file that will be generated
#   (e.g., libdepth_perception_native.so). It must match the name used in System.loadLibrary().
# - 'SHARED': Specifies building a shared library (.so), which is standard for JNI.
# - List all C/C++ source files that are part of this library.
add_library(
        depth_perception_native
        SHARED
        jni_bridge.cpp      # JNI function implementations, bridges Java/Kotlin to C++
        yuv_converter.cpp   # YUV to RGB conversion logic (uses libyuv)
        ransac.cpp          # RANSAC plane detection algorithm implementation
        # Add other C++ source files here if needed
        )

# --- Find Required NDK Libraries ---
# Find the NDK's logging library, which allows native code to write to Android's Logcat.
# - 'log-lib': Variable name to store the path to the found library.
# - 'log': The name of the NDK library to find (liblog.so).
find_library(
        log-lib
        log
        )

# --- Link Libraries ---
# Link the native library target ('depth_perception_native') against other libraries it depends on.
target_link_libraries(
        depth_perception_native
        # Link against the Android log library found above.
        ${log-lib}
        # Link against libyuv (Uncomment this once libyuv is integrated).
         ${LIBYUV_LIBRARIES}
        # Link against other NDK libraries if needed (e.g., for graphics, math):
        # -lm             # Math library (for functions like sqrt, abs in C) - often linked automatically
        # android
        # OpenGLESv2
        # EGL
        )

# --- Include Directories ---
# Specify directories where header files (.h, .hpp) are located.
# This allows your .cpp files to find headers using #include <...> or #include "..."
target_include_directories(depth_perception_native PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR} # Include current dir for headers like ransac.h
        # Include libyuv headers (Uncomment this once libyuv is integrated)
        ${LIBYUV_INCLUDE_DIRS} # <<< THIS LINE SHOULD BE UNCOMMENTED
        # Add other include paths if needed
) # <<< MAKE SURE THE CLOSING PARENTHESIS IS UNCOMMENTED