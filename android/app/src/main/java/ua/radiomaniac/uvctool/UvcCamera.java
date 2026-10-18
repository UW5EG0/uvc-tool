package ua.radiomaniac.uvctool;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;

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
        frameBufA = ByteBuffer.allocateDirect(640 * 480 * 2);
        frameBufB = ByteBuffer.allocateDirect(640 * 480 * 2);
        nativeInit();
        boolean ok = nativeOpen(connection.getFileDescriptor(), frameBufA, frameBufB);
        Log.i(TAG, "nativeOpen fd=" + connection.getFileDescriptor() + " ok=" + ok);
        return ok;
    }

    public boolean start() {
        firstFrameReceived = false;
        boolean ok = nativeStart();
        Log.i(TAG, "nativeStart ok=" + ok);
        if (ok) startFrameWatchdog();
        else if (errorListener != null) {
            errorListener.onError("start", "uvc_start_streaming failed (see log)");
        }
        return ok;
    }

    public void stop() {
        cancelFrameWatchdog();
        nativeStop();
    }

    public void close() {
        cancelFrameWatchdog();
        nativeClose();
        nativeDeinit();
        if (connection != null) { connection.close(); connection = null; }
        frameBufA = null;
        frameBufB = null;
    }

    private void startFrameWatchdog() {
        cancelFrameWatchdog();
        frameWatchdog = () -> {
            if (!firstFrameReceived) {
                Log.e(TAG, "no frames in " + FRAME_TIMEOUT_MS + "ms");
                if (errorListener != null) {
                    errorListener.onError("streaming_timeout",
                        "No frames received in " + FRAME_TIMEOUT_MS + "ms (640x480 YUYV). " +
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
    private native boolean nativeOpen(int fd, ByteBuffer bufA, ByteBuffer bufB);
    private native boolean nativeStart();
    private native void nativeStop();
    private native void nativeClose();
    private native void nativeDeinit();
}
