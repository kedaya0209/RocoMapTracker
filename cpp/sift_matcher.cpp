// sift_matcher.cpp — SIFT Matcher implementation.
#include <algorithm>
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

    return train_direct(map_gray);
}

MatchResult SiftMatcher::match(uint8_t* data, int w, int h, double hint_x, double hint_y) {
    MatchResult res{};
    if (!flann_index) return res;

    cv::Mat scene_img(h, w, CV_8UC1, data);

    auto t0 = std::chrono::steady_clock::now();
    std::vector<cv::KeyPoint> scene_kps;
    cv::Mat scene_descriptors;
    sift->detectAndCompute(scene_img, cv::noArray(), scene_kps, scene_descriptors);
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

    // knnSearch(2)
    cv::Mat indices(query_desc.rows, 2, CV_32SC1);
    cv::Mat dists(query_desc.rows, 2, CV_32FC1);
    flann_index->knnSearch(query_desc, indices, dists, 2,
        cv::flann::SearchParams(flann_search_checks, 0.0f, true));

    good_matches.clear();

    // 统一 Lowe 比率测试（跨所有子图）
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

    if (!map_id_for_feature.empty() && !good_matches.empty()) {
        // 按距离排序，取最近的前 N 个最佳匹配
        int vote_top_n = 10;
        int vote_count = std::min(vote_top_n, (int)good_matches.size());
        std::partial_sort(good_matches.begin(), good_matches.begin() + vote_count,
            good_matches.end(),
            [](auto& a, auto& b) { return a.distance < b.distance; });

        // 统计各子图总特征数
        std::unordered_map<int, int> sub_total;
        for (int m : map_id_for_feature) sub_total[m]++;

        // 归一化投票：匹配数 ÷ 子图总特征数（消除特征量差异带来的偏置）
        std::unordered_map<int, int> raw_votes;
        for (int i = 0; i < vote_count; i++) {
            int ti = good_matches[i].trainIdx;
            if (ti >= 0 && ti < (int)map_id_for_feature.size()) {
                raw_votes[map_id_for_feature[ti]]++;
            }
        }
        if (!raw_votes.empty()) {
            int best_id = -1;
            float best_score = -1.0f;
            for (auto& [sid, cnt] : raw_votes) {
                float score = (float)cnt / (float)std::max(sub_total[sid], 1);
                LOG("  sub %d: %d/%d = %.4f", sid, cnt, sub_total[sid], score);
                if (score > best_score) { best_score = score; best_id = sid; }
            }
            LOG("Sub vote (norm): best=%d score=%.4f", best_id, best_score);
            res.map_id = best_id;

            // 从 good_matches 中收集 winner 的匹配
            std::vector<cv::DMatch> voted;
            for (auto& dm : good_matches) {
                if (dm.trainIdx >= 0 && dm.trainIdx < (int)map_id_for_feature.size()
                    && map_id_for_feature[dm.trainIdx] == best_id) {
                    voted.push_back(dm);
                }
            }

            // 若不够，从 winner 子图补充
            if ((int)voted.size() < match_min_count) {
                for (int qi = 0; qi < query_desc.rows; qi++) {
                    int* idx = indices.ptr<int>(qi);
                    float* d = dists.ptr<float>(qi);
                    if (idx[0] >= 0 && idx[0] < (int)map_id_for_feature.size()
                        && map_id_for_feature[idx[0]] == best_id) {
                        float d0 = std::sqrt(d[0]);
                        if (idx[1] >= 0) {
                            float d1 = std::sqrt(d[1]);
                            if (d0 < match_ratio_threshold * d1) continue;
                        }
                        if (d0 < match_ratio_threshold * 512.0f) {
                            voted.push_back(cv::DMatch(qi, idx[0], d0));
                        }
                    }
                }
                LOG("  expanded to %zu matches from sub %d", voted.size(), best_id);
            }

            good_matches.swap(voted);
        }
    }

    if (good_matches.size() < (size_t)match_min_count) {
        res.t_matching_ms = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - t1).count();
        return res;
    }

    // Spatial filter: 利用 hint 位置过滤远处误匹配
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

    // Serial training — each sub-image with its own SIFT params (if overridden)
    // Build a lookup map: subImageIndex → SubImageSiftParams
    std::unordered_map<int, const SubImageSiftParams*> override_map;
    for (auto& sp : params.subImageSiftParams) {
        override_map[sp.subImageIndex] = &sp;
    }

    for (int si = 0; si < sub_count; si++) {
        int sh = sub_h[si];
        if (sh <= 0) { LOG("Sub-image %d: zero height, skipping", si); continue; }

        // Resolve SIFT params for this sub-image (override or default)
        auto it = override_map.find(si);
        if (it != override_map.end()) {
            auto* sp = it->second;
            int nf = (sp->nfeatures >= 0) ? sp->nfeatures : params.nfeatures;
            int nol = (sp->nOctaveLayers >= 0) ? sp->nOctaveLayers : params.nOctaveLayers;
            double ct = (sp->contrastThreshold > 0.0) ? sp->contrastThreshold : params.contrastThreshold;
            double et = (sp->edgeThreshold > 0.0) ? sp->edgeThreshold : params.edgeThreshold;
            double sg = (sp->sigma > 0.0) ? sp->sigma : params.sigma;
            cv::Ptr<cv::SIFT> sub_sift = cv::SIFT::create(nf, nol, ct, et, sg, false);
            cv::Mat sub_gray(sh, w, CV_8UC1, (void*)(gray_pixels + y_offsets[si] * w));
            sub_sift->detectAndCompute(sub_gray, cv::noArray(),
                sub_results[si].kps, sub_results[si].descs);
            LOG("  sub[%d] → %zu kp (override: ct=%.4f et=%.1f)", si,
                sub_results[si].kps.size(), ct, et);
        } else {
            cv::Ptr<cv::SIFT> default_sift = cv::SIFT::create(
                params.nfeatures, params.nOctaveLayers,
                params.contrastThreshold, params.edgeThreshold, params.sigma, false);
            cv::Mat sub_gray(sh, w, CV_8UC1, (void*)(gray_pixels + y_offsets[si] * w));
            default_sift->detectAndCompute(sub_gray, cv::noArray(),
                sub_results[si].kps, sub_results[si].descs);
            LOG("  sub[%d] → %zu kp (default params)", si, sub_results[si].kps.size());
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
            all_kps.push_back(kp);  // 子图局部坐标
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

    // Unified PCA + quantization
    if (!transform->train(merged_descs)) {
        LOGERR("Descriptor transform failed");
        return false;
    }

    // Unified index
    map_keypoints = std::move(all_kps);
    map_keypoint_pts.clear();
    map_keypoint_pts.reserve(map_keypoints.size());
    for (auto& kp : map_keypoints)
        map_keypoint_pts.push_back(kp.pt);

    build_flann_index();
    LOG("Multi-map trained: %zu features across %d sub-images",
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
        cv::flann::KDTreeIndexParams(1),
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
