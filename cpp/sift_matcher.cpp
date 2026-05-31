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

bool SiftMatcher::train(const uint8_t* gray_pixels, int w, int h) {
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

MatchResult SiftMatcher::match(uint8_t* data, int w, int h, double hint_x, double hint_y) {
    MatchResult res{};
    if (!f32_index && !u8_index) return res;

    cv::Mat scene_img(h, w, CV_8UC1, data);

    auto t0 = std::chrono::steady_clock::now();
    std::vector<cv::KeyPoint> scene_kps;
    cv::Mat scene_descriptors;
    sift->detectAndCompute(scene_img, cv::noArray(), scene_kps, scene_descriptors);
    auto t1 = std::chrono::steady_clock::now();
    res.t_extract_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();

    if (scene_descriptors.empty()) return res;
    if (scene_descriptors.type() != CV_32F) {
        scene_descriptors.convertTo(scene_descriptors, CV_32F);
    }

    good_matches.clear();
    if (u8_index) {
        cv::Mat query_u8 = transform->process_to_u8(scene_descriptors);
        int q_rows = query_u8.rows, q_cols = query_u8.cols;
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
        cv::Mat query_desc = transform->process(scene_descriptors);
        int q_rows = query_desc.rows, q_cols = query_desc.cols;
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

    if (good_matches.size() < (size_t)match_min_count) {
        res.t_matching_ms = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - t1).count();
        return res;
    }

    src_pts.clear();
    dst_pts.clear();
    for (auto& dm : good_matches) {
        if (dm.queryIdx >= 0 && dm.queryIdx < (int)scene_kps.size()
            && dm.trainIdx >= 0 && dm.trainIdx < (int)map_keypoint_pts.size()) {
            src_pts.push_back(scene_kps[dm.queryIdx].pt);
            dst_pts.push_back(map_keypoint_pts[dm.trainIdx]);
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
    return map_keypoints.size();
}

bool SiftMatcher::save_cache(const std::string& path) {
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

    int32_t kpCount = (int32_t)map_keypoints.size();
    fwrite(&kpCount, 4, 1, f);
    for (auto& kp : map_keypoints) {
        float x = kp.pt.x, y = kp.pt.y;
        fwrite(&x, 4, 1, f);
        fwrite(&y, 4, 1, f);
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
        LOG("Invalid cache version: %d", version);
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
    map_keypoints.clear();
    map_keypoints.reserve(kpCount);
    for (int i = 0; i < kpCount; i++) {
        float x, y;
        if (fread(&x, 4, 1, f) != 1 || fread(&y, 4, 1, f) != 1) {
            LOG("Truncated keypoint data at index %d", i);
            fclose(f); return false;
        }
        map_keypoints.push_back(cv::KeyPoint(x, y, 1.0f));
    }
    fclose(f);
    return load_from_cache();
}

bool SiftMatcher::train_direct(cv::Mat& map_gray) {
    cv::Mat raw_descriptors;
    sift->detectAndCompute(map_gray, cv::noArray(), map_keypoints, raw_descriptors);
    if (raw_descriptors.empty() || raw_descriptors.type() != CV_32F) {
        LOGERR("SIFT detection failed on map");
        return false;
    }

    map_keypoint_pts.reserve(map_keypoints.size());
    for (auto& kp : map_keypoints)
        map_keypoint_pts.push_back(kp.pt);

    if (!transform->train(raw_descriptors)) {
        LOGERR("Descriptor transform failed");
        return false;
    }
    build_flann_index();
    LOG("SIFT trained: %zu features", map_keypoints.size());
    return true;
}

bool SiftMatcher::train_tiled(cv::Mat& map_gray, int map_w, int map_h) {
    int stride = TILE_SIZE_SIFT - TILE_OVERLAP_SIFT;
    int cols = (int)std::ceil((double)(map_w - TILE_OVERLAP_SIFT) / stride);
    int rows = (int)std::ceil((double)(map_h - TILE_OVERLAP_SIFT) / stride);
    LOG("Tile layout: %dx%d (%d tiles)", cols, rows, cols * rows);

    struct KpEntry { float x, y; std::vector<float> desc; };
    std::vector<KpEntry> all_kps;
    int desc_dim = 128;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            int tile_x = c * stride, tile_y = r * stride;
            int tile_w = std::min(TILE_SIZE_SIFT, map_w - tile_x);
            int tile_h = std::min(TILE_SIZE_SIFT, map_h - tile_y);
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
    int cell_size = (int)std::ceil(DEDUP_DISTANCE);
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
                    if (existing >= 0 && std::hypot(kp.x - all_kps[existing].x, kp.y - all_kps[existing].y) < DEDUP_DISTANCE)
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

    map_keypoints.clear(); map_keypoints.reserve(keep_count);
    map_keypoint_pts.clear(); map_keypoint_pts.reserve(keep_count);
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

void SiftMatcher::build_flann_index() {
    cv::Mat& descs = transform->persistent_mat;
    if (descs.type() == CV_8U) {
        flann_data_storage = descs;
        int rows = descs.rows, cols = descs.cols;
        cvflann::Matrix<unsigned char> data(flann_data_storage.ptr<unsigned char>(0), (size_t)rows, (size_t)cols);
        auto index = std::make_unique<cvflann::KDTreeIndex<cvflann::L2<unsigned char>>>(data, cvflann::KDTreeIndexParams(1));
        index->buildIndex();
        u8_index = std::move(index);
    } else {
        flann_data_storage_32f = descs;
        int rows = descs.rows, cols = descs.cols;
        cvflann::Matrix<float> data(flann_data_storage_32f.ptr<float>(0), (size_t)rows, (size_t)cols);
        auto index = std::make_unique<cvflann::KDTreeIndex<cvflann::L2<float>>>(data, cvflann::KDTreeIndexParams(1));
        index->buildIndex();
        f32_index = std::move(index);
    }
}

bool SiftMatcher::load_from_cache() {
    build_flann_index();
    transform->persistent_mat = cv::Mat();
    map_keypoint_pts.reserve(map_keypoints.size());
    for (auto& kp : map_keypoints)
        map_keypoint_pts.push_back(kp.pt);
    LOG("SIFT loaded from cache: %zu features", map_keypoints.size());
    return true;
}
