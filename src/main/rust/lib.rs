#![allow(non_snake_case)]

#[macro_use]
extern crate lazy_static;

use std::sync::{Arc, Mutex, RwLock, Condvar};
use std::sync::atomic::{AtomicBool, Ordering};
use std::collections::HashMap;
use std::thread;
use std::time::Duration;

use windows::core::*;
use windows::Foundation::TypedEventHandler;
use windows::Graphics::Capture::*;
use windows::Graphics::DirectX::*;
use windows::Graphics::DirectX::Direct3D11::*;
use windows::Win32::Foundation::*;
use windows::Win32::Graphics::Direct3D::*;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::*;
use windows::Win32::Graphics::Dxgi::Common::*;
use windows::Win32::System::WinRT::Direct3D11::*;
use windows::Win32::System::WinRT::Graphics::Capture::*;
use windows::Win32::UI::WindowsAndMessaging::*;

// ================= 类型定义 =================

type JniCallback = extern "C" fn(id: i32, index: i32, data: *const u8, len: usize, w: i32, h: i32, code: i32);

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ROI {
    pub x: i32, pub y: i32, pub w: i32, pub h: i32,
}

struct FrameData {
    data: Vec<u8>,
    w: i32,
    h: i32,
    stride: usize,
}

struct GpuContext {
    device: ID3D11Device,
    context: Mutex<ID3D11DeviceContext>, // 必须加锁，Context 线程不安全
}
unsafe impl Send for GpuContext {}
unsafe impl Sync for GpuContext {}

struct CaptureInstance {
    id: i32,
    running: Arc<AtomicBool>,
    roi_list: Arc<RwLock<Vec<ROI>>>,
    frame_slot: Arc<(Mutex<Option<FrameData>>, Condvar)>,
    gpu: Arc<GpuContext>,
    // 存储 Texture 以及当前的尺寸，用于自动重置
    staging: Mutex<Option<(ID3D11Texture2D, u32, u32)>>,
    session: Mutex<Option<GraphicsCaptureSession>>,
}

// ================= 全局管理器 =================

lazy_static! {
    static ref MANAGER: Mutex<HashMap<i32, Arc<CaptureInstance>>> = Mutex::new(HashMap::new());
    static ref NEXT_ID: Mutex<i32> = Mutex::new(1);
}

// ================= 工具函数 =================

unsafe fn create_d3d() -> (ID3D11Device, ID3D11DeviceContext) {
    let mut device: Option<ID3D11Device> = None;
    D3D11CreateDevice(
        None, D3D_DRIVER_TYPE_HARDWARE, None, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        None, D3D11_SDK_VERSION, Some(&mut device), None, None,
    ).expect("Failed to create D3D11 Device");

    let dev = device.unwrap();
    let ctx = dev.GetImmediateContext().expect("Failed to get Context");
    (dev, ctx)
}

// ================= 导出 API =================

#[no_mangle]
pub extern "C" fn create(hwnd_i64: i64, cb: JniCallback) -> i32 {
    let hwnd = HWND(hwnd_i64 as _);
    if unsafe { !IsWindow(hwnd).as_bool() } { return -1; }

    let mut id_gen = NEXT_ID.lock().unwrap();
    let id = *id_gen;
    *id_gen += 1;

    let running = Arc::new(AtomicBool::new(true));
    let roi_list = Arc::new(RwLock::new(vec![]));
    let frame_slot = Arc::new((Mutex::new(None), Condvar::new()));

    unsafe {
        let (device, context) = create_d3d();
        let gpu = Arc::new(GpuContext { device, context: Mutex::new(context) });

        let dxgi: IDXGIDevice = gpu.device.cast().unwrap();
        let inspectable = CreateDirect3D11DeviceFromDXGIDevice(&dxgi).unwrap();
        let winrt_device: IDirect3DDevice = inspectable.cast().unwrap();

        let interop = windows::core::factory::<GraphicsCaptureItem, IGraphicsCaptureItemInterop>().unwrap();
        let item: GraphicsCaptureItem = interop.CreateForWindow(hwnd).unwrap();
        let size = item.Size().unwrap();

        let pool = Direct3D11CaptureFramePool::CreateFreeThreaded(
            &winrt_device, DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size,
        ).unwrap();

        let session = pool.CreateCaptureSession(&item).unwrap();
        let _ = session.SetIsBorderRequired(false); // 取消边框

        let inst = Arc::new(CaptureInstance {
            id,
            running: running.clone(),
            roi_list: roi_list.clone(),
            frame_slot: frame_slot.clone(),
            gpu: gpu.clone(),
            staging: Mutex::new(None),
            session: Mutex::new(Some(session.clone())),
        });

        // 窗口关闭监听
        let r_closed = running.clone();
        let slot_closed = frame_slot.clone();
        item.Closed(&TypedEventHandler::new(move |_, _| {
            r_closed.store(false, Ordering::SeqCst);
            slot_closed.1.notify_all();
            Ok(())
        })).unwrap();

        // 生产者回调
        let running_cb = running.clone();
        let frame_slot_cb = frame_slot.clone();
        let inst_cb = inst.clone();

        pool.FrameArrived(&TypedEventHandler::new(move |p: &Option<Direct3D11CaptureFramePool>, _| {
            if !running_cb.load(Ordering::SeqCst) { return Ok(()); }

            let pool_ref = p.as_ref().unwrap();
            if let Ok(frame) = pool_ref.TryGetNextFrame() {
                let f_size = frame.ContentSize()?;
                let surface = frame.Surface()?;
                let access: IDirect3DDxgiInterfaceAccess = surface.cast()?;
                let tex: ID3D11Texture2D = access.GetInterface()?;

                // 1. 检查 Staging Texture 尺寸并自动重置
                let mut staging_lock = inst_cb.staging.lock().unwrap();
                let mut needs_create = staging_lock.is_none();
                if let Some((_, w, h)) = &*staging_lock {
                    if *w != f_size.Width as u32 || *h != f_size.Height as u32 {
                        needs_create = true;
                    }
                }

                if needs_create {
                    let desc = D3D11_TEXTURE2D_DESC {
                        Width: f_size.Width as u32, Height: f_size.Height as u32,
                        MipLevels: 1, ArraySize: 1, Format: DXGI_FORMAT_B8G8R8A8_UNORM,
                        SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                        Usage: D3D11_USAGE_STAGING, CPUAccessFlags: D3D11_CPU_ACCESS_READ.0 as u32,
                        ..Default::default()
                    };
                    let mut t = None;
                    inst_cb.gpu.device.CreateTexture2D(&desc, None, Some(&mut t)).unwrap();
                    *staging_lock = Some((t.unwrap(), f_size.Width as u32, f_size.Height as u32));
                }

                let (staging_tex, _, _) = staging_lock.as_ref().unwrap();

                // 2. 线程安全地访问 Context
                let ctx = inst_cb.gpu.context.lock().unwrap();
                ctx.CopyResource(staging_tex, &tex);

                let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
                if ctx.Map(staging_tex, 0, D3D11_MAP_READ, 0, Some(&mut mapped)).is_ok() {
                    let stride = mapped.RowPitch as usize;
                    let h = f_size.Height as usize;
                    let required_size = stride * h;

                    let (lock, cvar) = &*frame_slot_cb;
                    let mut slot = lock.lock().unwrap();

                    // 3. 内存复用：如果 slot 里已有 buffer 且大小合适，直接拿来用，不新建
                    let mut buffer = if let Some(mut old_frame) = slot.take() {
                        if old_frame.data.len() < required_size { old_frame.data.resize(required_size, 0); }
                        old_frame.data
                    } else {
                        vec![0u8; required_size]
                    };

                    std::ptr::copy_nonoverlapping(mapped.pData as *const u8, buffer.as_mut_ptr(), required_size);
                    ctx.Unmap(staging_tex, 0);

                    *slot = Some(FrameData { data: buffer, w: f_size.Width, h: f_size.Height, stride });
                    cvar.notify_one();
                }
            }
            Ok(())
        })).unwrap();

        session.StartCapture().unwrap();
        MANAGER.lock().unwrap().insert(id, inst.clone());

        thread::spawn(move || {
            worker_loop(id, running, frame_slot, roi_list, cb, inst.clone());
        });
    }
    id
}

#[no_mangle]
pub extern "C" fn set_rois(id: i32, ptr: *const ROI, len: usize) {
    let mgr = MANAGER.lock().unwrap();
    if let Some(inst) = mgr.get(&id) {
        let mut list = inst.roi_list.write().unwrap();
        list.clear();
        if !ptr.is_null() && len > 0 {
            let slice = unsafe { std::slice::from_raw_parts(ptr, len) };
            list.extend_from_slice(slice);
        }
    }
}

#[no_mangle]
pub extern "C" fn stop(id: i32) {
    let mut mgr = MANAGER.lock().unwrap();
    if let Some(inst) = mgr.remove(&id) {
        inst.running.store(false, Ordering::SeqCst);
        let _ = inst.session.lock().unwrap().take().map(|s| s.Close());
        inst.frame_slot.1.notify_all();
    }
}

// ================= 消费者逻辑 =================

fn worker_loop(id: i32, running: Arc<AtomicBool>, slot_arc: Arc<(Mutex<Option<FrameData>>, Condvar)>, rois_arc: Arc<RwLock<Vec<ROI>>>, cb: JniCallback, _inst: Arc<CaptureInstance>) {
    let (lock, cvar) = &*slot_arc;

    // 【关键】本地复用缓冲，不需要 Mutex，不需要频繁 resize
    let mut gray_buffer = Vec::with_capacity(1920 * 1080);

    while running.load(Ordering::SeqCst) {
        let mut slot = lock.lock().unwrap();
        while running.load(Ordering::SeqCst) && slot.is_none() {
            slot = cvar.wait_timeout(slot, Duration::from_millis(100)).unwrap().0;
        }
        if !running.load(Ordering::SeqCst) { break; }

        if let Some(frame) = slot.take() {
            drop(slot); // 尽早释放锁
            process_frame(id, &frame, &rois_arc, cb, &mut gray_buffer);
        }
    }

    // 通知外部已停止
    cb(id, -1, std::ptr::null(), 0, 0, 0, -1);
}

fn process_frame(id: i32, frame: &FrameData, rois_arc: &Arc<RwLock<Vec<ROI>>>, cb: JniCallback, gray_buf: &mut Vec<u8>) {
    let rois = rois_arc.read().unwrap();
    for (i, roi) in rois.iter().enumerate() {
        let rx = ((roi.x as i64 * frame.w as i64 / 10000) as i32).max(0);
        let ry = ((roi.y as i64 * frame.h as i64 / 10000) as i32).max(0);
        let rw = ((roi.w as i64 * frame.w as i64 / 10000) as i32).min(frame.w - rx);
        let rh = ((roi.h as i64 * frame.h as i64 / 10000) as i32).min(frame.h - ry);

        if rw <= 0 || rh <= 0 { continue; }

        let required_size = (rw * rh) as usize;
        if gray_buf.len() < required_size {
            gray_buf.resize(required_size, 0);
        }

        let gray_slice = &mut gray_buf[..required_size];

        // 极致性能：整数移位灰度转换
        for y in 0..rh {
            let src_offset = (ry + y) as usize * frame.stride + rx as usize * 4;
            let dst_offset = (y * rw) as usize;
            for x in 0..rw as usize {
                let idx = src_offset + x * 4;
                let b = frame.data[idx] as u32;
                let g = frame.data[idx+1] as u32;
                let r = frame.data[idx+2] as u32;

                // (R*77 + G*150 + B*29) >> 8 是工业级标准灰度算法的快速实现
                gray_slice[dst_offset + x] = ((r * 77 + g * 150 + b * 29) >> 8) as u8;
            }
        }

        // JNA getByteArray 是同步的，此处直接传指针非常安全
        cb(id, i as i32, gray_slice.as_ptr(), gray_slice.len(), rw, rh, 0);
    }
}