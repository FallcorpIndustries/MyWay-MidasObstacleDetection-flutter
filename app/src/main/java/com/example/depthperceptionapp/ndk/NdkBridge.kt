package com.example.depthperceptionapp.ndk

import android.util.Log
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Bridge class for interacting with native C/C++ code via JNI (Java Native Interface).
 * This class loads the native library (`libdepth_perception_native.so`) and declares
 * the `external` functions that are implemented in C++ (`jni_bridge.cpp`).
 * These functions provide high-performance implementations for tasks like YUV conversion
 * and RANSAC plane fitting.
 *
 * ========================================
 * == NDK/JNI Function Interface Summary ==
 * ========================================
 * This table maps the Kotlin functions in this bridge to their corresponding C++ implementations.
 * The C++ function names follow a specific JNI naming convention:
 * `Java_{package_name}_{class_name}_{method_name}` (with underscores replacing dots).
 *
 * | Kotlin/Java Method     | Native C++ Function Name (in jni_bridge.cpp)             | Description                                     |
 * |------------------------|----------------------------------------------------------|-------------------------------------------------|
 * | yuvToRgb(...)          | Java_com_example_depthperceptionapp_ndk_NdkBridge_yuvToRgb | Converts YUV_420_888 frame to ARGB using libyuv. |
 * | detectWallsRansac(...) | Java_com_example_depthperceptionapp_ndk_NdkBridge_detectWallsRansac | Detects planes (walls) using RANSAC algorithm. |
 *
 */
object NdkBridge {

    init {
        try {
            // Load the native library. The name must match the library name
            // defined in CMakeLists.txt (target name in add_library command),
            // without the 'lib' prefix and '.so' suffix.
            System.loadLibrary("depth_perception_native")
            Log.i("NdkBridge", "Native library 'libdepth_perception_native.so' loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NdkBridge", "Failed to load native library 'depth_perception_native'. " +
                    "Check CMakeLists.txt, ABI filters, and native build configuration.", e)
            // Rethrow or handle appropriately (e.g., disable native features)
            throw e
        }
    }

    /**
     * Converts an image from YUV_420_888 format to ARGB_8888 format using native code (libyuv).
     * This MUST be used for performance reasons instead of pure Kotlin/Java conversion,
     * as native libraries like libyuv are highly optimized for these pixel format operations.
     *
     * IMPORTANT: Input buffers (`yBuffer`, `uBuffer`, `vBuffer`) and `outBuffer` should ideally be
     * Direct ByteBuffers for efficient memory access from native code without extra copying.
     *
     * @param width Width of the image in pixels.
     * @param height Height of the image in pixels.
     * @param yBuffer Direct ByteBuffer containing the Y plane data.
     * @param uBuffer Direct ByteBuffer containing the U plane data (or interleaved UV data for NV12).
     * @param vBuffer Direct ByteBuffer containing the V plane data (or ignored for NV12 if U contains UV).
     * @param yRowStride Row stride of the Y plane in bytes (bytes per row). Can be > width.
     * @param uvPixelStride Pixel stride of the U and V planes in bytes.
     * Typically 1 for planar formats (I420: Y, U, V separate).
     * Typically 2 for semi-planar formats (NV12: Y, UV interleaved; NV21: Y, VU interleaved).
     * This parameter helps the native code choose the correct libyuv conversion function.
     * @param uvRowStride Row stride of the U and V planes in bytes. Can be different from yRowStride.
     * @param outRgbBuffer Direct ByteBuffer allocated to store the output ARGB_8888 data.
     * Size MUST be width * height * 4 bytes. Content will be overwritten.
     * @return True if conversion was successful (as reported by libyuv), false otherwise.
     */
    external fun yuvToRgb(
        width: Int, height: Int,
        yBuffer: ByteBuffer, uBuffer: ByteBuffer, vBuffer: ByteBuffer,
        yRowStride: Int, uvPixelStride: Int, uvRowStride: Int,
        outRgbBuffer: ByteBuffer // Renamed for clarity
    ): Boolean

    /**
     * Detects dominant planes (interpreted as walls) in a depth map using the RANSAC algorithm
     * implemented in native C++ code. This is computationally intensive and benefits significantly
     * from native execution speed.
     *
     * It works by:
     * 1. Converting the 2D depth map + camera intrinsics into a 3D point cloud.
     * 2. Iteratively sampling 3 random points, fitting a plane, and counting inlier points.
     * 3. Returning the plane model with the most inliers, if it exceeds a minimum threshold.
     *
     * @param depthMap Direct FloatBuffer containing the 256x256 relative inverse depth map
     * (disparity) from the MiDaS model. Higher values = closer.
     * Must be a Direct FloatBuffer for efficient NDK access.
     * @param width Width of the depth map (e.g., 256).
     * @param height Height of the depth map (e.g., 256).
     *
     * @param fx /* CRITICAL PLACEHOLDER: Camera intrinsic parameter. */
     * /* Represents the focal length along the X-axis, typically measured in pixels. */
     * /* Obtain this value via a camera calibration process for the SPECIFIC camera */
     * /* module and resolution used on the target device (Samsung Galaxy Tab S9 FE). */
     * /* Using default/incorrect values leads to inaccurate 3D point cloud generation */
     * /* and consequently, poor RANSAC plane fitting results. */
     * /* Default: Example value, MUST be replaced. */
     * @param fy /* CRITICAL PLACEHOLDER: Camera intrinsic parameter. */
     * /* Represents the focal length along the Y-axis, typically measured in pixels. */
     * /* Obtain via camera calibration. Often similar to fx for square pixels. */
     * /* Default: Example value, MUST be replaced. */
     * @param cx /* CRITICAL PLACEHOLDER: Camera intrinsic parameter. */
     * /* Represents the X-coordinate of the principal point (optical center), */
     * /* typically measured in pixels relative to the top-left corner (0,0) of the image. */
     * /* Often close to width / 2. Obtain via camera calibration. */
     * /* Default: Example value (width/2), MUST be replaced. */
     * @param cy /* CRITICAL PLACEHOLDER: Camera intrinsic parameter. */
     * /* Represents the Y-coordinate of the principal point (optical center), */
     * /* typically measured in pixels relative to the top-left corner (0,0). */
     * /* Often close to height / 2. Obtain via camera calibration. */
     * /* Default: Example value (height/2), MUST be replaced. */
     *
     * @param distanceThreshold /* TUNABLE PARAMETER: RANSAC algorithm setting. */
     * /* Maximum perpendicular distance a 3D point can be from a candidate plane */
     * /* to be considered an inlier for that plane. */
     * /* Units are roughly meters IF the depth map were metric and intrinsics correct. */
     * /* Since depth is relative, this requires EMPIRICAL TUNING based on */
     * /* observed point cloud scale/noise and desired sensitivity to planar surfaces. */
     * /* Smaller values make it stricter (less noise tolerant), larger values looser. */
     * /* Default: Example value (approx 5cm), MUST be tuned. */
     * @param minInliers /* TUNABLE PARAMETER: RANSAC algorithm setting. */
     * /* Minimum number of inlier points required to consider a detected plane */
     * /* statistically significant (e.g., a valid wall vs. random alignment). */
     * /* Tune this based on the density of the generated point cloud (depends on */
     * /* depth map resolution) and the expected minimum size of a wall surface */
     * /* in the scene. Higher values make detection more robust but less sensitive */
     * /* to smaller planes. */
     * /* Default: Example value, MUST be tuned. */
     * @param maxIterations /* TUNABLE PARAMETER: RANSAC algorithm setting. */
     * /* Maximum number of random sampling iterations RANSAC will perform */
     * /* to find the best plane model. */
     * /* Higher values increase the probability of finding the globally optimal */
     * /* plane (or a good fit) but directly increase computation time. */
     * /* Lower values are faster but might miss the best plane or find a suboptimal one. */
     * /* Tune based on the desired balance between robustness and performance (latency). */
     * /* Default: Example value, MUST be tuned. */
     *
     * @return An array of float arrays (`Array<FloatArray>?`).
     * If planes are detected, each inner `FloatArray` represents one plane and contains
     * 5 values: `[A, B, C, D, numInliers]`, where `Ax + By + Cz + D = 0` is the
     * normalized plane equation, and `numInliers` is the integer number of points
     * supporting this plane (cast to float for the array).
     * Returns `null` if the native function fails internally or if no significant plane
     * (meeting `minInliers` requirement) is found after `maxIterations`.
     */
    external fun detectWallsRansac(
        depthMap: FloatBuffer, width: Int, height: Int,
        fx: Float, fy: Float, cx: Float, cy: Float,
        distanceThreshold: Float, minInliers: Int, maxIterations: Int
    ): Array<FloatArray>? // Return type is nullable array of float arrays
}