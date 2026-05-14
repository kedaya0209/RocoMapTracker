// test_wgc.cpp - Zero-allocation memory stability test for wgc_capture.dll (infinite run)
// Build: test_compile.bat
// Run:   test_wgc.exe [window_title_keyword]

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <psapi.h>
#include <cstdio>
#include <cstdint>
#include <atomic>
#include <chrono>
#include <thread>

// Must match DLL's ROI struct (1:1 memory layout)
#pragma pack(push, 1)
struct ROI {
    int32_t x, y, w, h;
};
#pragma pack(pop)

// DLL exports
typedef void (*JniCallback)(int id, int index, const uint8_t* data, size_t len,
                            int w, int h, int stride);
typedef int  (*CreateFn)(int64_t hwnd, int max_fps, JniCallback cb);
typedef void (*SetRoisFn)(int id, const ROI* ptr, size_t len);
typedef void (*StopFn)(int id);

// ============================================================
// Zero-allocation counters (atomic only in callback)
// ============================================================
static std::atomic<int64_t> g_frame_count{0};
static std::atomic<int64_t> g_total_bytes{0};
static std::atomic<int>     g_disconnect{0};

// Minimal callback: only atomic increments, zero heap alloc
static void capture_callback(int id, int index, const uint8_t* data, size_t len,
                              int w, int h, int stride) {
    if (stride == -1 || index < 0 || !data) {
        g_disconnect.store(1, std::memory_order_relaxed);
        return;
    }
    (void)id; (void)w; (void)h;
    g_frame_count.fetch_add(1, std::memory_order_relaxed);
    g_total_bytes.fetch_add((int64_t)len, std::memory_order_relaxed);
}

// Get process Private Working Set (bytes)
static SIZE_T get_ws() {
    PROCESS_MEMORY_COUNTERS_EX pmc = {sizeof(pmc)};
    if (GetProcessMemoryInfo(GetCurrentProcess(),
            (PROCESS_MEMORY_COUNTERS*)&pmc, sizeof(pmc)))
        return pmc.PrivateUsage;
    return 0;
}

// ---- EnumWindows context ----
struct FindCtx { const char* kw; HWND result; };

static BOOL CALLBACK enum_proc(HWND h, LPARAM lp) {
    FindCtx* c = (FindCtx*)lp;
    char title[256];
    if (GetWindowTextA(h, title, sizeof(title)) && strstr(title, c->kw)) {
        c->result = h;
        return FALSE;
    }
    return TRUE;
}

static HWND find_window_by_keyword(const char* keyword) {
    FindCtx ctx = {keyword, nullptr};
    EnumWindows(enum_proc, (LPARAM)&ctx);
    return ctx.result;
}

int main(int argc, char* argv[]) {
    printf("============================================================\n");
    printf("  wgc_capture.dll - infinite memory stability test\n");
    printf("  Callback: zero heap alloc (atomics only)\n");
    printf("============================================================\n\n");

    const char* keyword = argc > 1 ? argv[1] : "ROCO";

    // ---- Load DLL ----
    HMODULE dll = LoadLibraryW(L"wgc_capture.dll");
    if (!dll) {
        wchar_t cwd[MAX_PATH];
        GetCurrentDirectoryW(MAX_PATH, cwd);
        printf("[ERROR] Cannot load wgc_capture.dll (err=%lu)\n", GetLastError());
        printf("        CWD: %ls\n", cwd);
        printf("        Put DLL next to this exe.\n");
        return 1;
    }
    printf("DLL loaded.\n");

    CreateFn  create   = (CreateFn) GetProcAddress(dll, "create");
    SetRoisFn set_rois = (SetRoisFn)GetProcAddress(dll, "set_rois");
    StopFn    stop     = (StopFn)   GetProcAddress(dll, "stop");

    if (!create || !set_rois || !stop) {
        printf("[ERROR] Missing DLL exports\n");
        FreeLibrary(dll);
        return 1;
    }

    // ---- Find window ----
    printf("[1] Find window (keyword=\"%s\")...\n", keyword);
    HWND hwnd = find_window_by_keyword(keyword);
    if (!hwnd) {
        hwnd = GetForegroundWindow();
        if (hwnd) {
            char title[256];
            GetWindowTextA(hwnd, title, sizeof(title));
            printf("[WARN] No match, using foreground: \"%s\"\n", title);
        }
    } else {
        char title[256];
        GetWindowTextA(hwnd, title, sizeof(title));
        printf("      Found: HWND=0x%p  \"%s\"\n", hwnd, title);
    }

    if (!hwnd || !IsWindow(hwnd)) {
        printf("[ERROR] No valid window\n");
        FreeLibrary(dll);
        return 1;
    }

    // ---- Create capture ----
    printf("[2] Creating capture session...\n");
    int id = create((int64_t)hwnd, 30, capture_callback);
    if (id <= 0) {
        printf("[ERROR] create() returned %d\n", id);
        FreeLibrary(dll);
        return 1;
    }
    printf("      id=%d  OK\n", id);

    // ---- Set ROIs (parts per 10000) ----
    ROI rois[] = {
        {8900, 700,  1000, 1800}, // minimap
        {8750, 2870, 1100, 1700}, // inventory
    };
    const int nrois = sizeof(rois) / sizeof(rois[0]);
    set_rois(id, rois, nrois);
    printf("[3] Set %d ROIs\n", nrois);

    // ---- Run indefinitely (Ctrl+C to stop) ----
    printf("\n");
    printf("    Running... (Ctrl+C to stop)\n");
    printf("    ------------------------------------------------------\n");
    printf("    %-6s | %-9s | %-9s | %-8s | %s\n",
           "Time", "Frames", "MB/s", "WS(MB)", "Total GB");
    printf("    --------|-----------|-----------|----------|----------\n");

    auto t0 = std::chrono::steady_clock::now();
    SIZE_T ws_start = get_ws();
    int64_t prev_frames = 0;
    int64_t prev_bytes  = 0;
    int no_frame_warn   = 0;

    for (int sec = 1; ; sec++) {
        std::this_thread::sleep_for(std::chrono::seconds(1));

        if (g_disconnect.load(std::memory_order_relaxed)) {
            printf("\n[WARN] Capture stream disconnected at %d sec\n", sec);
            break;
        }

        // Every 10s: check if frames are still arriving
        if (sec % 10 == 0) {
            int64_t cur = g_frame_count.load(std::memory_order_relaxed);
            if (cur == prev_frames && ++no_frame_warn > 5) {
                printf("\n[WARN] No new frames for %d sec\n", 10 * no_frame_warn);
                no_frame_warn = 0;
            } else if (cur != prev_frames) {
                no_frame_warn = 0;
            }
        }

        // Every 30s: print stats
        if (sec % 30 == 0) {
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::steady_clock::now() - t0).count();

            int64_t cur_frames = g_frame_count.load(std::memory_order_relaxed);
            int64_t cur_bytes  = g_total_bytes.load(std::memory_order_relaxed);
            SIZE_T ws = get_ws();

            double mbps = (double)(cur_bytes - prev_bytes) / (1024.0 * 1024.0) / 30.0;
            double total_gb = (double)cur_bytes / (1024.0 * 1024.0 * 1024.0);

            printf("    %4llds | %9lld | %7.2f   | %5zu MB | %8.4f GB\n",
                   (long long)elapsed, (long long)cur_frames, mbps,
                   ws / (1024*1024), total_gb);

            prev_frames = cur_frames;
            prev_bytes  = cur_bytes;
        }
    }

    // ---- Cleanup ----
    printf("\n[4] Stopping capture...\n");
    stop(id);
    std::this_thread::sleep_for(std::chrono::milliseconds(500));

    SIZE_T ws_final = get_ws();
    int64_t total_frames = g_frame_count.load(std::memory_order_relaxed);
    int64_t total_bytes  = g_total_bytes.load(std::memory_order_relaxed);

    printf("\n============================================================\n");
    printf("  Test complete\n");
    printf("  --------------------------------------------------------\n");
    printf("  Total frames : %lld\n", (long long)total_frames);
    printf("  Total data   : %.3f GB\n", (double)total_bytes / (1024*1024*1024));
    printf("  WS start     : %zu MB\n", ws_start / (1024*1024));
    printf("  WS end       : %zu MB\n", ws_final / (1024*1024));
    printf("  WS delta     : %zd MB\n", (int64_t)(ws_final - ws_start) / (1024*1024));
    if (total_frames > 0)
        printf("  Avg frame sz : %.1f KB\n",
               (double)total_bytes / total_frames / 1024.0);
    printf("============================================================\n");

    int64_t delta_mb = (int64_t)(ws_final - ws_start) / (1024*1024);
    if (delta_mb > 50) {
        printf("\n  >>> FAIL: WS grew by %lld MB, DLL has leak <<<\n", (long long)delta_mb);
    } else {
        printf("\n  >>> PASS: WS stable, DLL has no leak <<<\n");
    }

    FreeLibrary(dll);
    return 0;
}
