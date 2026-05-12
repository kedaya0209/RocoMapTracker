// sift_match_main.cpp — Standalone SIFT matching process via TCP Socket.
// JavaCPP-free: all SIFT/FLANN/RANSAC/HoughCircles run in this process.
// Arrow direction (CNN/ONNX) is handled separately; angle always returns NaN from here.
//
// Protocol (binary TCP, big-endian):
//   HANDSHAKE:
//   220 C++→Java: REQUEST_CONFIG  {}                    — request algorithm parameters
//   221 Java→C++: CONFIG_DATA     {binary blob}         — SIFT/FLANN/RANSAC/MATCH params + paths
//   200 C++→Java: REQUEST_MAP     {}                    — cache miss, request map pixels
//   201 Java→C++: MAP_DATA        {w,h,pixelsLen,gray8} — map grayscale data
//   202 C++→Java: INIT_COMPLETE   {featureCount}        — ready for frames
//   203 C++→Java: INIT_FAILED     {errcode,msg}         — init failure
//
//   MATCHING LOOP:
//   204 C++→Java: READY           {}                    — backpressure, ready for next frame
//   205 Java→C++: FRAME_DATA      {w,h,hintX,hintY,pixelsLen,gray8}
//   206 C++→Java: MATCH_RESULT    {success,x,y,angle}
//
//   SHUTDOWN:
//   210 Java→C++: SHUTDOWN        {}
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
#include <opencv2/xfeatures2d.hpp>
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
#include <fstream>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "opencv_core4.lib")
#pragma comment(lib, "opencv_imgproc4.lib")
#pragma comment(lib, "opencv_features2d4.lib")
#pragma comment(lib, "opencv_xfeatures2d4.lib")
#pragma comment(lib, "opencv_calib3d4.lib")
#pragma comment(lib, "opencv_flann4.lib")
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
    REQUEST_MAP    = 200,  // C++ → Java (cache miss)
    MAP_DATA       = 201,  // Java → C++
    INIT_COMPLETE  = 202,  // C++ → Java
    INIT_FAILED    = 203,  // C++ → Java
    READY          = 204,  // C++ → Java (backpressure)
    FRAME_DATA     = 205,  // Java → C++
    MATCH_RESULT   = 206,  // C++ → Java
    SHUTDOWN       = 210,  // Java → C++
    REQUEST_CONFIG = 220,  // C++ → Java (new: request algorithm params)
    CONFIG_DATA    = 221,  // Java → C++ (new: serialized params)
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
// Big-endian read/write helpers
// ============================================================================
static inline void write_be32(uint8_t* buf, uint32_t v) {
    buf[0] = (uint8_t)((v >> 24) & 0xFF);
    buf[1] = (uint8_t)((v >> 16) & 0xFF);
    buf[2] = (uint8_t)((v >> 8) & 0xFF);
    buf[3] = (uint8_t)(v & 0xFF);
}
static inline uint32_t read_be32(const uint8_t* buf) {
    return ((uint32_t)buf[0] << 24) | ((uint32_t)buf[1] << 16)
         | ((uint32_t)buf[2] << 8)  |  (uint32_t)buf[3];
}

static inline void write_be64(uint8_t* buf, uint64_t v) {
    for (int i = 7; i >= 0; i--) {
        buf[7 - i] = (uint8_t)((v >> (i * 8)) & 0xFF);
    }
}
static inline uint64_t read_be64(const uint8_t* buf) {
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) {
        v = (v << 8) | buf[i];
    }
    return v;
}

static inline void write_double(uint8_t* buf, double v) {
    uint64_t u;
    memcpy(&u, &v, sizeof(u));
    write_be64(buf, u);
}
static inline double read_double(const uint8_t* buf) {
    uint64_t u = read_be64(buf);
    double v;
    memcpy(&v, &u, sizeof(v));
    return v;
}

// ============================================================================
// Socket helpers (blocking, handle partial send/recv)
// ============================================================================
static bool send_all(SOCKET sock, const void* data, size_t len) {
    const char* p = (const char*)data;
    while (len > 0) {
        int sent = send(sock, p, (int)len, 0);
        if (sent <= 0) return false;
        p += sent;
        len -= sent;
    }
    return true;
}

static bool recv_all(SOCKET sock, void* buf, size_t len) {
    char* p = (char*)buf;
    while (len > 0) {
        int rcvd = recv(sock, p, (int)len, 0);
        if (rcvd <= 0) return false;
        p += rcvd;
        len -= rcvd;
    }
    return true;
}

// Send a message: [4B msgType BE] [4B bodyLen BE] [body]
static bool send_message(SOCKET sock, MsgType type, const void* body, uint32_t body_len) {
    uint8_t header[8];
    write_be32(header, (uint32_t)type);
    write_be32(header + 4, body_len);
    if (!send_all(sock, header, 8)) return false;
    if (body_len > 0 && !send_all(sock, body, body_len)) return false;
    return true;
}

// Receive a message, returns msgType (or -1 on error), body stored in out param
static int32_t recv_message(SOCKET sock, std::vector<uint8_t>& body) {
    uint8_t header[8];
    if (!recv_all(sock, header, 8)) return -1;
    int32_t type = (int32_t)read_be32(header);
    uint32_t len = read_be32(header + 4);
    body.resize(len);
    if (len > 0 && !recv_all(sock, body.data(), len)) return -1;
    return type;
}

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
    for (int y = 0; y < h; y++) {
        int offset = y * w;
        double dy = y - center_y;
        double dy2 = dy * dy;

        if (dy2 >= r2) {
            memset(data + offset, 0, w);
            continue;
        }

        double dx_span = sqrt(r2 - dy2);
        int left = (int)ceil(center_x - dx_span);
        int right = (int)floor(center_x + dx_span);
        int safe_left = std::max(0, left);
        int safe_right = std::min(w - 1, right);

        if (safe_left > 0) {
            memset(data + offset, 0, safe_left);
        }
        if (safe_right < w - 1) {
            memset(data + offset + safe_right + 1, 0, w - safe_right - 1);
        }
    }
}

// ============================================================================
// SIFT Matcher: exact Java SiftMapMatcher parameters
// ============================================================================
class SiftMatcher {
public:
    cv::Ptr<cv::SIFT> sift;
    std::unique_ptr<DescriptorTransform> transform;
    cv::Ptr<cv::FlannBasedMatcher> flann_matcher;

    // Map keypoints
    std::vector<cv::KeyPoint> map_keypoints;
    std::vector<cv::Point2f> map_keypoint_pts;

    cv::Mat train_descriptors; // CV_32F

    // Algorithm params
    float match_ratio_threshold = 0.6f;
    int match_min_count = 10;
    int search_radius = 500;
    double ransac_reproj_threshold = 10.0;
    int ransac_max_iters = 200;
    double ransac_confidence = 0.95;

    SiftMatcher(const AlgoParams& p)
        : match_ratio_threshold((float)p.matchRatioThreshold)
        , match_min_count(p.matchMinCount)
        , search_radius(p.searchRadius)
        , ransac_reproj_threshold(p.ransacReprojThreshold)
        , ransac_max_iters(p.ransacMaxIters)
        , ransac_confidence(p.ransacConfidence)
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
        train_descriptors = transform->persistent_mat;

        if (train_descriptors.type() != CV_32F) {
            train_descriptors.convertTo(train_descriptors, CV_32F);
        }

        // Build FLANN KD-tree matcher
        flann_matcher = cv::makePtr<cv::FlannBasedMatcher>(
            cv::makePtr<cv::flann::KDTreeIndexParams>(1),
            cv::makePtr<cv::flann::SearchParams>(24, 0.0f, true)
        );
        std::vector<cv::Mat> train_vec = { train_descriptors };
        flann_matcher->add(train_vec);
        flann_matcher->train();

        LOG("SIFT trained: %zu features", map_keypoints.size());
        return true;
    }

    // Load from cache (all data already populated)
    bool load_from_cache() {
        if (train_descriptors.empty()) return false;
        if (train_descriptors.type() != CV_32F) {
            train_descriptors.convertTo(train_descriptors, CV_32F);
        }

        flann_matcher = cv::makePtr<cv::FlannBasedMatcher>(
            cv::makePtr<cv::flann::KDTreeIndexParams>(1),
            cv::makePtr<cv::flann::SearchParams>(24, 0.0f, true)
        );
        std::vector<cv::Mat> train_vec = { train_descriptors };
        flann_matcher->add(train_vec);
        flann_matcher->train();

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
    };

    // Match a frame (gray8 pixels), optionally with spatial hint
    MatchResult match(uint8_t* data, int w, int h, double hint_x, double hint_y) {
        MatchResult res{};
        if (!flann_matcher) return res;

        cv::Mat scene_img(h, w, CV_8UC1, data);

        // SIFT detect + compute on scene
        std::vector<cv::KeyPoint> scene_kps;
        cv::Mat scene_descriptors;
        sift->detectAndCompute(scene_img, cv::noArray(), scene_kps, scene_descriptors);

        if (scene_descriptors.empty()) return res;

        // Ensure CV_32F
        if (scene_descriptors.type() != CV_32F) {
            scene_descriptors.convertTo(scene_descriptors, CV_32F);
        }

        // Transform scene descriptors
        cv::Mat query_desc = transform->process(scene_descriptors);

        // FLANN knnMatch
        std::vector<std::vector<cv::DMatch>> knn_matches;
        flann_matcher->knnMatch(query_desc, knn_matches, 2);

        // Ratio test (Lowe)
        std::vector<cv::DMatch> good_matches;
        for (auto& knn : knn_matches) {
            if (knn.size() >= 2 && knn[0].distance < match_ratio_threshold * knn[1].distance) {
                good_matches.push_back(knn[0]);
            }
        }

        // Spatial filter: if hint available, exclude matches far from predicted position
        bool has_hint = !std::isnan(hint_x) && !std::isnan(hint_y)
                     && hint_x >= -1e9 && hint_y >= -1e9;
        std::vector<cv::DMatch> filtered_matches;
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
        std::vector<cv::Point2f> src_pts, dst_pts;
        for (auto& dm : filtered_matches) {
            if (dm.queryIdx >= 0 && dm.queryIdx < (int)scene_kps.size()
                && dm.trainIdx >= 0 && dm.trainIdx < (int)map_keypoint_pts.size()) {
                src_pts.push_back(scene_kps[dm.queryIdx].pt);
                dst_pts.push_back(map_keypoint_pts[dm.trainIdx]);
            }
        }

        if (src_pts.size() < (size_t)match_min_count) return res;

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
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) {
        LOGERR("Failed to create cache file: %s", path.c_str());
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

    fclose(f);
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
    matcher.train_descriptors = matcher.transform->persistent_mat;

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
// ============================================================================
static std::vector<uint8_t> serialize_result(bool success, double x, double y, double angle) {
    std::vector<uint8_t> buf(25);
    buf[0] = success ? 1 : 0;
    write_double(buf.data() + 1, x);
    write_double(buf.data() + 9, y);
    write_double(buf.data() + 17, angle);
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

        // Parse FRAME body: [4]w [4]h [8]hintX [8]hintY [4]pixelsLen [pixelsLen]gray8
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

        uint8_t* pixels = recv_body.data() + 28;

        // ---- Process frame ----
        SiftMatcher::MatchResult match_res;

        // 1. Minimap detection
        auto detection = minimap.detect(pixels, fw, fh);
        if (!detection.success) {
            // Send failure quickly, minimap not found
            auto result_buf = serialize_result(false, 0, 0,
                std::numeric_limits<double>::quiet_NaN());
            if (!send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size())) {
                LOG("Socket send failed (RESULT)");
                break;
            }
            if (frame_count % 100 == 0) {
                LOG("frames=%lld (minimap detection failures)", (long long)frame_count);
            }
            continue;
        }

        // 2. Apply circle mask
        apply_circle_mask(pixels, fw, fh, detection.center_x, detection.center_y, detection.radius);

        // 3. SIFT matching (arrow angle detached to separate process, always NaN)
        match_res = matcher.match(pixels, fw, fh, hint_x, hint_y);

        if (match_res.success) success_count++;

        // 4. Send result (angle always NaN for now)
        auto result_buf = serialize_result(match_res.success, match_res.x, match_res.y,
            std::numeric_limits<double>::quiet_NaN());
        if (!send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size())) {
            LOG("Socket send failed (RESULT)");
            break;
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
