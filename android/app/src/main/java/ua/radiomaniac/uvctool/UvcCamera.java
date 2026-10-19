package ua.radiomaniac.uvctool;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.List;

public class UvcCamera {

    private static final String TAG = "UvcTool";
    private static final long FRAME_TIMEOUT_MS = 8000;

    static { System.loadLibrary("uvctool"); }

    public interface FrameListener {
        void onFrame(ByteBuffer yuyv, int width, int height);
    }

    public interface ButtonListener {
        void onButtonPress();
    }

    public interface GlitchListener {
        /** kind 1 = luma/chroma swapped (green/purple picture). Main thread. */
        void onGlitch(int kind);
    }

    private GlitchListener glitchListener;
    public void setGlitchListener(GlitchListener l) { glitchListener = l; }

    // Called from JNI (frame thread)
    private void onGlitch(int kind) {
        Log.w(TAG, "stream glitch kind=" + kind);
        if (glitchListener != null) mainHandler.post(() -> glitchListener.onGlitch(kind));
    }

    public interface ErrorListener {
        void onError(String stage, String message);
    }

    private FrameListener frameListener;
    private ButtonListener buttonListener;
    private ErrorListener errorListener;

    private UsbDeviceConnection connection;
    private ByteBuffer frameBufA;
    private ByteBuffer frameBufB;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable frameWatchdog;
    private volatile boolean firstFrameReceived = false;
    private int lastButtonState = 0;
    private List<UvcMode> modes = java.util.Collections.emptyList();
    private volatile UvcMode mode;

    // Called from JNI (frame_cb thread): bufIndex=0 or 1 → which DirectByteBuffer was written
    private void onFrame(int bufIndex, int w, int h) {
        if (!firstFrameReceived) {
            firstFrameReceived = true;
            mainHandler.post(this::cancelFrameWatchdog);
            Log.d(TAG, "first frame received " + w + "x" + h + " buf=" + bufIndex + " — watchdog cancelled");
        }
        if (frameListener != null) {
            ByteBuffer buf = (bufIndex == 0) ? frameBufA : frameBufB;
            if (buf != null) {
                buf.rewind();
                frameListener.onFrame(buf, w, h);
            }
        }
    }

    // Called from JNI (status_cb thread), value=1 press, value=0 release
    private void onButton(int value) {
        if (lastButtonState == 1 && value == 0) {
            Log.d(TAG, "button press detected");
            if (buttonListener != null) {
                mainHandler.post(() -> buttonListener.onButtonPress());
            }
        }
        lastButtonState = value;
    }

    public void setFrameListener(FrameListener l)   { frameListener = l; }
    public void setButtonListener(ButtonListener l) { buttonListener = l; }
    public void setErrorListener(ErrorListener l)   { errorListener = l; }

    public boolean open(UsbManager mgr, UsbDevice device) {
        Log.d(TAG, "UvcCamera.open " + device.getDeviceName());
        if (connection != null) {
            Log.w(TAG, "open: previous session still open, closing it first");
            close();
        }
        connection = mgr.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "openDevice returned null");
            return false;
        }
        nativeInit();
        boolean ok = nativeOpen(connection.getFileDescriptor());
        Log.i(TAG, "nativeOpen fd=" + connection.getFileDescriptor() + " ok=" + ok);
        if (ok) {
            modes = UvcMode.parse(nativeGetModes());
            for (UvcMode m : modes) Log.i(TAG, "mode: " + m.label() + (m.supported() ? "" : " (unsupported)"));
        }
        return ok;
    }

    /** Modes the open camera advertises (empty before open()). */
    public List<UvcMode> getModes() { return modes; }

    /** Mode of the running stream, or null. */
    public UvcMode getMode() { return mode; }

    public boolean start(UvcMode m) {
        firstFrameReceived = false;
        if (m == null) {
            if (errorListener != null) errorListener.onError("start", "no supported video mode");
            return false;
        }
        // two frame buffers sized for this mode (YUYV: 2 bytes per pixel)
        int bytes = m.width * m.height * 2;
        if (frameBufA == null || frameBufA.capacity() < bytes) {
            frameBufA = ByteBuffer.allocateDirect(bytes);
            frameBufB = ByteBuffer.allocateDirect(bytes);
        }
        nativeSetQueue(queueDepth);
        boolean ok = nativeStart(m.fourcc, m.width, m.height, m.maxFps(), frameBufA, frameBufB);
        mode = ok ? m : null;
        Log.i(TAG, "nativeStart " + m.label() + " ok=" + ok + " queue=" + queueDepth);
        if (ok) { startFrameWatchdog(); scheduleQueueCheck(); }
        else if (errorListener != null) {
            errorListener.onError("start", "uvc_start_streaming failed (see log)");
        }
        return ok;
    }

    // Some USB hosts (MediaTek musb, e.g. CUBOT KingKong Mini 3) hand back
    // transfers queued beyond their limit "filled" with zeros. Start with a deep
    // queue (smooth on proper xHCI hosts) and fall back to a short one when the
    // stream shows header-less payloads. Kept for the rest of the process.
    private static final int QUEUE_DEEP = 128, QUEUE_SHORT = 8;
    private static volatile int queueDepth = QUEUE_DEEP;
    private final Runnable queueCheck = () -> {
        UvcMode m = mode;
        if (m == null) return;
        int junk = nativeJunkCount();
        if (junk > 200 && queueDepth > QUEUE_SHORT) {
            Log.w(TAG, junk + " payloads without UVC header: host drops a deep queue, using " + QUEUE_SHORT + " transfers");
            queueDepth = QUEUE_SHORT;
            cancelFrameWatchdog();
            nativeStop();
            start(m);
        } else {
            Log.i(TAG, "queue check: " + junk + " header-less payloads, queue=" + queueDepth);
        }
    };

    private void scheduleQueueCheck() {
        mainHandler.removeCallbacks(queueCheck);
        mainHandler.postDelayed(queueCheck, 1500);
    }

    public void stop() {
        mainHandler.removeCallbacks(queueCheck);
        cancelFrameWatchdog();
        nativeStop();
        mode = null;
    }

    public void close() {
        cancelFrameWatchdog();
        mainHandler.removeCallbacks(queueCheck);
        nativeClose();
        nativeDeinit();
        if (connection != null) { connection.close(); connection = null; }
        frameBufA = null;
        frameBufB = null;
        modes = java.util.Collections.emptyList();
        mode = null;
    }

    private void startFrameWatchdog() {
        cancelFrameWatchdog();
        frameWatchdog = () -> {
            if (!firstFrameReceived) {
                Log.e(TAG, "no frames in " + FRAME_TIMEOUT_MS + "ms");
                if (errorListener != null) {
                    errorListener.onError("streaming_timeout",
                        "No frames received in " + FRAME_TIMEOUT_MS + "ms (" +
                        (mode != null ? mode.label() : "?") + "). " +
                        "Check uvc_wrap/uvc_start_streaming in logcat.");
                }
            }
        };
        mainHandler.postDelayed(frameWatchdog, FRAME_TIMEOUT_MS);
    }

    private void cancelFrameWatchdog() {
        if (frameWatchdog != null) {
            mainHandler.removeCallbacks(frameWatchdog);
            frameWatchdog = null;
        }
    }

    private native void nativeInit();
    private native boolean nativeOpen(int fd);
    private native int[] nativeGetCtrl(int which);
    private native boolean nativeSetCtrl(int which, int value);

    public static final int CTRL_BRIGHTNESS = 0, CTRL_CONTRAST = 1;

    /** {min, max, def, cur} of a hardware control, or null if the camera has none. */
    public int[] getCtrl(int which) { return nativeGetCtrl(which); }

    public boolean setCtrl(int which, int value) { return nativeSetCtrl(which, value); }
    private native int[] nativeGetModes();
    private native boolean nativeStart(int fourcc, int w, int h, int fps, ByteBuffer bufA, ByteBuffer bufB);
    private native void nativeStop();
    private native void nativeSetQueue(int transfers);
    private native int nativeJunkCount();
    private native void nativeClose();
    private native void nativeDeinit();
}
