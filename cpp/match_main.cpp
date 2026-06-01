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

    // Create matcher
    auto matcher = create_matcher(params);
    if (!matcher) {
        LOGERR("Failed to create matcher");
        closesocket(sock); WSACleanup(); return 1;
    }

    bool init_ok = false;
    if (!params.cacheFilePath.empty()) {
        init_ok = matcher->load_cache(params.cacheFilePath);
    }

    if (!init_ok) {
        LOG("Cache miss, requesting map data...");
        if (!send_message(sock, REQUEST_MAP, nullptr, 0)) {
            LOGERR("Failed to send REQUEST_MAP");
            closesocket(sock); WSACleanup(); return 1;
        }

        type = recv_message(sock, recv_body);
        if (type != MAP_DATA || recv_body.size() < 12) {
            LOGERR("Expected MAP_DATA, got type=%d size=%zu", type, recv_body.size());
            closesocket(sock); WSACleanup(); return 1;
        }

        int map_w = (int)read_be32(recv_body.data());
        int map_h = (int)read_be32(recv_body.data() + 4);
        uint32_t pixels_len = read_be32(recv_body.data() + 8);
        if (pixels_len != (uint32_t)(map_w * map_h)) {
            LOGERR("Map pixel size mismatch");
            closesocket(sock); WSACleanup(); return 1;
        }
        LOG("Received map data: %dx%d (%u gray pixels)", map_w, map_h, pixels_len);

        uint8_t* map_pixels = recv_body.data() + 12;
        if (!matcher->train(map_pixels, map_w, map_h)) {
            LOGERR("Training failed");
            const char* err_msg = "Training failed";
            uint8_t err_buf[8];
            write_be32(err_buf, 1);
            write_be32(err_buf + 4, (uint32_t)strlen(err_msg));
            std::vector<uint8_t> body;
            body.insert(body.end(), err_buf, err_buf + 8);
            body.insert(body.end(), err_msg, err_msg + strlen(err_msg));
            send_message(sock, INIT_FAILED, body.data(), (uint32_t)body.size());
            closesocket(sock); WSACleanup(); return 1;
        }

        if (!params.cacheFilePath.empty()) {
            size_t lastSep = params.cacheFilePath.find_last_of("\\/");
            if (lastSep != std::string::npos) {
                std::string dir = params.cacheFilePath.substr(0, lastSep);
                CreateDirectoryA(dir.c_str(), nullptr);
            }
            matcher->save_cache(params.cacheFilePath);
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
