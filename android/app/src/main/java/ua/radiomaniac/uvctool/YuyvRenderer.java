package ua.radiomaniac.uvctool;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Renders a raw YUYV (YUY2) frame as an aspect-fit quad, and optionally
 * re-draws the same scene into a video encoder's input Surface.
 *
 * The frame is uploaded as an RGBA texture of (width/2) x height texels:
 * one texel holds two horizontal pixels (Y0 U Y1 V). The fragment shader
 * unpacks the pair, converts BT.601 -> RGB, then applies flip / rotation /
 * white-balance gains / gamma. Everything the user sees on screen is what
 * ends up in a photo or a video.
 */
public class YuyvRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "UvcTool";

    /** Called on the GL thread with the viewport the frame was drawn into. */
    public interface CaptureCallback {
        void onCapture(int x, int y, int width, int height);
    }

    private static final String VERT_SHADER =
            "attribute vec2 a_pos;\n" +
            "varying vec2 v_texCoord;\n" +
            "void main() {\n" +
            "    v_texCoord = vec2((a_pos.x + 1.0) * 0.5, (1.0 - a_pos.y) * 0.5);\n" +
            "    gl_Position = vec4(a_pos, 0.0, 1.0);\n" +
            "}\n";

    private static final float[] QUAD_VERTS = {
            -1f, -1f,  1f, -1f,  -1f,  1f,
             1f, -1f,  1f,  1f,  -1f,  1f
    };

    private final Context context;
    private final FloatBuffer quad;

    // --- user-controlled view parameters (set from UI thread, read on GL thread) ---
    public volatile boolean flipX = false;
    public volatile boolean flipY = false;
    /** Palettes.NONE .. RAINBOW; set from any thread. */
    public volatile int palette = Palettes.NONE;
    /** Show only the top half of the frame (thermal cameras put raw data in the bottom half). */
    public volatile boolean cropTop = false;
    /** Software brightness (-0.5..0.5) and contrast (0.5..2), used when the camera has no such controls. */
    public volatile float swBright = 0f, swContrast = 1f;
    private int uBright, uContrast;
    private int uCropY;
    private int lutTexture, lutPalette = -1, uLut, uPalette;
    public volatile int rotation = 0;              // 0 / 90 / 180 / 270
    public final float[] wbGain = {1f, 1f, 1f};    // R G B multipliers
    public volatile float gamma = 1.0f;

    // --- frame geometry ---
    private volatile int frameW = 640;
    private volatile int frameH = 480;
    private int texW = 0, texH = 0;

    private int program, texture;
    private int aPos, uTexture, uTexSize, uFlipX, uFlipY, uRotation, uWbGain, uGamma;
    private int surfaceW, surfaceH;
    private int vpX, vpY, vpW, vpH;

    private ByteBuffer pendingFrame;
    private final Object frameLock = new Object();
    private volatile CaptureCallback pendingCapture;

    // --- recording (GL thread only) ---
    private VideoRecorder recorder;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface recSurface = EGL14.EGL_NO_SURFACE;
    private int recW, recH;

    public YuyvRenderer(Context context) {
        this.context = context.getApplicationContext();
        quad = ByteBuffer.allocateDirect(QUAD_VERTS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD_VERTS).position(0);
    }

    // ------------------------------------------------------------------ public API

    /** Hand a complete YUYV frame to the renderer. Caller must requestRender(). */
    public void submitFrame(ByteBuffer buf, int width, int height) {
        synchronized (frameLock) {
            pendingFrame = buf;
            frameW = width;
            frameH = height;
        }
    }

    /** Snapshot the next drawn frame (exactly as shown). */
    public void requestCapture(CaptureCallback cb) {
        pendingCapture = cb;
    }

    public boolean isRotated90() {
        return rotation == 90 || rotation == 270;
    }

    /** GL thread (via queueEvent). Output size follows current rotation. */
    public boolean startRecording(VideoRecorder rec) {
        if (recorder != null) return true;
        boolean rot = isRotated90();
        int effH = cropTop ? frameH / 2 : frameH;
        int w = rot ? effH : frameW;
        int h = rot ? frameW : effH;
        if (!rec.start(w, h)) return false;
        recorder = rec;
        recW = w;
        recH = h;
        return true;
    }

    /** GL thread (via queueEvent). */
    public void stopRecording() {
        if (recorder == null) return;
        releaseRecSurface();
        recorder.stop();
        recorder = null;
    }

    // ------------------------------------------------------------------ GLSurfaceView.Renderer

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        program = buildProgram(VERT_SHADER, loadRawString(R.raw.yuyv_frag));

        aPos      = GLES20.glGetAttribLocation(program, "a_pos");
        uTexture  = GLES20.glGetUniformLocation(program, "u_texture");
        uTexSize  = GLES20.glGetUniformLocation(program, "u_texSize");
        uFlipX    = GLES20.glGetUniformLocation(program, "u_flipX");
        uFlipY    = GLES20.glGetUniformLocation(program, "u_flipY");
        uCropY    = GLES20.glGetUniformLocation(program, "u_cropY");
        uBright   = GLES20.glGetUniformLocation(program, "u_bright");
        uContrast = GLES20.glGetUniformLocation(program, "u_contrast");
        uRotation = GLES20.glGetUniformLocation(program, "u_rotation");
        uWbGain   = GLES20.glGetUniformLocation(program, "u_wbGain");
        uGamma    = GLES20.glGetUniformLocation(program, "u_gamma");
        uLut      = GLES20.glGetUniformLocation(program, "u_lut");
        uPalette  = GLES20.glGetUniformLocation(program, "u_palette");
        int[] lt = new int[1];
        GLES20.glGenTextures(1, lt, 0);
        lutTexture = lt[0];
        lutPalette = -1;

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texture = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        // NEAREST: a texel is a YUYV pair, interpolating pairs would smear chroma
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texW = texH = 0;

        eglDisplay = EGL14.eglGetCurrentDisplay();
        eglContext = EGL14.eglGetCurrentContext();
        recSurface = EGL14.EGL_NO_SURFACE; // a new context invalidates any old one
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceW = width;
        surfaceH = height;
        android.util.Log.i("UvcTool", "GL surface " + width + "x" + height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glViewport(0, 0, surfaceW, surfaceH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        ByteBuffer frame;
        int fw, fh;
        synchronized (frameLock) {
            frame = pendingFrame;
            pendingFrame = null;
            fw = frameW;
            fh = frameH;
        }

        uploadFrame(frame, fw, fh);
        if (texW == 0) return; // nothing uploaded yet

        // --- 1. screen ---
        computeViewport(fw, fh);
        GLES20.glViewport(vpX, vpY, vpW, vpH);
        drawQuad();

        CaptureCallback cb = pendingCapture;
        if (cb != null) {
            pendingCapture = null;
            cb.onCapture(vpX, vpY, vpW, vpH);
        }

        // --- 2. encoder (only on a genuinely new camera frame) ---
        if (recorder != null && frame != null) {
            drawToRecorder();
        }
    }

    // ------------------------------------------------------------------ drawing

    private void uploadFrame(ByteBuffer frame, int fw, int fh) {
        if (frame == null) return;
        int wantW = fw / 2, wantH = fh;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        frame.rewind();
        if (wantW != texW || wantH != texH) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    wantW, wantH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame);
            texW = wantW;
            texH = wantH;
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    texW, texH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame);
        }
    }

    private void drawQuad() {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(uTexture, 0);
        GLES20.glUniform2f(uTexSize, texW, texH);
        GLES20.glUniform1i(uFlipX, flipX ? 1 : 0);
        GLES20.glUniform1i(uFlipY, flipY ? 1 : 0);
        GLES20.glUniform1f(uCropY, cropTop ? 0.5f : 1f);
        GLES20.glUniform1f(uBright, swBright);
        GLES20.glUniform1f(uContrast, swContrast);
        GLES20.glUniform1i(uRotation, rotation);
        GLES20.glUniform3f(uWbGain, wbGain[0], wbGain[1], wbGain[2]);
        GLES20.glUniform1f(uGamma, gamma);
        int pal = palette;
        if (pal != Palettes.NONE && pal != lutPalette) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(Palettes.lut(pal)));
            lutPalette = pal;
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture);
        GLES20.glUniform1i(uLut, 1);
        GLES20.glUniform1i(uPalette, pal != Palettes.NONE ? 1 : 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    private void drawToRecorder() {
        if (recSurface == EGL14.EGL_NO_SURFACE && !createRecSurface()) {
            Log.e(TAG, "recorder surface unavailable, stopping recording");
            recorder.stop();
            recorder = null;
            return;
        }
        EGLSurface screenDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        EGLSurface screenRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);

        EGL14.eglMakeCurrent(eglDisplay, recSurface, recSurface, eglContext);
        GLES20.glViewport(0, 0, recW, recH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawQuad();
        EGLExt.eglPresentationTimeANDROID(eglDisplay, recSurface, System.nanoTime());
        EGL14.eglSwapBuffers(eglDisplay, recSurface);

        // back to the on-screen surface before GLSurfaceView swaps it
        EGL14.eglMakeCurrent(eglDisplay, screenDraw, screenRead, eglContext);

        try {
            recorder.drainEncoder(false);
        } catch (Exception e) {
            Log.e(TAG, "drainEncoder failed, stopping recording", e);
            stopRecording();
        }
    }

    private boolean createRecSurface() {
        Surface target = recorder.getInputSurface();
        if (target == null) return false;
        android.opengl.EGLConfig cfg = currentEglConfig();
        if (cfg == null) return false;
        int[] attrs = { EGL14.EGL_NONE };
        recSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, target, attrs, 0);
        if (recSurface == null || recSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface(encoder) failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            recSurface = EGL14.EGL_NO_SURFACE;
            return false;
        }
        return true;
    }

    private void releaseRecSurface() {
        if (recSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, recSurface);
            recSurface = EGL14.EGL_NO_SURFACE;
        }
    }

    /** The EGLConfig the GLSurfaceView context was created with (recordable, see MainActivity). */
    private android.opengl.EGLConfig currentEglConfig() {
        int[] id = new int[1];
        EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONFIG_ID, id, 0);
        int[] attrs = { EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE };
        android.opengl.EGLConfig[] cfgs = new android.opengl.EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) {
            Log.e(TAG, "eglChooseConfig by id failed");
            return null;
        }
        return cfgs[0];
    }

    /** Aspect-fit the (possibly rotated) frame into the surface, centred. */
    // ------------------------------------------------------------------ layout around the UI

    /**
     * Where the picture may go, in view coordinates (origin top-left, same size as
     * the GL surface): bounds = screen minus system bars / display cutout; hard
     * obstacles (control panel, WB slider) are always avoided; soft ones (status
     * badges, menu button) only when that costs no picture size.
     * Each rect is {left, top, right, bottom}. Set from the UI thread.
     */
    public void setLayoutHints(int[] bounds, int[][] hard, int[][] soft) {
        hintBounds = bounds; hintHard = hard; hintSoft = soft;
    }

    private volatile int[] hintBounds;
    private volatile int[][] hintHard, hintSoft;

    private void computeViewport(int fw, int fh) {
        if (cropTop) fh = fh / 2;
        boolean rot = isRotated90();
        float camW = rot ? fh : fw;
        float camH = rot ? fw : fh;

        int[] b = hintBounds;
        int[] area = (b != null) ? b.clone() : new int[]{0, 0, surfaceW, surfaceH};
        int[][] hard = hintHard, soft = hintSoft;
        if (hard != null) {
            int[] h = bestRect(area, hard, camW, camH);
            area = h;
            if (soft != null) {
                int[] s = bestRect(h, soft, camW, camH);
                if (scale(s, camW, camH) >= scale(h, camW, camH) * 0.995f) area = s;
            }
        }

        float k = scale(area, camW, camH);
        vpW = Math.round(camW * k);
        vpH = Math.round(camH * k);
        int left = area[0] + (area[2] - area[0] - vpW) / 2;
        int top  = area[1] + (area[3] - area[1] - vpH) / 2;
        vpX = left;
        vpY = surfaceH - top - vpH;          // GL origin is bottom-left
    }

    private static float scale(int[] r, float camW, float camH) {
        int w = r[2] - r[0], h = r[3] - r[1];
        if (w <= 0 || h <= 0) return 0f;
        return Math.min(w / camW, h / camH);
    }

    /**
     * Largest picture that fits in {@code area} without touching any obstacle:
     * for every obstacle that overlaps, try cutting the area on each of its four
     * sides (at most 4^n combinations, n <= 3 here).
     */
    private static int[] bestRect(int[] area, int[][] obstacles, float camW, float camH) {
        return bestRect(area, obstacles, 0, camW, camH);
    }

    private static int[] bestRect(int[] r, int[][] obs, int i, float camW, float camH) {
        if (i == obs.length) return r;
        int[] o = obs[i];
        if (o == null || o[2] <= r[0] || o[0] >= r[2] || o[3] <= r[1] || o[1] >= r[3])
            return bestRect(r, obs, i + 1, camW, camH);           // no overlap
        int[][] cuts = {
                { o[2], r[1], r[2], r[3] },   // keep the part right of the obstacle
                { r[0], r[1], o[0], r[3] },   // left of it
                { r[0], o[3], r[2], r[3] },   // below it
                { r[0], r[1], r[2], o[1] } }; // above it
        int[] best = null; float bestK = -1f;
        for (int[] c : cuts) {
            if (c[2] - c[0] <= 0 || c[3] - c[1] <= 0) continue;
            int[] res = bestRect(c, obs, i + 1, camW, camH);
            float k = scale(res, camW, camH);
            if (k > bestK) { bestK = k; best = res; }
        }
        return best != null ? best : r;
    }

    // ------------------------------------------------------------------ shader utils

    private String loadRawString(int resId) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getResources().openRawResource(resId);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException e) {
            Log.e(TAG, "loadRawString failed", e);
        }
        return sb.toString();
    }

    private static int buildProgram(String vertSrc, String fragSrc) {
        int vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc);
        int frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vert);
        GLES20.glAttachShader(prog, frag);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) Log.e(TAG, "Program link error: " + GLES20.glGetProgramInfoLog(prog));
        GLES20.glDeleteShader(vert);
        GLES20.glDeleteShader(frag);
        return prog;
    }

    private static int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }
}
