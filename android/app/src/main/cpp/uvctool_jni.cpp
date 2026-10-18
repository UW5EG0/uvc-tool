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
    return JNI_TRUE;
}

static void frame_cb(uvc_frame_t *frame, void *user_ptr) {
    size_t expected = (size_t)frame->width * frame->height * 2;
    if (frame->data_bytes != expected) {
        frame_drop_cnt++;
        LOGW("drop frame#%u: data_bytes=%zu expected=%zu (drops=%u)",
             frame->sequence, frame->data_bytes, expected, frame_drop_cnt);
        return;
    }

    int buf_idx = (int)(frame->sequence & 1u);
    if (!frame_bufs[buf_idx]) return;
    memcpy(frame_bufs[buf_idx], frame->data, expected);

    if (frame->sequence < 3) {
        uint8_t *d = (uint8_t*)frame->data;
        LOGI("frame#%u buf=%d %dx%d fmt=%d bytes=%zu first8=%02x%02x%02x%02x%02x%02x%02x%02x",
             frame->sequence, buf_idx, frame->width, frame->height,
             (int)frame->frame_format, frame->data_bytes,
             d[0],d[1],d[2],d[3],d[4],d[5],d[6],d[7]);
    }

    JNIEnv *env; bool attached = false;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        jvm->AttachCurrentThread(&env, NULL);
        attached = true;
    }
    env->CallVoidMethod((jobject)user_ptr, j_on_frame,
                        (jint)buf_idx, (jint)frame->width, (jint)frame->height);
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
