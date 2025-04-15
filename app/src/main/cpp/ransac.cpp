#include "ransac.h"

#include <vector>
#include <random>  // For random number generation (std::mt19937, std::uniform_int_distribution)
#include <cmath>   // For std::sqrt, std::abs, std::isnan
#include <limits>  // For std::numeric_limits
#include <android/log.h> // For logging

#define TAG_RANSAC "RANSAC_NDK" // Logging tag

// Simple structure to represent a 3D point
struct Point3D {
    float x, y, z;
};

// Simple structure to represent a plane defined by Ax + By + Cz + D = 0
// Assumes the normal vector (A, B, C) is normalized.
struct Plane {
    float a, b, c, d;
};

/**
 * @brief Calculates the plane equation parameters from 3 non-collinear points.
 * Uses the cross product of two vectors formed by the points to find the normal (A, B, C),
 * normalizes the normal vector, and then calculates D using one of the points.
 *
 * @param p1 First point.
 * @param p2 Second point.
 * @param p3 Third point.
 * @return A Plane struct containing the normalized parameters {A, B, C, D}.
 * Returns {NaN, NaN, NaN, NaN} if the points are collinear (cannot form a plane).
 */
Plane calculatePlane(const Point3D& p1, const Point3D& p2, const Point3D& p3) {
    Plane plane = {0.0f, 0.0f, 0.0f, 0.0f};

    // Calculate vectors defining the plane
    Point3D v1 = {p2.x - p1.x, p2.y - p1.y, p2.z - p1.z};
    Point3D v2 = {p3.x - p1.x, p3.y - p1.y, p3.z - p1.z};

    // Calculate the cross product to get the normal vector (A, B, C)
    plane.a = v1.y * v2.z - v1.z * v2.y;
    plane.b = v1.z * v2.x - v1.x * v2.z;
    plane.c = v1.x * v2.y - v1.y * v2.x;

    // Calculate the length (magnitude) of the normal vector
    float length = std::sqrt(plane.a * plane.a + plane.b * plane.b + plane.c * plane.c);

    // Normalize the normal vector (A, B, C) if it's valid (non-zero length)
    if (length > std::numeric_limits<float>::epsilon() * 100) { // Use epsilon check for floating point robustness
        plane.a /= length;
        plane.b /= length;
        plane.c /= length;
    } else {
        // Points are collinear or coincident, cannot form a unique plane.
        __android_log_print(ANDROID_LOG_WARN, TAG_RANSAC, "calculatePlane: Points are collinear or coincident, cannot form plane.");
        return {NAN, NAN, NAN, NAN}; // Indicate failure using NaN
    }

    // Calculate D using the normalized normal and one point (p1)
    // Plane equation: Ax + By + Cz + D = 0  =>  D = -(Ax + By + Cz)
    plane.d = -(plane.a * p1.x + plane.b * p1.y + plane.c * p1.z);

    return plane;
}

/**
 * @brief Calculates the shortest (perpendicular) distance from a 3D point to a plane.
 * Assumes the plane's normal vector (a, b, c) is already normalized.
 * Formula: distance = |Ax + By + Cz + D| / sqrt(A^2 + B^2 + C^2)
 * Since the normal is normalized, the denominator is 1.
 *
 * @param point The 3D point.
 * @param plane The Plane struct (with normalized normal).
 * @return The absolute perpendicular distance from the point to the plane.
 */
float pointPlaneDistance(const Point3D& point, const Plane& plane) {
    return std::abs(plane.a * point.x + plane.b * point.y + plane.c * point.z + plane.d);
}


/**
 * @brief Core RANSAC implementation to find the best-fitting plane in a point cloud.
 */
std::vector<PlaneResult> findPlanesRansac(
        const float* depthMap, int width, int height,
        float fx, float fy, float cx, float cy,
        float distanceThreshold, int minInliers, int maxIterations
) {
    __android_log_print(ANDROID_LOG_INFO, TAG_RANSAC, "Starting RANSAC Plane Detection.");
    __android_log_print(ANDROID_LOG_INFO, TAG_RANSAC, "Params: Thresh=%.3f, MinInliers=%d, MaxIters=%d", distanceThreshold, minInliers, maxIterations);
    // Log intrinsics clearly marking them as placeholders
    __android_log_print(ANDROID_LOG_WARN, TAG_RANSAC, "Using PLACEHOLDER Intrinsics: fx=%.1f, fy=%.1f, cx=%.1f, cy=%.1f. CALIBRATION NEEDED!", fx, fy, cx, cy);


    std::vector<Point3D> pointCloud;
    pointCloud.reserve(width * height / 4); // Pre-allocate roughly, adjust as needed

    // --- 1. Generate Point Cloud from Depth Map ---
    // Converts the 2D depth map (relative inverse depth) into a 3D point cloud using
    // the pinhole camera model equations.
    // X = (u - cx) * Z / fx
    // Y = (v - cy) * Z / fy
    // Z = 1.0f / inverseDepth (Relative depth)
    //
    // IMPORTANT CAVEATS:
    // a) PLACEHOLDER INTRINSICS: The accuracy of the 3D point coordinates (x, y, z)
    //    is directly dependent on using the CORRECT intrinsic parameters (fx, fy, cx, cy)
    //    obtained through camera calibration for the specific device and resolution.
    //    Using default/incorrect values will result in a distorted point cloud.
    // b) RELATIVE DEPTH: The input `depthMap` is relative inverse depth. We convert it
    //    to relative depth (Z = 1/inverseDepth). The resulting point cloud's scale is
    //    therefore relative and not metric (meters/feet). The `distanceThreshold` for
    //    RANSAC needs to be tuned based on the scale of this *relative* point cloud.
    // c) Inverse Depth Handling: We add a small epsilon check to avoid division by zero
    //    or using extremely small inverse depth values (very distant points), which can
    //    lead to huge Z values and numerical instability. Tune the threshold 1e-5 if needed.

    for (int v = 0; v < height; ++v) { // v represents pixel row (y-coordinate)
        for (int u = 0; u < width; ++u) { // u represents pixel column (x-coordinate)
            // Calculate 1D index for row-major depth map
            int index = v * width + u;
            float inverseDepth = depthMap[index];

            // Basic check to skip invalid or very distant points
            // Tune the 1e-5 threshold if necessary based on model output range/noise
            if (inverseDepth < 1e-5) {
                continue; // Skip this pixel
            }

            // Calculate relative 3D coordinates
            float z = 1.0f / inverseDepth; // Relative depth Z
            // Check for excessively large Z values if inverse depth was tiny but non-zero
            if (z > 1000.0f) continue; // Skip points likely too far away (Tune threshold if needed)

            float x = (static_cast<float>(u) - cx) * z / fx;
            float y = (static_cast<float>(v) - cy) * z / fy;

            pointCloud.push_back({x, y, z});
        }
    }
    __android_log_print(ANDROID_LOG_INFO, TAG_RANSAC, "Generated point cloud with %zu points.", pointCloud.size());

    // Check if there are enough points to even attempt RANSAC
    if (pointCloud.size() < static_cast<size_t>(minInliers) || pointCloud.size() < 3) {
        __android_log_print(ANDROID_LOG_WARN, TAG_RANSAC, "Point cloud size (%zu) is less than minInliers (%d) or 3. Cannot fit plane.", pointCloud.size(), minInliers);
        return {}; // Return empty vector - not enough points
    }

    // --- 2. RANSAC Plane Fitting Algorithm ---
    Plane bestPlane = {0.0f, 0.0f, 0.0f, 0.0f};
    int maxInliersFound = -1;

    // Random number generation setup for selecting points
    std::random_device rd; // Non-deterministic seed source
    std::mt19937 rng(rd()); // Mersenne Twister engine seeded with rd()
    std::uniform_int_distribution<size_t> dist(0, pointCloud.size() - 1); // Distribution for indices

    for (int iter = 0; iter < maxIterations; ++iter) {
        // a. Randomly select 3 distinct points from the point cloud.
        size_t idx1 = dist(rng);
        size_t idx2 = dist(rng);
        size_t idx3 = dist(rng);

        // Simple check for distinct indices. For very small clouds, might need retries.
        if (idx1 == idx2 || idx1 == idx3 || idx2 == idx3) {
            // iter--; // Decrement counter to retry this iteration - potential infinite loop if cloud size < 3
            continue; // Just skip this iteration if indices are the same
        }

        // b. Calculate a candidate plane equation from these 3 points.
        Plane currentPlane = calculatePlane(pointCloud[idx1], pointCloud[idx2], pointCloud[idx3]);

        // Check if plane calculation failed (e.g., points were collinear)
        if (std::isnan(currentPlane.a)) {
            continue; // Skip this iteration
        }

        // c. Count inliers: Iterate through all points in the cloud and count how many
        //    are close enough to the current candidate plane.
        int currentInliers = 0;
        for (const auto& point : pointCloud) {
            if (pointPlaneDistance(point, currentPlane) < distanceThreshold) {
                currentInliers++;
            }
        }

        // d. Update best model: If the current plane has more inliers than the best
        //    found so far, update the best plane and the max inlier count.
        if (currentInliers > maxInliersFound) {
            maxInliersFound = currentInliers;
            bestPlane = currentPlane;
            // Optional: Add an early exit condition if a very high number of inliers is found
            // if (maxInliersFound > pointCloud.size() * 0.8) break; // e.g., if 80% are inliers
        }
    } // End of RANSAC iterations

    // --- 3. Final Check and Result Packaging ---
    std::vector<PlaneResult> detectedPlanesResult; // Vector to return

    // Check if the best plane found meets the minimum inlier requirement.
    if (maxInliersFound >= minInliers) {
        __android_log_print(ANDROID_LOG_INFO, TAG_RANSAC, "Best plane found with %d inliers (>= min %d).", maxInliersFound, minInliers);
        // Add the best plane found to the results vector.
        detectedPlanesResult.push_back({bestPlane.a, bestPlane.b, bestPlane.c, bestPlane.d, maxInliersFound});

        // --- Optional Extension: Finding Multiple Planes ---
        // For Phase 1, we only return the single best plane. To find multiple planes:
        // 1. Refine the `bestPlane` using all its `maxInliersFound` inliers (e.g., using
        //    least squares fitting or Principal Component Analysis on the inlier subset)
        //    for better accuracy.
        // 2. Create a new point cloud containing only the points that were *not* inliers
        //    to the `bestPlane`.
        // 3. Repeat the RANSAC process (steps a-d) on the *remaining* points to find the
        //    next best plane.
        // 4. Continue until no more significant planes are found or a maximum number of
        //    planes is reached.
        // This requires more complex state management (handling remaining points).
        // --- End Optional Extension ---

    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG_RANSAC, "No significant plane found (max inliers %d < min required %d).", maxInliersFound, minInliers);
        // Return the empty vector initialized earlier.
    }

    return detectedPlanesResult;
}