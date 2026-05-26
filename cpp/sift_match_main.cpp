// sift_match_main.cpp — Standalone SIFT matching process via TCP Socket.
// JavaCPP-free: all SIFT/FLANN/RANSAC/HoughCircles run in this process.
// Arrow direction (HSV color-based: inRange + convex hull + min interior angle)
// is computed directly in this process on BGRA frame data.
//
// Protocol (binary TCP, big-endian):
//   HELLO:
//   1   C++→Java: HELLO          "sift"                 — identify client type
//
//   HANDSHAKE:
//   208 C++→Java: REQUEST_CONFIG  {}                    — request algorithm parameters
//   209 Java→C++: CONFIG_DATA     {binary blob}         — SIFT/FLANN/RANSAC/MATCH params + paths
//   200 C++→Java: REQUEST_MAP     {}                    — cache miss, request map pixels
//   201 Java→C++: MAP_DATA        {w,h,pixelsLen,gray8} — map grayscale data
//   202 C++→Java: INIT_COMPLETE   {featureCount}        — ready for frames
//   203 C++→Java: INIT_FAILED     {errcode,msg}         — init failure
//
//   MATCHING LOOP:
//   204 C++→Java: READY           {}                    — backpressure, ready for next frame
//   205 Java→C++: FRAME_DATA      {w,h,hintX,hintY,pixelsLen,BGRA32}
//   206 C++→Java: MATCH_RESULT    {success,x,y,angle}
//
//   SHUTDOWN:
//   207 Java→C++: SHUTDOWN        {}
//
// Build: build_sift.bat
// Run:   sift_match.exe <port>

#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>

#include <opencv2/opencv.hpp>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>
#include <opencv2/flann.hpp>

#include <zlib.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <cmath>
#include <exception>
#include <fstream>

#include "socket_common.h"

#pragma comment(lib, "ws2_32.lib")
// opencv libraries provided by build system (opencv_world4100.lib etc.)
#pragma comment(lib, "zlib.lib")

// ============================================================================
// Debug output
// ============================================================================
#define LOG(msg, ...) printf("[sift_match] " msg "\n", ##__VA_ARGS__)
#define LOGERR(msg, ...) fprintf(stderr, "[sift_match] [ERR] " msg "\n", ##__VA_ARGS__)

// ============================================================================
// Message types
// ============================================================================
enum MsgType : int32_t {
    // HELLO = 1  — defined in socket_common.h (CommonMsgType)
    REQUEST_MAP    = 200,  // C++ → Java (cache miss)
    MAP_DATA       = 201,  // Java → C++
    INIT_COMPLETE  = 202,  // C++ → Java
    INIT_FAILED    = 203,  // C++ → Java
    READY          = 204,  // C++ → Java (backpressure)
    FRAME_DATA     = 205,  // Java → C++
    MATCH_RESULT   = 206,  // C++ → Java
    SHUTDOWN       = 207,  // Java → C++
    REQUEST_CONFIG = 208,  // C++ → Java (request algorithm params)
    CONFIG_DATA    = 209,  // Java → C++ (serialized params)
};

// ============================================================================
// SIFT variant enum (matches Java DescriptorTransform.Variant)
// ============================================================================
enum SiftVariant : int32_t {
    STANDARD   = 0,  // 128-dim float
    PCA        = 1,  // PCA 64-dim float
    ULTRA      = 2,  // 8-bit quantized (no PCA)
    PCA_ULTRA  = 3,  // PCA 64-dim + 8-bit quantized (default)
};

// ============================================================================
// Algorithm parameters struct (populated from CONFIG_DATA)
// ============================================================================
struct AlgoParams {
    SiftVariant variant = PCA_ULTRA;

    // SIFT
    int nfeatures = 0;
    int nOctaveLayers = 3;
    double contrastThreshold = 0.001;
    double edgeThreshold = 50.0;
    double sigma = 1.6;

    // MATCH
    double matchRatioThreshold = 0.6;
    int matchMinCount = 10;
    int searchRadius = 500;

    // FLANN
    int flannKDTreeCount = 1;
    int flannSearchChecks = 24;

    // RANSAC
    double ransacReprojThreshold = 10.0;
    int ransacMaxIters = 200;
    double ransacConfidence = 0.95;

    // Paths
    std::string cacheFilePath;
};

// ============================================================================
// Zlib compression helpers
// ============================================================================
static std::vector<uint8_t> zlib_compress(const void* data, size_t len) {
    uLongf dstLen = compressBound((uLong)len);
    std::vector<uint8_t> dst(dstLen);
    if (compress2(dst.data(), &dstLen, (const Bytef*)data, (uLong)len, Z_BEST_SPEED) != Z_OK) {
        return {};
    }
    dst.resize(dstLen);
    return dst;
}

static std::vector<uint8_t> zlib_decompress(const void* data, size_t compressedLen, size_t rawLen) {
    std::vector<uint8_t> dst(rawLen);
    uLongf dstLen = (uLongf)rawLen;
    if (uncompress(dst.data(), &dstLen, (const Bytef*)data, (uLong)compressedLen) != Z_OK) {
        return {};
    }
    return dst;
}

// ============================================================================
// Cache serialization (compatible with Java DescriptorTransform cache semantics)
// ============================================================================

// Write a cv::Mat as: [rows(int32)] [cols(int32)] [type(int32)] [compressedLen(int32)] [rawLen(int32)] [zlib_data]
static void write_mat_compressed(FILE* f, const cv::Mat& m) {
    int rows = m.rows, cols = m.cols, type = m.type();
    fwrite(&rows, 4, 1, f);
    fwrite(&cols, 4, 1, f);
    fwrite(&type, 4, 1, f);

    size_t elemSize = m.elemSize();
    size_t rawLen = m.total() * elemSize;
    std::vector<uint8_t> raw(rawLen);
    if (m.isContinuous()) {
        memcpy(raw.data(), m.data, rawLen);
    } else {
        for (int r = 0; r < rows; r++) {
            memcpy(raw.data() + r * cols * elemSize, m.ptr(r), cols * elemSize);
        }
    }

    auto compressed = zlib_compress(raw.data(), rawLen);
    if (compressed.empty()) {
        // Fallback: store uncompressed
        int32_t cLen = (int32_t)rawLen;
        int32_t rLen = (int32_t)rawLen;
        fwrite(&cLen, 4, 1, f);
        fwrite(&rLen, 4, 1, f);
        fwrite(raw.data(), 1, rawLen, f);
    } else {
        int32_t cLen = (int32_t)compressed.size();
        int32_t rLen = (int32_t)rawLen;
        fwrite(&cLen, 4, 1, f);
        fwrite(&rLen, 4, 1, f);
        fwrite(compressed.data(), 1, compressed.size(), f);
    }
}

// Read a cv::Mat: inverse of write_mat_compressed
static cv::Mat read_mat_compressed(FILE* f) {
    int rows, cols, type;
    if (fread(&rows, 4, 1, f) != 1) return cv::Mat();
    if (fread(&cols, 4, 1, f) != 1) return cv::Mat();
    if (fread(&type, 4, 1, f) != 1) return cv::Mat();
    int32_t cLen, rLen;
    if (fread(&cLen, 4, 1, f) != 1) return cv::Mat();
    if (fread(&rLen, 4, 1, f) != 1) return cv::Mat();

    std::vector<uint8_t> cData(cLen);
    if (fread(cData.data(), 1, cLen, f) != (size_t)cLen) return cv::Mat();

    std::vector<uint8_t> raw;
    if (cLen == rLen) {
        // Uncompressed
        raw = std::move(cData);
    } else {
        raw = zlib_decompress(cData.data(), cLen, rLen);
        if (raw.empty()) return cv::Mat();
    }

    cv::Mat m(rows, cols, type);
    size_t elemSize = m.elemSize();
    if (m.isContinuous() && raw.size() == m.total() * elemSize) {
        memcpy(m.data, raw.data(), raw.size());
    }
    return m;
}

static void write_float(FILE* f, float v) {
    fwrite(&v, 4, 1, f);
}

static float read_float(FILE* f) {
    float v = 0;
    fread(&v, 4, 1, f);
    return v;
}

// ============================================================================
// Descriptor Transform: PCA + 8-bit quantization
// ============================================================================
class DescriptorTransform {
public:
    SiftVariant variant;

    // PCA state
    cv::Mat pca_eigenvectors;  // (pcadim × 128) CV_32F
    cv::Mat projected_mean;    // (1 × pcadim) CV_32F

    // Quantization params
    float q_min = 0;
    float q_scale = 1;

    // Final persistent descriptors (CV_32F or CV_8U)
    cv::Mat persistent_mat;

    DescriptorTransform(SiftVariant v) : variant(v) {}

    // Train on raw SIFT descriptors from map image
    bool train(cv::Mat& raw_descriptors) {
        if (raw_descriptors.empty() || raw_descriptors.type() != CV_32F)
            return false;

        cv::Mat result = raw_descriptors;

        if (variant == PCA || variant == PCA_ULTRA) {
            int pcadim = std::min(64, std::min(raw_descriptors.rows, 128));
            cv::Mat mean;
            cv::Mat full_eigenvectors;
            cv::PCACompute(raw_descriptors, mean, full_eigenvectors, cv::PCA::DATA_AS_ROW);
            pca_eigenvectors = full_eigenvectors.rowRange(0, pcadim).clone();
            projected_mean = cv::Mat(mean * pca_eigenvectors.t()).reshape(1, 1);
            result = pca_project(result);
        }

        if (variant == ULTRA || variant == PCA_ULTRA) {
            double min_val, max_val;
            cv::minMaxLoc(result, &min_val, &max_val, nullptr, nullptr, cv::noArray());
            q_min = (float)min_val;
            q_scale = 255.0f / ((float)max_val - q_min + 1e-6f);
            cv::Mat quantized;
            result.convertTo(quantized, CV_8U, q_scale, -q_min * q_scale);
            result = quantized;
        }

        persistent_mat = result.clone();
        return true;
    }

    // Transform scene descriptors for matching (always returns CV_32F)
    cv::Mat process(cv::Mat& scene_descriptors) {
        if (scene_descriptors.empty()) return scene_descriptors;
        cv::Mat result = scene_descriptors;

        if (variant == PCA || variant == PCA_ULTRA) {
            result = pca_project(result);
        }

        if (variant == ULTRA || variant == PCA_ULTRA) {
            cv::Mat quantized;
            result.convertTo(quantized, CV_8U, q_scale, -q_min * q_scale);
            cv::Mat as_float;
            quantized.convertTo(as_float, CV_32F);
            result = as_float;
        }

        return result; // Always CV_32F
    }

    // Transform scene descriptors to CV_8U (for u8_index path, no redundant dequantize)
    cv::Mat process_to_u8(cv::Mat& scene_descriptors) {
        cv::Mat result = scene_descriptors;
        if (variant == PCA || variant == PCA_ULTRA) {
            result = pca_project(result);
        }
        if (variant == ULTRA || variant == PCA_ULTRA) {
            cv::Mat quantized;
            result.convertTo(quantized, CV_8U, q_scale, -q_min * q_scale);
            result = quantized;
        }
        return result;
    }

    // ---- Cache I/O ----
    void save_cache(FILE* f) {
        if (variant == PCA || variant == PCA_ULTRA) {
            write_mat_compressed(f, pca_eigenvectors);
            write_mat_compressed(f, projected_mean);
        }
        write_mat_compressed(f, persistent_mat);
        if (variant == ULTRA || variant == PCA_ULTRA) {
            write_float(f, q_min);
            write_float(f, q_scale);
        }
    }

    bool load_cache(FILE* f) {
        try {
            if (variant == PCA || variant == PCA_ULTRA) {
                pca_eigenvectors = read_mat_compressed(f);
                if (pca_eigenvectors.empty()) return false;
                projected_mean = read_mat_compressed(f);
                if (projected_mean.empty()) return false;
            }
            persistent_mat = read_mat_compressed(f);
            if (persistent_mat.empty()) return false;
            if (variant == ULTRA || variant == PCA_ULTRA) {
                q_min = read_float(f);
                q_scale = read_float(f);
            }
            return true;
        } catch (...) {
            return false;
        }
    }

private:
    cv::Mat pca_project(cv::Mat& src) {
        cv::Mat dst;
        cv::gemm(src, pca_eigenvectors, 1.0, cv::noArray(), 0.0, dst, cv::GEMM_2_T);
        cv::Mat repeated_mean;
        cv::repeat(projected_mean, dst.rows, 1, repeated_mean);
        cv::subtract(dst, repeated_mean, dst);
        return dst;
    }
};

// ============================================================================
// Minimap Detector: HoughCircles + circle mask
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

    DetectionResult detect(uint8_t* data, int w, int h) {
        init_mats(w, h);

        gray_mat.data = data;
        cv::resize(gray_mat, small_gray, small_gray.size(), 0, 0, cv::INTER_LINEAR);
        small_gray_data.resize(small_gray.total());
        memcpy(small_gray_data.data(), small_gray.data, small_gray.total());
        cv::medianBlur(small_gray, blur_mat, 5);

        int min_side = std::min(small_gray.cols, small_gray.rows);

        std::vector<cv::Vec3f> circles;
        cv::HoughCircles(blur_mat, circles, cv::HOUGH_GRADIENT,
                1.2, min_side * 0.6, 50, 35,
                (int)(min_side * 0.4), (int)(min_side * 0.55));

        if (circles.empty()) {
            return DetectionResult{};
        }

        cv::Vec3f c = circles[0];
        double det_cx = c[0];
        double det_cy = c[1];
        double det_r = c[2];

        int black_count = 0;
        for (int i = 0; i < 120; i++) {
            double theta = (i * 3.0) * CV_PI / 180.0;
            int sx = (int)(det_cx + det_r * cos(theta));
            int sy = (int)(det_cy + det_r * sin(theta));
            if (sx >= 0 && sx < SMALL_WIDTH && sy >= 0 && sy < (int)small_gray.rows) {
                if (small_gray_data[sy * SMALL_WIDTH + sx] < 150) {
                    black_count++;
                }
            }
        }

        double dist_to_center = std::hypot(det_cx - SMALL_WIDTH / 2.0, det_cy - small_gray.rows / 2.0);
        double max_dist = min_side * CENTER_OFFSET_RATIO;
        if ((double)black_count / 120 > BLACK_RATIO_THRESHOLD && dist_to_center < max_dist) {
            double scale = (double)SMALL_WIDTH / w;
            return DetectionResult{
                true,
                det_cx / scale,
                det_cy / scale,
                (int)(det_r / scale)
            };
        }

        return DetectionResult{};
    }

private:
    cv::Mat gray_mat;
    cv::Mat small_gray;
    cv::Mat blur_mat;
    std::vector<uint8_t> small_gray_data;

    void init_mats(int w, int h) {
        if (gray_mat.cols != w || gray_mat.rows != h) {
            double scale = (double)SMALL_WIDTH / w;
            int sh = (int)(h * scale);
            gray_mat = cv::Mat(h, w, CV_8UC1);
            small_gray = cv::Mat(sh, SMALL_WIDTH, CV_8UC1);
            blur_mat = cv::Mat(sh, SMALL_WIDTH, CV_8UC1);
            small_gray_data.resize(SMALL_WIDTH * sh);
        }
    }
};

// ============================================================================
// Circle mask applier
// ============================================================================
static void apply_circle_mask(uint8_t* data, int w, int h,
                               double center_x, double center_y, int radius) {
    double r2 = (double)radius * radius;

    // 计算圆覆盖的 y 范围，超出范围的整行直接批量清零
    int min_y = std::max(0, (int)std::ceil(center_y - radius));
    int max_y = std::min(h - 1, (int)std::floor(center_y + radius));

    // 圆上方全部清零
    if (min_y > 0) {
        memset(data, 0, (size_t)min_y * w);
    }

    // LUT: 预计算每个 |dy| 对应的水平跨度，避免逐行 sqrt
    // radius 最大 ~100px，LUT 开销可忽略
    int lut_size = std::max(0, max_y - min_y + 1);
    std::vector<double> dx_lut(lut_size);
    for (int i = 0; i < lut_size; i++) {
        int y = min_y + i;
        double dy = y - center_y;
        double dy2 = dy * dy;
        dx_lut[i] = (dy2 < r2) ? std::sqrt(r2 - dy2) : 0.0;
    }

    for (int i = 0; i < lut_size; i++) {
        int y = min_y + i;
        int offset = y * w;
        double dx_span = dx_lut[i];

        if (dx_span <= 0.0) {
            memset(data + offset, 0, w);
            continue;
        }

        int left = (int)std::ceil(center_x - dx_span);
        int right = (int)std::floor(center_x + dx_span);
        int safe_left = std::max(0, left);
        int safe_right = std::min(w - 1, right);

        if (safe_left > 0) {
            memset(data + offset, 0, safe_left);
        }
        if (safe_right < w - 1) {
            memset(data + offset + safe_right + 1, 0, w - safe_right - 1);
        }
    }

    // 圆下方全部清零
    if (max_y < h - 1) {
        memset(data + (size_t)(max_y + 1) * w, 0, (size_t)(h - 1 - max_y) * w);
    }
}

// ============================================================================
// Arrow angle detection: HSV color-based method
//
// Ported from ArrowAngleDrawer.java — uses the same HSV thresholds
// (H 10~25, S 200~255, V 200~255) and geometric analysis pipeline.
//
// Algorithm: BGRA → crop center 64×64 → HSV → inRange → largest contour →
// convex hull → smallest interior angle vertex = tip →
// base midpoint → direction angle.
//
// Returns 0~360° angle, or NaN if detection fails.
// ============================================================================
static double detect_arrow_angle_hsv(const uint8_t* bgra_data, int w, int h,
                                      double cx, double cy, int radius) {
    if (radius < 15) return std::numeric_limits<double>::quiet_NaN();

    // 只处理圆中心 64×64 区域（箭头必定在小地图中心附近）
    static constexpr int CROP_SIZE = 64;
    int cropX = (int)std::round(cx - CROP_SIZE / 2);
    int cropY = (int)std::round(cy - CROP_SIZE / 2);
    // 裁剪边界保护
    if (cropX < 0) cropX = 0;
    if (cropY < 0) cropY = 0;
    if (cropX + CROP_SIZE > w) cropX = w - CROP_SIZE;
    if (cropY + CROP_SIZE > h) cropY = h - CROP_SIZE;
    if (cropX < 0 || cropY < 0) return std::numeric_limits<double>::quiet_NaN();

    // BGRA ROI（别名，不拷贝）
    cv::Mat full(h, w, CV_8UC4, const_cast<uint8_t*>(bgra_data));
    cv::Mat roi(full, cv::Rect(cropX, cropY, CROP_SIZE, CROP_SIZE));

    // ---- 1. HSV 颜色过滤（与 Java ArrowAngleDrawer 阈值一致） ----
    // H: 10~25（橙色范围，OpenCV 中 H 0~179）
    // S: 200~255（高饱和度，过滤背景浅色）
    // V: 200~255（中高亮度，捕获箭头渐变阴影）
    cv::Mat hsv;
    cv::cvtColor(roi, hsv, cv::COLOR_BGRA2BGR);
    cv::cvtColor(hsv, hsv, cv::COLOR_BGR2HSV);

    cv::Mat mask;
    cv::inRange(hsv, cv::Scalar(10, 200, 200), cv::Scalar(25, 255, 255), mask);

    // 闭运算填充细小孔洞（箭头内部可能因阴影有空洞）
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));
    cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, kernel);

    // ---- 2. 找最大外部轮廓 ----
    // 箭头尾部在 HSV 过滤后有时会分离出独立小圆点，
    // 只取最大轮廓可以自动过滤这种干扰
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(mask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    if (contours.empty()) return std::numeric_limits<double>::quiet_NaN();

    int maxIdx = 0;
    double maxArea = 0;
    for (size_t i = 0; i < contours.size(); i++) {
        double area = cv::contourArea(contours[i]);
        if (area > maxArea) {
            maxArea = area;
            maxIdx = (int)i;
        }
    }
    if (maxArea < 20) return std::numeric_limits<double>::quiet_NaN();

    // ---- 3. 凸包构建 ----
    std::vector<cv::Point> hull;
    cv::convexHull(contours[maxIdx], hull, false, true);
    if (hull.size() < 3) return std::numeric_limits<double>::quiet_NaN();

    // 顶点过多时简化（epsilon = 2% 周长）
    if (hull.size() > 5) {
        std::vector<cv::Point> simplified;
        cv::approxPolyDP(hull, simplified,
                         0.02 * cv::arcLength(contours[maxIdx], true), true);
        if (simplified.size() >= 3) hull = simplified;
    }

    // ---- 4. 最小内角顶点 = 箭头尖端 ----
    int tipIdx = 0;
    double minAngle = 1e10;
    int n = (int)hull.size();
    for (int i = 0; i < n; i++) {
        const cv::Point& cur = hull[i];
        const cv::Point& prev = hull[(i - 1 + n) % n];
        const cv::Point& next = hull[(i + 1) % n];

        double e1x = prev.x - cur.x, e1y = prev.y - cur.y;
        double e2x = next.x - cur.x, e2y = next.y - cur.y;
        double len1 = std::sqrt(e1x * e1x + e1y * e1y);
        double len2 = std::sqrt(e2x * e2x + e2y * e2y);
        if (len1 < 1 || len2 < 1) continue;

        double dot = (e1x * e2x + e1y * e2y) / (len1 * len2);
        double angle = std::acos(std::max(-1.0, std::min(1.0, dot)));
        if (angle < minAngle) {
            minAngle = angle;
            tipIdx = i;
        }
    }

    // ---- 5. 底边中点定向 ----
    // 方向 = 底边中点 → 尖端
    // 使用底边中点而非重心作为参考点的原因：
    //   重心受尾部质量分布影响（如尾部小圆点），可能偏移；
    //   底边中点是纯几何量，不受质量分布影响，方向更准确。
    const cv::Point& tip = hull[tipIdx];
    const cv::Point& prevPt = hull[(tipIdx - 1 + n) % n];
    const cv::Point& nextPt = hull[(tipIdx + 1) % n];

    double baseMX = (prevPt.x + nextPt.x) / 2.0;
    double baseMY = (prevPt.y + nextPt.y) / 2.0;

    double dx = tip.x - baseMX;
    double dy = tip.y - baseMY;
    double angleDeg = std::atan2(dy, dx) * 180.0 / CV_PI;
    if (angleDeg < 0) angleDeg += 360;

    return angleDeg;
}

// ============================================================================
// 重叠分块训练常量 (Overlapping Tiling)
// ============================================================================
static constexpr int TILE_SIZE = 2000;                     // 瓦片尺寸
static constexpr int TILE_OVERLAP = 200;                   // 重叠宽度
static constexpr int64_t LARGE_MAP_THRESHOLD_PX = 9000000; // 9Mpx → 启用分块
static constexpr float DEDUP_DISTANCE = 4.0f;              // 去重距离

// ============================================================================
// SIFT Matcher: exact Java SiftMapMatcher parameters
// ============================================================================
class SiftMatcher {
public:
    cv::Ptr<cv::SIFT> sift;
    std::unique_ptr<DescriptorTransform> transform;
    // Map keypoints
    std::vector<cv::KeyPoint> map_keypoints;
    std::vector<cv::Point2f> map_keypoint_pts;

    // CV_8U 数据存储 + FLANN 索引（直接 uint8，无 float 膨胀）
    cv::Mat flann_data_storage;
    std::unique_ptr<cvflann::KDTreeIndex<cvflann::L2<unsigned char>>> u8_index;
    // CV_32F 数据存储 + FLANN 索引（避免 FlannBasedMatcher 的 add() clone）
    cv::Mat flann_data_storage_32f;
    std::unique_ptr<cvflann::KDTreeIndex<cvflann::L2<float>>> f32_index;

    // 复用容器，避免每帧堆分配
    std::vector<cv::DMatch> good_matches;
    std::vector<cv::DMatch> filtered_matches;
    std::vector<cv::Point2f> src_pts;
    std::vector<cv::Point2f> dst_pts;

    // Algorithm params
    float match_ratio_threshold = 0.6f;
    int match_min_count = 10;
    int search_radius = 500;
    double ransac_reproj_threshold = 10.0;
    int ransac_max_iters = 200;
    double ransac_confidence = 0.95;
    int flann_search_checks = 24;

    SiftMatcher(const AlgoParams& p)
        : match_ratio_threshold((float)p.matchRatioThreshold)
        , match_min_count(p.matchMinCount)
        , search_radius(p.searchRadius)
        , ransac_reproj_threshold(p.ransacReprojThreshold)
        , ransac_max_iters(p.ransacMaxIters)
        , ransac_confidence(p.ransacConfidence)
        , flann_search_checks(p.flannSearchChecks)
    {
        sift = cv::SIFT::create(
            p.nfeatures,
            p.nOctaveLayers,
            p.contrastThreshold,
            p.edgeThreshold,
            p.sigma,
            false);  // enable_precise_upscale = false (matches Java)
        transform = std::make_unique<DescriptorTransform>(p.variant);
    }

    // Train from raw grayscale pixels (Java side already decoded PNG → Gray)
    bool train(const uint8_t* gray_pixels, int w, int h) {
        cv::Mat map_gray(h, w, CV_8UC1, (void*)gray_pixels);
        if (map_gray.empty()) {
            LOGERR("Invalid map image: %dx%d", w, h);
            return false;
        }

        int64_t total_pixels = (int64_t)w * h;
        if (total_pixels >= LARGE_MAP_THRESHOLD_PX) {
            LOG("Map is large (%dx%d=%lldpx), using overlapping tiling",
                w, h, (long long)total_pixels);
            return train_tiled(map_gray, w, h);
        }

        return train_direct(map_gray);
    }

private:
    // ----- 小图直接训练 (≤9Mpx) -----
    bool train_direct(cv::Mat& map_gray) {
        // SIFT detect + compute
        cv::Mat raw_descriptors;
        sift->detectAndCompute(map_gray, cv::noArray(), map_keypoints, raw_descriptors);

        if (raw_descriptors.empty() || raw_descriptors.type() != CV_32F) {
            LOGERR("SIFT detection failed on map");
            return false;
        }

        // Store keypoint coords for spatial filtering
        map_keypoint_pts.reserve(map_keypoints.size());
        for (auto& kp : map_keypoints) {
            map_keypoint_pts.push_back(kp.pt);
        }

        // Apply descriptor transform (PCA / quantize)
        if (!transform->train(raw_descriptors)) {
            LOGERR("Descriptor transform failed");
            return false;
        }
        build_flann_index();

        LOG("SIFT trained: %zu features", map_keypoints.size());
        return true;
    }

    // ----- 大图重叠分块训练 (>9Mpx) -----
    bool train_tiled(cv::Mat& map_gray, int map_w, int map_h) {
        int stride = TILE_SIZE - TILE_OVERLAP;
        int cols = (int)std::ceil((double)(map_w - TILE_OVERLAP) / stride);
        int rows = (int)std::ceil((double)(map_h - TILE_OVERLAP) / stride);
        LOG("Tile layout: %dx%d (%d tiles)", cols, rows, cols * rows);

        // 收集所有瓦片的特征点
        struct KpEntry {
            float x, y;
            std::vector<float> desc;
        };
        std::vector<KpEntry> all_kps;
        int desc_dim = 128; // SIFT default

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int tile_x = c * stride;
                int tile_y = r * stride;
                int tile_w = std::min(TILE_SIZE, map_w - tile_x);
                int tile_h = std::min(TILE_SIZE, map_h - tile_y);

                cv::Rect roi(tile_x, tile_y, tile_w, tile_h);
                cv::Mat tile_gray = map_gray(roi);

                std::vector<cv::KeyPoint> tile_kps;
                cv::Mat tile_descs;
                sift->detectAndCompute(tile_gray, cv::noArray(), tile_kps, tile_descs);

                if (tile_descs.empty()) continue;

                // 确保 CV_32F
                cv::Mat desc_float;
                if (tile_descs.type() != CV_32F) {
                    tile_descs.convertTo(desc_float, CV_32F);
                } else {
                    desc_float = tile_descs;
                }

                desc_dim = desc_float.cols; // 128 for SIFT

                for (size_t i = 0; i < tile_kps.size(); i++) {
                    // ★ 坐标还原：加上瓦片偏移量得到全图坐标
                    KpEntry entry;
                    entry.x = tile_kps[i].pt.x + (float)tile_x;
                    entry.y = tile_kps[i].pt.y + (float)tile_y;
                    entry.desc.resize(desc_dim);
                    memcpy(entry.desc.data(), desc_float.ptr<float>((int)i),
                           desc_dim * sizeof(float));
                    all_kps.push_back(std::move(entry));
                }
            }
        }

        if (all_kps.empty()) {
            LOGERR("No keypoints detected in any tile");
            return false;
        }

        // ========== 重叠区域去重 ==========
        int total_count = (int)all_kps.size();

        // 空间网格索引 O(1)：将 2D 坐标映射到 cellSize 网格
        int cell_size = (int)std::ceil(DEDUP_DISTANCE);
        int grid_cols = map_w / cell_size + 1;
        int grid_rows = map_h / cell_size + 1;
        std::vector<int> grid(grid_cols * grid_rows, -1);

        std::vector<bool> keep(total_count, false);
        int keep_count = 0;

        // 先到先保留：首次遇到的特征加入网格，后续落入同格或邻格且距离 < DEDUP_DISTANCE 的视为重复跳过
        for (int i = 0; i < total_count; i++) {
            auto& kp = all_kps[i];
            int cx = (int)(kp.x / cell_size);
            int cy = (int)(kp.y / cell_size);

            bool duplicate = false;
            for (int dx = -1; dx <= 1 && !duplicate; dx++) {
                for (int dy = -1; dy <= 1 && !duplicate; dy++) {
                    int nx = cx + dx;
                    int ny = cy + dy;
                    if (nx >= 0 && nx < grid_cols && ny >= 0 && ny < grid_rows) {
                        int existing = grid[ny * grid_cols + nx];
                        if (existing >= 0) {
                            auto& ekp = all_kps[existing];
                            float dist = std::hypot(kp.x - ekp.x, kp.y - ekp.y);
                            if (dist < DEDUP_DISTANCE) {
                                duplicate = true;
                            }
                        }
                    }
                }
            }

            if (!duplicate) {
                grid[cy * grid_cols + cx] = i;
                keep[i] = true;
                keep_count++;
            }
        }

        LOG("Dedup: %d → %d (removed %d overlapping duplicates)",
            total_count, keep_count, total_count - keep_count);

        // ========== 构建合并的描述符矩阵 ==========
        cv::Mat all_descs(keep_count, desc_dim, CV_32F);
        int pos = 0;
        for (int i = 0; i < total_count; i++) {
            if (keep[i]) {
                memcpy(all_descs.ptr<float>(pos), all_kps[i].desc.data(),
                       desc_dim * sizeof(float));
                pos++;
            }
        }

        // 描述符变换 (PCA + 量化)
        if (!transform->train(all_descs)) {
            LOGERR("Descriptor transform failed");
            return false;
        }

        // 构建关键点列表
        map_keypoints.clear();
        map_keypoints.reserve(keep_count);
        map_keypoint_pts.clear();
        map_keypoint_pts.reserve(keep_count);
        for (int i = 0; i < total_count; i++) {
            if (keep[i]) {
                auto& kp = all_kps[i];
                map_keypoints.push_back(cv::KeyPoint(kp.x, kp.y, 1.0f));
                map_keypoint_pts.emplace_back(kp.x, kp.y);
            }
        }

        build_flann_index();

        LOG("SIFT trained (tiled): %zu features", map_keypoints.size());
        return true;
    }

    void build_flann_index() {
        cv::Mat& descs = transform->persistent_mat;
        if (descs.type() == CV_8U) {
            // 直接建 CV_8U KD-tree，无 float 膨胀
            flann_data_storage = descs;
            int rows = descs.rows;
            int cols = descs.cols;
            cvflann::Matrix<unsigned char> data(flann_data_storage.ptr<unsigned char>(),
                                                  (size_t)rows, (size_t)cols);
            auto index = std::make_unique<cvflann::KDTreeIndex<cvflann::L2<unsigned char>>>(
                data, cvflann::KDTreeIndexParams(1));
            index->buildIndex();
            u8_index = std::move(index);
            LOG("FLANN CV_8U index built: %d features, %d dims", rows, cols);
        } else {
            // CV_32F 路径：直接 KDTreeIndex，避免 FlannBasedMatcher 的 add() clone
            flann_data_storage_32f = descs;
            int rows = descs.rows;
            int cols = descs.cols;
            cvflann::Matrix<float> data(flann_data_storage_32f.ptr<float>(),
                                          (size_t)rows, (size_t)cols);
            auto index = std::make_unique<cvflann::KDTreeIndex<cvflann::L2<float>>>(
                data, cvflann::KDTreeIndexParams(1));
            index->buildIndex();
            f32_index = std::move(index);
            LOG("FLANN CV_32F index built: %d features, %d dims", rows, cols);
        }
        // persistent_mat 的释放由调用方在保存缓存后处理
    }

public:

    // Load from cache (all data already populated)
    bool load_from_cache() {
        // 从 persistent_mat 构建 FLANN 索引（build_flann_index 根据类型自动选择 CV_8U 或 CV_32F）
        build_flann_index();

        // FLANN 索引已深拷贝数据，persistent_mat 可释放
        transform->persistent_mat = cv::Mat();

        // Build keypoint coords from stored keypoints
        map_keypoint_pts.reserve(map_keypoints.size());
        for (auto& kp : map_keypoints) {
            map_keypoint_pts.push_back(kp.pt);
        }

        LOG("SIFT loaded from cache: %zu features", map_keypoints.size());
        return true;
    }

    struct MatchResult {
        bool success = false;
        double x = 0;
        double y = 0;
        float t_minimap_ms = 0;  // 小地图 HoughCircles 检测耗时
        float t_extract_ms = 0;  // SIFT detectAndCompute 特征提取耗时
        float t_flann_ms = 0;    // FLANN knnSearch + RANSAC 匹配耗时
    };

    // Match a frame (gray8 pixels), optionally with spatial hint.
    // 注意: scene_img 不拷贝 data，仅别名外部 buffer。
    // 调用方必须保证 data 在 match() 返回前有效（当前为同步调用，满足此约束）。
    MatchResult match(uint8_t* data, int w, int h, double hint_x, double hint_y) {
        MatchResult res{};
        if (!f32_index && !u8_index) return res;

        cv::Mat scene_img(h, w, CV_8UC1, data);  // 非拷贝，别名 data

        // SIFT detect + compute on scene
        auto t0 = std::chrono::steady_clock::now();
        std::vector<cv::KeyPoint> scene_kps;
        cv::Mat scene_descriptors;
        sift->detectAndCompute(scene_img, cv::noArray(), scene_kps, scene_descriptors);
        auto t1 = std::chrono::steady_clock::now();
        res.t_extract_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();

        if (scene_descriptors.empty()) return res;

        // Ensure CV_32F
        if (scene_descriptors.type() != CV_32F) {
            scene_descriptors.convertTo(scene_descriptors, CV_32F);
        }

        // FLANN knnMatch — 根据索引类型选择路径
        good_matches.clear();
        if (u8_index) {
            // CV_8U 路径：直接量化到 uint8，无冗余反量化
            cv::Mat query_u8 = transform->process_to_u8(scene_descriptors);

            int q_rows = query_u8.rows;
            int q_cols = query_u8.cols;
            for (int qi = 0; qi < q_rows; qi++) {
                cvflann::Matrix<unsigned char> qmat(query_u8.ptr<unsigned char>(qi), 1, q_cols);
                int idx[2];
                float dists[2];
                u8_index->knnSearch(qmat, cvflann::Matrix<int>(idx, 1, 2),
                                    cvflann::Matrix<float>(dists, 1, 2), 2,
                                    cvflann::SearchParams(flann_search_checks));
                if (idx[1] >= 0 && dists[0] < match_ratio_threshold * dists[1]) {
                    good_matches.push_back(cv::DMatch(qi, idx[0], dists[0]));
                }
            }
        } else if (f32_index) {
            // CV_32F 路径：直接 float KD-tree，无需 clone
            cv::Mat query_desc = transform->process(scene_descriptors);
            int q_rows = query_desc.rows;
            int q_cols = query_desc.cols;
            for (int qi = 0; qi < q_rows; qi++) {
                cvflann::Matrix<float> qmat(query_desc.ptr<float>(qi), 1, q_cols);
                int idx[2];
                float dists[2];
                f32_index->knnSearch(qmat, cvflann::Matrix<int>(idx, 1, 2),
                                     cvflann::Matrix<float>(dists, 1, 2), 2,
                                     cvflann::SearchParams(flann_search_checks));
                if (idx[1] >= 0 && dists[0] < match_ratio_threshold * dists[1]) {
                    good_matches.push_back(cv::DMatch(qi, idx[0], dists[0]));
                }
            }
        }

        // Spatial filter: if hint available, exclude matches far from predicted position
        bool has_hint = !std::isnan(hint_x) && !std::isnan(hint_y)
                     && hint_x >= -1e9 && hint_y >= -1e9;
        filtered_matches.clear();
        if (has_hint) {
            for (auto& dm : good_matches) {
                if (dm.trainIdx >= 0 && dm.trainIdx < (int)map_keypoint_pts.size()) {
                    auto& pt = map_keypoint_pts[dm.trainIdx];
                    double dist = std::hypot(pt.x - hint_x, pt.y - hint_y);
                    if (dist <= search_radius) {
                        filtered_matches.push_back(dm);
                    }
                }
            }
            if (filtered_matches.size() < 4) {
                filtered_matches = good_matches; // fallback
            }
        } else {
            filtered_matches = good_matches;
        }

        if (filtered_matches.size() < (size_t)match_min_count) return res;

        // Build point sets for RANSAC homography
        src_pts.clear();
        dst_pts.clear();
        for (auto& dm : filtered_matches) {
            if (dm.queryIdx >= 0 && dm.queryIdx < (int)scene_kps.size()
                && dm.trainIdx >= 0 && dm.trainIdx < (int)map_keypoint_pts.size()) {
                src_pts.push_back(scene_kps[dm.queryIdx].pt);
                dst_pts.push_back(map_keypoint_pts[dm.trainIdx]);
            }
        }

        if (src_pts.size() < (size_t)match_min_count) {
            res.t_flann_ms = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t1).count();
            return res;
        }

        // RANSAC homography with Java params
        cv::Mat inlier_mask;
        cv::Mat H = cv::findHomography(src_pts, dst_pts, cv::RANSAC,
                ransac_reproj_threshold, inlier_mask, ransac_max_iters, ransac_confidence);

        if (!H.empty() && H.rows == 3) {
            // Transform scene center → world coordinates
            std::vector<cv::Point2f> src_center = { cv::Point2f(w / 2.0f, h / 2.0f) };
            std::vector<cv::Point2f> dst_center;
            cv::perspectiveTransform(src_center, dst_center, H);

            res.success = true;
            res.x = dst_center[0].x;
            res.y = dst_center[0].y;
        }

        res.t_flann_ms = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t1).count();
        return res;
    }
};

// ============================================================================
// CONFIG_DATA parser
// ============================================================================
static bool parse_config_data(const std::vector<uint8_t>& body, AlgoParams& p) {
    if (body.size() < 88) { // Minimum size without path strings
        LOGERR("CONFIG_DATA too short: %zu bytes", body.size());
        return false;
    }

    size_t off = 0;

    p.variant        = static_cast<SiftVariant>((int)read_be32(body.data() + off)); off += 4;
    p.nfeatures      = (int)read_be32(body.data() + off); off += 4;
    p.nOctaveLayers  = (int)read_be32(body.data() + off); off += 4;
    p.contrastThreshold = read_double(body.data() + off); off += 8;
    p.edgeThreshold  = read_double(body.data() + off); off += 8;
    p.sigma          = read_double(body.data() + off); off += 8;
    p.matchRatioThreshold = read_double(body.data() + off); off += 8;
    p.matchMinCount  = (int)read_be32(body.data() + off); off += 4;
    p.searchRadius   = (int)read_be32(body.data() + off); off += 4;
    p.flannKDTreeCount = (int)read_be32(body.data() + off); off += 4;
    p.flannSearchChecks = (int)read_be32(body.data() + off); off += 4;
    p.ransacReprojThreshold = read_double(body.data() + off); off += 8;
    p.ransacMaxIters = (int)read_be32(body.data() + off); off += 4;
    p.ransacConfidence = read_double(body.data() + off); off += 8;

    // cacheFilePath
    if (off + 4 > body.size()) return false;
    int32_t cachePathLen = (int32_t)read_be32(body.data() + off); off += 4;
    if (cachePathLen < 0 || off + cachePathLen > body.size()) return false;
    p.cacheFilePath = std::string((const char*)body.data() + off, cachePathLen);

    LOG("Parsed CONFIG_DATA:");
    LOG("  variant=%d", (int)p.variant);
    LOG("  SIFT: nfeatures=%d nOctaveLayers=%d contrast=%.4f edge=%.1f sigma=%.1f",
        p.nfeatures, p.nOctaveLayers, p.contrastThreshold, p.edgeThreshold, p.sigma);
    LOG("  MATCH: ratio=%.2f minCount=%d searchRadius=%d",
        p.matchRatioThreshold, p.matchMinCount, p.searchRadius);
    LOG("  FLANN: kd=%d checks=%d", p.flannKDTreeCount, p.flannSearchChecks);
    LOG("  RANSAC: reproj=%.1f iters=%d confidence=%.2f",
        p.ransacReprojThreshold, p.ransacMaxIters, p.ransacConfidence);
    LOG("  cachePath=%s", p.cacheFilePath.c_str());

    return true;
}

// ============================================================================
// Cache file I/O (full file format: header + DescriptorTransform + keypoints)
// ============================================================================
static constexpr uint32_t CACHE_MAGIC = 0x53494654; // "SIFT"
static constexpr int32_t CACHE_VERSION = 1;

static bool save_cache_file(const std::string& path, SiftMatcher& matcher) {
    // 原子写入: 先写临时文件，成功后再 rename，防止写入中途崩溃产生损坏文件
    std::string tmpPath = path + ".tmp";

    FILE* f = fopen(tmpPath.c_str(), "wb");
    if (!f) {
        LOGERR("Failed to create cache temp file: %s", tmpPath.c_str());
        return false;
    }

    uint32_t magic = CACHE_MAGIC;
    int32_t version = CACHE_VERSION;
    int32_t variant = (int32_t)matcher.transform->variant;

    fwrite(&magic, 4, 1, f);
    fwrite(&version, 4, 1, f);
    fwrite(&variant, 4, 1, f);

    // DescriptorTransform state
    matcher.transform->save_cache(f);

    // Keypoints
    int32_t kpCount = (int32_t)matcher.map_keypoints.size();
    fwrite(&kpCount, 4, 1, f);
    for (auto& kp : matcher.map_keypoints) {
        float x = kp.pt.x;
        float y = kp.pt.y;
        fwrite(&x, 4, 1, f);
        fwrite(&y, 4, 1, f);
    }

    if (fclose(f) != 0) {
        LOGERR("Failed to close cache temp file: %s", tmpPath.c_str());
        DeleteFileA(tmpPath.c_str());
        return false;
    }

    // Windows rename 不覆盖已有文件，先删除再改名
    DeleteFileA(path.c_str());
    if (rename(tmpPath.c_str(), path.c_str()) != 0) {
        LOGERR("Failed to rename cache file: %s -> %s", tmpPath.c_str(), path.c_str());
        DeleteFileA(tmpPath.c_str());
        return false;
    }

    LOG("Cache saved: %s (%d features)", path.c_str(), kpCount);
    return true;
}

static bool load_cache_file(const std::string& path, SiftMatcher& matcher) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) {
        LOG("No cache file at: %s", path.c_str());
        return false;
    }

    uint32_t magic;
    int32_t version, variant;
    if (fread(&magic, 4, 1, f) != 1 || magic != CACHE_MAGIC) {
        LOG("Invalid cache magic");
        fclose(f);
        return false;
    }
    if (fread(&version, 4, 1, f) != 1 || version != CACHE_VERSION) {
        LOG("Invalid cache version: %d", version);
        fclose(f);
        return false;
    }
    if (fread(&variant, 4, 1, f) != 1 || variant != (int32_t)matcher.transform->variant) {
        LOG("Cache variant mismatch: %d vs %d", variant, (int32_t)matcher.transform->variant);
        fclose(f);
        return false;
    }

    // DescriptorTransform state
    if (!matcher.transform->load_cache(f)) {
        LOG("Failed to load DescriptorTransform from cache");
        fclose(f);
        return false;
    }

    // Keypoints
    int32_t kpCount;
    if (fread(&kpCount, 4, 1, f) != 1 || kpCount <= 0) {
        LOG("Invalid keypoint count in cache");
        fclose(f);
        return false;
    }
    matcher.map_keypoints.clear();
    matcher.map_keypoints.reserve(kpCount);
    for (int i = 0; i < kpCount; i++) {
        float x, y;
        if (fread(&x, 4, 1, f) != 1 || fread(&y, 4, 1, f) != 1) {
            LOG("Truncated keypoint data at index %d", i);
            fclose(f);
            return false;
        }
        matcher.map_keypoints.push_back(cv::KeyPoint(x, y, 1.0f));
    }

    fclose(f);

    // Build FLANN matcher from loaded data
    if (!matcher.load_from_cache()) {
        LOG("Failed to build FLANN matcher from cache");
        return false;
    }

    LOG("Cache loaded: %s (%d features)", path.c_str(), kpCount);
    return true;
}

// ============================================================================
// Serialize MATCH_RESULT body: [1]success [8]x [8]y [8]angle
//                              [4]t_minimap_ms [4]t_extract_ms [4]t_flann_ms [4]t_arrow_ms
// ============================================================================
static std::vector<uint8_t> serialize_result(bool success, double x, double y, double angle,
                                              float t_minimap_ms = 0, float t_extract_ms = 0,
                                              float t_flann_ms = 0, float t_arrow_ms = 0) {
    std::vector<uint8_t> buf(41);  // 1 + 8 + 8 + 8 + 4 + 4 + 4 + 4
    buf[0] = success ? 1 : 0;
    write_double(buf.data() + 1, x);
    write_double(buf.data() + 9, y);
    write_double(buf.data() + 17, angle);
    write_float_be(buf.data() + 25, t_minimap_ms);
    write_float_be(buf.data() + 29, t_extract_ms);
    write_float_be(buf.data() + 33, t_flann_ms);
    write_float_be(buf.data() + 37, t_arrow_ms);
    return buf;
}

// ============================================================================
// MAIN
// ============================================================================
static std::atomic<bool> g_running{true};

int main(int argc, char* argv[]) {
    // 禁用 stdout 缓冲，确保 Java 侧能实时看到日志
    setvbuf(stdout, NULL, _IONBF, 0);

    LOG("============================================================");
    LOG("  SIFT Match Process (Socket Mode) v2");
    LOG("============================================================");

    if (argc < 2) {
        LOGERR("Usage: sift_match.exe <port>");
        LOGERR("  port : TCP port for Java connection");
        return 1;
    }

    int port = atoi(argv[1]);
    LOG("Config: port=%d", port);

    // ---- WinSock init ----
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        LOGERR("WSAStartup failed: %d", WSAGetLastError());
        return 1;
    }

    // ---- Connect to Java ----
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        LOGERR("socket() failed");
        WSACleanup();
        return 1;
    }

    int nodelay = 1;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (const char*)&nodelay, sizeof(nodelay));

    sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons((u_short)port);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    LOG("Connecting to 127.0.0.1:%d...", port);
    int retry = 0;
    const int MAX_RETRIES = 30;
    while (connect(sock, (sockaddr*)&addr, sizeof(addr)) != 0) {
        if (++retry > MAX_RETRIES) {
            LOGERR("Connection failed after %d retries", MAX_RETRIES);
            closesocket(sock);
            WSACleanup();
            return 1;
        }
        LOG("Retry %d/%d...", retry, MAX_RETRIES);
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    LOG("Connected to Java");

    // ---- Phase 0: HELLO (identify + declare provides/subscribes) ----
    {
        const int32_t provides[]   = { REQUEST_MAP, REQUEST_CONFIG, INIT_COMPLETE, INIT_FAILED, READY, MATCH_RESULT };
        const int32_t subscribes[] = { MAP_DATA, FRAME_DATA, SHUTDOWN, CONFIG_DATA };
        auto hello = build_hello("sift", provides, 6, subscribes, 4);
        LOG("Sending HELLO (sift, provides=%d, subscribes=%d)...", 6, 4);
        if (!send_message(sock, HELLO, hello.data(), (uint32_t)hello.size())) {
            LOGERR("Failed to send HELLO");
            closesocket(sock);
            WSACleanup();
            return 1;
        }
    }

    // ---- Phase 1: Request algorithm configuration ----
    LOG("Requesting algorithm configuration...");
    if (!send_message(sock, REQUEST_CONFIG, nullptr, 0)) {
        LOGERR("Failed to send REQUEST_CONFIG");
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    std::vector<uint8_t> recv_body;
    int32_t type = recv_message(sock, recv_body);
    if (type != CONFIG_DATA) {
        LOGERR("Expected CONFIG_DATA (221), got type=%d", type);
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    AlgoParams params;
    if (!parse_config_data(recv_body, params)) {
        LOGERR("Failed to parse CONFIG_DATA");
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    // ---- Phase 2: Try load cache, or request map data ----
    SiftMatcher matcher(params);
    bool init_ok = false;

    if (!params.cacheFilePath.empty()) {
        init_ok = load_cache_file(params.cacheFilePath, matcher);
    }

    if (!init_ok) {
        // Cache miss — request map data from Java
        LOG("Cache miss, requesting map data...");
        if (!send_message(sock, REQUEST_MAP, nullptr, 0)) {
            LOGERR("Failed to send REQUEST_MAP");
            closesocket(sock);
            WSACleanup();
            return 1;
        }

        type = recv_message(sock, recv_body);
        if (type != MAP_DATA || recv_body.size() < 12) {
            LOGERR("Expected MAP_DATA, got type=%d size=%zu", type, recv_body.size());
            closesocket(sock);
            WSACleanup();
            return 1;
        }

        int map_w = (int)read_be32(recv_body.data());
        int map_h = (int)read_be32(recv_body.data() + 4);
        uint32_t pixels_len = read_be32(recv_body.data() + 8);
        if (pixels_len != (uint32_t)(map_w * map_h)) {
            LOGERR("Map pixel size mismatch: %dx%d != %u", map_w, map_h, pixels_len);
            closesocket(sock);
            WSACleanup();
            return 1;
        }
        LOG("Received map data: %dx%d (%u gray pixels)", map_w, map_h, pixels_len);

        uint8_t* map_pixels = recv_body.data() + 12;

        if (!matcher.train(map_pixels, map_w, map_h)) {
            LOGERR("SIFT training failed");
            const char* err_msg = "SIFT training failed";
            uint8_t err_buf[8];
            write_be32(err_buf, 1);
            write_be32(err_buf + 4, (uint32_t)strlen(err_msg));
            std::vector<uint8_t> body;
            body.insert(body.end(), err_buf, err_buf + 8);
            body.insert(body.end(), err_msg, err_msg + strlen(err_msg));
            send_message(sock, INIT_FAILED, body.data(), (uint32_t)body.size());
            closesocket(sock);
            WSACleanup();
            return 1;
        }

        // Save cache for next run
        if (!params.cacheFilePath.empty()) {
            // Ensure parent directory exists
            size_t lastSep = params.cacheFilePath.find_last_of("\\/");
            if (lastSep != std::string::npos) {
                std::string dir = params.cacheFilePath.substr(0, lastSep);
                CreateDirectoryA(dir.c_str(), nullptr);
            }
            save_cache_file(params.cacheFilePath, matcher);
        }
        // 持久化描述符(CV_8U)已不再需要：FLANN 索引已深拷贝数据到 KD-tree
        matcher.transform->persistent_mat = cv::Mat();
    }

    // ---- Send INIT_COMPLETE ----
    {
        uint8_t feat_buf[4];
        write_be32(feat_buf, (uint32_t)matcher.map_keypoints.size());
        if (!send_message(sock, INIT_COMPLETE, feat_buf, 4)) {
            LOGERR("Failed to send INIT_COMPLETE");
            closesocket(sock);
            WSACleanup();
            return 1;
        }
    }
    LOG("INIT_COMPLETE sent, entering matching loop...");

    // ---- Phase 4: Matching loop ----
    MiniMapProcessor minimap;
    int64_t frame_count = 0;
    int64_t success_count = 0;
    bool first_ready = true;

    while (g_running.load(std::memory_order_acquire)) {
        if (first_ready) {
            LOG("Sending first READY...");
            first_ready = false;
        }
        // Signal READY (backpressure)
        if (!send_message(sock, READY, nullptr, 0)) {
            LOG("Socket send failed (READY)");
            break;
        }

        // Wait for next message
        type = recv_message(sock, recv_body);
        if (type < 0) {
            LOG("Socket recv failed, exiting");
            break;
        }

        if (type == SHUTDOWN) {
            LOG("Received shutdown, exiting");
            break;
        }

        if (type != FRAME_DATA) {
            LOG("Unexpected msgType=%d (expected %d)", type, FRAME_DATA);
            continue;
        }

        frame_count++;

        // Parse FRAME body: [4]w [4]h [8]hintX [8]hintY [4]pixelsLen [pixelsLen]BGRA32
        if (recv_body.size() < 28) {
            LOGERR("FRAME body too short: %zu bytes (frame=%lld)", recv_body.size(), (long long)frame_count);
            continue;
        }

        int fw = (int)read_be32(recv_body.data());
        int fh = (int)read_be32(recv_body.data() + 4);
        double hint_x = read_double(recv_body.data() + 8);
        double hint_y = read_double(recv_body.data() + 16);
        uint32_t pixels_len = read_be32(recv_body.data() + 24);

        if (pixels_len == 0 || 28 + pixels_len > recv_body.size()) {
            LOGERR("Invalid pixels_len: %u (body=%zu)", pixels_len, recv_body.size());
            continue;
        }

        uint8_t* bgra_data = recv_body.data() + 28;

        // ---- Process frame ----
        try {
            SiftMatcher::MatchResult match_res;

            // Convert BGRA → grayscale for minimap detection and SIFT matching
            cv::Mat bgra_mat(fh, fw, CV_8UC4, bgra_data);
            cv::Mat gray_mat;
            cv::cvtColor(bgra_mat, gray_mat, cv::COLOR_BGRA2GRAY);
            uint8_t* gray_data = gray_mat.data;

            // 1. Minimap detection (with timing)
            auto t_minimap_start = std::chrono::steady_clock::now();
            auto detection = minimap.detect(gray_data, fw, fh);
            float t_minimap = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - t_minimap_start).count();

            if (!detection.success) {
                // Send failure quickly, minimap not found
                auto result_buf = serialize_result(false, 0, 0,
                    std::numeric_limits<double>::quiet_NaN(), t_minimap, 0, 0);
                if (!send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size())) {
                    LOG("Socket send failed (RESULT)");
                    break;
                }
                if (frame_count % 100 == 0) {
                    LOG("frames=%lld (minimap detection failures)", (long long)frame_count);
                }
                continue;
            }

            // 2. Apply circle mask to grayscale (zeros non-minimap area for SIFT)
            apply_circle_mask(gray_data, fw, fh, detection.center_x, detection.center_y, detection.radius);

            // 3. SIFT matching (uses masked grayscale)
            match_res = matcher.match(gray_data, fw, fh, hint_x, hint_y);
            match_res.t_minimap_ms = t_minimap;

            if (match_res.success) success_count++;

            // 4. Arrow direction detection (HSV 颜色过滤 + 凸包 → 最小内角 → 底边中点定向)
            auto t_arrow_start = std::chrono::steady_clock::now();
            double arrow_angle = detect_arrow_angle_hsv(bgra_data, fw, fh,
                detection.center_x, detection.center_y, detection.radius);
            float t_arrow_ms = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - t_arrow_start).count();

            // 5. Send result with timing
            auto result_buf = serialize_result(match_res.success, match_res.x, match_res.y,
                arrow_angle,
                match_res.t_minimap_ms, match_res.t_extract_ms, match_res.t_flann_ms, t_arrow_ms);
            if (!send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size())) {
                LOG("Socket send failed (RESULT)");
                break;
            }
        } catch (const cv::Exception& e) {
            LOGERR("OpenCV exception in frame %lld: %s (code=%d)",
                (long long)frame_count, e.what(), e.code);
            // 发送失败结果，不中断匹配循环
            auto result_buf = serialize_result(false, 0, 0,
                std::numeric_limits<double>::quiet_NaN());
            send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size());
        } catch (const std::exception& e) {
            LOGERR("Unexpected exception in frame %lld: %s", (long long)frame_count, e.what());
            auto result_buf = serialize_result(false, 0, 0,
                std::numeric_limits<double>::quiet_NaN());
            send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size());
        }

        // Diagnostic
        if (frame_count == 1 || frame_count % 500 == 0) {
            LOG("frames=%lld success=%lld (%.1f%%)",
                (long long)frame_count, (long long)success_count,
                frame_count > 0 ? 100.0 * success_count / frame_count : 0.0);
        }
    }

    LOG("Exiting, total frames=%lld success=%lld (%.1f%%)",
        (long long)frame_count, (long long)success_count,
        frame_count > 0 ? 100.0 * success_count / frame_count : 0.0);
    closesocket(sock);
    WSACleanup();
    return 0;
}
