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
static constexpr int32_t SIFT_CACHE_VERSION = 1;

// ============================================================================
// SiftMatcher
// ============================================================================
class SiftMatcher : public MatcherBase {
public:
    cv::Ptr<cv::SIFT> sift;
    std::unique_ptr<DescriptorTransform> transform;
    std::vector<cv::KeyPoint> map_keypoints;
    std::vector<cv::Point2f> map_keypoint_pts;

    cv::Mat flann_data_storage;
    std::unique_ptr<cvflann::KDTreeIndex<cvflann::L2<unsigned char>>> u8_index;
    cv::Mat flann_data_storage_32f;
    std::unique_ptr<cvflann::KDTreeIndex<cvflann::L2<float>>> f32_index;

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

    explicit SiftMatcher(const AlgoParams& p);

    // MatcherBase interface
    bool train(const uint8_t* gray_pixels, int w, int h) override;
    MatchResult match(uint8_t* data, int w, int h, double hint_x, double hint_y) override;
    size_t feature_count() const override;
    bool save_cache(const std::string& path) override;
    bool load_cache(const std::string& path) override;

private:
    bool train_direct(cv::Mat& map_gray);
    bool train_tiled(cv::Mat& map_gray, int map_w, int map_h);
    void build_flann_index();
    bool load_from_cache();
};

#endif // SIFT_MATCHER_H
