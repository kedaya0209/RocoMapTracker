// wgc_capture.cpp — Windows Graphics Capture API DLL
// Uses official SDK headers for correct vtable definitions and IIDs.
// JNA-compatible exports: create / set_rois / stop.
//
// Build: see build.bat

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <psapi.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>
#include <roapi.h>
#include <winstring.h>

// Official WGC interface headers
#include <windows.graphics.capture.h>
#include <Windows.Graphics.Capture.Interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <vector>
#include <unordered_map>
#include <memory>
#include <chrono>
#include <cstdint>
#include <cstdio>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "windowsapp.lib")
#pragma comment(lib, "runtimeobject.lib")

using Microsoft::WRL::ComPtr;

// SDK namespace aliases
namespace WGC = ABI::Windows::Graphics::Capture;
namespace WGD = ABI::Windows::Graphics::DirectX::Direct3D11;
namespace WGI = Windows::Graphics::DirectX::Direct3D11;

// DXGI_FORMAT in newer SDKs is a scoped enum; cast from int constant
constexpr DXGI_FORMAT kFormat = static_cast<DXGI_FORMAT>(87); // DXGI_FORMAT_B8G8R8A8_UNORM

// ============================================================================
// Debug output (visible with DebugView)
// ============================================================================
#define DBG(msg) OutputDebugStringA("[wgc] " msg "\n")
#define DBG1(msg, arg) { char _b[256]; _snprintf(_b, sizeof(_b), "[wgc] " msg "\n", arg); OutputDebugStringA(_b); }
#define DBG2(msg, a1, a2) { char _b[256]; _snprintf(_b, sizeof(_b), "[wgc] " msg "\n", a1, a2); OutputDebugStringA(_b); }
#define DBG4(msg, a1, a2, a3, a4) { char _b[512]; _snprintf(_b, sizeof(_b), "[wgc] " msg "\n", a1, a2, a3, a4); OutputDebugStringA(_b); }
#define DBG5(msg, a1, a2, a3, a4, a5) { char _b[512]; _snprintf(_b, sizeof(_b), "[wgc] " msg "\n", a1, a2, a3, a4, a5); OutputDebugStringA(_b); }

// 获取当前进程私有工作集 (WorkingSet)
static SIZE_T get_process_ws() {
    PROCESS_MEMORY_COUNTERS_EX pmc = {sizeof(pmc)};
    if (GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS*)&pmc, sizeof(pmc)))
        return pmc.PrivateUsage;
    return 0;
}

// ============================================================================
// ROI struct (matches Java ROIData JNA Structure, 1:1 memory layout)
// ============================================================================
#pragma pack(push, 1)
struct ROI {
    int32_t x, y, w, h;
};
#pragma pack(pop)

// JNA callback — called from worker thread
typedef void (*JniCallback)(int id, int index, const uint8_t* data, size_t len,
                            int w, int h, int stride);

// ============================================================================
// Frame data structures
// ============================================================================
struct RoiCapture {
    int index;
    std::shared_ptr<std::vector<uint8_t>> data; // shared ownership, no pixel copy
    int rw, rh;
};

struct FrameBatch {
    std::vector<RoiCapture> rois;
};

// ============================================================================
// CaptureInstance — one per connected window
// ============================================================================
class CaptureInstance {
public:
    int id = -1;
    int max_fps = 30;
    JniCallback callback = nullptr;

    std::atomic<bool> running{true};

    // Diagnostic counters
    int64_t frame_count = 0;
    int64_t close_success = 0;
    int64_t close_fail = 0;
    int64_t frame_drop = 0;

    // D3D11
    ComPtr<ID3D11Device> d3d_device;
    ComPtr<ID3D11DeviceContext> d3d_context;

    // WGC (using official SDK types)
    ComPtr<WGC::IGraphicsCaptureItem> capture_item;
    ComPtr<WGC::IDirect3D11CaptureFramePool> frame_pool;
    ComPtr<WGC::IGraphicsCaptureSession> session;

    // Staging texture (GPU→CPU readback, reused across frames)
    ComPtr<ID3D11Texture2D> staging_tex;
    UINT staging_w = 0, staging_h = 0;
    UINT frame_w = 0, frame_h = 0;

    // ROI
    std::shared_mutex roi_mutex;
    std::vector<ROI> rois;
    std::vector<std::shared_ptr<std::vector<uint8_t>>> roi_buffers; // per-index, shared with FrameBatch

    // FrameSlot (capacity=1, backpressure)
    std::mutex frame_mutex;
    std::condition_variable frame_cv;
    std::unique_ptr<FrameBatch> frame_slot;

    // FPS limiting
    std::chrono::steady_clock::time_point last_frame_time;
    std::chrono::nanoseconds frame_interval{0};

    // Threads
    std::thread capture_thread;
    std::thread worker_thread;

    // ---- stop ----
    void stop_capture() {
        running.store(false, std::memory_order_release);

        // 1. Close session (stops frame delivery)
        if (session) {
            ComPtr<ABI::Windows::Foundation::IClosable> closable;
            if (SUCCEEDED(session.As(&closable))) {
                closable->Close();
            }
        }

        // 2. Close frame pool (releases all pooled D3D textures)
        if (frame_pool) {
            ComPtr<ABI::Windows::Foundation::IClosable> closable;
            if (SUCCEEDED(frame_pool.As(&closable))) {
                closable->Close();
            }
        }
    }

    // ---- join ----
    void join_and_cleanup() {
        frame_cv.notify_all();
        if (capture_thread.joinable()) capture_thread.join();
        if (worker_thread.joinable()) worker_thread.join();
        session.Reset();
        frame_pool.Reset();
        capture_item.Reset();
        staging_tex.Reset();
        d3d_context.Reset();
        d3d_device.Reset();
    }

    // ---- staging texture (recreate on resize) ----
    HRESULT ensure_staging(UINT w, UINT h) {
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

    // ---- process one frame ----
    void process_frame(ID3D11Texture2D* frame_texture) {
        D3D11_TEXTURE2D_DESC desc;
        frame_texture->GetDesc(&desc);
        UINT fw = desc.Width;
        UINT fh = desc.Height;
        if (fw != frame_w || fh != frame_h) { frame_w = fw; frame_h = fh; }
        if (fw == 0 || fh == 0) return;
        if (FAILED(ensure_staging(fw, fh))) return;

        d3d_context->CopyResource(staging_tex.Get(), frame_texture);

        D3D11_MAPPED_SUBRESOURCE mapped = {};
        HRESULT hr = d3d_context->Map(staging_tex.Get(), 0,
                                       D3D11_MAP_READ, 0, &mapped);
        if (FAILED(hr)) return;

        // Snapshot ROI list under shared lock
        std::vector<ROI> roi_snap;
        {
            std::shared_lock lock(roi_mutex);
            roi_snap = rois;
        }

        if (roi_buffers.size() < roi_snap.size())
            roi_buffers.resize(roi_snap.size());

        auto batch = std::make_unique<FrameBatch>();
        batch->rois.reserve(roi_snap.size());

        const uint8_t* src = static_cast<const uint8_t*>(mapped.pData);
        UINT pitch = mapped.RowPitch;
        const size_t total_bytes = (size_t)pitch * fh;

        for (size_t i = 0; i < roi_snap.size(); ++i) {
            const ROI& r = roi_snap[i];

            // 万分比 -> pixel coordinates
            int rx = (int)((int64_t)r.x * fw / 10000);
            int ry = (int)((int64_t)r.y * fh / 10000);
            int rw = (int)((int64_t)r.w * fw / 10000);
            int rh = (int)((int64_t)r.h * fh / 10000);

            // Clamp to frame (defensive, window may have resized)
            if (rx < 0) { rw += rx; rx = 0; }
            if (ry < 0) { rh += ry; ry = 0; }
            if (rx >= (int)fw) continue;
            if (ry >= (int)fh) continue;
            if (rx + rw > (int)fw) rw = (int)fw - rx;
            if (ry + rh > (int)fh) rh = (int)fh - ry;
            if (rw <= 0 || rh <= 0) continue;

            // Verify row fits within pitch
            if ((size_t)rx * 4 + (size_t)rw * 4 > (size_t)pitch) {
                rw = ((int)pitch - rx * 4) / 4;
                if (rw <= 0) continue;
            }

            const size_t buf_size = (size_t)rw * rh * 4;
            auto& buf_sp = roi_buffers[i];

            // Ensure buffer exists and is large enough (grow-only)
            if (!buf_sp) {
                buf_sp = std::make_shared<std::vector<uint8_t>>(buf_size);
            } else if (buf_sp->size() < buf_size) {
                buf_sp->resize(buf_size);
            }

            uint8_t* dst = buf_sp->data();

            // Row-by-row copy with safety bound check
            for (int y = 0; y < rh; ++y) {
                size_t src_offset = (size_t)(ry + y) * pitch + (size_t)rx * 4;
                if (src_offset + (size_t)rw * 4 > total_bytes) break;
                memcpy(dst + (size_t)y * rw * 4,
                       src + src_offset,
                       (size_t)rw * 4);
            }

            RoiCapture rc;
            rc.index = (int)i;
            rc.rw = rw;
            rc.rh = rh;
            rc.data = buf_sp; // shared_ptr copy — refcount only, zero pixel copy

            // Allocate fresh buffer for next frame (pre-sized to avoid grow path)
            buf_sp = std::make_shared<std::vector<uint8_t>>(buf_size);

            batch->rois.push_back(std::move(rc));
        }

        d3d_context->Unmap(staging_tex.Get(), 0);

        if (!batch->rois.empty()) {
            std::lock_guard lock(frame_mutex);
            if (!frame_slot) frame_slot = std::move(batch);
            frame_cv.notify_one();
        }
    }

    // ---- close a WGC frame (return to pool — CRITICAL to avoid leak) ----
    void close_frame_and_track(IUnknown* frame) {
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
            if (close_fail <= 5) DBG1("close_frame QI failed hr=0x%08lx", hr);
        }
    }

    // ---- capture loop (TryGetNextFrame polling) ----
    void capture_loop() {
        while (running.load(std::memory_order_acquire)) {
            // Backpressure: skip if worker hasn't consumed previous frame
            {
                std::lock_guard lock(frame_mutex);
                if (frame_slot) {
                    frame_drop++;
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                    continue;
                }
            }

            // FPS limit
            if (frame_interval.count() > 0) {
                auto now = std::chrono::steady_clock::now();
                if (now - last_frame_time < frame_interval) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                    continue;
                }
                last_frame_time = now;
            }

            ComPtr<WGC::IDirect3D11CaptureFrame> wgc_frame;
            HRESULT hr = frame_pool->TryGetNextFrame(&wgc_frame);
            if (FAILED(hr)) break;
            if (!wgc_frame) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }

            // get_Surface -> IDirect3DSurface
            ComPtr<WGD::IDirect3DSurface> surf;
            hr = wgc_frame->get_Surface(&surf);
            if (FAILED(hr) || !surf) { close_frame_and_track(wgc_frame.Get()); wgc_frame.Reset(); continue; }

            // IDirect3DSurface -> IDirect3DDxgiInterfaceAccess -> ID3D11Texture2D
            ComPtr<WGI::IDirect3DDxgiInterfaceAccess> dxgi_acc;
            hr = surf.As(&dxgi_acc);
            if (FAILED(hr) || !dxgi_acc) { close_frame_and_track(wgc_frame.Get()); wgc_frame.Reset(); continue; }

            ComPtr<ID3D11Texture2D> tex;
            hr = dxgi_acc->GetInterface(__uuidof(ID3D11Texture2D),
                                        reinterpret_cast<void**>(tex.GetAddressOf()));
            if (FAILED(hr) || !tex) { close_frame_and_track(wgc_frame.Get()); wgc_frame.Reset(); continue; }

            process_frame(tex.Get());

            // CRITICAL: return frame to pool, then release COM reference
            close_frame_and_track(wgc_frame.Get());
            wgc_frame.Reset();

            // Diagnostic: log every 500 frames
            frame_count++;
            if (frame_count % 500 == 0) {
                SIZE_T ws_mb = get_process_ws() / (1024 * 1024);
                DBG5("id=%d f=%lld mem=%zuMB cls=%lld drop=%lld",
                     id, (long long)frame_count, ws_mb,
                     (long long)close_success, (long long)frame_drop);
            }
        }

        // Capture loop exited — log final state
        {
            SIZE_T ws_mb = get_process_ws() / (1024 * 1024);
            DBG4("capture_loop exit id=%d frames=%lld ws=%zuMB drops=%lld",
                 id, (long long)frame_count, ws_mb, (long long)frame_drop);
        }

        running.store(false, std::memory_order_release);
        frame_cv.notify_all();
    }

    // ---- worker loop (JNI callback dispatch) ----
    void worker_loop() {
        while (running.load(std::memory_order_acquire)) {
            std::unique_ptr<FrameBatch> batch;
            {
                std::unique_lock lock(frame_mutex);
                frame_cv.wait_for(lock, std::chrono::milliseconds(100),
                    [this] {
                        return frame_slot != nullptr ||
                               !running.load(std::memory_order_acquire);
                    });
                if (!running.load(std::memory_order_acquire)) break;
                batch = std::move(frame_slot);
            }
            if (!batch) continue;

            for (auto& rc : batch->rois) {
                callback(id, rc.index,
                         rc.data->data(), rc.data->size(),
                         rc.rw, rc.rh, rc.rw * 4);
            }
        }
        // Disconnect signal: index=-1, stride=-1
        callback(id, -1, nullptr, 0, 0, 0, -1);
    }
};

// ============================================================================
// Global Manager
// ============================================================================
static std::mutex g_mgr_mutex;
static std::unordered_map<int, std::unique_ptr<CaptureInstance>> g_instances;
static int g_next_id = 1;
static LONG g_ro_init_count = 0;

static void ro_init() {
    if (InterlockedIncrement(&g_ro_init_count) == 1)
        RoInitialize(RO_INIT_MULTITHREADED);
}
static void ro_uninit() {
    if (InterlockedDecrement(&g_ro_init_count) == 0)
        RoUninitialize();
}

// HString helper — RAII wrapper
struct HString {
    HSTRING hs = nullptr;
    HString(const wchar_t* s) { WindowsCreateString(s, (UINT32)wcslen(s), &hs); }
    ~HString() { if (hs) WindowsDeleteString(hs); }
    HSTRING get() const { return hs; }
};

// Get activation factory by class name
template<typename T>
static HRESULT get_activation_factory(const wchar_t* name, ComPtr<T>& out) {
    HString hs(name);
    ComPtr<IUnknown> unk;
    HRESULT hr = RoGetActivationFactory(hs.get(), __uuidof(T),
                    reinterpret_cast<void**>(unk.GetAddressOf()));
    if (FAILED(hr)) return hr;
    return unk.As(&out);
}

// CreateDirect3D11DeviceFromDXGIDevice — loaded dynamically from d3d11.dll
static HRESULT create_winrt_device(IDXGIDevice* dxgi_dev,
                                    ComPtr<WGD::IDirect3DDevice>& out) {
    using PFN = HRESULT(WINAPI*)(IDXGIDevice*, IUnknown**);
    static auto pfn = []() -> PFN {
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

// ============================================================================
// Exports (JNA-compatible C ABI)
// ============================================================================
extern "C" {

__declspec(dllexport) int create(int64_t hwnd_i64, int max_fps, JniCallback cb) {
    DBG("create() called");
    if (!cb) { DBG("null callback"); return -1; }

    HWND hwnd = reinterpret_cast<HWND>(hwnd_i64);
    DBG1("hwnd=0x%llx", (unsigned long long)hwnd_i64);
    if (!IsWindow(hwnd)) { DBG("IsWindow failed"); return -2; }

    ro_init();

    auto inst = std::make_unique<CaptureInstance>();
    inst->max_fps = max_fps;
    inst->callback = cb;
    {
        std::lock_guard lk(g_mgr_mutex);
        inst->id = g_next_id++;
    }
    int id = inst->id;
    if (max_fps > 0)
        inst->frame_interval = std::chrono::nanoseconds(
            (int64_t)(1.0 / max_fps * 1'000'000'000));

    HRESULT hr;

    // 1. D3D11 device
    hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            nullptr, 0, D3D11_SDK_VERSION,
            &inst->d3d_device, nullptr, &inst->d3d_context);
    if (FAILED(hr)) { DBG1("D3D11CreateDevice failed hr=0x%08lx", hr); ro_uninit(); return -3; }
    DBG("D3D11 device OK");

    // 2. DXGI device → WinRT IDirect3DDevice
    ComPtr<IDXGIDevice> dxgi_dev;
    hr = inst->d3d_device.As(&dxgi_dev);
    if (FAILED(hr)) { DBG1("IDXGIDevice QI failed hr=0x%08lx", hr); ro_uninit(); return -4; }

    ComPtr<WGD::IDirect3DDevice> winrt_device;
    hr = create_winrt_device(dxgi_dev.Get(), winrt_device);
    if (FAILED(hr) || !winrt_device) { DBG1("CreateWinRTDevice failed hr=0x%08lx", hr); ro_uninit(); return -5; }
    DBG("WinRT device OK");

    // 3. IGraphicsCaptureItemInterop → CreateForWindow
    ComPtr<IGraphicsCaptureItemInterop> interop;
    hr = get_activation_factory(
        L"Windows.Graphics.Capture.GraphicsCaptureItem", interop);
    if (FAILED(hr) || !interop) { DBG1("CaptureItem factory failed hr=0x%08lx", hr); ro_uninit(); return -6; }

    ComPtr<IUnknown> item_unk;
    hr = interop->CreateForWindow(hwnd, __uuidof(WGC::IGraphicsCaptureItem),
                                   reinterpret_cast<void**>(item_unk.GetAddressOf()));
    if (FAILED(hr) || !item_unk) { DBG1("CreateForWindow failed hr=0x%08lx", hr); ro_uninit(); return -7; }
    DBG("CreateForWindow OK");

    hr = item_unk.As(&inst->capture_item);
    if (FAILED(hr)) { DBG1("capture item QI failed hr=0x%08lx", hr); ro_uninit(); return -8; }
    DBG("capture item OK");

    // 4. Get window size
    ABI::Windows::Graphics::SizeInt32 sz = {};
    inst->capture_item->get_Size(&sz);
    INT32 iw = sz.Width, ih = sz.Height;
    DBG2("get_Size -> %dx%d", iw, ih);
    if (iw <= 0 || ih <= 0) {
        RECT r;
        if (GetClientRect(hwnd, &r)) { iw = r.right - r.left; ih = r.bottom - r.top; }
        else { iw = 1920; ih = 1080; }
        DBG2("fallback size %dx%d", iw, ih);
    }
    inst->frame_w = (UINT)iw;
    inst->frame_h = (UINT)ih;

    // 5. Frame pool — prefer CreateFreeThreaded, fallback to Create
    ComPtr<WGC::IDirect3D11CaptureFramePoolStatics2> pool_s2;
    hr = get_activation_factory(
        L"Windows.Graphics.Capture.Direct3D11CaptureFramePool", pool_s2);

    if (SUCCEEDED(hr) && pool_s2) {
        DBG("using CreateFreeThreaded");
        hr = pool_s2->CreateFreeThreaded(winrt_device.Get(),
                 (ABI::Windows::Graphics::DirectX::DirectXPixelFormat)kFormat,
                 2, sz, &inst->frame_pool);
    } else {
        ComPtr<WGC::IDirect3D11CaptureFramePoolStatics> pool_s;
        hr = get_activation_factory(
            L"Windows.Graphics.Capture.Direct3D11CaptureFramePool", pool_s);
        if (FAILED(hr) || !pool_s) { DBG1("pool factory failed hr=0x%08lx", hr); ro_uninit(); return -9; }
        DBG("fallback to Create");
        hr = pool_s->Create(winrt_device.Get(),
                (ABI::Windows::Graphics::DirectX::DirectXPixelFormat)kFormat,
                2, sz, &inst->frame_pool);
    }
    if (FAILED(hr) || !inst->frame_pool) { DBG1("frame pool failed hr=0x%08lx", hr); ro_uninit(); return -10; }
    DBG("frame pool OK");

    // 6. Capture session
    hr = inst->frame_pool->CreateCaptureSession(inst->capture_item.Get(), &inst->session);
    if (FAILED(hr) || !inst->session) { DBG1("CreateCaptureSession failed hr=0x%08lx", hr); ro_uninit(); return -11; }
    DBG("session OK");

    // Disable yellow border (IGraphicsCaptureSession3)
    {
        ComPtr<WGC::IGraphicsCaptureSession3> session3;
        if (SUCCEEDED(inst->session.As(&session3))) {
            session3->put_IsBorderRequired(false);
            DBG("border disabled");
        }
    }

    hr = inst->session->StartCapture();
    if (FAILED(hr)) { DBG1("StartCapture failed hr=0x%08lx", hr); ro_uninit(); return -12; }
    DBG("StartCapture OK");

    // 7. Start threads
    {
        std::lock_guard lk(g_mgr_mutex);
        g_instances[id] = std::move(inst);
    }
    CaptureInstance* raw = g_instances[id].get();
    raw->worker_thread = std::thread([raw]() { raw->worker_loop(); });
    raw->capture_thread = std::thread([raw]() { raw->capture_loop(); });

    DBG1("create success, id=%d", id);
    return id;
}

__declspec(dllexport) void set_rois(int id, const ROI* ptr, size_t len) {
    std::lock_guard lk(g_mgr_mutex);
    auto it = g_instances.find(id);
    if (it == g_instances.end()) return;
    auto* inst = it->second.get();
    std::unique_lock roi_lk(inst->roi_mutex);
    inst->rois.clear();
    if (ptr && len > 0) inst->rois.assign(ptr, ptr + len);
}

__declspec(dllexport) void stop(int id) {
    std::unique_ptr<CaptureInstance> inst;
    {
        std::lock_guard lk(g_mgr_mutex);
        auto it = g_instances.find(id);
        if (it == g_instances.end()) return;
        inst = std::move(it->second);
        g_instances.erase(it);
    }
    inst->stop_capture();
    inst->join_and_cleanup();
    ro_uninit(); // balance ro_init() in create()
}

} // extern "C"

// ============================================================================
// DllMain
// ============================================================================
BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hinst);
    }
    // Do NOT call RoUninitialize here — loader lock is held at process exit,
    // COM teardown would deadlock. Reference counting in create()/stop() handles it.
    return TRUE;
}
