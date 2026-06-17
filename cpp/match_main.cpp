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

    // Create dual matchers: overworld (sub-image 0) + caves (sub-images 1+)
    auto overworld = create_matcher(params);
    auto matcher_cave = create_matcher(params);
    if (!overworld || !matcher_cave) {
        LOGERR("Failed to create matchers");
        closesocket(sock); WSACleanup(); return 1;
    }
    overworld->setSubImageGroup(0);
    matcher_cave->setSubImageGroup(1);

    // Try loading caches
    bool ow_ok = false, cv_ok = false;
    if (!params.cacheFilePath.empty()) {
        ow_ok = overworld->load_cache(params.cacheFilePath);
    }
    if (!params.caveCacheFilePath.empty()) {
        cv_ok = matcher_cave->load_cache(params.caveCacheFilePath);
    }

    // Request MAP_DATA if any cache needs training
    if (!ow_ok || !cv_ok) {
        LOG("Cache miss (ow=%d cave=%d), requesting map data...", ow_ok, cv_ok);
        if (!send_message(sock, REQUEST_MAP, nullptr, 0)) {
            LOGERR("Failed to send REQUEST_MAP");
            closesocket(sock); WSACleanup(); return 1;
        }

        int32_t type = recv_message(sock, recv_body);
        if (type != MAP_DATA) {
            LOGERR("Expected MAP_DATA, got type=%d", type);
            closesocket(sock); WSACleanup(); return 1;
        }

        // --- Parse MAP_DATA (supports multi-subimage format) ---
        int sub_count = 1;
        int map_w, map_h;
        uint32_t pixels_len;
        uint8_t* map_pixels;

        int first_val = (int)read_be32(recv_body.data());
        if (first_val > 0 && first_val <= 20) {
            // Multi-subimage format: [subImageCount][w][totalH][subH_0..subH_{N-1}][pixelsLen][gray8...]
            sub_count = first_val;
            map_w = (int)read_be32(recv_body.data() + 4);
            map_h = (int)read_be32(recv_body.data() + 8);
            int pixels_off = 12 + sub_count * 4;
            pixels_len = read_be32(recv_body.data() + pixels_off);
            map_pixels = recv_body.data() + pixels_off + 4;

            params.subImageHeights.resize(sub_count);
            for (int i = 0; i < sub_count; i++) {
                params.subImageHeights[i] = (int)read_be32(recv_body.data() + 12 + i * 4);
            }
        } else {
            // Single-map format: [w][h][pixelsLen][gray8...]
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

        // Train overworld if needed
        if (!ow_ok) {
            LOG("Training overworld matcher...");
            if (!overworld->train(map_pixels, map_w, map_h)) {
                LOGERR("Overworld training failed");
                {
                    const char* msg = "Overworld training failed";
                    uint32_t msg_len = (uint32_t)strlen(msg);
                    std::vector<uint8_t> body(8 + msg_len);
                    write_be32(body.data(), 1);
                    write_be32(body.data() + 4, msg_len);
                    memcpy(body.data() + 8, msg, msg_len);
                    send_message(sock, INIT_FAILED, body.data(), (uint32_t)body.size());
                }
                closesocket(sock); WSACleanup(); return 1;
            }
            if (!params.cacheFilePath.empty()) {
                overworld->save_cache(params.cacheFilePath);
            }
        }

        // Train caves if needed
        if (!cv_ok) {
            LOG("Training cave matcher...");
            if (!matcher_cave->train(map_pixels, map_w, map_h)) {
                LOGERR("Cave training failed");
                {
                    const char* msg = "Cave training failed";
                    uint32_t msg_len = (uint32_t)strlen(msg);
                    std::vector<uint8_t> body(8 + msg_len);
                    write_be32(body.data(), 1);
                    write_be32(body.data() + 4, msg_len);
                    memcpy(body.data() + 8, msg, msg_len);
                    send_message(sock, INIT_FAILED, body.data(), (uint32_t)body.size());
                }
                closesocket(sock); WSACleanup(); return 1;
            }
            if (!params.caveCacheFilePath.empty()) {
                matcher_cave->save_cache(params.caveCacheFilePath);
            }
        }
    }

    // INIT_COMPLETE — report total features from both matchers
    size_t ow_feat = 0, cv_feat = 0;
    {
        ow_feat = overworld->feature_count();
        cv_feat = matcher_cave->feature_count();
        size_t total_kp = ow_feat + cv_feat;
        uint8_t feat_buf[4];
        write_be32(feat_buf, (uint32_t)total_kp);
        if (!send_message(sock, INIT_COMPLETE, feat_buf, 4)) {
            LOGERR("Failed to send INIT_COMPLETE");
            closesocket(sock); WSACleanup(); return 1;
        }
        LOG("INIT_COMPLETE: overworld=%zu cave=%zu total=%zu features",
            ow_feat, cv_feat, total_kp);
    }

    // 释放训练内存（persistent_mat 等），FLANN 索引已持有内部拷贝
    overworld->release_training_memory();
    matcher_cave->release_training_memory();
    LOG("Training memory released for both matchers (%zu ow + %zu cave features)",
        ow_feat, cv_feat);

    // Windows 堆压缩，将空闲内存归还给 OS
    _heapmin();

    // Matching loop with dual matchers
    std::atomic<bool> g_running{true};
    MatcherBase& ref_overworld = *overworld;
    MatcherBase& ref_cave = *matcher_cave;
    int ret = run_match_loop(sock, params, ref_overworld, ref_cave, g_running);

    closesocket(sock);
    WSACleanup();
    return ret;
}
