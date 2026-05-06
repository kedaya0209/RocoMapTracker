#![allow(non_snake_case)]

#[macro_use]
extern crate lazy_static;

use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, RwLock};
use std::thread;
use std::time::{Duration, Instant};

use windows_capture::{
    capture::{Context, GraphicsCaptureApiHandler},
    frame::Frame,
    graphics_capture_api::InternalCaptureControl,
    settings::{
        ColorFormat,
        CursorCaptureSettings,
        DirtyRegionSettings,
        DrawBorderSettings,
        MinimumUpdateIntervalSettings,
        SecondaryWindowSettings,
        Settings,
    },
    window::Window,
};

type JniCallback =
    extern "C" fn(
        id: i32,
        index: i32,
        data: *const u8,
        len: usize,
        w: i32,
        h: i32,
        stride: i32,
    );

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ROI {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

struct RoiCapture {
    index: i32,
    data: Vec<u8>,
    rw: i32,
    rh: i32,
}

struct FrameBatch {
    rois: Vec<RoiCapture>,
}

struct CaptureInstance {
    running: Arc<AtomicBool>,

    roi_list: Arc<RwLock<Vec<ROI>>>,

    frame_slot: Arc<(Mutex<Option<FrameBatch>>, Condvar)>,

    max_fps: i32,
}

unsafe impl Send for CaptureInstance {}
unsafe impl Sync for CaptureInstance {}

lazy_static! {
    static ref MANAGER: Mutex<HashMap<i32, Arc<CaptureInstance>>> =
        Mutex::new(HashMap::new());

    static ref NEXT_ID: Mutex<i32> =
        Mutex::new(1);
}

fn worker_loop(
    id: i32,
    running: Arc<AtomicBool>,
    slot: Arc<(Mutex<Option<FrameBatch>>, Condvar)>,
    cb: JniCallback,
) {
    let (lock, cvar) = &*slot;

    while running.load(Ordering::SeqCst) {
        let batch = {
            let mut guard =
                lock.lock().unwrap();

            let mut result = None;

            while result.is_none()
                && running.load(Ordering::SeqCst)
            {
                result = guard.take();

                if result.is_none() {
                    let wait = cvar.wait_timeout(
                        guard,
                        Duration::from_millis(100),
                    );

                    guard = wait.unwrap().0;
                }
            }

            result
        };

        let mut batch = match batch {
            Some(v) => v,
            None => break,
        };

        for roi in &batch.rois {
            cb(
                id,
                roi.index,
                roi.data.as_ptr(),
                roi.data.len(),
                roi.rw,
                roi.rh,
                roi.rw * 4,
            );
        }

        // 自动 drop Vec<u8>
        batch.rois.clear();
    }

    cb(
        id,
        -1,
        std::ptr::null(),
        0,
        0,
        0,
        -1,
    );
}

struct CaptureHandler {
    running: Arc<AtomicBool>,

    roi_list: Arc<RwLock<Vec<ROI>>>,

    frame_slot: Arc<(Mutex<Option<FrameBatch>>, Condvar)>,

    last_frame: Instant,

    frame_interval: Duration,
}

impl GraphicsCaptureApiHandler
    for CaptureHandler
{
    type Flags = (
        Arc<AtomicBool>,
        Arc<RwLock<Vec<ROI>>>,
        Arc<(Mutex<Option<FrameBatch>>, Condvar)>,
        i32,
    );

    type Error =
        Box<dyn std::error::Error + Send + Sync>;

    fn new(
        ctx: Context<Self::Flags>,
    ) -> Result<Self, Self::Error> {
        let fps = ctx.flags.3;

        let interval = if fps > 0 {
            Duration::from_secs_f64(
                1.0 / fps as f64,
            )
        } else {
            Duration::ZERO
        };

        Ok(Self {
            running: ctx.flags.0,

            roi_list: ctx.flags.1,

            frame_slot: ctx.flags.2,

            last_frame: Instant::now(),

            frame_interval: interval,
        })
    }

    fn on_frame_arrived(
        &mut self,
        frame: &mut Frame,
        control: InternalCaptureControl,
    ) -> Result<(), Self::Error> {
        if !self.running.load(Ordering::SeqCst) {
            control.stop();

            return Ok(());
        }

        // FPS limit
        if self.frame_interval != Duration::ZERO {
            let now = Instant::now();

            if now.duration_since(self.last_frame)
                < self.frame_interval
            {
                return Ok(());
            }

            self.last_frame = now;
        }

        // worker busy => drop frame
        if self.frame_slot
            .0
            .lock()
            .unwrap()
            .is_some()
        {
            return Ok(());
        }

        let width =
            frame.width() as i32;

        let height =
            frame.height() as i32;

        let frame_buffer =
            frame.buffer()?;

        let mut nopadding =
            Vec::new();

        let bytes =
            frame_buffer.as_nopadding_buffer(
                &mut nopadding,
            );

        let stride =
            width as usize * 4;

        let rois =
            self.roi_list.read().unwrap();

        if rois.is_empty() {
            return Ok(());
        }

        let mut roi_caps =
            Vec::with_capacity(rois.len());

        for (idx, roi) in
            rois.iter().enumerate()
        {
            let rx =
                ((roi.x as i64
                    * width as i64
                    / 10000)
                    as i32)
                    .max(0);

            let ry =
                ((roi.y as i64
                    * height as i64
                    / 10000)
                    as i32)
                    .max(0);

            let rw =
                ((roi.w as i64
                    * width as i64
                    / 10000)
                    as i32)
                    .min(width - rx);

            let rh =
                ((roi.h as i64
                    * height as i64
                    / 10000)
                    as i32)
                    .min(height - ry);

            if rw <= 0 || rh <= 0 {
                continue;
            }

            let size =
                rw as usize
                    * rh as usize
                    * 4;

            let mut data =
                vec![0u8; size];

            for y in 0..rh as usize {
                let src_y =
                    ry as usize + y;

                let src_start =
                    src_y * stride
                        + rx as usize * 4;

                let src_end =
                    src_start
                        + rw as usize * 4;

                let dst_start =
                    y * rw as usize * 4;

                data[dst_start
                    ..dst_start
                        + rw as usize * 4]
                    .copy_from_slice(
                        &bytes
                            [src_start..src_end],
                    );
            }

            roi_caps.push(RoiCapture {
                index: idx as i32,
                data,
                rw,
                rh,
            });
        }

        if !roi_caps.is_empty() {
            let (lock, cvar) =
                &*self.frame_slot;

            let mut slot =
                lock.lock().unwrap();

            *slot = Some(FrameBatch {
                rois: roi_caps,
            });

            cvar.notify_one();
        }

        Ok(())
    }

    fn on_closed(
        &mut self,
    ) -> Result<(), Self::Error> {
        self.running.store(
            false,
            Ordering::SeqCst,
        );

        self.frame_slot
            .1
            .notify_all();

        Ok(())
    }
}

#[no_mangle]
pub extern "C" fn create(
    hwnd_i64: i64,
    max_fps: i32,
    cb: JniCallback,
) -> i32 {
    let id = {
        let mut g =
            NEXT_ID.lock().unwrap();

        let v = *g;

        *g += 1;

        v
    };

    let running =
        Arc::new(AtomicBool::new(true));

    let roi_list =
        Arc::new(RwLock::new(Vec::new()));

    let frame_slot =
        Arc::new((
            Mutex::new(None),
            Condvar::new(),
        ));

    let inst =
        Arc::new(CaptureInstance {
            running: running.clone(),

            roi_list: roi_list.clone(),

            frame_slot: frame_slot.clone(),

            max_fps,
        });

    MANAGER
        .lock()
        .unwrap()
        .insert(id, inst);

    {
        let worker_running =
            running.clone();

        let worker_slot =
            frame_slot.clone();

        thread::spawn(move || {
            worker_loop(
                id,
                worker_running,
                worker_slot,
                cb,
            );
        });
    }

    {
        thread::spawn(move || {
            let window =
                Window::from_raw_hwnd(
                    hwnd_i64
                        as *mut c_void,
                );

            let settings =
                Settings::new(
                    window,

                    CursorCaptureSettings::WithoutCursor,

                    DrawBorderSettings::WithoutBorder,

                    SecondaryWindowSettings::Default,

                    MinimumUpdateIntervalSettings::Default,

                    DirtyRegionSettings::Default,

                    ColorFormat::Bgra8,

                    (
                        running,
                        roi_list,
                        frame_slot,
                        max_fps,
                    ),
                );

            let _ =
                CaptureHandler::start(
                    settings,
                );
        });
    }

    id
}

#[no_mangle]
pub extern "C" fn set_rois(
    id: i32,
    ptr: *const ROI,
    len: usize,
) {
    let mgr =
        MANAGER.lock().unwrap();

    let inst = match mgr.get(&id) {
        Some(v) => v,
        None => return,
    };

    let mut list =
        inst.roi_list.write().unwrap();

    list.clear();

    if !ptr.is_null() && len > 0 {
        unsafe {
            list.extend_from_slice(
                std::slice::from_raw_parts(
                    ptr,
                    len,
                ),
            );
        }
    }
}

#[no_mangle]
pub extern "C" fn stop(id: i32) {
    let mut mgr =
        MANAGER.lock().unwrap();

    if let Some(inst) =
        mgr.remove(&id)
    {
        inst.running.store(
            false,
            Ordering::SeqCst,
        );

        inst.frame_slot
            .1
            .notify_all();
    }
}
