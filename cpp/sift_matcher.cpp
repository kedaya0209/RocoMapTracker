// sift_matcher.cpp — SIFT Matcher implementation.
#include <algorithm>
#include "sift_matcher.h"

// ============================================================================
// 分块 SIFT 检测 + 去重（用于大子图）
// ============================================================================
static void detect_tiled(
    cv::Ptr<cv::SIFT>& sift_instance,
    const cv::Mat& img, int img_w, int img_h,
    int tile_size, int tile_overlap, float dedup_dist,
    std::vector<cv::KeyPoint>& out_kps, cv::Mat& out_descs) {
    int stride = tile_size - tile_overlap;
    int cols = (int)std::ceil((double)(img_w - tile_overlap) / stride);
    int rows = (int)std::ceil((double)(img_h - tile_overlap) / stride);

    struct KpEntry {
        float x, y;
        std::vector<float> desc;
    };
    std::vector<KpEntry> all_kps;
    int desc_dim = 128;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            int tile_x = c * stride;
            int tile_y = r * stride;
            int tile_w = std::min(tile_size, img_w - tile_x);
            int tile_h = std::min(tile_size, img_h - tile_y);
            cv::Rect roi(tile_x, tile_y, tile_w, tile_h);
            cv::Mat tile_gray = img(roi);
            std::vector<cv::KeyPoint> tile_kps;
            cv::Mat tile_descs;
            sift_instance->detectAndCompute(tile_gray, cv::noArray(), tile_kps, tile_descs);
            if (tile_descs.empty()) continue;
            cv::Mat desc_float;
            if (tile_descs.type() != CV_32F)
                tile_descs.convertTo(desc_float, CV_32F);
            else
                desc_float = tile_descs;
            desc_dim = desc_float.cols;
            for (size_t i = 0; i < tile_kps.size(); i++) {
                KpEntry e;
                e.x = tile_kps[i].pt.x + (float)tile_x;
                e.y = tile_kps[i].pt.y + (float)tile_y;
                e.desc.resize(desc_dim);
                memcpy(e.desc.data(), desc_float.ptr<float>((int)i), desc_dim * sizeof(float));
                all_kps.push_back(std::move(e));
            }
        }
    }

    if (all_kps.empty()) return;

    // 去重
    int total = (int)all_kps.size();
    int cell = (int)std::ceil(dedup_dist);
    int gc = img_w / cell + 1;
    int gr = img_h / cell + 1;
    std::vector<int> grid(gc * gr, -1);
    std::vector<bool> keep(total, false);
    int kept = 0;

    for (int i = 0; i < total; i++) {
        auto& kp = all_kps[i];
        int cx = (int)(kp.x / cell), cy = (int)(kp.y / cell);
        bool dup = false;
        for (int dx = -1; dx <= 1 && !dup; dx++)
            for (int dy = -1; dy <= 1 && !dup; dy++) {
                int nx = cx + dx, ny = cy + dy;
                if (nx >= 0 && nx < gc && ny >= 0 && ny < gr) {
                    int ex = grid[ny * gc + nx];
                    if (ex >= 0) {
                        float dx = kp.x - all_kps[ex].x, dy = kp.y - all_kps[ex].y;
                        if (dx * dx + dy * dy < dedup_dist * dedup_dist)
                            dup = true;
                    }
                }
            }
        if (!dup) { grid[cy * gc + cx] = i; keep[i] = true; kept++; }
    }

    out_kps.clear();
    out_kps.reserve(kept);
    out_descs = cv::Mat(kept, desc_dim, CV_32F);
    int pos = 0;
    for (int i = 0; i < total; i++) {
        if (keep[i]) {
            out_kps.push_back(cv::KeyPoint(all_kps[i].x, all_kps[i].y, 1.0f));
            memcpy(out_descs.ptr<float>(pos), all_kps[i].desc.data(), desc_dim * sizeof(float));
            pos++;
        }
    }
}

// ============================================================================
// Auto-crop grayscale image to non-void content bounding box.
// Scans rows/columns at stride 4, counts pixels > threshold.
// Returns the content rect in image coordinates. If no content found,
// returns the full image rect.
// ============================================================================
static cv::Rect find_content_rect(const cv::Mat& gray, uint8_t threshold = 16, int stride = 4) {
    int w = gray.cols, h = gray.rows;
    if (w <= 0 || h <= 0) return cv::Rect(0, 0, w, h);

    auto row_has_content = [&](int y) -> bool {
        const uint8_t* r = gray.ptr<uint8_t>(y);
        for (int x = 0; x < w; x += stride)
            if (r[x] > threshold) return true;
        return false;
    };
    auto col_has_content = [&](int x) -> bool {
        for (int y = 0; y < h; y += stride)
            if (gray.at<uint8_t>(y, x) > threshold) return true;
        return false;
    };

    int top = 0;
    while (top < h && !row_has_content(top)) top++;
    if (top >= h) return cv::Rect(0, 0, w, h); // fully void

    int bottom = h - 1;
    while (bottom > top && !row_has_content(bottom)) bottom--;

    int left = 0;
    while (left < w && !col_has_content(left)) left++;

    int right = w - 1;
    while (right > left && !col_has_content(right)) right--;

    return cv::Rect(left, top, right - left + 1, bottom - top + 1);
}

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
    , search_radius(p.searchRadius)
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
    // Plan B: multi-subimage unified index
    if (!params.subImageHeights.empty()) {
        return train_multimap(gray_pixels, w, h);
    }

    cv::Mat map_gray(h, w, CV_8UC1, (void*)gray_pixels);
    if (map_gray.empty()) {
        LOGERR("Invalid map image: %dx%d", w, h);
        return false;
    }

    // Plan A: 大图采用重叠分块训练（从 master 移植）
    int64_t total_pixels = (int64_t)w * h;
    if (total_pixels >= params.largeMapThreshold) {
        return train_tiled(map_gray, w, h);
    }

    return train_direct(map_gray);
}

MatchResult SiftMatcher::match(uint8_t* data, int w, int h, double hint_x, double hint_y) {
    MatchResult res{};
    if (!flann_index) return res;

    cv::Mat scene_img(h, w, CV_8UC1, data);

    // 洞穴匹配：CLAHE 增强暗区纹理
    cv::Mat match_img = scene_img;
    if (sub_image_group == 1) {
        if (!clahe) clahe = cv::createCLAHE(3.0, cv::Size(8, 8));
        cv::Mat enhanced;
        clahe->apply(scene_img, enhanced);
        match_img = enhanced;
    }

    auto t0 = std::chrono::steady_clock::now();
    scene_kps.clear();
    cv::Mat scene_descriptors;
    sift->detectAndCompute(match_img, cv::noArray(), scene_kps, scene_descriptors);
    auto t1 = std::chrono::steady_clock::now();
    res.t_extract_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();

    if (scene_descriptors.empty()) return res;

    // PCA 投影 + 量化（必须与训练时的变换一致）
    cv::Mat query_desc;
    if (transform->variant == ULTRA || transform->variant == PCA_ULTRA) {
        cv::Mat q_u8 = transform->process_to_u8(scene_descriptors);
        q_u8.convertTo(query_desc, CV_32F);
    } else {
        query_desc = transform->process(scene_descriptors);
    }

    // knnSearch(k=2) + 比率测试（统一处理单图和子图）
    cv::Mat indices(query_desc.rows, 2, CV_32SC1);
    cv::Mat dists(query_desc.rows, 2, CV_32FC1);
    flann_index->knnSearch(query_desc, indices, dists, 2,
        cv::flann::SearchParams(flann_search_checks, 0.0f, true));

    good_matches.clear();
    for (int qi = 0; qi < query_desc.rows; qi++) {
        int* idx = indices.ptr<int>(qi);
        float* d = dists.ptr<float>(qi);
        if (idx[1] >= 0) {
            float d0 = std::sqrt(d[0]);
            float d1 = std::sqrt(d[1]);
            if (d0 < match_ratio_threshold * d1) {
                good_matches.push_back(cv::DMatch(qi, idx[0], d0));
            }
        }
    }

    if (good_matches.size() < (size_t)match_min_count) {
        res.t_matching_ms = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - t1).count();
        return res;
    }

    // Spatial filter: 利用 hint 位置过滤远处误匹配
    bool has_hint = !std::isnan(hint_x) && !std::isnan(hint_y)
                 && hint_x >= -1e9 && hint_y >= -1e9
                 && hint_x != -1.0 && hint_y != -1.0; // -1 = Java 无效 sentinel
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

    if (filtered_matches.size() < (size_t)match_min_count) {
        res.t_matching_ms = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - t1).count();
        return res;
    }

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

        // 空间一致性校验：匹配结果与 hint 距离远超搜索半径则拒绝。
        if (has_hint) {
            double dist = std::hypot(res.x - hint_x, res.y - hint_y);
            int inlier_cnt = inlier_mask.empty() ? 0 : cv::countNonZero(inlier_mask);
            if (dist > search_radius * 2 && inlier_cnt < 8) {
                LOG("  spatial reject: (%.1f,%.1f) too far from hint (%.1f,%.1f), dist=%.1f inliers=%d/%zu",
                    res.x, res.y, hint_x, hint_y, dist, inlier_cnt, src_pts.size());
                res.success = false;
            }
        }

        // 从完整图坐标 y 值确定子图 ID（多子图模式）
        if (res.success && !params.subImageHeights.empty()) {
            res.map_id = resolve_map_id((float)res.y);
            // 将完整图坐标转回局部坐标（Java 端会加 offsetY）
            int y_accum = 0;
            for (int i = 0; i < res.map_id && i < (int)params.subImageHeights.size(); i++) {
                y_accum += std::max(0, params.subImageHeights[i]);
            }
            res.y -= y_accum;
        }
    }

    res.t_matching_ms = std::chrono::duration<float, std::milli>(
        std::chrono::steady_clock::now() - t1).count();
    return res;
}

int SiftMatcher::resolve_map_id(float y) const {
    if (params.subImageHeights.empty()) return 0;
    int y_accum = 0;
    for (int i = 0; i < (int)params.subImageHeights.size(); i++) {
        y_accum += std::max(0, params.subImageHeights[i]);
        if (y < y_accum) return i;
    }
    return (int)params.subImageHeights.size() - 1;
}

void SiftMatcher::setSubImageGroup(int group) {
    sub_image_group = group;
    // Matching 侧始终使用 AlgoParams 全局参数（ct=0.001, sigma=1.6），
    // 训练侧 per-sub-image 覆盖只在 train_multimap 中生效。
    // 不在此处覆盖 sift 参数，否则低 ct 导致过多弱特征（慢 + 抖动）。
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

    uint32_t config_hash = compute_config_hash();
    fwrite(&config_hash, 4, 1, f);

    fwrite(&variant, 4, 1, f);

    transform->save_cache(f);

    int32_t kpCount = (int32_t)map_keypoints.size();
    fwrite(&kpCount, 4, 1, f);
    for (auto& kp : map_keypoints) {
        float x = kp.pt.x, y = kp.pt.y;
        fwrite(&x, 4, 1, f);
        fwrite(&y, 4, 1, f);
    }

    // v3: map_id_per_feature array
    int32_t mapIdCount = (int32_t)map_id_for_feature.size();
    fwrite(&mapIdCount, 4, 1, f);
    if (mapIdCount > 0) {
        fwrite(map_id_for_feature.data(), 4, mapIdCount, f);
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
    // config hash validation
    uint32_t cached_hash = 0;
    if (fread(&cached_hash, 4, 1, f) != 1) {
        LOG("Truncated cache: missing config hash");
        fclose(f); return false;
    }
    uint32_t current_hash = compute_config_hash();
    if (cached_hash != current_hash) {
        LOG("Config hash mismatch: cached=0x%08x current=0x%08x, retraining", cached_hash, current_hash);
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

    // v3: read map_id_per_feature array (absent in v2 caches, which get version-bumped and retrained)
    map_id_for_feature.clear();
    if (version >= 3) {
        int32_t mapIdCount;
        if (fread(&mapIdCount, 4, 1, f) != 1) {
            LOG("Truncated map_id count");
            fclose(f); return false;
        }
        if (mapIdCount > 0) {
            if (mapIdCount != kpCount) {
                LOG("map_id count mismatch: %d vs %d", mapIdCount, kpCount);
                fclose(f); return false;
            }
            map_id_for_feature.resize(mapIdCount);
            if (fread(map_id_for_feature.data(), 4, mapIdCount, f) != (size_t)mapIdCount) {
                LOG("Truncated map_id data");
                fclose(f); return false;
            }
        }
    }
    fclose(f);
    return load_from_cache();
}

uint32_t SiftMatcher::compute_config_hash() const {
    uint8_t buf[128];
    size_t off = 0;

    auto w4 = [&](int32_t v) { memcpy(buf + off, &v, 4); off += 4; };
    auto w8 = [&](double v) { memcpy(buf + off, &v, 8); off += 8; };
    auto wf = [&](float v)  { memcpy(buf + off, &v, 4); off += 4; };

    w4(params.siftVariant);
    w4(params.nfeatures);
    w4(params.nOctaveLayers);
    w8(params.contrastThreshold);
    w8(params.edgeThreshold);
    w8(params.sigma);
    w8(params.matchRatioThreshold);
    w4(params.matchMinCount);
    w4(params.searchRadius);
    w4(params.flannKDTreeCount);
    w4(params.flannSearchChecks);
    w8(params.ransacReprojThreshold);
    w4(params.ransacMaxIters);
    w8(params.ransacConfidence);
    w4(params.tileSize);
    w4(params.tileOverlap);
    w8((double)params.largeMapThreshold);
    wf(params.dedupDistance);

    return crc32(0, buf, (uInt)off);
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

// ----- 大图重叠分块训练 (>9Mpx) — 从 master 移植 -----
bool SiftMatcher::train_tiled(cv::Mat& map_gray, int map_w, int map_h) {
    int stride = params.tileSize - params.tileOverlap;
    int cols = (int)std::ceil((double)(map_w - params.tileOverlap) / stride);
    int rows = (int)std::ceil((double)(map_h - params.tileOverlap) / stride);
    LOG("Tiled training: %dx%d map, %dx%d tiles (%d total)",
        map_w, map_h, cols, rows, cols * rows);

    struct KpEntry {
        float x, y;
        std::vector<float> desc;
    };
    std::vector<KpEntry> all_kps;
    int desc_dim = 128;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            int tile_x = c * stride;
            int tile_y = r * stride;
            int tile_w = std::min(params.tileSize, map_w - tile_x);
            int tile_h = std::min(params.tileSize, map_h - tile_y);

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

    // 重叠区域去重（空间网格索引 O(1)）
    int total_count = (int)all_kps.size();
    int cell_size = (int)std::ceil(params.dedupDistance);
    int grid_cols = map_w / cell_size + 1;
    int grid_rows = map_h / cell_size + 1;
    std::vector<int> grid(grid_cols * grid_rows, -1);
    std::vector<bool> keep(total_count, false);
    int keep_count = 0;

    for (int i = 0; i < total_count; i++) {
        auto& kp = all_kps[i];
        int cx = (int)(kp.x / cell_size);
        int cy = (int)(kp.y / cell_size);
        bool duplicate = false;
        for (int dx = -1; dx <= 1 && !duplicate; dx++) {
            for (int dy = -1; dy <= 1 && !duplicate; dy++) {
                int nx = cx + dx, ny = cy + dy;
                if (nx >= 0 && nx < grid_cols && ny >= 0 && ny < grid_rows) {
                    int existing = grid[ny * grid_cols + nx];
                    if (existing >= 0) {
                        auto& ekp = all_kps[existing];
                        float dx = kp.x - ekp.x, dy = kp.y - ekp.y;
                        if (dx * dx + dy * dy < params.dedupDistance * params.dedupDistance)
                            duplicate = true;
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
    LOG("Dedup: %d -> %d (removed %d overlapping duplicates)",
        total_count, keep_count, total_count - keep_count);

    // 构建合并描述符矩阵
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

bool SiftMatcher::train_multimap(const uint8_t* gray_pixels, int w, int h) {
    auto& sub_h = params.subImageHeights;
    int sub_count = (int)sub_h.size();
    LOG("Multi-map training: %d sub-images, total %dx%d", sub_count, w, h);

    // Pre-compute y offsets for each sub-image
    std::vector<int> y_offsets(sub_count, 0);
    for (int si = 1; si < sub_count; si++) {
        y_offsets[si] = y_offsets[si - 1] + std::max(0, sub_h[si - 1]);
    }

    // Per-sub-image results
    struct SubResult {
        std::vector<cv::KeyPoint> kps;
        cv::Mat descs;
        int map_id = 0;
    };
    std::vector<SubResult> sub_results(sub_count);
    int total_kp = 0;
    // 裁剪偏移（每个子图不同），用于后续坐标修正
    std::vector<cv::Point> crop_offsets(sub_count, cv::Point(0, 0));

    // Serial training — each sub-image with its own SIFT params (if overridden)
    // Build a lookup map: subImageIndex → SubImageSiftParams
    std::unordered_map<int, const SubImageSiftParams*> override_map;
    for (auto& sp : params.subImageSiftParams) {
        override_map[sp.subImageIndex] = &sp;
    }

    LOG("  group_filter=%d (0=overworld, 1=caves, -1=all)", sub_image_group);

    for (int si = 0; si < sub_count; si++) {
        int sh = sub_h[si];
        if (sh <= 0) { LOG("Sub-image %d: zero height, skipping", si); continue; }

        // Group filter: 0=overworld(only sub 0), 1=caves(sub 1+), -1=all
        if (sub_image_group == 0 && si > 0) continue;
        if (sub_image_group == 1 && si == 0) continue;

        // Resolve SIFT params for this sub-image (override or default)
        cv::Ptr<cv::SIFT> sub_sift;
        auto it = override_map.find(si);
        if (it != override_map.end()) {
            auto* sp = it->second;
            int nf = (sp->nfeatures >= 0) ? sp->nfeatures : params.nfeatures;
            int nol = (sp->nOctaveLayers >= 0) ? sp->nOctaveLayers : params.nOctaveLayers;
            double ct = (sp->contrastThreshold > 0.0) ? sp->contrastThreshold : params.contrastThreshold;
            double et = (sp->edgeThreshold > 0.0) ? sp->edgeThreshold : params.edgeThreshold;
            double sg = (sp->sigma > 0.0) ? sp->sigma : params.sigma;
            sub_sift = cv::SIFT::create(nf, nol, ct, et, sg, false);
        } else {
            sub_sift = cv::SIFT::create(
                params.nfeatures, params.nOctaveLayers,
                params.contrastThreshold, params.edgeThreshold, params.sigma, false);
        }

        cv::Mat sub_gray(sh, w, CV_8UC1, (void*)(gray_pixels + y_offsets[si] * w));

        // 自动裁剪空白边缘，只对有效内容区域做 SIFT 检测
        cv::Rect crop = find_content_rect(sub_gray, 16, 4);
        if (crop.width > 0 && crop.height > 0 && crop.area() < (int64_t)w * sh) {
            crop_offsets[si] = cv::Point(crop.x, crop.y);
            sub_gray = sub_gray(crop);
        }

        // 洞穴子图：CLAHE 增强对比度，提升暗区纹理可见度
        bool is_cave = (sub_image_group == 1) || (sub_image_group == -1 && si > 0);
        if (is_cave) {
            cv::Ptr<cv::CLAHE> clahe_train = cv::createCLAHE(3.0, cv::Size(8, 8));
            cv::Mat enhanced;
            clahe_train->apply(sub_gray, enhanced);
            sub_gray = enhanced;
        }

        // 大子图使用分块检测（同步 master 行为）
        if ((int64_t)sub_gray.cols * sub_gray.rows >= params.largeMapThreshold) {
            detect_tiled(sub_sift, sub_gray, sub_gray.cols, sub_gray.rows,
                params.tileSize, params.tileOverlap, params.dedupDistance,
                sub_results[si].kps, sub_results[si].descs);
            LOG("  sub[%d] -> %zu kp (tiled)", si, sub_results[si].kps.size());
        } else {
            sub_sift->detectAndCompute(sub_gray, cv::noArray(),
                sub_results[si].kps, sub_results[si].descs);
        }
        sub_results[si].map_id = si;
        total_kp += (int)sub_results[si].kps.size();
    }

    // Collect results from all sub-images
    std::vector<cv::Mat> all_raw_descs;
    std::vector<cv::KeyPoint> all_kps;
    map_id_for_feature.clear();
    int desc_dim = 128;

    for (int si = 0; si < sub_count; si++) {
        auto& r = sub_results[si];
        if (r.descs.empty()) continue;
        cv::Mat desc_float;
        if (r.descs.type() != CV_32F) {
            r.descs.convertTo(desc_float, CV_32F);
        } else {
            desc_float = r.descs;
        }
        desc_dim = desc_float.cols;
        all_raw_descs.push_back(desc_float);
        for (auto& kp : r.kps) {
            cv::KeyPoint kp_full = kp;
            kp_full.pt.x += crop_offsets[si].x;  // 裁剪偏移 → 子图坐标
            kp_full.pt.y += crop_offsets[si].y;
            kp_full.pt.y += y_offsets[si];        // 子图坐标 → 完整图坐标
            all_kps.push_back(kp_full);
            map_id_for_feature.push_back(r.map_id);
        }
    }

    if (all_kps.empty()) {
        LOGERR("No keypoints detected in any sub-image");
        return false;
    }
    LOG("Total keypoints across all sub-images: %zu", all_kps.size());

    // Merge descriptors
    cv::Mat merged_descs;
    if (all_raw_descs.size() == 1) {
        merged_descs = all_raw_descs[0];
    } else {
        cv::vconcat(all_raw_descs, merged_descs);
    }
    LOG("Merged descriptors: %d x %d", merged_descs.rows, merged_descs.cols);

    // Unified PCA + quantization (shared across all sub-images in this group)
    if (!transform->train(merged_descs)) {
        LOGERR("Descriptor transform failed");
        return false;
    }

    // ---- Unified index (all sub-images in full coordinates) ----
    map_keypoints = std::move(all_kps);
    map_keypoint_pts.clear();
    map_keypoint_pts.reserve(map_keypoints.size());
    for (auto& kp : map_keypoints)
        map_keypoint_pts.push_back(kp.pt);

    build_flann_index();
    LOG("Multi-map trained: %zu features across %d sub-images (full coordinates)",
        map_keypoints.size(), sub_count);
    return true;
}

void SiftMatcher::build_flann_index() {
    cv::Mat& descs = transform->persistent_mat;
    cv::Mat train_data;
    if (descs.type() == CV_8U) {
        // ULTRA 变体：CV_8U → CV_32F（flann::Index 需要）
        descs.convertTo(train_data, CV_32F);
    } else {
        train_data = descs;
    }

    // 直接创建 flann::Index — 仅 1 份 features_clone 拷贝（vs FlannBasedMatcher 的 3 份）
    flann_index = std::make_unique<cv::flann::Index>(
        train_data,
        cv::flann::KDTreeIndexParams(params.flannKDTreeCount),
        cvflann::FLANN_DIST_L2
    );

    // 不释放 persistent_mat — save_cache() 需要它写出缓存文件
    // train_data 出作用域自动释放
}

bool SiftMatcher::load_from_cache() {
    build_flann_index();
    transform->persistent_mat = cv::Mat();
    map_keypoint_pts.reserve(map_keypoints.size());
    for (auto& kp : map_keypoints)
        map_keypoint_pts.push_back(kp.pt);
    LOG("SIFT loaded from cache: %zu features", map_keypoints.size());
    if (!map_id_for_feature.empty()) {
        int max_id = *std::max_element(map_id_for_feature.begin(), map_id_for_feature.end());
        LOG("  multi-map mode: %d sub-images", max_id + 1);
    }
    return true;
}


