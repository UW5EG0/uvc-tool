#include <time.h>
#include <jni.h>
#include <android/log.h>
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <libuvc/libuvc_internal.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>

#define TAG "UvcTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static uvc_context_t      *uvc_ctx  = NULL;
static uvc_device_handle_t *devh    = NULL;
static uvc_stream_ctrl_t   ctrl;
static bool                 streaming = false;

static uint8_t  *frame_bufs[2]  = {NULL, NULL};  // double buffer, index by seq&1
static uint32_t  frame_drop_cnt = 0;
static JavaVM   *jvm            = NULL;
static jobject   j_camera_obj   = NULL;  // global ref to UvcCamera instance
static jmethodID j_on_frame     = NULL;
static jmethodID j_on_button    = NULL;
static jmethodID j_on_glitch    = NULL;
static int       glitch_streak  = 0;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_ua_radiomaniac_uvctool_UvcCamera_nativeInit(JNIEnv *env, jobject thiz) {
    if (j_camera_obj) { env->DeleteGlobalRef(j_camera_obj); }
    j_camera_obj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    j_on_frame  = env->GetMethodID(cls, "onFrame",  "(III)V");
    j_on_button = env->GetMethodID(cls, "onButton", "(I)V");
    j_on_glitch = env->GetMethodID(cls, "onGlitch", "(I)V");

    // Clean up any previous state before reinitializing
    if (devh) {
        if (streaming) { uvc_stop_streaming(devh); streaming = false; }
        uvc_close(devh); devh = NULL;
    }
    frame_bufs[0] = frame_bufs[1] = NULL;
    frame_drop_cnt = 0;
    if (uvc_ctx) { uvc_exit(uvc_ctx); uvc_ctx = NULL; }

    // NO_DEVICE_DISCOVERY must be set before libusb_init (which uvc_init calls internally)
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);
    // libusb logs to logcat under tag "libusb" on Android; warnings+errors only
    libusb_set_option(NULL, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
    LOGI("libusb_set_option NO_DEVICE_DISCOVERY done");

    uvc_error_t r = uvc_init(&uvc_ctx, NULL);
    LOGI("uvc_init: %d (%s)", r, uvc_strerror(r));
}

static void status_cb(uvc_status_class status_class, int event, int selector,
                      uvc_status_attribute attribute, void *data, size_t data_len,
                      void *user_ptr) {
    if (data_len >= 1) {
        uint8_t *d = (uint8_t*)data;
        LOGI("status: class=%d event=%d sel=%d attr=%d data=%02x %02x %02x %02x",
             (int)status_class, event, selector, (int)attribute,
             data_len>0?d[0]:0, data_len>1?d[1]:0,
             data_len>2?d[2]:0, data_len>3?d[3]:0);
        // bStatusType=2(VIDEO_CONTROL), bEvent=0, bValue=1/0 → button press/release
        if ((int)status_class == 2 && event == 0 && data_len >= 1) {
            JNIEnv *env; bool attached = false;
            if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
                jvm->AttachCurrentThread(&env, NULL);
                attached = true;
            }
            env->CallVoidMethod((jobject)user_ptr, j_on_button, (jint)d[0]);
            if (attached) jvm->DetachCurrentThread();
        }
    }
}

// Snapshot button on the camera. The endoscope sends it as a VideoStreaming-interface
// status packet ("02 01 00 01" press / "... 00" release); libuvc routes those to the
// button callback, not to the status callback above.
static void button_cb(int button, int state, void *user_ptr) {
    LOGI("button: intf=%d state=%d", button, state);
    JNIEnv *env; bool attached = false;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread(&env, NULL);
        attached = true;
    }
    env->CallVoidMethod((jobject)user_ptr, j_on_button, (jint)state);
    if (attached) jvm->DetachCurrentThread();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ua_radiomaniac_uvctool_UvcCamera_nativeOpen(JNIEnv *env, jobject, jint fd) {
    // Close stale handle before re-opening (avoids BUSY on repeated open)
    if (devh) {
        if (streaming) { uvc_stop_streaming(devh); streaming = false; }
        uvc_close(devh);
        devh = NULL;
    }
    frame_bufs[0] = frame_bufs[1] = NULL;
    frame_drop_cnt = 0;
    uvc_error_t r = uvc_wrap(fd, uvc_ctx, &devh);
    LOGI("uvc_wrap fd=%d: %d (%s)", fd, r, uvc_strerror(r));
    if (r != UVC_SUCCESS) return JNI_FALSE;
    uvc_set_status_callback(devh, status_cb, j_camera_obj);
    uvc_set_button_callback(devh, button_cb, j_camera_obj);
    return JNI_TRUE;
}

// --- split-frame stitching ---------------------------------------------------
// Some host controllers (seen on CUBOT KingKong Mini 3, 320x240) make libuvc cut
// every frame in two: ~96% of the frame, then the rest ~1 ms later. Glue the
// pieces back together instead of dropping both.
static uint8_t  *stitch_buf     = NULL;
static size_t    stitch_cap     = 0;
static size_t    stitch_len     = 0;     // bytes of a pending head piece, 0 = none
static int64_t   stitch_t_us    = 0;
static uint32_t  stitch_ok_cnt  = 0;
static uint32_t  deliver_cnt    = 0;     // alternates the double buffer

static int64_t now_us() {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

static void deliver_frame(const uint8_t *data, int w, int h, uint32_t seq, void *user_ptr);

static void frame_cb(uvc_frame_t *frame, void *user_ptr) {
    size_t expected = (size_t)frame->width * frame->height * 2;
    if (frame->data_bytes == expected) {
        stitch_len = 0;
        deliver_frame((const uint8_t *)frame->data, frame->width, frame->height, frame->sequence, user_ptr);
        return;
    }

    if (stitch_cap < expected) {
        free(stitch_buf);
        stitch_buf = (uint8_t *)malloc(expected);
        stitch_cap = stitch_buf ? expected : 0;
        stitch_len = 0;
        if (!stitch_buf) return;
    }
    int64_t t = now_us();
    size_t n = frame->data_bytes;
    size_t tol = expected / 32;                    // ~3% slack on the total

    if (n >= expected / 2 && n < expected) {
        // head piece: remember it, wait for the tail
        if (stitch_len >= expected - tol) {
            // previous head never got its tail but is nearly whole: show it anyway
            deliver_frame(stitch_buf, frame->width, frame->height, frame->sequence, user_ptr);
        }
        memcpy(stitch_buf, frame->data, n);
        stitch_len = n;
        stitch_t_us = t;
        return;
    }

    if (n < expected / 2 && stitch_len > 0 && t - stitch_t_us < 10000 &&
        stitch_len + n + tol >= expected && stitch_len + n <= expected + tol) {
        // tail piece: append (rows past the end are cut, a short total keeps
        // the previous bottom rows) and deliver the whole frame
        size_t take = n;
        if (stitch_len + take > expected) take = expected - stitch_len;
        memcpy(stitch_buf + stitch_len, frame->data, take);
        stitch_len = 0;
        stitch_ok_cnt++;
        if (stitch_ok_cnt == 1 || (stitch_ok_cnt % 300) == 0)
            LOGW("stitched split frame #%u (%u so far)", frame->sequence, stitch_ok_cnt);
        deliver_frame(stitch_buf, frame->width, frame->height, frame->sequence, user_ptr);
        return;
    }

    stitch_len = 0;
    frame_drop_cnt++;
    if (frame_drop_cnt < 20 || (frame_drop_cnt % 100) == 0)
        LOGW("drop frame#%u: data_bytes=%zu expected=%zu (drops=%u)",
             frame->sequence, frame->data_bytes, expected, frame_drop_cnt);
}

static void deliver_frame(const uint8_t *data, int w, int h, uint32_t seq, void *user_ptr) {
    size_t expected = (size_t)w * h * 2;

    // --- byte-order sanity check (green/purple picture) ---
    // In YUYV, luma (even bytes) carries the fine detail while chroma (odd bytes)
    // is smooth. If the stream lands one byte off, the roles swap: the picture
    // turns green/magenta. Compare horizontal variation of both on a sample.
    {
        const uint8_t *d = data;
        size_t stride = (expected / 2048) & ~(size_t)3;
        if (stride < 8) stride = 8;
        uint32_t dy = 0, dc = 0, cnt = 0;
        for (size_t i = 0; i + 8 < expected; i += stride, cnt++) {
            dy += abs((int)d[i]     - (int)d[i + 4]) + abs((int)d[i + 2] - (int)d[i + 6]);
            dc += abs((int)d[i + 1] - (int)d[i + 5]) + abs((int)d[i + 3] - (int)d[i + 7]);
        }
        if (dy + dc > cnt * 4) {                       // enough texture to judge
            if (dc > dy * 2) glitch_streak++; else glitch_streak = 0;
        }
        if ((seq % 300) == 0)
            LOGI("frame check #%u: luma var %u, chroma var %u (n=%u)", seq, dy, dc, cnt);
        if (glitch_streak == 8) {
            LOGW("frame #%u: luma/chroma look swapped for 8 frames (y=%u c=%u) -> restart",
                 seq, dy, dc);
            JNIEnv *genv; bool gatt = false;
            if (jvm->GetEnv((void**)&genv, JNI_VERSION_1_6) != JNI_OK) { jvm->AttachCurrentThread(&genv, NULL); gatt = true; }
            if (j_on_glitch) genv->CallVoidMethod((jobject)user_ptr, j_on_glitch, (jint)1);
            if (gatt) jvm->DetachCurrentThread();
        }
    }

    int buf_idx = (int)(deliver_cnt++ & 1u);
    if (!frame_bufs[buf_idx]) return;
    memcpy(frame_bufs[buf_idx], data, expected);

    if (seq < 3) {
        const uint8_t *d = data;
        LOGI("frame#%u buf=%d %dx%d fmt=%d bytes=%zu first8=%02x%02x%02x%02x%02x%02x%02x%02x",
             seq, buf_idx, w, h,
             0, expected,
             d[0],d[1],d[2],d[3],d[4],d[5],d[6],d[7]);
    }

    JNIEnv *env; bool attached = false;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread(&env, NULL);
        attached = true;
    }
    env->CallVoidMethod((jobject)user_ptr, j_on_frame,
                        (jint)buf_idx, (jint)w, (jint)h);
    if (attached) jvm->DetachCurrentThread();
}

// Dump all formats/resolutions the camera advertises via libuvc
static void dump_formats() {
    const uvc_format_desc_t *fmt = uvc_get_format_descs(devh);
    int fi = 0;
    while (fmt) {
        char guid[33] = {};
        for (int i = 0; i < 16; i++) sprintf(guid + i*2, "%02x", fmt->guidFormat[i]);
        LOGI("fmt[%d] subtype=%d guid=%s", fi, (int)fmt->bDescriptorSubtype, guid);
        const uvc_frame_desc_t *fd = fmt->frame_descs;
        int ri = 0;
        while (fd) {
            uint32_t fps = fd->dwDefaultFrameInterval ? 10000000u / fd->dwDefaultFrameInterval : 0;
            LOGI("  res[%d] %dx%d default=%ufps", ri, fd->wWidth, fd->wHeight, fps);
            // print all interval entries
            if (fd->intervals) {
                for (int ii = 0; fd->intervals[ii]; ii++) {
                    uint32_t f = 10000000u / fd->intervals[ii];
                    LOGI("    interval[%d] %u fps", ii, f);
                }
            }
            fd = fd->next; ri++;
        }
        fmt = fmt->next; fi++;
    }
}

static uvc_frame_format req_format = UVC_FRAME_FORMAT_YUYV;
static int req_w = 640, req_h = 480, req_fps = 30;

static uvc_frame_format format_from_fourcc(uint32_t fourcc) {
    switch (fourcc) {
        case 0x32595559: return UVC_FRAME_FORMAT_YUYV;   // 'YUY2'
        case 0x59565955: return UVC_FRAME_FORMAT_UYVY;   // 'UYVY'
        case 0x47504a4d: return UVC_FRAME_FORMAT_MJPEG;  // 'MJPG'
        case 0x3231564e: return UVC_FRAME_FORMAT_NV12;   // 'NV12'
        case 0x34363248: return UVC_FRAME_FORMAT_H264;   // 'H264'
        default:         return UVC_FRAME_FORMAT_UNKNOWN;
    }
}

static uint32_t fourcc_of(const uvc_format_desc_t *fmt) {
    if (fmt->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG) return 0x47504a4d;
    return (uint32_t)fmt->guidFormat[0] | ((uint32_t)fmt->guidFormat[1] << 8)
         | ((uint32_t)fmt->guidFormat[2] << 16) | ((uint32_t)fmt->guidFormat[3] << 24);
}

/** Exactly the requested mode; if the fps is not offered, the same mode at any fps. */
static uvc_error_t negotiate() {
    uvc_error_t r = uvc_get_stream_ctrl_format_size(devh, &ctrl, req_format, req_w, req_h, req_fps);
    LOGI("stream_ctrl fmt=%d %dx%d@%d: %d (%s)", (int)req_format, req_w, req_h, req_fps, r, uvc_strerror(r));
    if (r != UVC_SUCCESS) {
        r = uvc_get_stream_ctrl_format_size(devh, &ctrl, req_format, req_w, req_h, 0);
        LOGI("stream_ctrl fmt=%d %dx%d@any: %d (%s)", (int)req_format, req_w, req_h, r, uvc_strerror(r));
    }
    if (r == UVC_SUCCESS) {
        LOGI("stream_ctrl: bFormatIndex=%u bFrameIndex=%u dwFrameInterval=%u"
             " dwMaxVideoFrameSize=%u dwMaxPayloadTransferSize=%u bInterfaceNumber=%u",
             (unsigned)ctrl.bFormatIndex, (unsigned)ctrl.bFrameIndex,
             (unsigned)ctrl.dwFrameInterval, (unsigned)ctrl.dwMaxVideoFrameSize,
             (unsigned)ctrl.dwMaxPayloadTransferSize, (unsigned)ctrl.bInterfaceNumber);
    }
    return r;
}

/**
 * Every mode the camera advertises, flattened for Java:
 *   [fourcc, width, height, nIntervals, interval_0 .. interval_n-1]*   (intervals in 100 ns)
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_ua_radiomaniac_uvctool_UvcCamera_nativeGetModes(JNIEnv *env, jobject) {
    if (!devh) return env->NewIntArray(0);
    dump_formats();
    int n = 0;
    int cap = 256;
    jint *out = (jint*)malloc(sizeof(jint) * cap);
    for (const uvc_format_desc_t *fmt = uvc_get_format_descs(devh); fmt; fmt = fmt->next) {
        uint32_t fourcc = fourcc_of(fmt);
        for (const uvc_frame_desc_t *fd = fmt->frame_descs; fd; fd = fd->next) {
            int ni = 0;
            if (fd->intervals) while (fd->intervals[ni]) ni++;
            int nint = ni > 0 ? ni : 1;
            if (n + 4 + nint > cap) { cap = (n + 4 + nint) * 2; out = (jint*)realloc(out, sizeof(jint) * cap); }
            out[n++] = (jint)fourcc;
            out[n++] = fd->wWidth;
            out[n++] = fd->wHeight;
            out[n++] = nint;
            if (ni > 0) for (int i = 0; i < ni; i++) out[n++] = (jint)fd->intervals[i];
            else out[n++] = (jint)fd->dwDefaultFrameInterval;   // continuous range: default only
        }
    }
    jintArray arr = env->NewIntArray(n);
    env->SetIntArrayRegion(arr, 0, n, out);
    free(out);
    return arr;
}

static void reset_stream_alt() {
    // SET_INTERFACE(alt=0) on the streaming interface: stops a camera that is
    // still streaming from a previous (killed / failed) session.
    if (devh->info && devh->info->stream_ifs) {
        uint8_t iface = devh->info->stream_ifs->bInterfaceNumber;
        int rr = libusb_set_interface_alt_setting(devh->usb_devh, iface, 0);
        LOGI("alt_setting iface=%d -> 0: %d (%s)", (int)iface, rr, libusb_error_name(rr));
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ua_radiomaniac_uvctool_UvcCamera_nativeStart(JNIEnv *env, jobject, jint fourcc,
                                                   jint w, jint h, jint fps,
                                                   jobject bufA, jobject bufB) {
    if (!devh) { LOGE("nativeStart: no device"); return JNI_FALSE; }
    req_format = format_from_fourcc((uint32_t)fourcc);
    req_w = w; req_h = h; req_fps = fps;
    if (req_format != UVC_FRAME_FORMAT_YUYV) {
        LOGE("nativeStart: format %08x not supported yet", (unsigned)fourcc);
        return JNI_FALSE;
    }
    frame_bufs[0] = (uint8_t*)env->GetDirectBufferAddress(bufA);
    frame_bufs[1] = (uint8_t*)env->GetDirectBufferAddress(bufB);
    if (!frame_bufs[0] || !frame_bufs[1]
            || env->GetDirectBufferCapacity(bufA) < (jlong)w * h * 2
            || env->GetDirectBufferCapacity(bufB) < (jlong)w * h * 2) {
        LOGE("nativeStart: frame buffers too small for %dx%d", w, h);
        frame_bufs[0] = frame_bufs[1] = NULL;
        return JNI_FALSE;
    }

    // The cheap endoscope sometimes refuses the first start (I/O error) right
    // after open / after a previous failed session. One failed attempt must not
    // kill the session: reset alt-setting, wait, renegotiate, try again.
    const int ATTEMPTS = 3;
    uvc_error_t r = UVC_ERROR_OTHER;
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
        r = negotiate();
        if (r != UVC_SUCCESS) {
            LOGE("attempt %d: cannot negotiate stream format", attempt);
        } else {
            reset_stream_alt();
            usleep(attempt == 1 ? 50000 : 300000);
            frame_drop_cnt = 0;
            glitch_streak = 0;
            stitch_len = 0; stitch_ok_cnt = 0;
            r = uvc_start_streaming(devh, &ctrl, frame_cb, j_camera_obj, 0);
            LOGI("attempt %d: uvc_start_streaming: %d (%s)", attempt, r, uvc_strerror(r));
            if (r == UVC_SUCCESS) { streaming = true; return JNI_TRUE; }
        }
        usleep(300000);
    }
    LOGE("uvc_start_streaming failed after %d attempts: %d (%s)", ATTEMPTS, r, uvc_strerror(r));
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_ua_radiomaniac_uvctool_UvcCamera_nativeStop(JNIEnv *, jobject) {
    if (!devh || !streaming) {
        LOGI("nativeStop: skipped (devh=%p streaming=%d)", devh, streaming);
        return;
    }
    uvc_stop_streaming(devh);
    streaming = false;
    frame_bufs[0] = frame_bufs[1] = NULL;
    LOGI("uvc_stop_streaming done");
}

extern "C" JNIEXPORT void JNICALL
Java_ua_radiomaniac_uvctool_UvcCamera_nativeClose(JNIEnv *, jobject) {
    if (devh) { uvc_close(devh); devh = NULL; }
    LOGI("uvc_close done");
}

extern "C" JNIEXPORT void JNICALL
Java_ua_radiomaniac_uvctool_UvcCamera_nativeDeinit(JNIEnv *env, jobject) {
    if (uvc_ctx) { uvc_exit(uvc_ctx); uvc_ctx = NULL; }
    if (j_camera_obj) { env->DeleteGlobalRef(j_camera_obj); j_camera_obj = NULL; }
    frame_bufs[0] = frame_bufs[1] = NULL;
    LOGI("nativeDeinit done");
}

// ---------------------------------------------------------------------- image controls
// which: 0 = brightness (int16), 1 = contrast (uint16). Returns {min, max, def, cur}
// or null when the camera does not have the control.
extern "C" JNIEXPORT jintArray JNICALL
Java_ua_radiomaniac_uvctool_UvcCamera_nativeGetCtrl(JNIEnv *env, jobject, jint which) {
    if (!devh) return NULL;
    jint v[4];
    const uvc_req_code reqs[4] = { UVC_GET_MIN, UVC_GET_MAX, UVC_GET_DEF, UVC_GET_CUR };
    for (int i = 0; i < 4; i++) {
        uvc_error_t r;
        if (which == 0) { int16_t x = 0; r = uvc_get_brightness(devh, &x, reqs[i]); v[i] = x; }
        else            { uint16_t x = 0; r = uvc_get_contrast(devh, &x, reqs[i]); v[i] = x; }
        if (r != UVC_SUCCESS) {
            LOGI("ctrl %d: not available (%s)", which, uvc_strerror(r));
            return NULL;
        }
    }
    LOGI("ctrl %d: min=%d max=%d def=%d cur=%d", which, v[0], v[1], v[2], v[3]);
    if (v[1] <= v[0]) return NULL;
    jintArray a = env->NewIntArray(4);
    env->SetIntArrayRegion(a, 0, 4, v);
    return a;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ua_radiomaniac_uvctool_UvcCamera_nativeSetCtrl(JNIEnv *, jobject, jint which, jint value) {
    if (!devh) return JNI_FALSE;
    uvc_error_t r = which == 0 ? uvc_set_brightness(devh, (int16_t)value)
                               : uvc_set_contrast(devh, (uint16_t)value);
    return r == UVC_SUCCESS ? JNI_TRUE : JNI_FALSE;
}
