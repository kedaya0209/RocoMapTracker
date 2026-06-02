// match_main.cpp — Unified matching process, shared main() for SIFT/AKAZE.
// Depends on match_common.h/.cpp for shared preprocessing, and per-algo
// matcher implementations (sift_matcher, akaze_matcher).
//
// The actual algorithm is selected at runtime via AlgoParams::kind.

#include "match_common.h"
#include "sift_matcher.h"

// ============================================================================
// Matcher factory — dispatches to the appropriate implementation.
// ============================================================================
std::unique_ptr<MatcherBase> create_matcher(const AlgoParams& params) {
    switch (params.kind) {
    case AlgoKind::SIFT:
        return std::make_unique<SiftMatcher>(params);
    default:
        LOGERR("Unsupported algorithm kind: %d", (int)params.kind);
        return nullptr;
    }
}

// ============================================================================
// Helper: send INIT_FAILED message
// ============================================================================
static bool send_init_failed(SOCKET sock, const char* msg) {
    uint32_t msg_len = (uint32_t)strlen(msg);
    std::vector<uint8_t> body(8 + msg_len);
    write_be32(body.data(), 1);                    // error code
    write_be32(body.data() + 4, msg_len);
    memcpy(body.data() + 8, msg, msg_len);
    return send_message(sock, INIT_FAILED, body.data(), (uint32_t)body.size());
}

// ============================================================================
// MAIN
// ============================================================================
int main(int argc, char* argv[]) {
    setvbuf(stdout, NULL, _IONBF, 0);

    LOG("============================================================");
    LOG("  Match Process (Socket Mode)");
    LOG("============================================================");

    if (argc < 2) {
        LOGERR("Usage: match.exe <port>");
        return 1;
    }

    int port = atoi(argv[1]);
    LOG("Config: port=%d", port);

    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        LOGERR("WSAStartup failed: %d", WSAGetLastError());
        return 1;
    }

    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        LOGERR("socket() failed");
        WSACleanup(); return 1;
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
            closesocket(sock); WSACleanup(); return 1;
        }
        LOG("Retry %d/%d...", retry, MAX_RETRIES);
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    LOG("Connected to Java");

    // HELLO
    {
        const int32_t provides[]   = { REQUEST_MAP, REQUEST_CONFIG, INIT_COMPLETE, INIT_FAILED, READY, MATCH_RESULT };
        const int32_t subscribes[] = { MAP_DATA, FRAME_DATA, SHUTDOWN, CONFIG_DATA };
        auto hello = build_hello("match", provides, 6, subscribes, 4);
        if (!send_message(sock, HELLO, hello.data(), (uint32_t)hello.size())) {
            LOGERR("Failed to send HELLO");
            closesocket(sock); WSACleanup(); return 1;
        }
    }

    // Request config
    if (!send_message(sock, REQUEST_CONFIG, nullptr, 0)) {
        LOGERR("Failed to send REQUEST_CONFIG");
        closesocket(sock); WSACleanup(); return 1;
    }

    std::vector<uint8_t> recv_body;
    int32_t type = recv_message(sock, recv_body);
    if (type != CONFIG_DATA) {
        LOGERR("Expected CONFIG_DATA, got type=%d", type);
        closesocket(sock); WSACleanup(); return 1;
    }

    AlgoParams params;
    if (!parse_config_data(recv_body, params)) {
        LOGERR("Failed to parse CONFIG_DATA");
        closesocket(sock); WSACleanup(); return 1;
    }

    LOG("Algorithm: SIFT (kind=%d)", (int)params.kind);

    // Create matcher
    auto matcher = create_matcher(params);
    if (!matcher) {
        LOGERR("Failed to create matcher");
        closesocket(sock); WSACleanup(); return 1;
    }

    // ============================================================================
    // Load caches (dual or single)
    // ============================================================================
    bool init_ok = false;
    bool needs_retrain = false;  // dual-cache mode: cave cache missing, need full re-train
    if (!params.cacheFilePath.empty()) {
        if (!params.caveCacheFilePath.empty()) {
            auto* sift = dynamic_cast<SiftMatcher*>(matcher.get());
            if (sift) {
                init_ok = sift->load_two_caches(params.cacheFilePath, params.caveCacheFilePath);
                // Dual cache mode: full cache 加载成功但 cave 缓存不存在时，
                // 标记需要重新训练，跳过单缓存回退
                if (init_ok && !sift->cache_cave_.valid) {
                    LOG("Dual cache mode: cave cache missing, triggering re-train");
                    needs_retrain = true;
                    init_ok = false;
                }
            }
        }
        if (!init_ok && !needs_retrain) {
            init_ok = matcher->load_cache(params.cacheFilePath);
        }
    }

    // ============================================================================
    // Cache miss → request map data and train
    // ============================================================================
    if (!init_ok) {
        LOG("Cache miss, requesting map data...");
        if (!send_message(sock, REQUEST_MAP, nullptr, 0)) {
            LOGERR("Failed to send REQUEST_MAP");
            closesocket(sock); WSACleanup(); return 1;
        }

        type = recv_message(sock, recv_body);
        if (type != MAP_DATA) {
            LOGERR("Expected MAP_DATA, got type=%d", type);
            closesocket(sock); WSACleanup(); return 1;
        }

        // --- Parse MAP_DATA header (supports multi-subimage format) ---
        int sub_count = 1;    // default: single sub-image
        int map_w, map_h;
        uint32_t pixels_len;
        uint8_t* map_pixels;
        std::vector<int> sub_heights;

        int first_val = (int)read_be32(recv_body.data());
        if (first_val > 0 && first_val <= 20) {
            // New multi-subimage format: [subImageCount][w][totalH][subH_0..subH_{N-1}][pixelsLen]
            if (recv_body.size() < (size_t)(16 + first_val * 4)) {
                LOGERR("MAP_DATA too short for multi-subimage header");
                closesocket(sock); WSACleanup(); return 1;
            }
            sub_count = first_val;
            map_w   = (int)read_be32(recv_body.data() + 4);
            map_h   = (int)read_be32(recv_body.data() + 8);
            int pixels_off = 12 + sub_count * 4;
            pixels_len = read_be32(recv_body.data() + pixels_off);
            map_pixels = recv_body.data() + pixels_off + 4;

            sub_heights.resize(sub_count);
            for (int i = 0; i < sub_count; i++) {
                sub_heights[i] = (int)read_be32(recv_body.data() + 12 + i * 4);
            }
        } else {
            // Old format: [w][h][pixelsLen][pixels]
            if (recv_body.size() < 12) {
                LOGERR("MAP_DATA too short: %zu bytes", recv_body.size());
                closesocket(sock); WSACleanup(); return 1;
            }
            map_w = first_val;
            map_h = (int)read_be32(recv_body.data() + 4);
            pixels_len = read_be32(recv_body.data() + 8);
            map_pixels = recv_body.data() + 12;
        }

        if (pixels_len != (uint32_t)(map_w * map_h)) {
            LOGERR("Map pixel size mismatch: %u vs %dx%d=%d",
                    pixels_len, map_w, map_h, map_w * map_h);
            closesocket(sock); WSACleanup(); return 1;
        }
        LOG("Received map data: %dx%d (%u gray pixels, subImageCount=%d)",
            map_w, map_h, pixels_len, sub_count);

        // Copy sub-image heights for runtime sub-image detection logging
        params.subImageHeights = sub_heights;

        // --- Train full map (uses tiling for large maps) ---
        if (!matcher->train(map_pixels, map_w, map_h)) {
            LOGERR("Full map training failed");
            send_init_failed(sock, "Full map training failed");
            closesocket(sock); WSACleanup(); return 1;
        }
        LOG("Full map trained");

        // --- Save full cache immediately (before cave training changes transform state) ---
        if (!params.cacheFilePath.empty()) {
            size_t lastSep = params.cacheFilePath.find_last_of("\\/");
            if (lastSep != std::string::npos) {
                std::string dir = params.cacheFilePath.substr(0, lastSep);
                CreateDirectoryA(dir.c_str(), nullptr);
            }
            matcher->save_cache(params.cacheFilePath);
        }

        // --- Train cave-only cache if dual-cache mode ---
        bool cave_trained = false;
        if (sub_count > 1 && !params.caveCacheFilePath.empty()) {
            auto* sift = dynamic_cast<SiftMatcher*>(matcher.get());
            if (sift) {
                int cave_offset = sub_heights[0] * map_w;
                int cave_height = map_h - sub_heights[0];
                if (cave_height > 0 && cave_offset < (int)pixels_len) {
                    if (!sift->train_cave(map_pixels + cave_offset, map_w, cave_height)) {
                        LOGERR("Cave-only training failed, continuing with single cache");
                    } else {
                        LOG("Cave-only trained: %zu features", sift->cache_cave_.keypoints.size());
                        // Save cave cache (temporarily swap persistent_mat to cave descriptors)
                        cv::Mat saved = sift->transform->persistent_mat;
                        sift->transform->persistent_mat = sift->cache_cave_.descriptors;
                        sift->save_cave_cache(params.caveCacheFilePath);
                        sift->transform->persistent_mat = saved;  // restore full descriptors
                        cave_trained = true;
                    }
                }
            }
        }

        // --- If only single cache mode, save was done above ---
        if (cave_trained) {
            LOG("Dual cache saved: full=%s cave=%s",
                params.cacheFilePath.c_str(), params.caveCacheFilePath.c_str());
        }
    }

    // INIT_COMPLETE
    {
        uint8_t feat_buf[4];
        write_be32(feat_buf, (uint32_t)matcher->feature_count());
        if (!send_message(sock, INIT_COMPLETE, feat_buf, 4)) {
            LOGERR("Failed to send INIT_COMPLETE");
            closesocket(sock); WSACleanup(); return 1;
        }
    }
    LOG("INIT_COMPLETE sent, entering matching loop...");

    // Matching loop
    std::atomic<bool> g_running{true};
    int ret = run_match_loop(sock, params, *matcher, g_running);

    closesocket(sock);
    WSACleanup();
    return ret;
}
