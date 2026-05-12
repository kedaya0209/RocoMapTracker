// capture_main.cpp - Standalone WGC capture process, TCP Socket to Java
// Replaces in-process DLL, eliminates JNI/FFM memory overhead entirely.
//
// Protocol (binary TCP, big-endian):
//   Header: [4] msgType (int32 BE) + [4] bodyLength (int32 BE)
//
// Message flow:
//   C++ -> Java: msgType=1 (request ROI list)
//   Java -> C++: msgType=2 (return ROI list)
//   C++ -> Java: msgType=3 (capture ready)
//   Java -> C++: msgType=5 (processing done, request next frame)
//   C++ -> Java: msgType=4 (frame data, sent only after msgType=5 received)
//   Java -> C++: msgType=7 (stop request)
//   C++ -> Java: msgType=6 (window closed)
//   C++ -> Java: msgType=8 (window minimized/restored)
//
// Build: build_capture.bat
// Run:   capture.exe <hwnd_decimal> <port>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <psapi.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>
#include <roapi.h>
#include <winstring.h>
#include <winsock2.h>
#include <ws2tcpip.h>

#include <windows.graphics.capture.h>
#include <Windows.Graphics.Capture.Interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <vector>
#include <memory>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "windowsapp.lib")
#pragma comment(lib, "runtimeobject.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "ws2_32.lib")

using Microsoft::WRL::ComPtr;

namespace WGC = ABI::Windows::Graphics::Capture;
namespace WGD = ABI::Windows::Graphics::DirectX::Direct3D11;
namespace WGI = Windows::Graphics::DirectX::Direct3D11;

constexpr DXGI_FORMAT kFormat = static_cast<DXGI_FORMAT>(87); // DXGI_FORMAT_B8G8R8A8_UNORM

// ============================================================================
// Debug output (visible with DebugView)
// ============================================================================
#define DBG(msg) OutputDebugStringA("[capture] " msg "\n")
#define DBG1(msg, a1) { char _b[256]; _snprintf(_b, sizeof(_b), "[capture] " msg "\n", a1); OutputDebugStringA(_b); }
#define DBG2(msg, a1, a2) { char _b[256]; _snprintf(_b, sizeof(_b), "[capture] " msg "\n", a1, a2); OutputDebugStringA(_b); }

#define LOG(msg, ...) printf("[capture] " msg "\n", ##__VA_ARGS__)
#define LOGERR(msg, ...) fprintf(stderr, "[capture] [ERR] " msg "\n", ##__VA_ARGS__)

// ============================================================================
// Message types
// ============================================================================
enum MsgType : int32_t {
    REQUEST_ROI      = 1,  // C++ -> Java
    RETURN_ROI       = 2,  // Java -> C++
    CAPTURE_READY    = 3,  // C++ -> Java
    FRAME_DATA       = 4,  // C++ -> Java
    PROCESSING_DONE  = 5,  // Java -> C++
    WINDOW_CLOSED    = 6,  // C++ -> Java
    STOP_REQUEST     = 7,  // Java -> C++
    WINDOW_STATE     = 8,  // C++ -> Java
};

// ============================================================================
// ROI struct (matches Java ROIData, per-mil coords 0-10000)
// ============================================================================
#pragma pack(push, 1)
struct ROI {
    int32_t x, y, w, h;
};
#pragma pack(pop)

// ============================================================================
// Big-endian read/write helpers
// ============================================================================
static inline void write_be16(uint8_t* buf, uint16_t v) {
    buf[0] = (uint8_t)((v >> 8) & 0xFF);
    buf[1] = (uint8_t)(v & 0xFF);
}
static inline void write_be32(uint8_t* buf, uint32_t v) {
    buf[0] = (uint8_t)((v >> 24) & 0xFF);
    buf[1] = (uint8_t)((v >> 16) & 0xFF);
    buf[2] = (uint8_t)((v >> 8) & 0xFF);
    buf[3] = (uint8_t)(v & 0xFF);
}
static inline uint16_t read_be16(const uint8_t* buf) {
    return ((uint16_t)buf[0] << 8) | (uint16_t)buf[1];
}
static inline uint32_t read_be32(const uint8_t* buf) {
    return ((uint32_t)buf[0] << 24) | ((uint32_t)buf[1] << 16)
         | ((uint32_t)buf[2] << 8)  |  (uint32_t)buf[3];
}

// ============================================================================
// Process memory query
// ============================================================================
static SIZE_T get_process_ws() {
    PROCESS_MEMORY_COUNTERS_EX pmc = {sizeof(pmc)};
    if (GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS*)&pmc, sizeof(pmc)))
        return pmc.PrivateUsage;
    return 0;
}

// ============================================================================
// Socket helpers (blocking, handle partial send/recv)
// ============================================================================
static bool send_all(SOCKET sock, const void* data, size_t len) {
    const char* p = (const char*)data;
    while (len > 0) {
        int sent = send(sock, p, (int)len, 0);
        if (sent <= 0) return false;
        p += sent;
        len -= sent;
    }
    return true;
}

static bool recv_all(SOCKET sock, void* buf, size_t len) {
    char* p = (char*)buf;
    while (len > 0) {
        int rcvd = recv(sock, p, (int)len, 0);
        if (rcvd <= 0) return false;
        p += rcvd;
        len -= rcvd;
    }
    return true;
}

// Send a message: [4B msgType BE] [4B bodyLen BE] [body]
static bool send_message(SOCKET sock, MsgType type, const void* body, uint32_t body_len) {
    uint8_t header[8];
    write_be32(header, (uint32_t)type);
    write_be32(header + 4, body_len);
    if (!send_all(sock, header, 8)) return false;
    if (body_len > 0 && !send_all(sock, body, body_len)) return false;
    return true;
}

// Receive a message, returns msgType (or -1 on error), body stored in out param
static int32_t recv_message(SOCKET sock, std::vector<uint8_t>& body) {
    uint8_t header[8];
    if (!recv_all(sock, header, 8)) return -1;
    int32_t type = (int32_t)read_be32(header);
    uint32_t len = read_be32(header + 4);
    body.resize(len);
    if (len > 0 && !recv_all(sock, body.data(), len)) return -1;
    return type;
}

// ============================================================================
// Frame cache: capture thread writes, send thread reads & clears
// ============================================================================
struct FrameCache {
    std::mutex mtx;
    std::condition_variable cv;
    std::vector<uint8_t> data;
    bool ready = false;
};

// ============================================================================
// Global state
// ============================================================================
static std::atomic<bool> g_running{true};
static std::atomic<bool> g_paused{false};

// ============================================================================
// Serialize one frame into msgType=4 body format
//   [2] roi_count (BE uint16)
//   Per ROI:
//     [1] index
//     [2] w (BE uint16)
//     [2] h (BE uint16)
//     [2] stride (BE uint16)
//     [4] data_len (BE uint32)
//     [data_len] BGRA pixels
// ============================================================================
static void serialize_frame_body(
    const std::vector<ROI>& roi_list,
    const std::vector<std::vector<uint8_t>>& roi_data,
    const std::vector<int>& roi_w,
    const std::vector<int>& roi_h,
    size_t roi_count,
    std::vector<uint8_t>& out)
{
    out.clear();
    if (roi_count > 65535) roi_count = 65535;

    // roi_count
    {
        uint8_t c[2];
        write_be16(c, (uint16_t)roi_count);
        out.insert(out.end(), c, c + 2);
    }

    for (size_t i = 0; i < roi_count; i++) {
        const auto& data = roi_data[i];
        if (data.empty()) continue;
        uint16_t w = (uint16_t)roi_w[i];
        uint16_t h = (uint16_t)roi_h[i];
        uint16_t stride = w * 4;
        uint32_t data_len = (uint32_t)data.size();

        uint8_t hdr[11];
        hdr[0] = (uint8_t)i;
        write_be16(hdr + 1, w);
        write_be16(hdr + 3, h);
        write_be16(hdr + 5, stride);
        write_be32(hdr + 7, data_len);

        out.insert(out.end(), hdr, hdr + 11);
        out.insert(out.end(), data.begin(), data.end());
    }
}

// ============================================================================
// WinRT capture manager (WGC + D3D11)
// ============================================================================
class CaptureManager {
public:
    int max_fps = 30;
    std::chrono::nanoseconds frame_interval{0};

    // D3D11
    ComPtr<ID3D11Device> d3d_device;
    ComPtr<ID3D11DeviceContext> d3d_context;

    // WGC
    ComPtr<WGC::IGraphicsCaptureItem> capture_item;
    ComPtr<WGC::IDirect3D11CaptureFramePool> frame_pool;
    ComPtr<WGC::IGraphicsCaptureSession> session;

    // Staging texture (GPU->CPU readback, reused across frames)
    ComPtr<ID3D11Texture2D> staging_tex;
    UINT staging_w = 0, staging_h = 0;
    UINT frame_w = 0, frame_h = 0;

    // ROI list (protected by shared_mutex)
    std::shared_mutex roi_mutex;
    std::vector<ROI> rois;

    // Per-ROI buffers (capture thread only)
    std::vector<std::vector<uint8_t>> roi_buffers;
    std::vector<int> roi_buf_w;
    std::vector<int> roi_buf_h;

    // Diagnostics
    int64_t frame_count = 0;
    int64_t close_success = 0;
    int64_t close_fail = 0;

    // FPS limiting
    std::chrono::steady_clock::time_point last_frame_time;

    // ---- Init WGC capture for a window ----
    HRESULT init(HWND hwnd) {
        HRESULT hr;

        // 1. D3D11 device
        hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                nullptr, 0, D3D11_SDK_VERSION,
                &d3d_device, nullptr, &d3d_context);
        if (FAILED(hr)) { LOGERR("D3D11CreateDevice failed hr=0x%08lx", hr); return hr; }
        LOG("D3D11 device created");

        // 2. DXGI device -> WinRT IDirect3DDevice
        ComPtr<IDXGIDevice> dxgi_dev;
        hr = d3d_device.As(&dxgi_dev);
        if (FAILED(hr)) { LOGERR("IDXGIDevice QI failed hr=0x%08lx", hr); return hr; }

        ComPtr<WGD::IDirect3DDevice> winrt_device;
        hr = CreateWinRTDevice(dxgi_dev.Get(), winrt_device);
        if (FAILED(hr) || !winrt_device) {
            LOGERR("CreateWinRTDevice failed hr=0x%08lx", hr);
            return hr;
        }
        LOG("WinRT device created");

        // 3. CreateForWindow via IGraphicsCaptureItemInterop
        ComPtr<IGraphicsCaptureItemInterop> interop;
        hr = GetActivationFactory<IGraphicsCaptureItemInterop>(
            L"Windows.Graphics.Capture.GraphicsCaptureItem", interop);
        if (FAILED(hr) || !interop) {
            LOGERR("CaptureItem factory failed hr=0x%08lx", hr);
            return hr;
        }

        ComPtr<IUnknown> item_unk;
        hr = interop->CreateForWindow(hwnd, __uuidof(WGC::IGraphicsCaptureItem),
                                       reinterpret_cast<void**>(item_unk.GetAddressOf()));
        if (FAILED(hr) || !item_unk) {
            LOGERR("CreateForWindow failed hr=0x%08lx", hr);
            return hr;
        }
        LOG("CreateForWindow succeeded");

        hr = item_unk.As(&capture_item);
        if (FAILED(hr)) { LOGERR("capture_item QI failed hr=0x%08lx", hr); return hr; }

        // 4. Get window size
        ABI::Windows::Graphics::SizeInt32 sz = {};
        capture_item->get_Size(&sz);
        if (sz.Width <= 0 || sz.Height <= 0) {
            RECT r;
            if (GetClientRect(hwnd, &r)) {
                sz.Width = r.right - r.left;
                sz.Height = r.bottom - r.top;
            } else {
                sz.Width = 1920;
                sz.Height = 1080;
            }
            LOG("Fallback size: %dx%d", sz.Width, sz.Height);
        }
        frame_w = (UINT)sz.Width;
        frame_h = (UINT)sz.Height;
        LOG("Window size: %dx%d", frame_w, frame_h);

        // 5. Frame pool
        hr = CreateFramePool(winrt_device.Get(), sz);
        if (FAILED(hr)) {
            LOGERR("Frame pool creation failed hr=0x%08lx", hr);
            return hr;
        }
        LOG("Frame pool created");

        // 6. Capture session
        hr = frame_pool->CreateCaptureSession(capture_item.Get(), &session);
        if (FAILED(hr) || !session) {
            LOGERR("CreateCaptureSession failed hr=0x%08lx", hr);
            return hr;
        }
        LOG("Session created");

        // Disable yellow border
        {
            ComPtr<WGC::IGraphicsCaptureSession3> session3;
            if (SUCCEEDED(session.As(&session3))) {
                session3->put_IsBorderRequired(false);
                LOG("Border disabled");
            }
        }

        hr = session->StartCapture();
        if (FAILED(hr)) { LOGERR("StartCapture failed hr=0x%08lx", hr); return hr; }
        LOG("Capture started");

        if (max_fps > 0) {
            frame_interval = std::chrono::nanoseconds(
                (int64_t)(1.0 / max_fps * 1000000000));
        }

        return S_OK;
    }

    // ---- Stop capture (close session + frame pool) ----
    void stop_capture() {
        if (session) {
            ComPtr<ABI::Windows::Foundation::IClosable> closable;
            if (SUCCEEDED(session.As(&closable))) closable->Close();
        }
        if (frame_pool) {
            ComPtr<ABI::Windows::Foundation::IClosable> closable;
            if (SUCCEEDED(frame_pool.As(&closable))) closable->Close();
        }
    }

    // ---- Cleanup all COM resources ----
    void cleanup() {
        session.Reset();
        frame_pool.Reset();
        capture_item.Reset();
        staging_tex.Reset();
        d3d_context.Reset();
        d3d_device.Reset();
    }

    // ---- Set ROI list (called from main thread, read by capture thread) ----
    void set_rois(const ROI* ptr, size_t count) {
        std::unique_lock lock(roi_mutex);
        rois.clear();
        if (ptr && count > 0) rois.assign(ptr, ptr + count);
    }

    // ---- Main capture loop (runs in dedicated thread) ----
    void capture_loop(FrameCache& cache) {
        while (g_running.load(std::memory_order_acquire)) {
            // Pause when window is minimized
            if (g_paused.load(std::memory_order_acquire)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(500));
                continue;
            }

            // FPS limiting — sleep_until for precise timing
            if (frame_interval.count() > 0) {
                auto target = last_frame_time + frame_interval;
                auto now = std::chrono::steady_clock::now();
                if (now < target) {
                    std::this_thread::sleep_until(target);
                }
                last_frame_time = std::chrono::steady_clock::now();
            }

            // TryGetNextFrame
            ComPtr<WGC::IDirect3D11CaptureFrame> wgc_frame;
            HRESULT hr = frame_pool->TryGetNextFrame(&wgc_frame);
            if (FAILED(hr)) {
                DBG1("TryGetNextFrame failed hr=0x%08lx", hr);
                g_running.store(false, std::memory_order_release);
                break;
            }
            if (!wgc_frame) {
                // Window minimized: TryGetNextFrame returns S_OK + null frame
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }

            // get_Surface -> IDirect3DSurface
            ComPtr<WGD::IDirect3DSurface> surf;
            hr = wgc_frame->get_Surface(&surf);
            if (FAILED(hr) || !surf) {
                CloseWgcFrame(wgc_frame.Get());
                wgc_frame.Reset();
                continue;
            }

            // IDirect3DSurface -> ID3D11Texture2D
            ComPtr<WGI::IDirect3DDxgiInterfaceAccess> dxgi_acc;
            hr = surf.As(&dxgi_acc);
            if (FAILED(hr) || !dxgi_acc) {
                CloseWgcFrame(wgc_frame.Get());
                wgc_frame.Reset();
                continue;
            }

            ComPtr<ID3D11Texture2D> tex;
            hr = dxgi_acc->GetInterface(__uuidof(ID3D11Texture2D),
                                        reinterpret_cast<void**>(tex.GetAddressOf()));
            if (FAILED(hr) || !tex) {
                CloseWgcFrame(wgc_frame.Get());
                wgc_frame.Reset();
                continue;
            }

            // Process frame (extract ROIs from GPU)
            bool ok = ProcessFrame(tex.Get());

            // Return frame to pool (CRITICAL to avoid D3D texture leak)
            CloseWgcFrame(wgc_frame.Get());
            wgc_frame.Reset();

            if (!ok) continue;

            frame_count++;

            // Serialize frame body (roi_count + per-roi headers + pixel data)
            std::vector<uint8_t> body;
            {
                std::shared_lock lock(roi_mutex);
                serialize_frame_body(rois, roi_buffers, roi_buf_w, roi_buf_h,
                                     rois.size(), body);
            }

            // Swap into cache (overwrite if send thread hasn't consumed previous)
            {
                std::lock_guard lk(cache.mtx);
                cache.data = std::move(body);
                cache.ready = true;
            }
            cache.cv.notify_one();

            // Diagnostic log every 500 frames
            if (frame_count % 500 == 0) {
                SIZE_T ws_mb = get_process_ws() / (1024 * 1024);
                LOG("frames=%lld mem=%zuMB close_ok=%lld",
                    (long long)frame_count, ws_mb, (long long)close_success);
            }
        }

        SIZE_T ws_mb = get_process_ws() / (1024 * 1024);
        LOG("capture_loop exit: frames=%lld ws=%zuMB",
            (long long)frame_count, ws_mb);

        g_running.store(false, std::memory_order_release);
        cache.cv.notify_all();
    }

private:
    // ---- Ensure staging texture matches frame size ----
    HRESULT EnsureStaging(UINT w, UINT h) {
        if (staging_tex && staging_w == w && staging_h == h) return S_OK;
        staging_tex.Reset();
        staging_w = staging_h = 0;
        D3D11_TEXTURE2D_DESC desc = {};
        desc.Width = w;
        desc.Height = h;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = kFormat;
        desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_STAGING;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        HRESULT hr = d3d_device->CreateTexture2D(&desc, nullptr, &staging_tex);
        if (SUCCEEDED(hr)) { staging_w = w; staging_h = h; }
        return hr;
    }

    // ---- Extract ROIs from a D3D11 frame texture ----
    bool ProcessFrame(ID3D11Texture2D* frame_texture) {
        D3D11_TEXTURE2D_DESC desc;
        frame_texture->GetDesc(&desc);
        UINT fw = desc.Width;
        UINT fh = desc.Height;
        if (fw != frame_w || fh != frame_h) {
            frame_w = fw; frame_h = fh;
            LOG("Frame size changed: %dx%d", fw, fh);
        }
        if (fw == 0 || fh == 0) return false;
        if (FAILED(EnsureStaging(fw, fh))) return false;

        d3d_context->CopyResource(staging_tex.Get(), frame_texture);

        D3D11_MAPPED_SUBRESOURCE mapped = {};
        HRESULT hr = d3d_context->Map(staging_tex.Get(), 0, D3D11_MAP_READ, 0, &mapped);
        if (FAILED(hr)) return false;

        // Snapshot ROI list (shared lock)
        std::vector<ROI> roi_snap;
        {
            std::shared_lock lock(roi_mutex);
            roi_snap = rois;
        }

        size_t nrois = roi_snap.size();
        roi_buffers.resize(nrois);
        roi_buf_w.resize(nrois);
        roi_buf_h.resize(nrois);

        const uint8_t* src = static_cast<const uint8_t*>(mapped.pData);
        UINT pitch = mapped.RowPitch;
        const size_t total_bytes = (size_t)pitch * fh;

        for (size_t i = 0; i < nrois; i++) {
            const ROI& r = roi_snap[i];

            // Per-mil coords -> pixel coords
            int rx = (int)((int64_t)r.x * fw / 10000);
            int ry = (int)((int64_t)r.y * fh / 10000);
            int rw = (int)((int64_t)r.w * fw / 10000);
            int rh = (int)((int64_t)r.h * fh / 10000);

            // Clamp to frame bounds
            if (rx < 0) { rw += rx; rx = 0; }
            if (ry < 0) { rh += ry; ry = 0; }
            if (rx >= (int)fw || ry >= (int)fh) continue;
            if (rx + rw > (int)fw) rw = (int)fw - rx;
            if (ry + rh > (int)fh) rh = (int)fh - ry;
            if (rw <= 0 || rh <= 0) continue;

            // Verify row fits within pitch
            if ((size_t)rx * 4 + (size_t)rw * 4 > (size_t)pitch) {
                rw = ((int)pitch - rx * 4) / 4;
                if (rw <= 0) continue;
            }

            size_t buf_size = (size_t)rw * rh * 4;
            auto& buf = roi_buffers[i];
            buf.resize(buf_size);

            uint8_t* dst = buf.data();
            for (int y = 0; y < rh; y++) {
                size_t src_off = (size_t)(ry + y) * pitch + (size_t)rx * 4;
                if (src_off + (size_t)rw * 4 > total_bytes) break;
                memcpy(dst + (size_t)y * rw * 4, src + src_off, (size_t)rw * 4);
            }

            roi_buf_w[i] = rw;
            roi_buf_h[i] = rh;
        }

        d3d_context->Unmap(staging_tex.Get(), 0);
        return true;
    }

    // ---- Close a WGC frame (return to frame pool) ----
    void CloseWgcFrame(IUnknown* frame) {
        if (!frame) return;
        ComPtr<ABI::Windows::Foundation::IClosable> closable;
        HRESULT hr = frame->QueryInterface(
            __uuidof(ABI::Windows::Foundation::IClosable),
            reinterpret_cast<void**>(closable.GetAddressOf()));
        if (SUCCEEDED(hr) && closable) {
            closable->Close();
            close_success++;
        } else {
            close_fail++;
            if (close_fail <= 5) DBG1("CloseWgcFrame QI failed hr=0x%08lx", hr);
        }
    }

    // ---- Create frame pool (prefer CreateFreeThreaded) ----
    HRESULT CreateFramePool(WGD::IDirect3DDevice* winrt_device,
                            ABI::Windows::Graphics::SizeInt32 sz) {
        HRESULT hr;
        ComPtr<WGC::IDirect3D11CaptureFramePoolStatics2> pool_s2;
        hr = GetActivationFactory<WGC::IDirect3D11CaptureFramePoolStatics2>(
            L"Windows.Graphics.Capture.Direct3D11CaptureFramePool", pool_s2);

        if (SUCCEEDED(hr) && pool_s2) {
            LOG("Using CreateFreeThreaded");
            return pool_s2->CreateFreeThreaded(winrt_device,
                (ABI::Windows::Graphics::DirectX::DirectXPixelFormat)kFormat,
                2, sz, &frame_pool);
        }

        ComPtr<WGC::IDirect3D11CaptureFramePoolStatics> pool_s;
        hr = GetActivationFactory<WGC::IDirect3D11CaptureFramePoolStatics>(
            L"Windows.Graphics.Capture.Direct3D11CaptureFramePool", pool_s);
        if (FAILED(hr) || !pool_s) return hr;
        LOG("Fallback to Create");
        return pool_s->Create(winrt_device,
            (ABI::Windows::Graphics::DirectX::DirectXPixelFormat)kFormat,
            2, sz, &frame_pool);
    }

    // ---- WinRT Activation Factory helper ----
    template<typename T>
    static HRESULT GetActivationFactory(const wchar_t* name, ComPtr<T>& out) {
        HSTRING hs = nullptr;
        HRESULT hr = WindowsCreateString(name, (UINT32)wcslen(name), &hs);
        if (FAILED(hr)) return hr;
        ComPtr<IUnknown> unk;
        hr = RoGetActivationFactory(hs, __uuidof(T),
            reinterpret_cast<void**>(unk.GetAddressOf()));
        WindowsDeleteString(hs);
        if (FAILED(hr)) return hr;
        return unk.As(&out);
    }

    // ---- Create WinRT D3D device from DXGI device ----
    static HRESULT CreateWinRTDevice(IDXGIDevice* dxgi_dev,
                                     ComPtr<WGD::IDirect3DDevice>& out) {
        using PFN = HRESULT(WINAPI*)(IDXGIDevice*, IUnknown**);
        static PFN pfn = []() -> PFN {
            HMODULE h = GetModuleHandleW(L"d3d11.dll");
            if (!h) h = LoadLibraryW(L"d3d11.dll");
            if (!h) return nullptr;
            return reinterpret_cast<PFN>(
                GetProcAddress(h, "CreateDirect3D11DeviceFromDXGIDevice"));
        }();
        if (!pfn) return E_FAIL;
        ComPtr<IUnknown> unk;
        HRESULT hr = pfn(dxgi_dev, &unk);
        if (FAILED(hr)) return hr;
        return unk.As(&out);
    }
};

// ============================================================================
// Send thread: recv(msgType=5) -> wait new frame -> send msgType=4 -> repeat
// ============================================================================
static void send_loop(SOCKET sock, FrameCache& cache) {
    std::vector<uint8_t> recv_body;
    std::vector<uint8_t> send_buf;

    while (g_running.load(std::memory_order_acquire)) {
        // 1. Wait for Java to signal processing complete (msgType=5)
        int32_t type = recv_message(sock, recv_body);
        if (type < 0) {
            LOG("Socket recv failed, send loop exiting");
            g_running.store(false, std::memory_order_release);
            break;
        }

        if (type == STOP_REQUEST) {
            LOG("Received stop request");
            g_running.store(false, std::memory_order_release);
            break;
        }

        if (type != PROCESSING_DONE) {
            LOG("Unexpected msgType=%d (expected %d)", type, PROCESSING_DONE);
            continue;
        }

        // 2. Wait for new frame
        {
            std::unique_lock lk(cache.mtx);
            cache.cv.wait(lk, [&cache] {
                return cache.ready || !g_running.load(std::memory_order_acquire);
            });
            if (!g_running.load(std::memory_order_acquire)) break;
            if (!cache.ready) continue;

            // Steal frame data (zero-copy via move)
            send_buf = std::move(cache.data);
            cache.ready = false;
        }

        // 3. Send frame data
        if (!send_buf.empty()) {
            if (!send_message(sock, FRAME_DATA, send_buf.data(),
                              (uint32_t)send_buf.size())) {
                LOG("Socket send failed");
                g_running.store(false, std::memory_order_release);
                break;
            }
        }
    }

    cache.cv.notify_all();
}

// ============================================================================
// Window monitor thread: check IsWindow / IsIconic every second
// ============================================================================
static void monitor_loop(HWND hwnd, SOCKET sock) {
    bool was_minimized = false;

    while (g_running.load(std::memory_order_acquire)) {
        std::this_thread::sleep_for(std::chrono::seconds(1));

        // Check if window still exists
        if (!IsWindow(hwnd)) {
            LOG("Window destroyed, sending close notification");
            send_message(sock, WINDOW_CLOSED, nullptr, 0);
            g_running.store(false, std::memory_order_release);
            break;
        }

        // Check minimize state
        bool is_min = (IsIconic(hwnd) != 0);
        if (is_min != was_minimized) {
            was_minimized = is_min;
            g_paused.store(is_min, std::memory_order_release);

            uint8_t state = is_min ? 0 : 1;
            send_message(sock, WINDOW_STATE, &state, 1);
            LOG("Window %s", is_min ? "minimized" : "restored");
        }
    }
}

// ============================================================================
// Parse ROI list from msgType=2 body:
//   [2] roi_count (BE uint16)
//   Per ROI: [2] x [2] y [2] w [2] h (BE int16 each)
// ============================================================================
static bool parse_roi_body(const std::vector<uint8_t>& body, std::vector<ROI>& out) {
    if (body.size() < 2) return false;
    uint16_t count = read_be16(body.data());
    if (body.size() < 2 + (size_t)count * 8) return false;
    out.resize(count);
    for (uint16_t i = 0; i < count; i++) {
        const uint8_t* p = body.data() + 2 + (size_t)i * 8;
        out[i].x = read_be16(p);
        out[i].y = read_be16(p + 2);
        out[i].w = read_be16(p + 4);
        out[i].h = read_be16(p + 6);
    }
    return true;
}

// ============================================================================
// main()
// ============================================================================
int main(int argc, char* argv[]) {
    LOG("============================================================");
    LOG("  WGC Capture Process (Socket Mode)");
    LOG("============================================================");

    if (argc < 3) {
        LOGERR("Usage: capture.exe <hwnd_decimal> <port> [max_fps]");
        LOGERR("  hwnd_decimal : target window handle (decimal integer)");
        LOGERR("  port         : TCP port for Java connection");
        LOGERR("  max_fps      : target frame rate (default 30)");
        return 1;
    }

    HWND hwnd = (HWND)(intptr_t)_strtoi64(argv[1], nullptr, 10);
    int port = atoi(argv[2]);
    int max_fps = argc >= 4 ? atoi(argv[3]) : 30;
    if (max_fps <= 0) max_fps = 30;
    if (max_fps > 60) max_fps = 60;

    LOG("Target: HWND=0x%p port=%d max_fps=%d", hwnd, port, max_fps);
    if (!IsWindow(hwnd)) {
        LOGERR("Invalid window handle");
        return 1;
    }

    // ---- WinSock init ----
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        LOGERR("WSAStartup failed: %d", WSAGetLastError());
        return 1;
    }

    // ---- Connect to Java server (with retries) ----
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        LOGERR("socket() failed: %d", WSAGetLastError());
        WSACleanup();
        return 1;
    }

    // TCP_NODELAY for low latency
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
            closesocket(sock);
            WSACleanup();
            return 1;
        }
        LOG("Retry %d/%d...", retry, MAX_RETRIES);
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    LOG("Connected to Java");

    // ---- WinRT init ----
    HRESULT hr = RoInitialize(RO_INIT_MULTITHREADED);
    if (FAILED(hr)) {
        LOGERR("RoInitialize failed hr=0x%08lx", hr);
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    // ---- Handshake: request ROI -> receive ROI ----
    LOG("Requesting ROI list...");
    if (!send_message(sock, REQUEST_ROI, nullptr, 0)) {
        LOGERR("Failed to send REQUEST_ROI");
        RoUninitialize();
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    std::vector<ROI> initial_rois;
    {
        std::vector<uint8_t> body;
        int32_t type = recv_message(sock, body);
        if (type != RETURN_ROI) {
            LOGERR("Expected RETURN_ROI, got type=%d", type);
            RoUninitialize();
            closesocket(sock);
            WSACleanup();
            return 1;
        }
        if (!parse_roi_body(body, initial_rois)) {
            LOGERR("Failed to parse ROI body (%zu bytes)", body.size());
            RoUninitialize();
            closesocket(sock);
            WSACleanup();
            return 1;
        }
        LOG("Received %zu ROIs", initial_rois.size());
    }

    // ---- Init WGC capture ----
    CaptureManager mgr;
    mgr.max_fps = max_fps;
    mgr.set_rois(initial_rois.data(), initial_rois.size());

    hr = mgr.init(hwnd);
    if (FAILED(hr)) {
        LOGERR("Capture init failed hr=0x%08lx", hr);
        RoUninitialize();
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    // ---- Notify Java: capture ready ----
    LOG("Sending CAPTURE_READY");
    if (!send_message(sock, CAPTURE_READY, nullptr, 0)) {
        LOGERR("Failed to send CAPTURE_READY");
        mgr.stop_capture();
        mgr.cleanup();
        RoUninitialize();
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    // ---- Start threads ----
    FrameCache cache;

    std::thread capture_thread([&mgr, &cache]() {
        mgr.capture_loop(cache);
    });

    std::thread send_thread([sock, &cache]() {
        send_loop(sock, cache);
    });

    std::thread monitor_thread([hwnd, sock]() {
        monitor_loop(hwnd, sock);
    });

    LOG("All threads started, capturing...");

    // ---- Wait for threads ----
    monitor_thread.join();
    g_running.store(false, std::memory_order_release);
    cache.cv.notify_all();

    capture_thread.join();
    send_thread.join();

    // ---- Cleanup ----
    mgr.stop_capture();
    mgr.cleanup();
    RoUninitialize();
    closesocket(sock);
    WSACleanup();

    LOG("Process exiting normally");
    return 0;
}
