#ifndef YUV_CONVERTER_H
#define YUV_CONVERTER_H

#include <cstdint> // For uint8_t

/**
 * @brief Converts image data from YUV_420_888 format to ARGB_8888 format.
 *
 * This function acts as a wrapper around the appropriate libyuv conversion function,
 * chosen based on the U/V plane pixel stride to handle planar (I420) and
 * semi-planar (NV12/NV21) layouts.
 *
 * @param width The width of the image in pixels.
 * @param height The height of the image in pixels.
 * @param yData Pointer to the start of the Y plane data.
 * @param uData Pointer to the start of the U plane data (for I420) or the start of the interleaved UV plane data (for NV12).
 * @param vData Pointer to the start of the V plane data (for I420) or the start of the interleaved VU plane data (for NV21, if needed).
 * @param yRowStride The row stride (bytes per row) for the Y plane.
 * @param uvPixelStride The pixel stride (bytes per pixel) for the U and V planes.
 * 1 indicates planar format (like I420).
 * 2 indicates semi-planar format (like NV12 or NV21).
 * @param uvRowStride The row stride (bytes per row) for the U and V planes.
 * @param outData Pointer to the output buffer where the ARGB_8888 data will be written.
 * This buffer MUST be pre-allocated with a size of at least width * height * 4 bytes.
 * @return True if the conversion was successful (libyuv returned 0), false otherwise.
 */
bool convertToRgb(
    int width, int height,
    const uint8_t* yData, const uint8_t* uData, const uint8_t* vData,
    int yRowStride, int uvPixelStride, int uvRowStride,
    uint8_t* outData // Output buffer for ARGB data
);

#endif // YUV_CONVERTER_H