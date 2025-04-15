#include "yuv_converter.h"

#include <android/log.h> // For logging from native code

// --- libyuv Integration ---
// IMPORTANT: This file assumes that libyuv has been correctly integrated
// into the project via CMakeLists.txt (either using add_subdirectory or find_library)
// and that the necessary header files are accessible in the include path.
// If libyuv is not integrated, this file WILL NOT COMPILE.
#include "libyuv.h" // Main header for libyuv functions (e.g., I420ToARGB, NV12ToARGB)

#define TAG_YUV "YUV_CONVERTER" // Tag for logging

/**
 * Implementation of the YUV_420_888 to ARGB_8888 conversion.
 */
bool convertToRgb(
        int width, int height,
        const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
        int yRowStride, int uvPixelStride, int uvRowStride,
        uint8_t* outData // Expects ARGB output buffer (width * height * 4 bytes)
) {
    // Calculate the stride for the destination ARGB buffer.
    // ARGB format uses 4 bytes per pixel (Alpha, Red, Green, Blue).
    int dstStride = width * 4;
    int result = -1; // libyuv functions usually return 0 on success, negative on error.

    // --- Determine YUV Format and Call Appropriate libyuv Function ---
    // Android's YUV_420_888 format can map to different underlying memory layouts.
    // The key clues are the `uvPixelStride` and potentially the relationship between planes/strides.
    // - If uvPixelStride == 1: Chroma planes (U, V) are typically fully planar (like I420).
    //   Each chroma sample corresponds to a 2x2 block of luma samples. U and V have separate planes.
    // - If uvPixelStride == 2: Chroma planes (U, V) are typically interleaved (semi-planar, like NV12 or NV21).
    //   There's one chroma plane where U/V samples alternate.

    // Log the parameters received for debugging format issues
    // __android_log_print(ANDROID_LOG_DEBUG, TAG_YUV, "convertToRgb called: W=%d, H=%d, yStride=%d, uvPixStride=%d, uvRowStride=%d",
    //                     width, height, yRowStride, uvPixelStride, uvRowStride);

    if (uvPixelStride == 1) {
        // --- Assume Planar Format (I420) ---
        // I420 layout: [YYYYYYYY], [UUUU], [VVVV]
        // All planes are separate. U and V planes have half width and half height compared to Y.
        // libyuv::I420ToARGB is the function for this.
        // Note: uvRowStride is used for both U and V planes' strides in planar formats.
        __android_log_print(ANDROID_LOG_INFO, TAG_YUV, "uvPixelStride=1. Assuming I420 planar format. Calling libyuv::I420ToARGB.");
        result = libyuv::I420ToARGB(
                yData, yRowStride,    // Y plane data and its row stride
                uData, uvRowStride,   // U plane data and its row stride
                vData, uvRowStride,   // V plane data and its row stride
                outData, dstStride,   // Output ARGB buffer and its row stride
                width, height        // Image dimensions
        );

    } else if (uvPixelStride == 2) {
        // --- Assume Semi-Planar Format (NV12 or NV21) ---
        // NV12 layout: [YYYYYYYY], [UVUVUVUV] (U/V interleaved in one plane)
        // NV21 layout: [YYYYYYYY], [VUVUVUVU] (V/U interleaved in one plane)
        // Both have a full-size Y plane and one chroma plane with half width/height but stride might be full width.
        // The U/V data is interleaved within the second plane.
        // We need to figure out if it's NV12 or NV21. NV12 is more common on Android.

        // Assumption: Based on common Android practice and how ImageProxy usually provides
        // planes for semi-planar YUV_420_888, the buffer associated with the U plane (`uData`)
        // often contains the interleaved UV data for NV12. The V plane buffer might point
        // to the same memory or be structured differently depending on the implementation.
        // We'll default to trying NV12 using the uData buffer as the interleaved source.

        __android_log_print(ANDROID_LOG_INFO, TAG_YUV, "uvPixelStride=2. Assuming NV12 semi-planar format (UV interleaved). Calling libyuv::NV12ToARGB.");
        // For NV12, libyuv expects the pointer to the beginning of the interleaved UV plane.
        result = libyuv::NV12ToARGB(
                yData, yRowStride,      // Y plane data and its row stride
                uData, uvRowStride,     // Interleaved UV plane data (using uBuffer) and its stride
                outData, dstStride,     // Output ARGB buffer and its row stride
                width, height          // Image dimensions
        );

        // **Comment for potential color swap issues:**
        // If the resulting colors in the ARGB buffer appear swapped (e.g., blues and reds are reversed),
        // it might indicate the actual format was NV21 (VU interleaved) instead of NV12 (UV interleaved).
        // In that case, you might need to try:
        // 1. Using libyuv::NV21ToARGB instead.
        // 2. Passing the `vData` pointer as the source for the interleaved data if the V plane buffer
        //    correctly points to the start of the VU interleaved data in the NV21 case.
        // Example for NV21 (if colors are swapped):
        // __android_log_print(ANDROID_LOG_WARN, TAG_YUV, "Colors might be swapped. Consider trying NV21ToARGB with vData as chroma source if NV12 fails.");
        // result = libyuv::NV21ToARGB(yData, yRowStride, vData, uvRowStride, outData, dstStride, width, height);


    } else {
        // Unsupported format based on pixel stride.
        __android_log_print(ANDROID_LOG_ERROR, TAG_YUV, "Unsupported UV pixel stride: %d. Cannot determine YUV format.", uvPixelStride);
        return false; // Indicate failure
    }

    // Check the result code from the libyuv function.
    if (result != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG_YUV, "libyuv conversion function failed with error code: %d", result);
        return false; // Indicate failure
    }

    // If we reach here, the conversion was successful.
    // __android_log_print(ANDROID_LOG_INFO, TAG_YUV, "libyuv conversion successful."); // Can be verbose
    return true; // Indicate success
}