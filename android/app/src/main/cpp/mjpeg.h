// MJPEG -> YUYV for UVC cameras that only stream compressed frames at full size
// (e.g. MS2107 HDMI grabbers: 1080p is MJPEG only). The rest of the app (shader,
// recorder, photo) stays YUYV; this decodes to planar YUV with libjpeg-turbo and
// interleaves it. UVC MJPEG is nearly always 4:2:2, which maps 1:1 onto YUYV.
#pragma once

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <turbojpeg.h>

struct MjpegDecoder {
    tjhandle tj = nullptr;
    uint8_t *planes = nullptr;
    size_t cap = 0;
};

enum { MJPEG_OK = 0, MJPEG_WARN = 1, MJPEG_BAD_HEADER = -1, MJPEG_SIZE = -2,
       MJPEG_DECODE = -3, MJPEG_SUBSAMP = -4, MJPEG_NOMEM = -5 };

static inline void mjpeg_free(MjpegDecoder *d) {
    if (d->tj) { tj3Destroy(d->tj); d->tj = nullptr; }
    free(d->planes); d->planes = nullptr; d->cap = 0;
}

/** Decode one JPEG into a w x h YUYV buffer (w*h*2 bytes). On MJPEG_WARN the
 *  picture was decoded but the data was damaged (e.g. a truncated frame). */
static inline int mjpeg_to_yuyv(MjpegDecoder *d, const uint8_t *jpg, size_t n,
                                int w, int h, uint8_t *out, int *subsamp_out,
                                const char **err) {
    *err = "";
    if (!d->tj) {
        d->tj = tj3Init(TJINIT_DECOMPRESS);
        if (!d->tj) { *err = "tj3Init failed"; return MJPEG_NOMEM; }
        tj3Set(d->tj, TJPARAM_FASTDCT, 1);
    }
    if (n < 4 || jpg[0] != 0xFF || jpg[1] != 0xD8) { *err = "no SOI"; return MJPEG_BAD_HEADER; }
    if (tj3DecompressHeader(d->tj, jpg, n) != 0) { *err = tj3GetErrorStr(d->tj); return MJPEG_BAD_HEADER; }
    int jw = tj3Get(d->tj, TJPARAM_JPEGWIDTH), jh = tj3Get(d->tj, TJPARAM_JPEGHEIGHT);
    int ss = tj3Get(d->tj, TJPARAM_SUBSAMP);
    if (subsamp_out) *subsamp_out = ss;
    if (jw != w || jh != h) { *err = "frame size differs from the mode"; return MJPEG_SIZE; }
    if (ss < 0) { *err = "unknown chroma subsampling"; return MJPEG_SUBSAMP; }

    bool gray = ss == TJSAMP_GRAY;
    int nc = gray ? 1 : 3;
    int pw[3] = {0, 0, 0}, ph[3] = {0, 0, 0};
    size_t total = 0;
    for (int i = 0; i < nc; i++) {
        pw[i] = tj3YUVPlaneWidth(i, w, ss);
        ph[i] = tj3YUVPlaneHeight(i, h, ss);
        if (pw[i] <= 0 || ph[i] <= 0) { *err = "bad plane size"; return MJPEG_SUBSAMP; }
        total += (size_t)pw[i] * ph[i];
    }
    if (d->cap < total) {
        free(d->planes);
        d->planes = (uint8_t *)malloc(total);
        d->cap = d->planes ? total : 0;
        if (!d->planes) { *err = "out of memory"; return MJPEG_NOMEM; }
    }
    uint8_t *p[3] = { d->planes, nullptr, nullptr };
    if (!gray) { p[1] = p[0] + (size_t)pw[0] * ph[0]; p[2] = p[1] + (size_t)pw[1] * ph[1]; }

    int rc = MJPEG_OK;
    if (tj3DecompressToYUVPlanes8(d->tj, jpg, n, p, pw) != 0) {
        if (tj3GetErrorCode(d->tj) != TJERR_WARNING) { *err = tj3GetErrorStr(d->tj); return MJPEG_DECODE; }
        *err = tj3GetErrorStr(d->tj);
        rc = MJPEG_WARN;
    }

    // Interleave: Y0 U Y1 V per pixel pair. Chroma is sampled at whatever
    // resolution the JPEG used (exact for 4:2:2, nearest for 4:2:0 / 4:4:4 / 4:1:1).
    int half = w / 2;
    for (int y = 0; y < h; y++) {
        const uint8_t *Y = p[0] + (size_t)y * pw[0];
        uint8_t *o = out + (size_t)y * w * 2;
        if (gray) {
            for (int x = 0; x < half; x++) {
                o[0] = Y[2 * x]; o[1] = 128; o[2] = Y[2 * x + 1]; o[3] = 128; o += 4;
            }
            continue;
        }
        int cy = (int)((long)y * ph[1] / h);
        const uint8_t *U = p[1] + (size_t)cy * pw[1], *V = p[2] + (size_t)cy * pw[2];
        if (pw[1] == half) {                       // 4:2:2 and 4:2:0
            for (int x = 0; x < half; x++) {
                o[0] = Y[2 * x]; o[1] = U[x]; o[2] = Y[2 * x + 1]; o[3] = V[x]; o += 4;
            }
        } else {
            for (int x = 0; x < half; x++) {
                int cx = (int)((long)x * pw[1] / half);
                o[0] = Y[2 * x]; o[1] = U[cx]; o[2] = Y[2 * x + 1]; o[3] = V[cx]; o += 4;
            }
        }
    }
    return rc;
}
