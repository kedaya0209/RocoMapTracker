// sift_matcher.h — SIFT Matcher declaration.
#ifndef SIFT_MATCHER_H
#define SIFT_MATCHER_H

#include "match_common.h"
#include <opencv2/flann.hpp>
#include <cfloat>
#include <memory>

// ============================================================================
// SIFT variant enum (matches Java DescriptorTransform.Variant)
// ============================================================================
enum SiftVariant : int32_t {
    STANDARD   = 0,
    PCA        = 1,
    ULTRA      = 2,
    PCA_ULTRA  = 3,
};

// ============================================================================
// Descriptor Transform: PCA + 8-bit quantization
// ============================================================================
class DescriptorTransform {
public:
    SiftVariant variant;

    cv::Mat pca_eigenvectors;
    cv::Mat projected_mean;

    float q_min = 0;
    float q_scale = 1;

    cv::Mat persistent_mat;

    explicit DescriptorTransform(SiftVariant v);

    bool train(cv::Mat& raw_descriptors);
    cv::Mat process(cv::Mat& scene_descriptors);
    cv::Mat process_to_u8(cv::Mat& scene_descriptors);

    void save_cache(FILE* f);
    bool load_cache(FILE* f);

private:
    cv::Mat pca_project(cv::Mat& src);
};

// ============================================================================
// Cache file magic (SIFT-specific)
// ============================================================================
static constexpr uint32_t SIFT_CACHE_MAGIC = 0x53494654; // "SIFT"
static constexpr int32_t SIFT_CACHE_VERSION = 2;  // v2: added subImageHeights

// ============================================================================
// FeatureCache — single set of keypoints + descriptors + FLANN index
// ============================================================================
struct FeatureCache {
    std::vector<cv::KeyPoint> keypoints;
    std::vector<cv::Point2f> keypoint_pts;
    cv::Mat descriptors;
    cv::Mat flann_storage;
    std::unique_ptr<cvflann::KDTreeIndex<cvflann::L2<unsigned char>>> u8_index;
    cv::Mat flann_storage_32f;
    std::unique_ptr<cvflann::KDTreeIndex<cvflann::L2<float>>> f32_index;
    bool valid = false;

    void build_flann_index(DescriptorTransform& transform, int flannKDTreeCount);
    MatchResult match_scene(const cv::Mat& queryDesc, int flannSearchChecks);
    void clear();
};

// ============================================================================
// SiftMatcher — supports dual cache (full + cave-only)
// ============================================================================
class SiftMatcher : public MatcherBase {
public:
    cv::Ptr<cv::SIFT> sift;
    std::unique_ptr<DescriptorTransform> transform;

    FeatureCache cache_full_;
    FeatureCache cache_cave_;

    std::vector<cv::DMatch> good_matches;
    std::vector<cv::Point2f> src_pts;
    std::vector<cv::Point2f> dst_pts;

    float match_ratio_threshold = 0.6f;
    int match_min_count = 10;
    double ransac_reproj_threshold = 10.0;
    int ransac_max_iters = 200;
    double ransac_confidence = 0.95;
    int flann_search_checks = 24;

    AlgoParams params;

    int active_cache_ = -1;  // -1=none, 0=full, 1=cave

    // 暗像素比例 — 用于缓存选择（洞穴小地图背景更暗）
    static constexpr double CAVE_ENTER_RATIO = 0.15;   // 暗像素 > 15% → 判定为洞穴
    static constexpr double CAVE_EXIT_RATIO  = 0.08;   // 暗像素 < 8%  → 判定为大陆

    explicit SiftMatcher(const AlgoParams& p);

    // MatcherBase interface
    bool train(const uint8_t* gray_pixels, int w, int h) override;
    bool train_cave(const uint8_t* gray_pixels, int w, int h);  // train cave-only cache (uses existing transform)
    bool save_cave_cache(const std::string& path);              // save cave-only cache
    MatchResult match(uint8_t* data, int w, int h, double hint_x, double hint_y) override;
    size_t feature_count() const override;
    bool save_cache(const std::string& path) override;
    bool load_cache(const std::string& path) override;

    // Dual cache
    bool load_two_caches(const std::string& fullPath, const std::string& cavePath);
    bool save_two_caches(const std::string& fullPath, const std::string& cavePath);
    void select_cache_by_dark_ratio(double darkRatio);

    // Cache management (public for match_main.cpp dual-cache init)
    bool train_direct(cv::Mat& map_gray, FeatureCache& cache);

private:
    bool train_tiled(cv::Mat& map_gray, int map_w, int map_h, FeatureCache& cache);
    bool train_cave_tiled(cv::Mat& map_gray, int w, int h);
    bool finalize_cave_train(std::vector<cv::KeyPoint>& kps, cv::Mat& raw_descs);
    bool load_from_cache(FeatureCache& cache, const std::string& path);
    bool save_cache(FeatureCache& cache, const std::string& path) const;
};

#endif // SIFT_MATCHER_H
