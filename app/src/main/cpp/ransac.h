#ifndef RANSAC_H
#define RANSAC_H

#include <vector>  // For std::vector
#include <cstdint> // For standard integer types

/**
 * @brief Structure to hold the results of RANSAC plane detection.
 *
 * Contains the parameters of the detected plane equation (Ax + By + Cz + D = 0)
 * assuming the normal vector (A, B, C) is normalized, and the number of inlier points
 * that support this plane model.
 */
struct PlaneResult {
    float A, B, C, D; // Plane equation parameters (normalized normal A, B, C)
    int numInliers;   // Number of points considered inliers to this plane
};

/**
 * @brief Detects dominant planes in a 3D point cloud derived from a depth map using the RANSAC algorithm.
 *
 * This function first generates a 3D point cloud from the input depth map and camera intrinsics,
 * then applies the RANSAC algorithm to find the best-fitting plane(s).
 *
 * @param depthMap Pointer to the raw float data of the depth map (relative inverse depth).
 * Assumed to be in row-major order.
 * @param width Width of the depth map in pixels.
 * @param height Height of the depth map in pixels.
 * @param fx Camera focal length along the X-axis (pixels). CRITICAL PLACEHOLDER.
 * @param fy Camera focal length along the Y-axis (pixels). CRITICAL PLACEHOLDER.
 * @param cx Principal point X-coordinate (pixels). CRITICAL PLACEHOLDER.
 * @param cy Principal point Y-coordinate (pixels). CRITICAL PLACEHOLDER.
 * @param distanceThreshold RANSAC parameter: Maximum distance for a point to be an inlier (approx. meters). TUNE.
 * @param minInliers RANSAC parameter: Minimum number of inliers to validate a plane. TUNE.
 * @param maxIterations RANSAC parameter: Maximum number of iterations. TUNE.
 *
 * @return A std::vector containing PlaneResult structs for each significant plane detected.
 * The vector will be empty if no plane meets the minInliers threshold.
 * Currently implemented to return at most one plane (the best one found).
 */
std::vector<PlaneResult> findPlanesRansac(
        const float* depthMap, int width, int height,
        float fx, float fy, float cx, float cy,
        float distanceThreshold, int minInliers, int maxIterations
);

#endif // RANSAC_H