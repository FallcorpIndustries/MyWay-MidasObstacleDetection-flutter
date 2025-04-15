#include <jni.h> // JNI header provided by NDK
#include <android/log.h> // NDK logging functions
#include <vector> // Standard C++ vector
#include <cmath> // Standard C++ math functions (e.g., isnan)
#include <string> // Standard C++ string
#include <cstdint> // For fixed-width integer types like uint8_t

// Include custom header files for our C++ logic
#include "yuv_converter.h"
#include "ransac.h"

// Define a logging tag for easy filtering in Logcat
#define TAG "NDK_BRIDGE"

// Helper macro for logging messages from native code
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)


// JNI function implementations must be wrapped in extern "C" to prevent C++ name mangling.
extern "C" {

/**
 * JNI implementation for the NdkBridge.yuvToRgb method.
 * This function receives pointers to YUV data (passed as Direct ByteBuffers from Kotlin/Java)
 * and calls the C++ `convertToRgb` function (which uses libyuv) to perform the conversion.
 *
 * @param env JNI Environment pointer, provides access to JNI functions.
 * @param clazz The Java class object (NdkBridge.class) since this is a static method.
 * @param width Image width.
 * @param height Image height.
 * @param yBuffer Java Direct ByteBuffer for Y plane.
 * @param uBuffer Java Direct ByteBuffer for U plane (or UV interleaved).
 * @param vBuffer Java Direct ByteBuffer for V plane (or VU interleaved).
 * @param yRowStride Row stride for Y plane.
 * @param uvPixelStride Pixel stride for U/V planes (1 for planar, 2 for semi-planar).
 * @param uvRowStride Row stride for U/V planes.
 * @param outRgbBuffer Java Direct ByteBuffer for ARGB output (must be allocated with size width*height*4).
 * @return JNI_TRUE if conversion succeeded, JNI_FALSE otherwise.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_depthperceptionapp_ndk_NdkBridge_yuvToRgb(
        JNIEnv *env,
        jclass clazz, // Use jclass for static methods
        jint width, jint height,
        jobject yBuffer, jobject uBuffer, jobject vBuffer,
        jint yRowStride, jint uvPixelStride, jint uvRowStride,
        jobject outRgbBuffer) {

    // Get direct access to the memory of the Direct ByteBuffers passed from Kotlin/Java.
    // This avoids expensive data copying between JVM and native memory.
    // Check for null in case buffers are invalid.
    auto yData = static_cast<const uint8_t *>(env->GetDirectBufferAddress(yBuffer));
    auto uData = static_cast<const uint8_t *>(env->GetDirectBufferAddress(uBuffer));
    auto vData = static_cast<const uint8_t *>(env->GetDirectBufferAddress(vBuffer));
    auto outData = static_cast<uint8_t *>(env->GetDirectBufferAddress(outRgbBuffer));

    if (yData == nullptr || uData == nullptr || vData == nullptr || outData == nullptr) {
        LOGE("Failed to get Direct Buffer Address. One or more buffers are null or not direct.");
        return JNI_FALSE; // Indicate failure
    }

    // Call the actual C++ conversion function (implemented in yuv_converter.cpp)
    // This function encapsulates the call to the libyuv library.
    // LOGD("Calling C++ convertToRgb (width=%d, height=%d, uvPixelStride=%d)", (int)width, (int)height, (int)uvPixelStride); // Verbose logging
    bool success = convertToRgb(
            width, height,
            yData, uData, vData,
            yRowStride, uvPixelStride, uvRowStride,
            outData
    );

    if (!success) {
        // Log specific error in convertToRgb implementation if possible
        // LOGE("C++ convertToRgb function returned false."); // Already logged in yuv_converter.cpp
        return JNI_FALSE;
    }

    // LOGD("convertToRgb successful."); // Verbose logging
    return JNI_TRUE; // Indicate success
}


/**
 * JNI implementation for the NdkBridge.detectWallsRansac method.
 * Receives the depth map (as a Direct FloatBuffer) and parameters, calls the C++
 * `findPlanesRansac` function, and packages the results back into a Java/Kotlin array.
 *
 * @param env JNI Environment pointer.
 * @param clazz The Java class object (NdkBridge.class).
 * @param depthMap Java Direct FloatBuffer containing the depth map (relative inverse depth).
 * @param width Width of the depth map.
 * @param height Height of the depth map.
 * @param fx Camera intrinsic fx (placeholder).
 * @param fy Camera intrinsic fy (placeholder).
 * @param cx Camera intrinsic cx (placeholder).
 * @param cy Camera intrinsic cy (placeholder).
 * @param distanceThreshold RANSAC distance threshold (tunable).
 * @param minInliers RANSAC minimum inliers (tunable).
 * @param maxIterations RANSAC maximum iterations (tunable).
 * @return A Java object array (Array<FloatArray>) where each element is a float array
 * representing a plane [A, B, C, D, numInliers], or null if no planes are found
 * or an error occurs.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_example_depthperceptionapp_ndk_NdkBridge_detectWallsRansac(
        JNIEnv *env,
        jclass clazz,
        jobject depthMap, jint width, jint height,
        jfloat fx, jfloat fy, jfloat cx, jfloat cy,
        jfloat distanceThreshold, jint minInliers, jint maxIterations) {

    // Get direct access to the depth map FloatBuffer's memory.
    auto depthMapData = static_cast<const float *>(env->GetDirectBufferAddress(depthMap));

    if (depthMapData == nullptr) {
        LOGE("Failed to get Direct Buffer Address for depthMap.");
        return nullptr; // Return null to indicate failure
    }

    // Call the C++ RANSAC implementation (from ransac.cpp)
    // LOGD("Calling C++ findPlanesRansac..."); // Verbose logging
    std::vector<PlaneResult> detectedPlanes = findPlanesRansac(
            depthMapData, width, height,
            fx, fy, cx, cy,
            distanceThreshold, minInliers, maxIterations
    );

    // --- Package results back for Kotlin/Java ---

    if (detectedPlanes.empty()) {
        // LOGD("No significant planes found by C++ RANSAC."); // Already logged in ransac.cpp
        return nullptr; // Return null if the C++ function returned an empty vector
    }

    // 1. Find the Java class for float[] (float array).
    //    Note the JNI signature format: "[F" represents a float array.
    jclass floatArrayClass = env->FindClass("[F");
    if (floatArrayClass == nullptr) {
        LOGE("Failed to find class '[F' (float array class).");
        return nullptr; // Error finding the class
    }

    // 2. Create the outer Java object array (Array<FloatArray>) to hold the results.
    //    The size is the number of planes detected.
    //    The third argument is the class of the elements (float[]).
    //    The fourth argument (nullptr) initializes elements to null initially.
    jobjectArray resultArray = env->NewObjectArray(detectedPlanes.size(), floatArrayClass, nullptr);
    if (resultArray == nullptr) {
        LOGE("Failed to create new Object Array (for FloatArray).");
        return nullptr; // Error creating the outer array
    }

    // 3. Iterate through the detected planes (from C++) and populate the Java array.
    for (size_t i = 0; i < detectedPlanes.size(); ++i) {
        // a. Create an inner Java float array (float[]) of size 5 for [A, B, C, D, numInliers].
        jfloatArray planeDataArray = env->NewFloatArray(5);
        if (planeDataArray == nullptr) {
            LOGE("Failed to create new Float Array (inner) for plane %zu.", i);
            // Clean up previously allocated resources if necessary (though JNI handles local refs)
            return nullptr; // Error creating the inner array
        }

        // b. Prepare the data from the C++ PlaneResult struct.
        //    Cast numInliers to jfloat for the array.
        jfloat planeData[5] = {
                detectedPlanes[i].A,
                detectedPlanes[i].B,
                detectedPlanes[i].C,
                detectedPlanes[i].D,
                static_cast<jfloat>(detectedPlanes[i].numInliers)
        };

        // c. Copy the C++ plane data into the Java float array.
        //    - planeDataArray: Destination Java array.
        //    - 0: Starting index in the Java array.
        //    - 5: Number of elements to copy.
        //    - planeData: Source C++ array.
        env->SetFloatArrayRegion(planeDataArray, 0, 5, planeData);

        // d. Add the newly created inner Java float array (planeDataArray) to the
        //    outer Java object array (resultArray) at the current index 'i'.
        env->SetObjectArrayElement(resultArray, i, planeDataArray);

        // e. Clean up the local reference to the inner array.
        //    While JNI often manages local references automatically when a native method
        //    returns, explicitly deleting them within loops is good practice to avoid
        //    potentially exhausting the JNI local reference table limit if many objects
        //    are created.
        env->DeleteLocalRef(planeDataArray);
    }

    // LOGD("Returning %zu detected planes to Kotlin/Java.", detectedPlanes.size()); // Verbose logging
    return resultArray; // Return the populated Java array of float arrays
}


} // extern "C"