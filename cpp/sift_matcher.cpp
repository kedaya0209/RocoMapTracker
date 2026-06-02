// sift_matcher.cpp — SIFT Matcher implementation.
#include "sift_matcher.h"

// ============================================================================
// DescriptorTransform
// ============================================================================
DescriptorTransform::DescriptorTransform(SiftVariant v) : variant(v) {}

bool DescriptorTransform::train(cv::Mat& raw_descriptors) {
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

cv::Mat DescriptorTransform::process(cv::Mat& scene_descriptors) {
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
    return result;
}

cv::Mat DescriptorTransform::process_to_u8(cv::Mat& scene_descriptors) {
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

void DescriptorTransform::save_cache(FILE* f) {
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

bool DescriptorTransform::load_cache(FILE* f) {
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

cv::Mat DescriptorTransform::pca_project(cv::Mat& src) {
    cv::Mat dst;
    cv::gemm(src, pca_eigenvectors, 1.0, cv::noArray(), 0.0, dst, cv::GEMM_2_T);
    for (int i = 0; i < dst.rows; i++) {
        cv::subtract(dst.row(i), projected_mean, dst.row(i));
    }
    return dst;
}

// ============================================================================
// FeatureCache
// ============================================================================
void FeatureCache::build_flann_index(DescriptorTransform& transform, int flannKDTreeCount) {
    cv::Mat& descs = transform.persistent_mat;
    if (descs.empty()) return;

    if (descs.type() == CV_8U) {
        flann_storage = descs;
        int rows = descs.rows, cols = descs.cols;
        cvflann::Matrix<unsigned char> data(flann_storage.ptr<unsigned char>(0), (size_t)rows, (size_t)cols);
        auto index = std::make_unique<cvflann::KDTreeIndex<cvflann::L2<unsigned char>>>(
            data, cvflann::KDTreeIndexParams(flannKDTreeCount));
        index->buildIndex();
        u8_index = std::move(index);
    } else {
        flann_storage_32f = descs;
        int rows = descs.rows, cols = descs.cols;
        cvflann::Matrix<float> data(flann_storage_32f.ptr<float>(0), (size_t)rows, (size_t)cols);
        auto index = std::make_unique<cvflann::KDTreeIndex<cvflann::L2<float>>>(
            data, cvflann::KDTreeIndexParams(flannKDTreeCount));
        index->buildIndex();
        f32_index = std::move(index);
    }
    valid = true;
}

MatchResult FeatureCache::match_scene(const cv::Mat& queryDesc, int flannSearchChecks) {
    MatchResult res{};
    if (!u8_index && !f32_index) return res;
    if (queryDesc.empty()) return res;
    if (queryDesc.type() != CV_32F) return res;

    std::vector<cv::DMatch> matches;
    float ratioThreshold = 0.6f;

    if (u8_index) {
        // Already 8-bit quantized, use u8 index
        int q_rows = queryDesc.rows, q_cols = queryDesc.cols;
        for (int qi = 0; qi < q_rows; qi++) {
            cvflann::Matrix<unsigned char> qmat(
                const_cast<unsigned char*>(queryDesc.ptr<unsigned char>(qi)), 1, q_cols);
            int idx[2];
            float dists[2];
            u8_index->knnSearch(qmat, cvflann::Matrix<int>(idx, 1, 2),
                                cvflann::Matrix<float>(dists, 1, 2), 2,
                                cvflann::SearchParams(flannSearchChecks));
            if (idx[1] >= 0 && dists[0] < ratioThreshold * dists[1]) {
                matches.push_back(cv::DMatch(qi, idx[0], dists[0]));
            }
        }
    } else if (f32_index) {
        int q_rows = queryDesc.rows, q_cols = queryDesc.cols;
        for (int qi = 0; qi < q_rows; qi++) {
            cvflann::Matrix<float> qmat(
                const_cast<float*>(queryDesc.ptr<float>(qi)), 1, q_cols);
            int idx[2];
            float dists[2];
            f32_index->knnSearch(qmat, cvflann::Matrix<int>(idx, 1, 2),
                                 cvflann::Matrix<float>(dists, 1, 2), 2,
                                 cvflann::SearchParams(flannSearchChecks));
            if (idx[1] >= 0 && dists[0] < ratioThreshold * dists[1]) {
                matches.push_back(cv::DMatch(qi, idx[0], dists[0]));
            }
        }
    }

    if (matches.size() < 4) return res;

    // Build point pairs
    std::vector<cv::Point2f> src_pts, dst_pts;
    src_pts.reserve(matches.size());
    dst_pts.reserve(matches.size());
    for (auto& dm : matches) {
        if (dm.queryIdx < queryDesc.rows && dm.trainIdx < (int)keypoint_pts.size()) {
            // Extract scene keypoint from the query descriptor's original keypoint
            // We don't have the original scene keypoints here, so estimate from
            // the center of the frame (handled in the caller's match())
            src_pts.emplace_back(0, 0); // placeholder, just need dst
            dst_pts.push_back(keypoint_pts[dm.trainIdx]);
        }
    }

    // We use the cached FLANN index only for nearest-neighbor search.
    // The RANSAC + center transform is done in the caller.
    // Return only the matched trainIdx coordinates.
    return res;
}

void FeatureCache::clear() {
    keypoints.clear();
    keypoint_pts.clear();
    descriptors = cv::Mat();
    flann_storage = cv::Mat();
    u8_index.reset();
    flann_storage_32f = cv::Mat();
    f32_index.reset();
    valid = false;
}

// ============================================================================
// SiftMatcher
// ============================================================================
SiftMatcher::SiftMatcher(const AlgoParams& p)
    : match_ratio_threshold((float)p.matchRatioThreshold)
    , match_min_count(p.matchMinCount)
    , ransac_reproj_threshold(p.ransacReprojThreshold)
    , ransac_max_iters(p.ransacMaxIters)
    , ransac_confidence(p.ransacConfidence)
    , flann_search_checks(p.flannSearchChecks)
    , params(p)
{
    sift = cv::SIFT::create(
        p.nfeatures, p.nOctaveLayers,
        p.contrastThreshold, p.edgeThreshold, p.sigma, false);
    auto v = static_cast<SiftVariant>(p.siftVariant);
    transform = std::make_unique<DescriptorTransform>(v);
}

// ============================================================================
// Single cache (backward compatible)
// ============================================================================
bool SiftMatcher::train(const uint8_t* gray_pixels, int w, int h) {
    cv::Mat map_gray(h, w, CV_8UC1, (void*)gray_pixels);
    if (map_gray.empty()) {
        LOGERR("Invalid map image: %dx%d", w, h);
        return false;
    }

    int64_t total_pixels = (int64_t)w * h;
    if (total_pixels >= params.largeMapThreshold) {
        LOG("Map is large (%dx%d=%lldpx), using overlapping tiling",
            w, h, (long long)total_pixels);
        return train_tiled(map_gray, w, h, cache_full_);
    }
    return train_direct(map_gray, cache_full_);
}

bool SiftMatcher::train_cave(const uint8_t* gray_pixels, int w, int h) {
    cv::Mat map_gray(h, w, CV_8UC1, (void*)gray_pixels);
    if (map_gray.empty()) {
        LOGERR("Invalid cave map image: %dx%d", w, h);
        return false;
    }

    // Cave 地图也可能很大（5 个洞穴 = 8192x40960），同样用分块训练
    int64_t total_pixels = (int64_t)w * h;
    if (total_pixels >= params.largeMapThreshold) {
        LOG("Cave map is large (%dx%d=%lldpx), using tiled training",
            w, h, (long long)total_pixels);
        return train_cave_tiled(map_gray, w, h);
    }

    // Detect keypoints on cave image
    std::vector<cv::KeyPoint> kps;
    cv::Mat raw_descs;
    sift->detectAndCompute(map_gray, cv::noArray(), kps, raw_descs);
    if (raw_descs.empty() || raw_descs.type() != CV_32F) {
        LOGERR("SIFT detection failed on cave map");
        return false;
    }

    // Apply EXISTING transform (don't recompute from cave descriptors)
    return finalize_cave_train(kps, raw_descs);
}

/**
 * 应用已有 transform 并构建 FLANN 索引（供 train_cave / train_cave_tiled 共用）。
 */
bool SiftMatcher::finalize_cave_train(std::vector<cv::KeyPoint>& kps, cv::Mat& raw_descs) {
    cache_cave_.keypoints = std::move(kps);
    cache_cave_.keypoint_pts.reserve(cache_cave_.keypoints.size());
    for (auto& kp : cache_cave_.keypoints)
        cache_cave_.keypoint_pts.push_back(kp.pt);

    cv::Mat post_descs;
    if (transform->variant == ULTRA || transform->variant == PCA_ULTRA) {
        post_descs = transform->process_to_u8(raw_descs);
    } else if (transform->variant == PCA) {
        post_descs = transform->process(raw_descs);
    } else {
        raw_descs.convertTo(post_descs, CV_32F);
    }

    cache_cave_.descriptors = post_descs.clone();

    // Build FLANN using cave descriptors (temporarily swap persistent_mat)
    cv::Mat saved = transform->persistent_mat;
    transform->persistent_mat = post_descs;
    cache_cave_.build_flann_index(*transform, params.flannKDTreeCount);
    transform->persistent_mat = saved;  // restore full descriptors

    cache_cave_.valid = true;
    LOG("Cave-only trained: %zu features (applied existing transform)", cache_cave_.keypoints.size());
    return true;
}

bool SiftMatcher::train_cave_tiled(cv::Mat& map_gray, int w, int h) {
    int tileSize = params.tileSize > 0 ? params.tileSize : 2000;
    int tileOverlap = params.tileOverlap;
    float dedupDist = params.dedupDistance > 0 ? params.dedupDistance : 4.0f;
    int stride = tileSize - tileOverlap;
    int cols = (int)std::ceil((double)(w - tileOverlap) / stride);
    int rows = (int)std::ceil((double)(h - tileOverlap) / stride);
    LOG("Cave tile layout: %dx%d (%d tiles)", cols, rows, cols * rows);

    struct KpEntry { float x, y; std::vector<float> desc; };
    std::vector<KpEntry> all_kps;
    int desc_dim = 128;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            int tile_x = c * stride, tile_y = r * stride;
            int tile_w = std::min(tileSize, w - tile_x);
            int tile_h = std::min(tileSize, h - tile_y);
            cv::Rect roi(tile_x, tile_y, tile_w, tile_h);
            cv::Mat tile_gray = map_gray(roi);

            std::vector<cv::KeyPoint> tile_kps;
            cv::Mat tile_descs;
            sift->detectAndCompute(tile_gray, cv::noArray(), tile_kps, tile_descs);
            if (tile_descs.empty()) continue;

            cv::Mat desc_float;
            if (tile_descs.type() != CV_32F)
                tile_descs.convertTo(desc_float, CV_32F);
            else
                desc_float = tile_descs;

            desc_dim = desc_float.cols;
            for (size_t i = 0; i < tile_kps.size(); i++) {
                KpEntry entry;
                entry.x = tile_kps[i].pt.x + (float)tile_x;
                entry.y = tile_kps[i].pt.y + (float)tile_y;
                entry.desc.resize(desc_dim);
                memcpy(entry.desc.data(), desc_float.ptr<float>((int)i), desc_dim * sizeof(float));
                all_kps.push_back(std::move(entry));
            }
        }
    }

    if (all_kps.empty()) { LOGERR("No cave keypoints detected in any tile"); return false; }

    // Dedup
    int total_count = (int)all_kps.size();
    int cell_size = (int)std::ceil(dedupDist);
    int grid_cols = w / cell_size + 1, grid_rows = h / cell_size + 1;
    std::vector<int> grid(grid_cols * grid_rows, -1);
    std::vector<bool> keep(total_count, false);
    int keep_count = 0;
    for (int i = 0; i < total_count; i++) {
        auto& kp = all_kps[i];
        int cx = (int)(kp.x / cell_size), cy = (int)(kp.y / cell_size);
        bool duplicate = false;
        for (int dx = -1; dx <= 1 && !duplicate; dx++)
            for (int dy = -1; dy <= 1 && !duplicate; dy++) {
                int nx = cx + dx, ny = cy + dy;
                if (nx >= 0 && nx < grid_cols && ny >= 0 && ny < grid_rows) {
                    int existing = grid[ny * grid_cols + nx];
                    if (existing >= 0 && std::hypot(kp.x - all_kps[existing].x, kp.y - all_kps[existing].y) < dedupDist)
                        duplicate = true;
                }
            }
        if (!duplicate) { grid[cy * grid_cols + cx] = i; keep[i] = true; keep_count++; }
    }
    LOG("Cave dedup: %d → %d", total_count, keep_count);

    // 组装连续描述子矩阵（不调 transform->train，直接做后处理）
    cv::Mat all_descs(keep_count, desc_dim, CV_32F);
    int pos = 0;
    for (int i = 0; i < total_count; i++)
        if (keep[i]) memcpy(all_descs.ptr<float>(pos++), all_kps[i].desc.data(), desc_dim * sizeof(float));

    // 构建 keypoints 数组
    std::vector<cv::KeyPoint> final_kps;
    final_kps.reserve(keep_count);
    for (int i = 0; i < total_count; i++)
        if (keep[i]) final_kps.push_back(cv::KeyPoint(all_kps[i].x, all_kps[i].y, 1.0f));

    return finalize_cave_train(final_kps, all_descs);
}

bool SiftMatcher::save_cave_cache(const std::string& path) {
    return save_cache(cache_cave_, path);
}

MatchResult SiftMatcher::match(uint8_t* data, int w, int h, double hint_x, double hint_y) {
    MatchResult res{};

    // 1. 始终使用全量缓存，不再通过亮度区分地图
    FeatureCache* cache = &cache_full_;
    if (!cache || !cache->valid) {
        if (!cache_cave_.valid) return res;
        cache = &cache_cave_;
    }
    res.cache_type = (cache == &cache_cave_) ? 1 : 0;
    active_cache_ = res.cache_type;

    cv::Mat scene_img(h, w, CV_8UC1, data);

    // CLAHE contrast enhancement (must match training)
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(3.0, cv::Size(8, 8));
    cv::Mat enhanced;
    clahe->apply(scene_img, enhanced);

    auto t0 = std::chrono::steady_clock::now();
    std::vector<cv::KeyPoint> scene_kps;
    cv::Mat scene_descriptors;
    sift->detectAndCompute(enhanced, cv::noArray(), scene_kps, scene_descriptors);
    auto t1 = std::chrono::steady_clock::now();
    res.t_extract_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();

    if (scene_descriptors.empty()) return res;
    if (scene_descriptors.type() != CV_32F) {
        scene_descriptors.convertTo(scene_descriptors, CV_32F);
    }

    good_matches.clear();
    if (cache->u8_index) {
        cv::Mat query_u8 = transform->process_to_u8(scene_descriptors);
        int q_rows = query_u8.rows, q_cols = query_u8.cols;
        for (int qi = 0; qi < q_rows; qi++) {
            cvflann::Matrix<unsigned char> qmat(query_u8.ptr<unsigned char>(qi), 1, q_cols);
            int idx[2];
            float dists[2];
            cache->u8_index->knnSearch(qmat, cvflann::Matrix<int>(idx, 1, 2),
                                       cvflann::Matrix<float>(dists, 1, 2), 2,
                                       cvflann::SearchParams(flann_search_checks));
            if (idx[1] >= 0 && dists[0] < match_ratio_threshold * dists[1]) {
                good_matches.push_back(cv::DMatch(qi, idx[0], dists[0]));
            }
        }
    } else if (cache->f32_index) {
        cv::Mat query_desc = transform->process(scene_descriptors);
        int q_rows = query_desc.rows, q_cols = query_desc.cols;
        for (int qi = 0; qi < q_rows; qi++) {
            cvflann::Matrix<float> qmat(query_desc.ptr<float>(qi), 1, q_cols);
            int idx[2];
            float dists[2];
            cache->f32_index->knnSearch(qmat, cvflann::Matrix<int>(idx, 1, 2),
                                        cvflann::Matrix<float>(dists, 1, 2), 2,
                                        cvflann::SearchParams(flann_search_checks));
            if (idx[1] >= 0 && dists[0] < match_ratio_threshold * dists[1]) {
                good_matches.push_back(cv::DMatch(qi, idx[0], dists[0]));
            }
        }
    }

    if (good_matches.size() < (size_t)match_min_count) {
        res.t_matching_ms = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - t1).count();
        return res;
    }

    // 4. FLANN 后按坐标划分子图：跨洞穴误匹配过滤
    //    统计每个子图命中的匹配数，只保留主流子图的匹配做 RANSAC
    if (!params.subImageHeights.empty()) {
        std::vector<int> subCounts(params.subImageHeights.size(), 0);
        for (size_t mi = 0; mi < good_matches.size(); mi++) {
            int ti = good_matches[mi].trainIdx;
            if (ti < 0 || ti >= (int)cache->keypoint_pts.size()) continue;
            double y = cache->keypoint_pts[ti].y;
            int subIdx = -1;
            if (active_cache_ == 1) {
                // 洞穴缓存：Y 是洞穴相对坐标，跳过大陆子图
                double accum = 0;
                for (size_t j = 1; j < params.subImageHeights.size(); j++) {
                    if (y >= accum && y < accum + params.subImageHeights[j]) { subIdx = (int)j; break; }
                    accum += params.subImageHeights[j];
                }
            } else {
                // 全量缓存：Y 是复合坐标
                double accum = 0;
                for (size_t j = 0; j < params.subImageHeights.size(); j++) {
                    if (y >= accum && y < accum + params.subImageHeights[j]) { subIdx = (int)j; break; }
                    accum += params.subImageHeights[j];
                }
            }
            if (subIdx >= 0) subCounts[subIdx]++;
        }

        // 找主流子图
        int dominantSub = -1, maxCount = 0;
        for (size_t j = 0; j < subCounts.size(); j++) {
            if (subCounts[j] > maxCount) { maxCount = subCounts[j]; dominantSub = (int)j; }
        }

        if (dominantSub >= 0) {
            // 只保留主流子图的匹配
            auto it = good_matches.begin();
            while (it != good_matches.end()) {
                int ti = it->trainIdx;
                if (ti < 0 || ti >= (int)cache->keypoint_pts.size()) { ++it; continue; }
                double y = cache->keypoint_pts[ti].y;
                int subIdx = -1;
                if (active_cache_ == 1) {
                    double accum = 0;
                    for (size_t j = 1; j < params.subImageHeights.size(); j++) {
                        if (y >= accum && y < accum + params.subImageHeights[j]) { subIdx = (int)j; break; }
                        accum += params.subImageHeights[j];
                    }
                } else {
                    double accum = 0;
                    for (size_t j = 0; j < params.subImageHeights.size(); j++) {
                        if (y >= accum && y < accum + params.subImageHeights[j]) { subIdx = (int)j; break; }
                        accum += params.subImageHeights[j];
                    }
                }
                if (subIdx != dominantSub) { it = good_matches.erase(it); } else { ++it; }
            }
            int totalMatches = 0;
            for (int c : subCounts) totalMatches += c;
            LOG("子图分割: 主流子图=%d (%d/总%d匹配), 过滤后=%zu",
                dominantSub, maxCount, totalMatches, good_matches.size());
        }
    }

    src_pts.clear();
    dst_pts.clear();
    for (auto& dm : good_matches) {
        if (dm.queryIdx >= 0 && dm.queryIdx < (int)scene_kps.size()
            && dm.trainIdx >= 0 && dm.trainIdx < (int)cache->keypoint_pts.size()) {
            src_pts.push_back(scene_kps[dm.queryIdx].pt);
            dst_pts.push_back(cache->keypoint_pts[dm.trainIdx]);
        }
    }

    if (src_pts.size() < (size_t)match_min_count) {
        res.t_matching_ms = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - t1).count();
        return res;
    }

    cv::Mat inlier_mask;
    cv::Mat H = cv::findHomography(src_pts, dst_pts, cv::RANSAC,
            ransac_reproj_threshold, inlier_mask, ransac_max_iters, ransac_confidence);

    if (!H.empty() && H.rows == 3) {
        std::vector<cv::Point2f> src_center = { cv::Point2f((float)w / 2.0f, (float)h / 2.0f) };
        std::vector<cv::Point2f> dst_center;
        cv::perspectiveTransform(src_center, dst_center, H);
        res.success = true;
        res.x = dst_center[0].x;
        res.y = dst_center[0].y;

    }

    res.t_matching_ms = std::chrono::duration<float, std::milli>(
        std::chrono::steady_clock::now() - t1).count();
    return res;
}

size_t SiftMatcher::feature_count() const {
    size_t total = cache_full_.keypoints.size();
    if (cache_cave_.valid) total += cache_cave_.keypoints.size();
    return total;
}

// ============================================================================
// Dual cache
// ============================================================================
bool SiftMatcher::load_two_caches(const std::string& fullPath, const std::string& cavePath) {
    bool fullOk = load_from_cache(cache_full_, fullPath);
    if (!fullOk) {
        LOGERR("Failed to load full cache: %s", fullPath.c_str());
        return false;
    }

    bool caveOk = load_from_cache(cache_cave_, cavePath);
    if (caveOk) {
        LOG("Dual cache loaded: full=%zu cave=%zu features",
            cache_full_.keypoints.size(), cache_cave_.keypoints.size());
    } else {
        LOG("Cave cache not found (single cache mode): %s", cavePath.c_str());
    }
    active_cache_ = 0;
    return true;
}

bool SiftMatcher::save_two_caches(const std::string& fullPath, const std::string& cavePath) {
    if (!save_cache(cache_full_, fullPath)) return false;
    if (cache_cave_.valid && !save_cache(cache_cave_, cavePath)) return false;
    return true;
}

void SiftMatcher::select_cache_by_dark_ratio(double darkRatio) {
    if (!cache_cave_.valid && !cache_full_.valid) return;

    LOG("darkRatio=%.3f active_cache=%d cave_valid=%d full_valid=%d",
        darkRatio, active_cache_, (int)cache_cave_.valid, (int)cache_full_.valid);

    if (active_cache_ == 1) {
        // 当前在洞穴缓存：暗像素比例低于退出阈值才切回大陆
        if (darkRatio < CAVE_EXIT_RATIO && cache_full_.valid) {
            LOG("缓存切换: cave → full (darkRatio=%.3f)", darkRatio);
            active_cache_ = 0;
        }
    } else {
        // 当前在大陆缓存（含初始状态 -1）：暗像素比例高于进入阈值才切到洞穴
        if (darkRatio > CAVE_ENTER_RATIO && cache_cave_.valid) {
            LOG("缓存切换: full → cave (darkRatio=%.3f)", darkRatio);
            active_cache_ = 1;
        } else if (active_cache_ < 0) {
            // 初始状态，默认用 full cache
            active_cache_ = cache_full_.valid ? 0 : (cache_cave_.valid ? 1 : -1);
        }
    }
}

// ============================================================================
// Single cache save/load (backward compatible)
// ============================================================================
bool SiftMatcher::save_cache(const std::string& path) {
    return save_cache(cache_full_, path);
}

bool SiftMatcher::save_cache(FeatureCache& cache, const std::string& path) const {
    std::string tmpPath = path + ".tmp";
    FILE* f = fopen(tmpPath.c_str(), "wb");
    if (!f) {
        LOGERR("Failed to create cache temp file: %s", tmpPath.c_str());
        return false;
    }

    uint32_t magic = SIFT_CACHE_MAGIC;
    int32_t version = SIFT_CACHE_VERSION;
    int32_t variant = (int32_t)transform->variant;

    fwrite(&magic, 4, 1, f);
    fwrite(&version, 4, 1, f);
    fwrite(&variant, 4, 1, f);

    transform->save_cache(f);

    int32_t kpCount = (int32_t)cache.keypoints.size();
    fwrite(&kpCount, 4, 1, f);
    for (auto& kp : cache.keypoints) {
        float x = kp.pt.x, y = kp.pt.y;
        fwrite(&x, 4, 1, f);
        fwrite(&y, 4, 1, f);
    }

    // Save subImageHeights (for cave cache Y offset and consistency check)
    int32_t subCount = (int32_t)params.subImageHeights.size();
    fwrite(&subCount, 4, 1, f);
    for (int h : params.subImageHeights) {
        int32_t height = (int32_t)h;
        fwrite(&height, 4, 1, f);
    }

    if (fclose(f) != 0) {
        LOGERR("Failed to close cache temp file: %s", tmpPath.c_str());
        DeleteFileA(tmpPath.c_str());
        return false;
    }

    DeleteFileA(path.c_str());
    if (rename(tmpPath.c_str(), path.c_str()) != 0) {
        LOGERR("Failed to rename cache file: %s -> %s", tmpPath.c_str(), path.c_str());
        DeleteFileA(tmpPath.c_str());
        return false;
    }

    LOG("Cache saved: %s (%d features)", path.c_str(), kpCount);
    return true;
}

bool SiftMatcher::load_cache(const std::string& path) {
    return load_from_cache(cache_full_, path);
}

bool SiftMatcher::load_from_cache(FeatureCache& cache, const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) {
        LOG("No cache file at: %s", path.c_str());
        return false;
    }

    uint32_t magic;
    int32_t version, variant;
    if (fread(&magic, 4, 1, f) != 1 || magic != SIFT_CACHE_MAGIC) {
        LOG("Invalid cache magic");
        fclose(f); return false;
    }
    if (fread(&version, 4, 1, f) != 1 || version != SIFT_CACHE_VERSION) {
        LOG("Invalid cache version: %d (expected %d)", version, SIFT_CACHE_VERSION);
        fclose(f); return false;
    }
    if (fread(&variant, 4, 1, f) != 1 || variant != (int32_t)transform->variant) {
        LOG("Cache variant mismatch: %d vs %d", variant, (int32_t)transform->variant);
        fclose(f); return false;
    }

    if (!transform->load_cache(f)) {
        LOG("Failed to load DescriptorTransform from cache");
        fclose(f); return false;
    }

    int32_t kpCount;
    if (fread(&kpCount, 4, 1, f) != 1 || kpCount <= 0) {
        LOG("Invalid keypoint count in cache");
        fclose(f); return false;
    }
    cache.clear();
    cache.keypoints.reserve(kpCount);
    for (int i = 0; i < kpCount; i++) {
        float x, y;
        if (fread(&x, 4, 1, f) != 1 || fread(&y, 4, 1, f) != 1) {
            LOG("Truncated keypoint data at index %d", i);
            fclose(f); return false;
        }
        cache.keypoints.push_back(cv::KeyPoint(x, y, 1.0f));
    }

    // Read subImageHeights (v2+)
    if (version >= 2) {
        int32_t subCount;
        if (fread(&subCount, 4, 1, f) == 1 && subCount > 0 && subCount <= 20) {
            params.subImageHeights.resize(subCount);
            bool subOk = true;
            for (int i = 0; i < subCount; i++) {
                int32_t h;
                if (fread(&h, 4, 1, f) != 1) { subOk = false; break; }
                params.subImageHeights[i] = (int)h;
            }
            if (!subOk) params.subImageHeights.clear();
        }
    }
    fclose(f);

    // Build FLANN index and keypoint_pts for this cache
    cache.descriptors = transform->persistent_mat.clone();
    cache.keypoint_pts.reserve(cache.keypoints.size());
    for (auto& kp : cache.keypoints) {
        cache.keypoint_pts.push_back(kp.pt);
    }
    cache.build_flann_index(*transform, params.flannKDTreeCount);
    cache.valid = true;

    LOG("Cache loaded: %s (%d features, subImageHeights=%zu)", path.c_str(), kpCount, params.subImageHeights.size());
    return true;
}

// ============================================================================
// Training methods (refactored to use FeatureCache)
// ============================================================================
bool SiftMatcher::train_direct(cv::Mat& map_gray, FeatureCache& cache) {
    cv::Mat raw_descriptors;
    cache.keypoints.clear();
    sift->detectAndCompute(map_gray, cv::noArray(), cache.keypoints, raw_descriptors);
    if (raw_descriptors.empty() || raw_descriptors.type() != CV_32F) {
        LOGERR("SIFT detection failed on map");
        return false;
    }

    cache.keypoint_pts.reserve(cache.keypoints.size());
    for (auto& kp : cache.keypoints)
        cache.keypoint_pts.push_back(kp.pt);

    if (!transform->train(raw_descriptors)) {
        LOGERR("Descriptor transform failed");
        return false;
    }
    cache.build_flann_index(*transform, params.flannKDTreeCount);
    cache.valid = true;
    LOG("SIFT trained: %zu features", cache.keypoints.size());
    return true;
}

bool SiftMatcher::train_tiled(cv::Mat& map_gray, int map_w, int map_h, FeatureCache& cache) {
    int tileSize = params.tileSize > 0 ? params.tileSize : 2000;
    int tileOverlap = params.tileOverlap;
    float dedupDist = params.dedupDistance > 0 ? params.dedupDistance : 4.0f;
    int stride = tileSize - tileOverlap;
    int cols = (int)std::ceil((double)(map_w - tileOverlap) / stride);
    int rows = (int)std::ceil((double)(map_h - tileOverlap) / stride);
    LOG("Tile layout: %dx%d (%d tiles)", cols, rows, cols * rows);

    struct KpEntry { float x, y; std::vector<float> desc; };
    std::vector<KpEntry> all_kps;
    int desc_dim = 128;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            int tile_x = c * stride, tile_y = r * stride;
            int tile_w = std::min(tileSize, map_w - tile_x);
            int tile_h = std::min(tileSize, map_h - tile_y);
            cv::Rect roi(tile_x, tile_y, tile_w, tile_h);
            cv::Mat tile_gray = map_gray(roi);

            std::vector<cv::KeyPoint> tile_kps;
            cv::Mat tile_descs;
            sift->detectAndCompute(tile_gray, cv::noArray(), tile_kps, tile_descs);
            if (tile_descs.empty()) continue;

            cv::Mat desc_float;
            if (tile_descs.type() != CV_32F)
                tile_descs.convertTo(desc_float, CV_32F);
            else
                desc_float = tile_descs;

            desc_dim = desc_float.cols;
            for (size_t i = 0; i < tile_kps.size(); i++) {
                KpEntry entry;
                entry.x = tile_kps[i].pt.x + (float)tile_x;
                entry.y = tile_kps[i].pt.y + (float)tile_y;
                entry.desc.resize(desc_dim);
                memcpy(entry.desc.data(), desc_float.ptr<float>((int)i), desc_dim * sizeof(float));
                all_kps.push_back(std::move(entry));
            }
        }
    }

    if (all_kps.empty()) { LOGERR("No keypoints detected in any tile"); return false; }

    int total_count = (int)all_kps.size();
    int cell_size = (int)std::ceil(dedupDist);
    int grid_cols = map_w / cell_size + 1, grid_rows = map_h / cell_size + 1;
    std::vector<int> grid(grid_cols * grid_rows, -1);
    std::vector<bool> keep(total_count, false);
    int keep_count = 0;

    for (int i = 0; i < total_count; i++) {
        auto& kp = all_kps[i];
        int cx = (int)(kp.x / cell_size), cy = (int)(kp.y / cell_size);
        bool duplicate = false;
        for (int dx = -1; dx <= 1 && !duplicate; dx++)
            for (int dy = -1; dy <= 1 && !duplicate; dy++) {
                int nx = cx + dx, ny = cy + dy;
                if (nx >= 0 && nx < grid_cols && ny >= 0 && ny < grid_rows) {
                    int existing = grid[ny * grid_cols + nx];
                    if (existing >= 0 && std::hypot(kp.x - all_kps[existing].x, kp.y - all_kps[existing].y) < dedupDist)
                        duplicate = true;
                }
            }
        if (!duplicate) { grid[cy * grid_cols + cx] = i; keep[i] = true; keep_count++; }
    }

    LOG("Dedup: %d → %d", total_count, keep_count);

    cv::Mat all_descs(keep_count, desc_dim, CV_32F);
    int pos = 0;
    for (int i = 0; i < total_count; i++)
        if (keep[i]) memcpy(all_descs.ptr<float>(pos++), all_kps[i].desc.data(), desc_dim * sizeof(float));

    if (!transform->train(all_descs)) { LOGERR("Descriptor transform failed"); return false; }

    cache.keypoints.clear(); cache.keypoints.reserve(keep_count);
    cache.keypoint_pts.clear(); cache.keypoint_pts.reserve(keep_count);
    for (int i = 0; i < total_count; i++) {
        if (keep[i]) {
            auto& kp = all_kps[i];
            cache.keypoints.push_back(cv::KeyPoint(kp.x, kp.y, 1.0f));
            cache.keypoint_pts.emplace_back(kp.x, kp.y);
        }
    }

    cache.build_flann_index(*transform, params.flannKDTreeCount);
    cache.valid = true;
    LOG("SIFT trained (tiled): %zu features", cache.keypoints.size());
    return true;
}
