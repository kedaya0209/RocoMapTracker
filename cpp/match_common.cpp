// match_common.cpp — Shared preprocessing & protocol implementations.
// See match_common.h for declarations.

#include "match_common.h"

// ============================================================================
// Zlib compression helpers
// ============================================================================
std::vector<uint8_t> zlib_compress(const void* data, size_t len) {
    uLongf dstLen = compressBound((uLong)len);
    std::vector<uint8_t> dst(dstLen);
    if (compress2(dst.data(), &dstLen, (const Bytef*)data, (uLong)len, Z_BEST_SPEED) != Z_OK) {
        return {};
    }
    dst.resize(dstLen);
    return dst;
}

std::vector<uint8_t> zlib_decompress(const void* data, size_t compressedLen, size_t rawLen) {
    std::vector<uint8_t> dst(rawLen);
    uLongf dstLen = (uLongf)rawLen;
    if (uncompress(dst.data(), &dstLen, (const Bytef*)data, (uLong)compressedLen) != Z_OK) {
        return {};
    }
    return dst;
}

// ============================================================================
// Mat serialization
// ============================================================================
void write_mat_compressed(FILE* f, const cv::Mat& m) {
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
        int32_t sz = (int32_t)rawLen;
        fwrite(&sz, 4, 1, f);
        fwrite(&sz, 4, 1, f);
        fwrite(raw.data(), 1, rawLen, f);
    } else {
        int32_t cLen = (int32_t)compressed.size();
        int32_t rLen = (int32_t)rawLen;
        fwrite(&cLen, 4, 1, f);
        fwrite(&rLen, 4, 1, f);
        fwrite(compressed.data(), 1, compressed.size(), f);
    }
}

cv::Mat read_mat_compressed(FILE* f) {
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

void write_float(FILE* f, float v) {
    fwrite(&v, 4, 1, f);
}

float read_float(FILE* f) {
    float v = 0;
    fread(&v, 4, 1, f);
    return v;
}

// ============================================================================
// MiniMapProcessor
// ============================================================================
MiniMapProcessor::DetectionResult MiniMapProcessor::detect(uint8_t* data, int w, int h) {
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

    double dcx = det_cx - SMALL_WIDTH / 2.0, dcy = det_cy - small_gray.rows / 2.0;
    double dist_to_center_sq = dcx * dcx + dcy * dcy;
    double max_dist = min_side * CENTER_OFFSET_RATIO;
    if ((double)black_count / 120 > BLACK_RATIO_THRESHOLD && dist_to_center_sq < max_dist * max_dist) {
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

void MiniMapProcessor::init_mats(int w, int h) {
    if (gray_mat.cols != w || gray_mat.rows != h) {
        double scale = (double)SMALL_WIDTH / w;
        int sh = (int)(h * scale);
        gray_mat = cv::Mat(h, w, CV_8UC1);
        small_gray = cv::Mat(sh, SMALL_WIDTH, CV_8UC1);
        blur_mat = cv::Mat(sh, SMALL_WIDTH, CV_8UC1);
        small_gray_data.resize(SMALL_WIDTH * sh);
    }
}

// ============================================================================
// Circle mask
// ============================================================================
void apply_circle_mask(uint8_t* data, int w, int h,
                       double center_x, double center_y, int radius) {
    double r2 = (double)radius * radius;

    int min_y = std::max(0, (int)std::ceil(center_y - radius));
    int max_y = std::min(h - 1, (int)std::floor(center_y + radius));

    if (min_y > 0) {
        memset(data, 0, (size_t)min_y * w);
    }

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

    if (max_y < h - 1) {
        memset(data + (size_t)(max_y + 1) * w, 0, (size_t)(h - 1 - max_y) * w);
    }
}

// ============================================================================
// Brightness classification: is the cropped minimap dark (cave-like)?
// Applies sigmoid LUT (midpoint=100, steepness=0.05) to amplify brightness
// separation, then counts pixels < 100 at stride 4.
// ============================================================================
float is_dark_minimap(const uint8_t* gray_data, int w, int h,
                     double cx, double cy, int radius,
                     float dark_ratio_threshold) {
    // Precomputed sigmoid LUT: lut[i] = 255 / (1 + exp(-0.05 * (i - 100)))
    static const uint8_t SIGMOID_LUT[256] = {
        1,  1,  1,  1,  2,  2,  2,  2,  2,  2,  2,  2,  3,  3,  3,  3,  3,  3,  4,  4,  4,  4,  5,  5,  5,  5,  6,  6,  6,  7,  7,  7,
        8,  8,  9,  9,  9, 10, 10, 11, 12, 12, 13, 13, 14, 15, 16, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 29, 30, 31, 33, 34,
       36, 37, 39, 41, 42, 44, 46, 48, 50, 52, 54, 56, 59, 61, 63, 66, 68, 71, 73, 76, 79, 81, 84, 87, 90, 93, 96, 99,102,105,108,111,
      114,117,121,124,127,130,133,137,140,143,146,149,152,155,158,161,164,167,170,173,175,178,181,183,186,188,191,193,195,198,200,202,
      204,206,208,210,212,213,215,217,218,220,221,223,224,225,227,228,229,230,231,232,233,234,235,236,237,238,238,239,240,241,241,242,
      242,243,244,244,245,245,245,246,246,247,247,247,248,248,248,249,249,249,249,250,250,250,250,251,251,251,251,251,251,252,252,252,
      252,252,252,252,252,253,253,253,253,253,253,253,253,253,253,253,253,253,253,254,254,254,254,254,254,254,254,254,254,254,254,254,
      254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254,254
    };
    constexpr int STRIDE = 4;
    double r2 = (double)radius * radius;
    double inner_r2 = (double)(radius / 4) * (double)(radius / 4);  // skip flashlight at center
    int dark_count = 0;
    int total = 0;

    for (int y = 0; y < h; y += STRIDE) {
        double dy = y - cy;
        const uint8_t* row = gray_data + (size_t)y * w;
        for (int x = 0; x < w; x += STRIDE) {
            double dx = x - cx;
            if (dx * dx + dy * dy > r2) continue;        // outside minimap circle
            if (dx * dx + dy * dy < inner_r2) continue;   // skip player flashlight center
            uint8_t gray_val = row[x];
            if (gray_val < 5 || gray_val > 250) continue;  // trim extremes: borders & UI
            if (SIGMOID_LUT[gray_val] < 100) {
                dark_count++;
            }
            total++;
        }
    }

    return (float)dark_count / (float)std::max(total, 1);
}

// ============================================================================
// Debug PNG helpers
// ============================================================================
static void write_be32(FILE* f, uint32_t v) {
    uint8_t buf[4] = {(uint8_t)(v >> 24), (uint8_t)(v >> 16), (uint8_t)(v >> 8), (uint8_t)v};
    fwrite(buf, 1, 4, f);
}

static void write_png_chunk(FILE* f, const char* type, const uint8_t* data, uint32_t len) {
    write_be32(f, len);
    fwrite(type, 1, 4, f);
    if (len > 0) fwrite(data, 1, len, f);
    uint32_t crc = crc32(0, Z_NULL, 0);
    crc = crc32(crc, (const Bytef*)type, 4);
    if (len > 0) crc = crc32(crc, (const Bytef*)data, len);
    write_be32(f, crc);
}

void save_roi_png(const cv::Mat& bgra, const char* path) {
    int w = bgra.cols, h = bgra.rows;
    cv::Mat bgr;
    cv::cvtColor(bgra, bgr, cv::COLOR_BGRA2BGR);
    int row_bytes = w * 3;
    int raw_row_size = 1 + row_bytes;
    std::vector<uint8_t> raw(h * raw_row_size);
    for (int y = 0; y < h; y++) {
        raw[y * raw_row_size] = 0;
        const uint8_t* src = bgr.ptr(y);
        uint8_t* dst = &raw[y * raw_row_size + 1];
        for (int x = 0; x < w; x++) {
            dst[x * 3 + 0] = src[x * 3 + 2];
            dst[x * 3 + 1] = src[x * 3 + 1];
            dst[x * 3 + 2] = src[x * 3 + 0];
        }
    }
    uLongf comp_len = compressBound(raw.size());
    std::vector<uint8_t> comp(comp_len);
    compress2(comp.data(), &comp_len, raw.data(), raw.size(), Z_BEST_SPEED);
    comp.resize(comp_len);
    FILE* f = fopen(path, "wb");
    if (!f) return;
    const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    fwrite(sig, 1, 8, f);
    uint8_t ihdr[13];
    memset(ihdr, 0, 13);
    ihdr[0] = (uint8_t)(w >> 24); ihdr[1] = (uint8_t)(w >> 16);
    ihdr[2] = (uint8_t)(w >> 8);  ihdr[3] = (uint8_t)w;
    ihdr[4] = (uint8_t)(h >> 24); ihdr[5] = (uint8_t)(h >> 16);
    ihdr[6] = (uint8_t)(h >> 8);  ihdr[7] = (uint8_t)h;
    ihdr[8] = 8;  ihdr[9] = 2;  ihdr[10] = 0;  ihdr[11] = 0;  ihdr[12] = 0;
    write_png_chunk(f, "IHDR", ihdr, 13);
    write_png_chunk(f, "IDAT", comp.data(), (uint32_t)comp.size());
    write_png_chunk(f, "IEND", nullptr, 0);
    fclose(f);
}

void save_png(const cv::Mat& bgr, const char* path) {
    int w = bgr.cols, h = bgr.rows;
    if (w <= 0 || h <= 0) return;

    auto be32 = [](uint8_t buf[4], uint32_t v) {
        buf[0] = (uint8_t)(v >> 24); buf[1] = (uint8_t)(v >> 16);
        buf[2] = (uint8_t)(v >> 8);  buf[3] = (uint8_t)(v);
    };
    auto wr32 = [&](std::ofstream& f, uint32_t v) {
        uint8_t b[4]; be32(b, v); f.write((char*)b, 4);
    };
    auto chunk = [&](std::ofstream& f, const char type[4],
                     const uint8_t* data, uint32_t len) {
        wr32(f, len);
        f.write(type, 4);
        if (len) f.write((char*)data, len);
        uint32_t crc = crc32(0, (const Bytef*)type, 4);
        if (len) crc = crc32(crc, data, len);
        wr32(f, crc);
    };

    int rowBytes = w * 3;
    std::vector<uint8_t> raw((rowBytes + 1) * h);
    for (int y = 0; y < h; y++) {
        raw[y * (rowBytes + 1)] = 0;
        const uint8_t* src = bgr.ptr(y);
        uint8_t* dst = &raw[y * (rowBytes + 1) + 1];
        for (int x = 0; x < w; x++) {
            dst[x * 3]     = src[x * 3 + 2];
            dst[x * 3 + 1] = src[x * 3 + 1];
            dst[x * 3 + 2] = src[x * 3];
        }
    }

    uLong rawLen = (uLong)raw.size();
    uLong compLen = compressBound(rawLen);
    std::vector<uint8_t> comp(compLen);
    compress2(comp.data(), &compLen, raw.data(), rawLen, Z_BEST_SPEED);

    uint8_t ihdr[13];
    be32(ihdr, w); be32(ihdr + 4, h);
    ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;

    uint8_t sig[8] = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    std::ofstream f(path, std::ios::binary);
    f.write((char*)sig, 8);
    chunk(f, "IHDR", ihdr, 13);
    chunk(f, "IDAT", comp.data(), (uint32_t)compLen);
    chunk(f, "IEND", nullptr, 0);
}

// ============================================================================
// Arrow direction detection
// ============================================================================
double detect_arrow_angle_hsv(const uint8_t* bgra_data, int w, int h,
                              double cx, double cy, int radius) {
    if (radius < 15) return std::numeric_limits<double>::quiet_NaN();

    static constexpr int CROP_SIZE = 64;
    int cropX = (int)std::round(cx - CROP_SIZE / 2);
    int cropY = (int)std::round(cy - CROP_SIZE / 2);
    if (cropX < 0) cropX = 0;
    if (cropY < 0) cropY = 0;
    if (cropX + CROP_SIZE > w) cropX = w - CROP_SIZE;
    if (cropY + CROP_SIZE > h) cropY = h - CROP_SIZE;
    if (cropX < 0 || cropY < 0) return std::numeric_limits<double>::quiet_NaN();

    cv::Mat full(h, w, CV_8UC4, const_cast<uint8_t*>(bgra_data));
    cv::Mat roi(full, cv::Rect(cropX, cropY, CROP_SIZE, CROP_SIZE));

    cv::Mat hsv;
    cv::cvtColor(roi, hsv, cv::COLOR_BGRA2BGR);
    cv::cvtColor(hsv, hsv, cv::COLOR_BGR2HSV);

    cv::Mat mask;
    cv::inRange(hsv, cv::Scalar(15, 200, 230), cv::Scalar(25, 240, 255), mask);

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

    // PCA 主轴
    cv::Moments m = cv::moments(contours[maxIdx]);
    double mcx = m.m10 / m.m00, mcy = m.m01 / m.m00;
    double axisRad = 0.5 * std::atan2(2.0 * m.mu11, m.mu20 - m.mu02);
    if (axisRad < 0) axisRad += CV_PI;
    double axisDeg = axisRad * 180.0 / CV_PI;
    double cosA = std::cos(axisRad), sinA = std::sin(axisRad);

    const auto& c = contours[maxIdx];
    double tMin = 1e18, tMax = -1e18;
    for (const auto& pt : c) {
        double t = (pt.x - mcx) * cosA + (pt.y - mcy) * sinA;
        if (t < tMin) tMin = t;
        if (t > tMax) tMax = t;
    }
    double tRange = tMax - tMin;
    if (tRange < 1.0) return axisDeg;

    // PCA 两端测宽度判断箭尖方向
    double pcts[] = {0.05, 0.10, 0.20};
    double ep = std::max(3.0, tRange * 0.06);
    double pdx = -sinA, pdy = cosA;
    double perpLo[2][3] = {{1e18,1e18,1e18},{1e18,1e18,1e18}};
    double perpHi[2][3] = {{-1e18,-1e18,-1e18},{-1e18,-1e18,-1e18}};
    for (const auto& pt : c) {
        double t = (pt.x - mcx) * cosA + (pt.y - mcy) * sinA;
        double perp = (pt.x - mcx) * pdx + (pt.y - mcy) * pdy;
        for (int i = 0; i < 3; i++) {
            double o = tRange * pcts[i];
            if (std::abs(t - (tMin + o)) <= ep) {
                if (perp < perpLo[0][i]) perpLo[0][i] = perp;
                if (perp > perpHi[0][i]) perpHi[0][i] = perp;
            }
            if (std::abs(t - (tMax - o)) <= ep) {
                if (perp < perpLo[1][i]) perpLo[1][i] = perp;
                if (perp > perpHi[1][i]) perpHi[1][i] = perp;
            }
        }
    }
    double widths[2][3];
    for (int side = 0; side < 2; side++)
        for (int i = 0; i < 3; i++)
            widths[side][i] = (perpHi[side][i] > perpLo[side][i]) ? perpHi[side][i] - perpLo[side][i] : 999.0;
    auto median3 = [](double a[3]) -> double {
        double b[3] = {a[0], a[1], a[2]};
        if (b[0] > b[1]) std::swap(b[0], b[1]);
        if (b[1] > b[2]) std::swap(b[1], b[2]);
        if (b[0] > b[1]) std::swap(b[0], b[1]);
        return b[1];
    };
    double med[2] = {median3(widths[0]), median3(widths[1])};
    int tipEnd = (med[0] < med[1]) ? -1 : 1;

    double angleDeg;
    if (tipEnd == 1) {
        angleDeg = axisDeg;
    } else {
        angleDeg = std::fmod(axisDeg + 180.0, 360.0);
    }

    return angleDeg;
}

// ============================================================================
// Serialize MATCH_RESULT body
// ============================================================================
std::vector<uint8_t> serialize_result(bool success, double x, double y, double angle,
                                      float t_minimap_ms, float t_extract_ms,
                                      float t_matching_ms, float t_arrow_ms,
                                      int map_id) {
    std::vector<uint8_t> buf(42);
    buf[0] = success ? 1 : 0;
    write_double(buf.data() + 1, x);
    write_double(buf.data() + 9, y);
    write_double(buf.data() + 17, angle);
    write_float_be(buf.data() + 25, t_minimap_ms);
    write_float_be(buf.data() + 29, t_extract_ms);
    write_float_be(buf.data() + 33, t_matching_ms);
    write_float_be(buf.data() + 37, t_arrow_ms);
    buf[41] = (uint8_t)(map_id & 0xFF);
    return buf;
}

// ============================================================================
// CONFIG_DATA parser
// ============================================================================
bool parse_config_data(const std::vector<uint8_t>& body, AlgoParams& p) {
    if (body.size() < 108) {
        LOGERR("CONFIG_DATA too short: %zu bytes", body.size());
        return false;
    }

    size_t off = 0;

    int32_t kind_raw = (int32_t)read_be32(body.data() + off); off += 4;
    p.kind = static_cast<AlgoKind>(kind_raw);
    p.siftVariant   = (int32_t)read_be32(body.data() + off); off += 4;
    p.nfeatures     = (int32_t)read_be32(body.data() + off); off += 4;
    p.nOctaveLayers = (int32_t)read_be32(body.data() + off); off += 4;
    p.contrastThreshold = read_double(body.data() + off); off += 8;
    p.edgeThreshold     = read_double(body.data() + off); off += 8;
    p.sigma             = read_double(body.data() + off); off += 8;
    p.matchRatioThreshold = read_double(body.data() + off); off += 8;
    p.matchMinCount   = (int32_t)read_be32(body.data() + off); off += 4;
    p.searchRadius    = (int32_t)read_be32(body.data() + off); off += 4;
    p.flannKDTreeCount   = (int32_t)read_be32(body.data() + off); off += 4;
    p.flannSearchChecks  = (int32_t)read_be32(body.data() + off); off += 4;
    p.ransacReprojThreshold = read_double(body.data() + off); off += 8;
    p.ransacMaxIters   = (int32_t)read_be32(body.data() + off); off += 4;
    p.ransacConfidence = read_double(body.data() + off); off += 8;

    // Tile training params
    p.tileSize          = (int32_t)read_be32(body.data() + off); off += 4;
    p.tileOverlap       = (int32_t)read_be32(body.data() + off); off += 4;
    p.largeMapThreshold = (int64_t)read_be64(body.data() + off); off += 8;
    p.dedupDistance     = read_float_be(body.data() + off); off += 4;

    if (off + 4 > body.size()) return false;
    int32_t cachePathLen = (int32_t)read_be32(body.data() + off); off += 4;
    if (cachePathLen < 0 || off + cachePathLen > body.size()) return false;
    p.cacheFilePath = std::string((const char*)body.data() + off, cachePathLen);
    off += cachePathLen;

    // Second cache path (cave)
    if (off + 4 <= body.size()) {
        int32_t extraPathLen = (int32_t)read_be32(body.data() + off); off += 4;
        if (extraPathLen > 0 && off + extraPathLen <= body.size()) {
            p.caveCacheFilePath = std::string((const char*)body.data() + off, extraPathLen);
            off += extraPathLen;
            LOG("  cave cache: %s", p.caveCacheFilePath.c_str());
        }
    }

    // Plan B: sub-image heights for unified multi-map index (optional, 0 = not multi-map)
    if (off + 4 <= body.size()) {
        int32_t subCount = (int32_t)read_be32(body.data() + off); off += 4;
        if (subCount > 0) {
            p.subImageHeights.resize(subCount);
            for (int i = 0; i < subCount && off + 4 <= body.size(); i++) {
                p.subImageHeights[i] = (int32_t)read_be32(body.data() + off); off += 4;
            }
            LOG("  subImageHeights: %d sub-images", subCount);

            // Per-sub-image SIFT param overrides (optional)
            if (off + 4 <= body.size()) {
                int32_t overrideCount = (int32_t)read_be32(body.data() + off); off += 4;
                if (overrideCount > 0) {
                    p.subImageSiftParams.resize(overrideCount);
                    for (int i = 0; i < overrideCount; i++) {
                        auto& sp = p.subImageSiftParams[i];
                        sp.subImageIndex   = (int32_t)read_be32(body.data() + off); off += 4;
                        sp.contrastThreshold = read_double(body.data() + off); off += 8;
                        sp.edgeThreshold     = read_double(body.data() + off); off += 8;
                        sp.nfeatures         = (int32_t)read_be32(body.data() + off); off += 4;
                        sp.nOctaveLayers     = (int32_t)read_be32(body.data() + off); off += 4;
                        sp.sigma             = read_double(body.data() + off); off += 8;
                    }
                    LOG("  subImageSiftParams: %d overrides", overrideCount);
                }
            }
        }
    }

    LOG("CONFIG: kind=%d SIFT(%d,%d,%d,%.4f,%.1f,%.1f) MATCH(%.2f,%d,%d) FLANN(%d,%d) RANSAC(%.1f,%d,%.2f) TILE(%d,%d,%lld,%.1f) cache=%s subHeights=%zu overrides=%zu",
        (int)p.kind,
        (int)p.siftVariant, (int)p.nfeatures, (int)p.nOctaveLayers, p.contrastThreshold, p.edgeThreshold, p.sigma,
        p.matchRatioThreshold, (int)p.matchMinCount, (int)p.searchRadius,
        (int)p.flannKDTreeCount, (int)p.flannSearchChecks,
        p.ransacReprojThreshold, (int)p.ransacMaxIters, p.ransacConfidence,
        (int)p.tileSize, (int)p.tileOverlap, (long long)p.largeMapThreshold, p.dedupDistance,
        p.cacheFilePath.c_str(),
        p.subImageHeights.size(),
        p.subImageSiftParams.size());

    return true;
}

// ============================================================================
// Main loop driver
// ============================================================================
int run_match_loop(SOCKET sock, AlgoParams& params,
                   MatcherBase& matcher_overworld, MatcherBase& matcher_cave,
                   std::atomic<bool>& g_running) {
    MiniMapProcessor minimap;
    cv::Mat gray_mat;
    cv::Mat roi_contiguous;
    int64_t frame_count = 0;
    int64_t success_count = 0;
    bool first_ready = true;

    while (g_running.load(std::memory_order_acquire)) {
        if (first_ready) {
            LOG("Sending first READY...");
            first_ready = false;
        }
        if (!send_message(sock, READY, nullptr, 0)) {
            LOG("Socket send failed (READY)");
            break;
        }

        std::vector<uint8_t> recv_body;
        int32_t type = recv_message(sock, recv_body);
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

        try {
            MatchResult match_res;

            // BGRA → GRAY（gray_mat 复用外部声明的缓冲区）
            cv::Mat bgra_mat(fh, fw, CV_8UC4, bgra_data);
            cv::cvtColor(bgra_mat, gray_mat, cv::COLOR_BGRA2GRAY);
            uint8_t* gray_data = gray_mat.data;

            // 1. Minimap detection
            auto t_minimap_start = std::chrono::steady_clock::now();
            auto detection = minimap.detect(gray_data, fw, fh);
            float t_minimap = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - t_minimap_start).count();

            if (!detection.success) {
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

            // 检查裁剪出来的小地图是否是完整圆形（排除过渡动画中未完全显示的情况）
            if (detection.center_x - detection.radius < 0 ||
                detection.center_y - detection.radius < 0 ||
                detection.center_x + detection.radius >= fw ||
                detection.center_y + detection.radius >= fh) {
                auto result_buf = serialize_result(false, 0, 0,
                    std::numeric_limits<double>::quiet_NaN(), t_minimap, 0, 0);
                if (!send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size())) {
                    LOG("Socket send failed (RESULT)");
                    break;
                }
                if (frame_count % 100 == 0) {
                    LOG("frames=%lld (incomplete circle)", (long long)frame_count);
                }
                continue;
            }

            // 2. ROI crop around minimap (no circle mask — mask boundary creates false
            //    features for AKAZE's nonlinear diffusion; SIFT's edgeThreshold also
            //    doesn't need it since the crop itself isolates the minimap region)
            constexpr double CROP_MARGIN = 1.5;
            int crop_r = (int)(detection.radius * CROP_MARGIN);
            int crop_x = std::max(0, (int)(detection.center_x - crop_r));
            int crop_y = std::max(0, (int)(detection.center_y - crop_r));
            int crop_w = std::min(fw - crop_x, 2 * crop_r);
            int crop_h = std::min(fh - crop_y, 2 * crop_r);

            uint8_t* sift_data = gray_data;
            int sift_w = fw, sift_h = fh;
            double local_cx = detection.center_x;
            double local_cy = detection.center_y;
            if (crop_w > 64 && crop_h > 64 && crop_w * crop_h < fw * fh * 0.85) {
                cv::Mat roi_full(gray_mat, cv::Rect(crop_x, crop_y, crop_w, crop_h));
                roi_full.copyTo(roi_contiguous);
                sift_data = roi_contiguous.data;
                sift_w = crop_w;
                sift_h = crop_h;
                local_cx = detection.center_x - crop_x;
                local_cy = detection.center_y - crop_y;
            }

            // 3. Brightness classification → select cache (with hysteresis)
            float dark_ratio = is_dark_minimap(sift_data, sift_w, sift_h,
                                               local_cx, local_cy, detection.radius);
            static bool cave_mode = true;
            if (cave_mode) {
                // Stay in cave unless clearly overworld
                if (dark_ratio < 0.30f) cave_mode = false;
            } else {
                // Switch to cave only if clearly cave
                if (dark_ratio >= 0.50f) cave_mode = true;
            }
            MatcherBase& matcher = cave_mode ? matcher_cave : matcher_overworld;

            // 4. Match
            match_res = matcher.match(sift_data, sift_w, sift_h, hint_x, hint_y);
            match_res.t_minimap_ms = t_minimap;

            if (match_res.success) {
                success_count++;
            }

            // 5. Arrow direction detection
            auto t_arrow_start = std::chrono::steady_clock::now();
            double arrow_angle = detect_arrow_angle_hsv(bgra_data, fw, fh,
                detection.center_x, detection.center_y, detection.radius);
            float t_arrow_ms = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - t_arrow_start).count();

            // 6. Send result
            auto result_buf = serialize_result(match_res.success, match_res.x, match_res.y,
                arrow_angle,
                match_res.t_minimap_ms, match_res.t_extract_ms, match_res.t_matching_ms, t_arrow_ms,
                match_res.map_id);
            if (!send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size())) {
                LOG("Socket send failed (RESULT)");
                break;
            }
        } catch (const cv::Exception& e) {
            LOGERR("OpenCV exception in frame %lld: %s (code=%d)",
                (long long)frame_count, e.what(), e.code);
            auto result_buf = serialize_result(false, 0, 0,
                std::numeric_limits<double>::quiet_NaN());
            send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size());
        } catch (const std::exception& e) {
            LOGERR("Unexpected exception in frame %lld: %s", (long long)frame_count, e.what());
            auto result_buf = serialize_result(false, 0, 0,
                std::numeric_limits<double>::quiet_NaN());
            send_message(sock, MATCH_RESULT, result_buf.data(), (uint32_t)result_buf.size());
        }

        if (frame_count == 1 || frame_count % 500 == 0) {
            LOG("frames=%lld success=%lld (%.1f%%)",
                (long long)frame_count, (long long)success_count,
                frame_count > 0 ? 100.0 * success_count / frame_count : 0.0);
        }
    }

    LOG("Exiting, total frames=%lld success=%lld (%.1f%%)",
        (long long)frame_count, (long long)success_count,
        frame_count > 0 ? 100.0 * success_count / frame_count : 0.0);
    return 0;
}
