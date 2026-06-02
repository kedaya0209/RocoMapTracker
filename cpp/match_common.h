// match_common.h — Shared preprocessing & matching interface for C++ subprocesses.
// Used by sift_matcher.cpp.
//
// This file aggregates:
//   - LOG macros, message types, algorithm parameters struct
//   - Zlib compression helpers
//   - Mat serialization (compressed zlib-based)
//   - MiniMapProcessor (HoughCircles)
//   - Circle mask utility
//   - Arrow angle detection (HSV inRange + PCA)
//   - Result serialization
//   - Abstract MatcherBase interface
//   - CONFIG_DATA parser
//   - Factory function for creating matchers
//   - Main loop driver

#ifndef MATCH_COMMON_H
#define MATCH_COMMON_H

#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>

#include <opencv2/opencv.hpp>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>

#include <zlib.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <cmath>
#include <exception>
#include <fstream>
#include <thread>

#include "socket_common.h"

// ============================================================================
// Debug output
// ============================================================================
#define LOG(msg, ...) printf("[match] " msg "\n", ##__VA_ARGS__)
#define LOGERR(msg, ...) fprintf(stderr, "[match] [ERR] " msg "\n", ##__VA_ARGS__)

// ============================================================================
// Message types
// ============================================================================
enum MsgType : int32_t {
    REQUEST_MAP    = 200,
    MAP_DATA       = 201,
    INIT_COMPLETE  = 202,
    INIT_FAILED    = 203,
    READY          = 204,
    FRAME_DATA     = 205,
    MATCH_RESULT   = 206,
    SHUTDOWN       = 207,
    REQUEST_CONFIG = 208,
    CONFIG_DATA    = 209,
};

// ============================================================================
// Algorithm parameters struct (populated from CONFIG_DATA)
// ============================================================================
enum class AlgoKind : int32_t {
    SIFT  = 0,
};

struct AlgoParams {
    AlgoKind kind = AlgoKind::SIFT;

    // SIFT
    int32_t siftVariant = 3;      // PCA_ULTRA
    int32_t nfeatures = 0;
    int32_t nOctaveLayers = 3;
    double contrastThreshold = 0.001;
    double edgeThreshold = 50.0;
    double sigma = 1.6;

    // MATCH (shared)
    double matchRatioThreshold = 0.6;
    int32_t matchMinCount = 10;
    int32_t searchRadius = 500;

    // FLANN (SIFT only)
    int32_t flannKDTreeCount = 1;
    int32_t flannSearchChecks = 24;

    // RANSAC (shared)
    double ransacReprojThreshold = 10.0;
    int32_t ransacMaxIters = 200;
    double ransacConfidence = 0.95;

    // Tile training
    int32_t tileSize = 2000;
    int32_t tileOverlap = 200;
    int64_t largeMapThreshold = 9000000;
    float dedupDistance = 4.0f;

    // Paths
    std::string cacheFilePath;
};

// ============================================================================
// Zlib compression helpers
// ============================================================================
std::vector<uint8_t> zlib_compress(const void* data, size_t len);
std::vector<uint8_t> zlib_decompress(const void* data, size_t compressedLen, size_t rawLen);

// ============================================================================
// Mat serialization (compressed, shared format for both SIFT and AKAZE caches)
// ============================================================================
void write_mat_compressed(FILE* f, const cv::Mat& m);
cv::Mat read_mat_compressed(FILE* f);

void write_float(FILE* f, float v);
float read_float(FILE* f);

// ============================================================================
// MiniMapProcessor: detect minimap circle via HoughCircles
// ============================================================================
class MiniMapProcessor {
public:
    static constexpr int SMALL_WIDTH = 120;
    static constexpr double BLACK_RATIO_THRESHOLD = 0.15;
    static constexpr double CENTER_OFFSET_RATIO = 0.2;

    struct DetectionResult {
        bool success = false;
        double center_x = 0;
        double center_y = 0;
        int radius = 0;
    };

    DetectionResult detect(uint8_t* data, int w, int h);

private:
    cv::Mat gray_mat;
    cv::Mat small_gray;
    cv::Mat blur_mat;
    std::vector<uint8_t> small_gray_data;

    void init_mats(int w, int h);
};

// ============================================================================
// Circle mask: zero out pixels outside the detected minimap circle
// ============================================================================
void apply_circle_mask(uint8_t* data, int w, int h,
                       double center_x, double center_y, int radius);

// ============================================================================
// Arrow direction detection: HSV color-based + PCA
// ============================================================================
double detect_arrow_angle_hsv(const uint8_t* bgra_data, int w, int h,
                              double cx, double cy, int radius);

// ============================================================================
// Debug PNG utilities (no OpenCV imwrite dependency)
// ============================================================================
void save_roi_png(const cv::Mat& bgra, const char* path);
void save_png(const cv::Mat& bgr, const char* path);

// ============================================================================
// Match result struct (shared across all matchers)
// ============================================================================
struct MatchResult {
    bool success = false;
    double x = 0;
    double y = 0;
    float t_minimap_ms = 0;
    float t_extract_ms = 0;
    float t_matching_ms = 0;
};

// ============================================================================
// Serialize MATCH_RESULT body: [1]success [8]x [8]y [8]angle
//                              [4]t_minimap_ms [4]t_extract_ms [4]t_matching_ms [4]t_arrow_ms
// ============================================================================
std::vector<uint8_t> serialize_result(bool success, double x, double y, double angle,
                                      float t_minimap_ms = 0, float t_extract_ms = 0,
                                      float t_matching_ms = 0, float t_arrow_ms = 0);

// ============================================================================
// Abstract matcher interface
// ============================================================================
class MatcherBase {
public:
    virtual ~MatcherBase() = default;
    virtual bool train(const uint8_t* gray_pixels, int w, int h) = 0;
    virtual MatchResult match(uint8_t* data, int w, int h, double hint_x, double hint_y) = 0;
    virtual bool save_cache(const std::string& path) = 0;
    virtual bool load_cache(const std::string& path) = 0;
    virtual size_t feature_count() const = 0;
};

// ============================================================================
// AlgoParams parser from CONFIG_DATA body
// ============================================================================
bool parse_config_data(const std::vector<uint8_t>& body, AlgoParams& p);

// ============================================================================
// Matcher factory
// ============================================================================
std::unique_ptr<MatcherBase> create_matcher(const AlgoParams& params);

// ============================================================================
// Main loop driver
// ============================================================================
int run_match_loop(SOCKET sock, AlgoParams& params, MatcherBase& matcher,
                   std::atomic<bool>& g_running);

#endif // MATCH_COMMON_H
