package ua.radiomaniac.uvctool;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.ContextCompat;

import com.google.android.material.imageview.ShapeableImageView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * Single-screen endoscope viewer.
 *
 * Layout: GLSurfaceView full-screen underneath, one overlay on top. The Activity
 * survives rotation (configChanges in the manifest) so the UVC stream is never
 * restarted; only the overlay's ConstraintSet is swapped between portrait
 * (panel at bottom) and landscape (panel at right).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "UvcTool";
    private static final String PREFS = "uvc_prefs";
    private static final String KEY_FLIP = "flip_x";            // legacy boolean (horizontal)
    private static final String KEY_FLIP_MODE = "flip_mode";    // 0 off, 1 H, 2 V, 3 both
    private static final String KEY_PALETTE = "palette";        // Palettes.*
    private static final String KEY_CROP_PREFIX = "crop_top_";  // + camera key
    private static final String KEY_ROTATION = "rotation";
    private static final String KEY_WARMTH = "warmth";
    private static final String KEY_LAST_CAMERA = "last_camera";     // "vvvv:pppp"
    private static final String KEY_MODE_PREFIX = "mode_";           // + camera key -> UvcMode.key()

    private GLSurfaceView glView;
    private YuyvRenderer renderer;
    private UsbDeviceManager usbDeviceManager;
    private UvcCamera camera;
    private PhotoSaver photoSaver;
    private UsbManager usbManager;
    private SharedPreferences prefs;

    private ConstraintLayout overlayRoot;
    private final ConstraintSet portraitSet = new ConstraintSet();
    private final ConstraintSet landscapeSet = new ConstraintSet();

    private TextView tvStatus, tvFps, tvRec;
    private View groupNoCamera, panelWb;
    private ImageButton btnShutter, btnMode, btnLog, btnFlip, btnRotate;
    private int flipMode = 0;
    private boolean videoMode = false;            // shutter + cable button: photo or record
    private static final String KEY_VIDEO_MODE = "video_mode";
    private final Beeper beeper = new Beeper();
    private ShapeableImageView btnGallery;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // CAMERA runtime permission gates USB access to UVC devices (Android 10+)
    private ActivityResultLauncher<String> cameraPermLauncher;
    private UsbDevice deviceAwaitingCameraPerm;

    private LogPanel logPanel;
    private VideoRecorder videoRecorder;
    private volatile boolean recording = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long s = videoRecorder.elapsedMs() / 1000;
            tvRec.setText(String.format(Locale.US, "● %02d:%02d", s / 60, s % 60));
            uiHandler.postDelayed(this, 500);
        }
    };

    private boolean streaming = false;
    private long lastGlitchRestart = 0;
    private int glitchRestarts = 0;
    private UsbDevice currentDevice;      // camera of the open session
    private boolean cameraOpen = false;   // native session is open (streaming or starting)
    private boolean resumed = false;      // false while a system dialog sits on top of us
    private int frameCount = 0;
    private long fpsWindowStart = 0;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        cameraPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    Log.d(TAG, "CAMERA permission granted=" + granted);
                    if (!granted) {
                        setStatus("Потрібен дозвіл «Камера» — без нього Android не дає доступ до USB-камери",
                                R.color.text_error);
                        return;
                    }
                    UsbDevice d = deviceAwaitingCameraPerm;
                    deviceAwaitingCameraPerm = null;
                    if (d != null) openOrRequest(d); else handleStartIntent(getIntent());
                });

        setupVideo();
        setupOverlay();
        setupCamera();
        setupUsb();

        photoSaver = new PhotoSaver(this);
        videoRecorder = new VideoRecorder(this);

        // Launched by USB_DEVICE_ATTACHED (manifest filter) or by the launcher icon
        handleStartIntent(getIntent());

        // GitHub release check, at most once a day; shows a small badge, never a popup
        Updater.check(this, false, (r, err) -> {
            if (r == null || isFinishing()) return;
            TextView tvUpdate = findViewById(R.id.tv_update);
            tvUpdate.setText(getString(R.string.update_banner, r.version));
            tvUpdate.setVisibility(View.VISIBLE);
            tvUpdate.setOnClickListener(v -> {
                if (recording) return;            // installing restarts the app
                tvUpdate.setVisibility(View.GONE);
                UpdateUi.offer(this, r);
            });
        });
        Log.d(TAG, "MainActivity created");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleStartIntent(intent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientation(newConfig.orientation);
        overlayRoot.post(() -> Log.i(TAG, "screen: " + screenInfo()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshGalleryThumb();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Never leave a half-written file behind when the app goes to background
        stopRecording();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logPanel.stop();
        bg.shutdownNow();
        usbDeviceManager.unregister();
        camera.stop();
        camera.close();
    }

    // ------------------------------------------------------------------ setup

    private void setupVideo() {
        renderer = new YuyvRenderer(this);
        flipMode = prefs.getInt(KEY_FLIP_MODE, prefs.getBoolean(KEY_FLIP, false) ? 1 : 0);
        renderer.flipX = (flipMode & 1) != 0;
        renderer.flipY = (flipMode & 2) != 0;
        renderer.rotation = prefs.getInt(KEY_ROTATION, 0);
        renderer.palette = prefs.getInt(KEY_PALETTE, Palettes.NONE);
        applyWarmth(prefs.getInt(KEY_WARMTH, 50));

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(new RecordableConfigChooser());
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setContentView(glView);
    }

    private void setupOverlay() {
        View overlay = getLayoutInflater().inflate(R.layout.overlay, null);
        addContentView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlayRoot   = overlay.findViewById(R.id.overlay_root);
        tvStatus      = overlay.findViewById(R.id.tv_status);
        tvFps         = overlay.findViewById(R.id.tv_fps);
        groupNoCamera = overlay.findViewById(R.id.group_no_camera);
        panelWb       = overlay.findViewById(R.id.panel_wb);
        btnShutter    = overlay.findViewById(R.id.btn_photo);
        btnFlip       = overlay.findViewById(R.id.btn_flip);
        btnRotate     = overlay.findViewById(R.id.btn_rotate);
        btnFlip.setOnClickListener(v -> showMirrorMenu(btnFlip));
        btnRotate.setOnClickListener(v -> rotate90());
        updateFlipButton();
        btnMode       = overlay.findViewById(R.id.btn_mode);
        tvRec         = overlay.findViewById(R.id.tv_rec);
        tvStatus.setOnClickListener(v -> showSourceSheet());
        btnGallery    = overlay.findViewById(R.id.btn_gallery);
        btnGallery.setOnClickListener(v -> openGallery());

        logPanel = new LogPanel(overlay.findViewById(R.id.panel_log), overlay.findViewById(R.id.tv_log));
        logPanel.start();
        btnLog = overlay.findViewById(R.id.btn_log);
        btnLog.setOnClickListener(v -> showMainMenu());
        btnLog.setOnLongClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("UvcTool log", logPanel.snapshot()));
            Toast.makeText(this, "Лог скопійовано", Toast.LENGTH_SHORT).show();
            return true;
        });

        portraitSet.clone(overlayRoot);
        buildLandscapeSet();
        // ConstraintSet also stores visibility and would restore the start-up state on
        // every rotation ("Підключіть ендоскоп" over live video, fps/REC/panels hidden).
        // Visibility of these views is runtime state: tell both sets to leave it alone.
        int[] runtimeVisibility = {
                R.id.group_no_camera, R.id.tv_fps, R.id.tv_rec, R.id.panel_wb, R.id.panel_log,
                R.id.tv_update };
        for (int id : runtimeVisibility) {
            portraitSet.setVisibilityMode(id, ConstraintSet.VISIBILITY_MODE_IGNORE);
            landscapeSet.setVisibilityMode(id, ConstraintSet.VISIBILITY_MODE_IGNORE);
        }
        applyOrientation(getResources().getConfiguration().orientation);
        overlayRoot.post(() -> Log.i(TAG, "screen: " + screenInfo()));

        // Keep the UI clear of system bars and the display cutout (notch / hole).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(overlayRoot, (v, insets) -> {
            androidx.core.graphics.Insets in = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(in.left, in.top, in.right, in.bottom);
            Log.i(TAG, "insets l=" + in.left + " t=" + in.top + " r=" + in.right + " b=" + in.bottom);
            return insets;
        });
        // Re-place the picture whenever the controls move or show/hide (rotation, WB slider...).
        overlayRoot.getViewTreeObserver().addOnGlobalLayoutListener(this::updateVideoLayout);


        videoMode = prefs.getBoolean(KEY_VIDEO_MODE, false);
        updateShutter();
        btnShutter.setOnClickListener(v -> onShutter());
        btnMode.setOnClickListener(v -> {
            if (recording) return;
            videoMode = !videoMode;
            prefs.edit().putBoolean(KEY_VIDEO_MODE, videoMode).apply();
            updateShutter();
            Toast.makeText(this, videoMode ? R.string.toast_mode_video : R.string.toast_mode_photo,
                    Toast.LENGTH_SHORT).show();
        });
        overlay.findViewById(R.id.btn_connect).setOnClickListener(v -> connectCamera());


        setupImageSliders(overlay);


        showNoCamera(true);
    }

    /** Landscape = same views, panel docked on the right edge, WB panel to its left. */
    private void buildLandscapeSet() {
        landscapeSet.clone(portraitSet);

        landscapeSet.clear(R.id.panel_controls, ConstraintSet.START);
        landscapeSet.connect(R.id.panel_controls, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
        landscapeSet.connect(R.id.panel_controls, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
        landscapeSet.connect(R.id.panel_controls, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);

        landscapeSet.clear(R.id.panel_wb, ConstraintSet.START);
        landscapeSet.clear(R.id.panel_wb, ConstraintSet.END);
        landscapeSet.clear(R.id.panel_wb, ConstraintSet.BOTTOM);
        landscapeSet.constrainWidth(R.id.panel_wb, ConstraintSet.WRAP_CONTENT);
        landscapeSet.constrainHeight(R.id.panel_wb, ConstraintSet.MATCH_CONSTRAINT);
        // pinned to the control column: right beside it, vertically centred on it
        landscapeSet.connect(R.id.panel_wb, ConstraintSet.END, R.id.panel_controls, ConstraintSet.START, dp(4));
        landscapeSet.connect(R.id.panel_wb, ConstraintSet.TOP, R.id.panel_controls, ConstraintSet.TOP, 0);
        landscapeSet.connect(R.id.panel_wb, ConstraintSet.BOTTOM, R.id.panel_controls, ConstraintSet.BOTTOM, 0);
        landscapeSet.setMargin(R.id.panel_wb, ConstraintSet.START, 0);

        // log toggle sits left of the control column; log panel never covers the controls
        landscapeSet.connect(R.id.btn_log, ConstraintSet.END, R.id.panel_controls, ConstraintSet.START, dp(8));
        landscapeSet.connect(R.id.panel_log, ConstraintSet.END, R.id.panel_controls, ConstraintSet.START, dp(8));
        landscapeSet.constrainPercentHeight(R.id.panel_log, 0.75f);
    }

    private void applyOrientation(int orientation) {
        boolean land = orientation == Configuration.ORIENTATION_LANDSCAPE;
        (land ? landscapeSet : portraitSet).applyTo(overlayRoot);
        android.widget.LinearLayout panel = overlayRoot.findViewById(R.id.panel_controls);
        panel.setOrientation(land ? android.widget.LinearLayout.VERTICAL
                                  : android.widget.LinearLayout.HORIZONTAL);

        // Sizes are physical (mm), not dp: the shutter must be at least 12 x 12 mm.
        // Full panel: flip | rotate | SHUTTER | mode | gallery. If that does not fit
        // along the panel axis at those sizes: mode | SHUTTER | gallery (the rest is
        // in the menu).
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Configuration c = getResources().getConfiguration();
        float dpi = land ? dm.ydpi : dm.xdpi;
        if (dpi < dm.densityDpi * 0.7f || dpi > dm.densityDpi * 1.3f) dpi = dm.densityDpi;  // bogus xdpi/ydpi
        float mm = dpi / 25.4f;                                     // px per mm
        // panel margin is 6dp on each side (overlay.xml)
        float axisPx = (land ? c.screenHeightDp : c.screenWidthDp) * dm.density - dp(12);
        final float SHUTTER = 12f, BTN = 8f, BTN_MIN = 7f, GAP = 2f, GAP_SHUTTER = 2.5f, PAD = 2.5f;
        float fullMm = 2 * PAD + SHUTTER + 4 * BTN + 2 * GAP + 2 * GAP_SHUTTER;
        boolean compact = fullMm * mm > axisPx;
        float btnMm = BTN;
        if (compact) {
            float compactMm = 2 * PAD + SHUTTER + 2 * BTN + 2 * GAP_SHUTTER;
            if (compactMm * mm > axisPx)                            // tiny screen: shrink side buttons
                btnMm = Math.max(BTN_MIN, (axisPx / mm - 2 * PAD - SHUTTER - 2 * GAP_SHUTTER) / 2);
        }
        View[] order = compact
                ? new View[]{ btnMode, btnShutter, btnGallery }
                : new View[]{ btnFlip, btnRotate, btnShutter, btnMode, btnGallery };
        panel.removeAllViews();
        for (View v : order) panel.addView(v);
        btnFlip.setVisibility(compact ? View.GONE : View.VISIBLE);
        btnRotate.setVisibility(compact ? View.GONE : View.VISIBLE);

        int pad = Math.round(PAD * mm), across = Math.round(1.5f * mm);
        if (land) panel.setPadding(across, pad, across, pad); else panel.setPadding(pad, across, pad, across);
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            boolean isShutter = child.getId() == R.id.btn_photo;
            boolean afterShutter = i > 0 && panel.getChildAt(i - 1).getId() == R.id.btn_photo;
            int size = Math.round((isShutter ? SHUTTER : btnMm) * mm);
            int before = (i == 0) ? 0 : Math.round((isShutter || afterShutter ? GAP_SHUTTER : GAP) * mm);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            lp.width = lp.height = size;
            lp.leftMargin = lp.rightMargin = lp.topMargin = lp.bottomMargin = 0;
            if (land) lp.topMargin = before; else lp.leftMargin = before;
            child.setLayoutParams(lp);
        }
        layoutAdjustPanel(land);
        Log.i(TAG, String.format(Locale.US,
                "panel: %s, axis %.1f mm, shutter %.1f mm, buttons %.1f mm (dpi %.0f, full needs %.1f mm)",
                compact ? "compact" : "full", axisPx / mm, SHUTTER, btnMm, dpi, fullMm));
    }

    private void setupCamera() {
        camera = new UvcCamera();
        camera.setFrameListener((buf, width, height) -> {
            // JNI thread. Hand the buffer to GL and wake the render loop.
            renderer.submitFrame(buf, width, height);
            glView.requestRender();
            countFps();
            if (!streaming) {
                streaming = true;
                runOnUiThread(() -> setStatus(streamingStatus(), R.color.text_ok));
                runOnUiThread(() -> tvFps.setVisibility(View.VISIBLE));
            }
        });
        camera.setButtonListener(this::onShutter);       // cable button = shutter
        // Green/purple picture (stream one byte off): restart the stream with the
        // same mode. Not while recording (it would cut the file); at most every 5 s.
        camera.setGlitchListener(kind -> {
            long now = android.os.SystemClock.elapsedRealtime();
            if (recording || !cameraOpen || now - lastGlitchRestart < 5000) {
                Log.w(TAG, "glitch: restart skipped (recording=" + recording + ")");
                return;
            }
            lastGlitchRestart = now;
            glitchRestarts++;
            UvcMode m = camera.getMode();
            Log.w(TAG, "glitch: restarting stream (#" + glitchRestarts + ") " + (m != null ? m.label() : ""));
            camera.stop();
            streaming = false;
            if (m == null || !camera.start(m)) setStatus("Камера не перезапустилась — див. лог", R.color.text_error);
        });
        camera.setErrorListener((stage, message) -> {
            Log.e(TAG, "camera error [" + stage + "]: " + message);
            runOnUiThread(() -> {
                if ("streaming_timeout".equals(stage)) setStatus(getString(R.string.status_no_frames), R.color.text_warn);
                else setStatus(stage + ": " + message, R.color.text_error);
            });
        });
    }

    private void setupUsb() {
        usbDeviceManager = new UsbDeviceManager(this);
        usbDeviceManager.setListener(new UsbDeviceManager.Listener() {
            @Override public void onDeviceAttached(UsbDevice device) {
                if (cameraOpen) {
                    // another camera (e.g. on a hub) while one is running: just tell
                    Toast.makeText(MainActivity.this, getString(R.string.toast_camera_added,
                            UsbDeviceManager.label(device)), Toast.LENGTH_LONG).show();
                    return;
                }
                if (usbDeviceManager.hasPermission(device)) {
                    Log.d(TAG, "attach: permission already held -> open");
                    uiHandler.postDelayed(() -> { if (!cameraOpen) openCamera(device); }, 300);
                    return;
                }
                // The manifest ATTACHED filter makes the system show its own "open with
                // UVC Tool" dialog and then hand us the device via onNewIntent with
                // permission granted. Asking ourselves at the same time = two stacked
                // dialogs. So wait; ask only as a fallback when nothing arrived and no
                // dialog is on top of us.
                Log.d(TAG, "attach: waiting for system dialog / onNewIntent");
                setStatus(getString(R.string.status_found), R.color.text_secondary);
                scheduleAttachFallback(device, 3000);
            }
            @Override public void onDeviceDetached(UsbDevice device) {
                if (currentDevice != null && !currentDevice.getDeviceName().equals(device.getDeviceName())) {
                    Log.d(TAG, "detach of another camera, ours keeps running");
                    return;
                }
                currentDevice = null;
                runOnUiThread(MainActivity.this::stopRecording);
                streaming = false;
                cameraOpen = false;
                camera.stop();
                camera.close();
                runOnUiThread(() -> {
                    setStatus(getString(R.string.status_detached), R.color.text_warn);
                    tvFps.setVisibility(View.GONE);
                    showNoCamera(true);
                });
            }
            @Override public void onPermissionGranted(UsbDevice device) {
                runOnUiThread(() -> openCamera(device));
            }
            @Override public void onPermissionDenied() {
                runOnUiThread(() -> {
                    setStatus(getString(R.string.status_denied), R.color.text_error);
                    showNoCamera(true);
                });
            }
        });
        usbDeviceManager.register();
    }

    // ------------------------------------------------------------------ USB flow

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void handleStartIntent(Intent intent) {
        UsbDevice fromIntent = usbDeviceManager.getTargetDeviceFromIntent(intent);
        if (!hasCameraPermission()) {
            Log.d(TAG, "no CAMERA permission yet -> asking first");
            deviceAwaitingCameraPerm = (fromIntent != null) ? fromIntent : preferredDevice();
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
            return;
        }
        if (fromIntent != null) {
            // Launched by the system for this device; normally permission is already
            // granted, openOrRequest() falls back to asking if it is not.
            openOrRequest(fromIntent);
            return;
        }
        UsbDevice device = preferredDevice();
        if (device != null) openOrRequest(device);
        else if (usbDeviceManager.findDevices().size() > 1) showCameraPicker();
        else showNoCamera(true);
    }

    private void scheduleAttachFallback(UsbDevice device, long delayMs) {
        uiHandler.postDelayed(() -> {
            if (cameraOpen || usbDeviceManager.findDevices().isEmpty()) return;
            if (!resumed) {               // a system dialog is still up: check again later
                scheduleAttachFallback(device, 1000);
                return;
            }
            Log.d(TAG, "attach fallback: nothing arrived, asking ourselves");
            openOrRequest(device);
        }, delayMs);
    }

    private void openOrRequest(UsbDevice device) {
        if (cameraOpen) { Log.d(TAG, "openOrRequest: camera already open, ignoring"); return; }
        if (!hasCameraPermission()) {
            deviceAwaitingCameraPerm = device;
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
            return;
        }
        if (usbDeviceManager.hasPermission(device)) {
            openCamera(device);
            return;
        }
        // hasPermission can lie after a re-attach; a real openDevice is the ground truth.
        android.hardware.usb.UsbDeviceConnection probe = usbManager.openDevice(device);
        if (probe != null) {
            probe.close();
            openCamera(device);
            return;
        }
        setStatus(getString(R.string.status_found), R.color.text_secondary);
        usbDeviceManager.requestPermission(device);
    }

    private void openCamera(UsbDevice device) {
        if (cameraOpen) { Log.d(TAG, "openCamera: already open, ignoring"); return; }
        cameraOpen = true;
        showNoCamera(false);
        setStatus(getString(R.string.status_opening), R.color.text_secondary);
        streaming = false;
        boolean ok = camera.open(usbManager, device);
        Log.d(TAG, "camera.open=" + ok);
        UvcMode mode = ok ? chooseMode(device) : null;
        if (ok && mode == null) {
            ok = false;
            Log.e(TAG, "no supported mode on " + UsbDeviceManager.label(device));
        }
        if (ok) ok = camera.start(mode);
        Log.d(TAG, "camera.start=" + ok);
        if (ok) {
            currentDevice = device;
            bindImageControls();
            // Thermal cameras (e.g. 256x384: picture on top, raw temperatures below)
            // start with the bottom half hidden; the choice is remembered per camera.
            boolean thermalLike = mode.width == 256 && mode.height == 384;
            renderer.cropTop = prefs.getBoolean(KEY_CROP_PREFIX + UsbDeviceManager.key(device), thermalLike);
            lastLayoutSig = null;
            prefs.edit().putString(KEY_LAST_CAMERA, UsbDeviceManager.key(device)).apply();
        }
        if (!ok) {
            // release the native handle AND the UsbDeviceConnection, otherwise the
            // claimed interface stays busy and every retry fails with "Busy"
            camera.stop();
            camera.close();
            cameraOpen = false;
            setStatus(mode == null && !camera.getModes().isEmpty()
                    ? getString(R.string.status_no_supported_mode) : "Камера не стартувала — див. лог",
                    R.color.text_error);
            showNoCamera(true);
        }
    }

    /** Remembered mode for this camera if it still offers it, else the default pick. */
    private UvcMode chooseMode(UsbDevice device) {
        String saved = prefs.getString(KEY_MODE_PREFIX + UsbDeviceManager.key(device), null);
        for (UvcMode m : camera.getModes()) if (m.supported() && m.sameAs(saved)) return m;
        return UvcMode.pickDefault(camera.getModes());
    }

    private UsbDevice preferredDevice() {
        return usbDeviceManager.findDevice(prefs.getString(KEY_LAST_CAMERA, null));
    }

    private String streamingStatus() {
        UvcMode m = camera.getMode();
        String mirror = flipMode == 1 ? "  ⇋" : flipMode == 2 ? "  ⇅" : flipMode == 3 ? "  ⇋⇅" : "";
        return (m != null ? m.sizeName() + " " + m.formatName() : getString(R.string.status_streaming)) + mirror + "  ▾";
    }

    // ------------------------------------------------------------------ camera / mode picker

    /** Tap on the status badge: cameras (when several) and the modes of the current one. */
    private void showSourceSheet() {
        java.util.List<UsbDevice> cams = usbDeviceManager.findDevices();
        if (!cameraOpen) {
            if (cams.size() > 1) showCameraPicker(); else connectCamera();
            return;
        }
        java.util.List<CharSequence> items = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        if (cams.size() > 1) {
            for (UsbDevice d : cams) {
                boolean cur = currentDevice != null && d.getDeviceName().equals(currentDevice.getDeviceName());
                items.add((cur ? "● " : "○ ") + UsbDeviceManager.label(d));
                actions.add(cur ? null : () -> switchCamera(d));
            }
        }
        UvcMode curMode = camera.getMode();
        for (UvcMode m : camera.getModes()) {
            boolean cur = curMode != null && m.sameAs(curMode.key());
            String text = (cur ? "✓ " : "    ") + m.label()
                    + (m.supported() ? "" : "  — " + getString(R.string.mode_unsupported));
            items.add(text);
            actions.add(cur ? null : m.supported() ? () -> switchMode(m)
                    : () -> Toast.makeText(this, R.string.mode_unsupported_toast, Toast.LENGTH_LONG).show());
        }
        String title = currentDevice != null ? UsbDeviceManager.label(currentDevice) : getString(R.string.picker_title);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    Runnable r = actions.get(which);
                    if (r != null) r.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** No camera open and several connected: choose which one. */
    private void showCameraPicker() {
        java.util.List<UsbDevice> cams = usbDeviceManager.findDevices();
        if (cams.isEmpty()) { showNoCamera(true); return; }
        CharSequence[] names = new CharSequence[cams.size()];
        for (int i = 0; i < names.length; i++) names[i] = UsbDeviceManager.label(cams.get(i));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.picker_title)
                .setItems(names, (d, which) -> openOrRequest(cams.get(which)))
                .setNegativeButton(android.R.string.cancel, (d, w) -> showNoCamera(true))
                .show();
    }

    private void switchCamera(UsbDevice device) {
        Log.i(TAG, "switch camera -> " + UsbDeviceManager.label(device));
        stopRecording();
        camera.stop();
        camera.close();
        streaming = false;
        cameraOpen = false;
        currentDevice = null;
        tvFps.setVisibility(View.GONE);
        openOrRequest(device);
    }

    private void switchMode(UvcMode m) {
        if (recording) { Toast.makeText(this, R.string.toast_stop_rec_first, Toast.LENGTH_SHORT).show(); return; }
        Log.i(TAG, "switch mode -> " + m.label());
        camera.stop();
        streaming = false;
        setStatus(getString(R.string.status_opening), R.color.text_secondary);
        if (camera.start(m)) {
            if (currentDevice != null)
                prefs.edit().putString(KEY_MODE_PREFIX + UsbDeviceManager.key(currentDevice), m.key()).apply();
        } else {
            setStatus("Режим не стартував — див. лог", R.color.text_error);
        }
    }

    private void connectCamera() {
        UsbDevice device = preferredDevice();
        if (device == null && usbDeviceManager.findDevices().size() > 1) { showCameraPicker(); return; }
        if (device == null) {
            setStatus(getString(R.string.status_no_camera), R.color.text_warn);
            return;
        }
        openOrRequest(device);
    }

    // ------------------------------------------------------------------ actions

    /** Shutter and the camera's cable button: photo, or start/stop recording. */
    private void onShutter() {
        if (videoMode) { if (recording) stopRecording(); else startRecording(); }
        else takePhoto();
    }

    /** Shutter look follows the mode: camera icon / red dot / stop square while recording. */
    private void updateShutter() {
        if (videoMode) {
            btnShutter.setImageResource(recording ? R.drawable.ic_stop : R.drawable.ic_shutter_rec);
            btnShutter.setBackground(ContextCompat.getDrawable(this,
                    recording ? R.drawable.bg_btn_rec_active : R.drawable.bg_btn_shutter));
        } else {
            btnShutter.setImageResource(R.drawable.ic_photo);
            btnShutter.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_btn_shutter));
        }
        // the toggle shows the mode you switch TO
        btnMode.setImageResource(videoMode ? R.drawable.ic_mode_photo : R.drawable.ic_mode_video);
        btnMode.setEnabled(!recording);
        btnMode.setAlpha(recording ? 0.35f : 1f);
    }

    private void takePhoto() {
        if (!streaming) return;
        beeper.photo();
        renderer.requestCapture((x, y, w, h) -> {
            boolean ok = photoSaver.captureFromGl(x, y, w, h);
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? R.string.toast_photo_saved : R.string.toast_photo_failed,
                        Toast.LENGTH_SHORT).show();
                if (ok) refreshGalleryThumb();
            });
        });
        glView.requestRender();
    }

    private void startRecording() {
        if (recording || !streaming) return;
        glView.queueEvent(() -> {
            boolean ok = renderer.startRecording(videoRecorder);
            runOnUiThread(() -> {
                if (!ok) {
                    Toast.makeText(this, R.string.toast_rec_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                recording = true;
                beeper.recStart();
                updateShutter();
                btnRotate.setEnabled(false);       // output size is fixed for the file
                btnRotate.setAlpha(0.35f);
                btnGallery.setEnabled(false);      // leaving the screen ends the recording
                btnGallery.setAlpha(0.35f);
                tvRec.setText("● 00:00");
                tvRec.setVisibility(View.VISIBLE);
                uiHandler.post(recTick);
            });
        });
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        uiHandler.removeCallbacks(recTick);
        tvRec.setVisibility(View.GONE);
        beeper.recStop();
        updateShutter();
        btnRotate.setEnabled(true);
        btnRotate.setAlpha(1f);
        btnGallery.setEnabled(true);
        btnGallery.setAlpha(1f);
        glView.queueEvent(() -> {
            renderer.stopRecording();
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.toast_rec_saved, Toast.LENGTH_SHORT).show();
                refreshGalleryThumb();
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * RGBA8888 + ES2 + EGL_RECORDABLE_ANDROID, so the same config can back both
     * the on-screen surface and the MediaCodec input surface. Falls back to a
     * plain config if the device has no recordable one.
     */
    private static class RecordableConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        private static final int EGL_OPENGL_ES2_BIT = 4;

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[] recordable = {
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1, EGL10.EGL_NONE };
            EGLConfig c = pick(egl, display, recordable);
            if (c != null) return c;
            Log.w(TAG, "no recordable EGL config, recording may fail");
            int[] plain = {
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE };
            c = pick(egl, display, plain);
            if (c == null) throw new IllegalStateException("no usable EGL config");
            return c;
        }

        private static EGLConfig pick(EGL10 egl, EGLDisplay d, int[] attrs) {
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] n = new int[1];
            return (egl.eglChooseConfig(d, attrs, cfgs, 1, n) && n[0] > 0) ? cfgs[0] : null;
        }
    }

    /** 0 = cold (boost blue), 50 = neutral, 100 = warm (boost red). */
    private void applyWarmth(int p) {
        float t = (p - 50) / 50f;            // -1 .. +1
        renderer.wbGain[0] = 1f + 0.35f * t; // R
        renderer.wbGain[1] = 1f;
        renderer.wbGain[2] = 1f - 0.35f * t; // B
    }

    // ------------------------------------------------------------------ problem report

    private void showReportDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_report, null);
        android.widget.EditText et = v.findViewById(R.id.et_comment);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_report)
                .setView(v)
                .setPositiveButton(R.string.report_github, (d, w) -> sendReport(et.getText().toString(), true))
                .setNeutralButton(R.string.report_share, (d, w) -> sendReport(et.getText().toString(), false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void sendReport(String comment, boolean github) {
        ReportBuilder.CameraState cs = new ReportBuilder.CameraState();
        cs.device = currentDevice;
        cs.mode = camera.getMode();
        cs.modes = camera.getModes();
        cs.fps = tvFps.getVisibility() == View.VISIBLE ? tvFps.getText().toString() : null;
        cs.streaming = streaming;
        cs.recording = recording;
        cs.videoMode = videoMode;
        cs.screen = screenInfo();
        String title = ReportBuilder.title(comment);
        String body = ReportBuilder.body(this, comment, cs, logPanel.snapshot());
        // the full text always goes to the clipboard: the GitHub URL may carry a shortened log
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(title, body));
        try {
            if (github) {
                startActivity(new Intent(Intent.ACTION_VIEW, ReportBuilder.issueUrl(title, body)));
                Toast.makeText(this, R.string.report_opened, Toast.LENGTH_LONG).show();
            } else {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, title)
                        .putExtra(Intent.EXTRA_TEXT, body);
                startActivity(Intent.createChooser(send, getString(R.string.report_share)));
            }
        } catch (Exception e) {
            Log.e(TAG, "report: cannot open target", e);
            Toast.makeText(this, R.string.report_copied, Toast.LENGTH_LONG).show();
        }
    }

    /** Round gallery button shows the newest photo/video, or the gallery icon. */
    private void refreshGalleryThumb() {
        if (btnGallery == null || bg.isShutdown()) return;
        bg.execute(() -> {
            MediaRepo.Item last = MediaRepo.latest(this);
            android.graphics.Bitmap b = last != null ? MediaRepo.thumbnail(this, last, 160) : null;
            runOnUiThread(() -> {
                if (b != null) {
                    btnGallery.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                    btnGallery.setImageBitmap(b);
                } else {
                    btnGallery.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                    btnGallery.setImageResource(R.drawable.ic_gallery);
                }
            });
        });
    }

    // ------------------------------------------------------------------ main menu (top right)

    /**
     * Everything except the shutter and the photo/video toggle lives here, so the
     * bottom panel needs only three buttons and fits small phones.
     */
    private void showMainMenu() {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, btnLog);
        android.view.Menu m = pm.getMenu();
        m.add(0, 10, 0, R.string.menu_gallery).setEnabled(!recording);
        android.view.SubMenu mirror = m.addSubMenu(0, 20, 1, R.string.menu_mirror);
        int[] names = { R.string.mirror_off, R.string.mirror_h, R.string.mirror_v, R.string.mirror_hv };
        for (int i = 0; i < 4; i++) mirror.add(1, 21 + i, i, names[i]).setChecked(flipMode == i);
        mirror.setGroupCheckable(1, true, true);
        m.add(0, 30, 2, getString(R.string.menu_rotate, renderer.rotation)).setEnabled(!recording);
        android.view.SubMenu pal = m.addSubMenu(0, 50, 3, R.string.menu_palette);
        int[] pn = { R.string.pal_none, R.string.pal_white, R.string.pal_black, R.string.pal_iron, R.string.pal_rainbow };
        for (int i = 0; i < Palettes.COUNT; i++) pal.add(3, 51 + i, i, pn[i]).setChecked(renderer.palette == i);
        pal.setGroupCheckable(3, true, true);
        m.add(0, 60, 3, R.string.menu_crop_top).setCheckable(true).setChecked(renderer.cropTop)
                .setEnabled(!recording);
        m.add(0, 40, 3, R.string.menu_wb).setCheckable(true).setChecked(panelWb.getVisibility() == View.VISIBLE);
        m.add(2, 1, 4, R.string.menu_log).setCheckable(true).setChecked(logPanel.isShown());
        if (Updater.enabled()) m.add(2, 2, 5, R.string.update_check);
        m.add(2, 3, 6, R.string.menu_report);
        m.add(2, 4, 7, R.string.cd_about);
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= 21 && id <= 24) { setFlipMode(id - 21); return true; }
            if (id >= 51 && id < 51 + Palettes.COUNT) {
                renderer.palette = id - 51;
                prefs.edit().putInt(KEY_PALETTE, renderer.palette).apply();
                glView.requestRender();
                return true;
            }
            switch (id) {
                case 10: openGallery(); return true;
                case 60:
                    renderer.cropTop = !renderer.cropTop;
                    if (currentDevice != null) prefs.edit().putBoolean(
                            KEY_CROP_PREFIX + UsbDeviceManager.key(currentDevice), renderer.cropTop).apply();
                    lastLayoutSig = null;
                    glView.requestRender();
                    return true;
                case 30: rotate90(); return true;
                case 40:
                    panelWb.setVisibility(panelWb.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    return true;
                case 1: {
                    boolean shown = logPanel.toggle();
                    btnLog.setBackground(ContextCompat.getDrawable(this,
                            shown ? R.drawable.bg_btn_active : R.drawable.bg_badge));
                    return true;
                }
                case 2: UpdateUi.checkNow(this); return true;
                case 3: showReportDialog(); return true;
                case 4: UpdateUi.about(this); return true;
            }
            return false;
        });
        pm.show();
    }

    /** Tell the renderer where the controls are so the picture can sit beside them. */
    private void updateVideoLayout() {
        View root = overlayRoot;
        if (root == null || root.getWidth() == 0) return;
        int[] bounds = { root.getPaddingLeft(), root.getPaddingTop(),
                root.getWidth() - root.getPaddingRight(), root.getHeight() - root.getPaddingBottom() };
        int gap = dp(4);
        int[][] hard = { rectOf(R.id.panel_controls, gap), rectOf(R.id.panel_wb, gap) };
        int[][] soft = { union(rectOf(R.id.tv_status, gap), rectOf(R.id.tv_fps, gap),
                               rectOf(R.id.tv_rec, gap), rectOf(R.id.tv_update, gap)),
                         rectOf(R.id.btn_log, gap) };
        String sig = java.util.Arrays.toString(bounds) + java.util.Arrays.deepToString(hard)
                + java.util.Arrays.deepToString(soft);
        if (sig.equals(lastLayoutSig)) return;
        lastLayoutSig = sig;
        Log.i(TAG, "video area " + sig);
        renderer.setLayoutHints(bounds, hard, soft);
        glView.requestRender();
    }

    private String lastLayoutSig;

    /** Bounds of a visible overlay child (+gap), or null if gone. */
    private int[] rectOf(int id, int gap) {
        View v = overlayRoot.findViewById(id);
        if (v == null || v.getVisibility() != View.VISIBLE || v.getWidth() == 0) return null;
        return new int[]{ v.getLeft() - gap, v.getTop() - gap, v.getRight() + gap, v.getBottom() + gap };
    }

    private static int[] union(int[]... rs) {
        int[] u = null;
        for (int[] r : rs) {
            if (r == null) continue;
            if (u == null) u = r.clone();
            else { u[0] = Math.min(u[0], r[0]); u[1] = Math.min(u[1], r[1]);
                   u[2] = Math.max(u[2], r[2]); u[3] = Math.max(u[3], r[3]); }
        }
        return u;
    }

    // ------------------------------------------------------------------ picture adjustments
    // One slider; the chips above choose what it controls: warmth, brightness, contrast.

    private static final int ADJ_WARMTH = 0, ADJ_BRIGHT = 1, ADJ_CONTRAST = 2;
    private static final String KEY_SW_BRIGHT = "sw_bright", KEY_SW_CONTRAST = "sw_contrast";
    private static final String KEY_ADJ = "adj_selected";
    private SeekBar sbAdjust;
    private TextView adjLeft, adjRight, adjValueText;
    private TextView[] adjChips;
    private int adjSel = ADJ_WARMTH;
    private final int[] adjValue = { 50, 50, 50 };          // slider positions 0..100
    /** Hardware ranges {min, max, def, cur} or null -> software in the shader. */
    private int[] hwBright, hwContrast;

    private void setupImageSliders(View overlay) {
        sbAdjust = overlay.findViewById(R.id.sb_adjust);
        adjLeft = overlay.findViewById(R.id.adj_left);
        adjRight = overlay.findViewById(R.id.adj_right);
        adjValueText = overlay.findViewById(R.id.adj_value);
        adjChips = new TextView[]{ overlay.findViewById(R.id.adj_warmth),
                overlay.findViewById(R.id.adj_bright), overlay.findViewById(R.id.adj_contrast) };
        for (int i = 0; i < 3; i++) { final int k = i; adjChips[i].setOnClickListener(v -> selectAdj(k)); }
        overlay.findViewById(R.id.adj_reset).setOnClickListener(v -> resetImageControls());
        overlay.findViewById(R.id.adj_reset_v).setOnClickListener(v -> resetImageControls());
        overlay.findViewById(R.id.adj_cycle).setOnClickListener(v -> selectAdj((adjSel + 1) % 3));
        adjValue[ADJ_WARMTH] = prefs.getInt(KEY_WARMTH, 50);
        sbAdjust.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                adjValue[adjSel] = p;
                applyAdj(adjSel, p);
                showAdjValue();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveAdj(); }
        });
        bindImageControls();
        selectAdj(prefs.getInt(KEY_ADJ, ADJ_WARMTH));
        layoutAdjustPanel(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    /**
     * Portrait: chips in a row above a horizontal slider. Landscape: chips in a
     * column, and a vertical slider running along the control column.
     */
    private void layoutAdjustPanel(boolean land) {
        android.widget.LinearLayout pw = (android.widget.LinearLayout) panelWb;
        android.widget.LinearLayout chips = overlayRoot.findViewById(R.id.adj_chips);
        android.widget.LinearLayout row = overlayRoot.findViewById(R.id.adj_slider_row);
        if (pw == null || chips == null || row == null || sbAdjust == null) return;
        final int W = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT;
        final int M = android.widget.LinearLayout.LayoutParams.MATCH_PARENT;
        pw.setOrientation(land ? android.widget.LinearLayout.HORIZONTAL : android.widget.LinearLayout.VERTICAL);
        chips.setOrientation(land ? android.widget.LinearLayout.VERTICAL : android.widget.LinearLayout.HORIZONTAL);
        chips.setLayoutParams(new android.widget.LinearLayout.LayoutParams(land ? W : M, land ? M : W));
        chips.setGravity(android.view.Gravity.CENTER);
        for (int i = 0; i < chips.getChildCount(); i++) {
            View ch = chips.getChildAt(i);
            boolean reset = ch.getId() == R.id.adj_reset;
            android.widget.LinearLayout.LayoutParams lp = land
                    ? new android.widget.LinearLayout.LayoutParams(dp(104), dp(46))
                    : new android.widget.LinearLayout.LayoutParams(reset ? dp(50) : 0, dp(46), reset ? 0 : 1);
            if (land) { lp.topMargin = lp.bottomMargin = dp(3); } else { lp.leftMargin = lp.rightMargin = dp(2); }
            ch.setLayoutParams(lp);
        }

        View left = row.findViewById(R.id.adj_left), right = row.findViewById(R.id.adj_right),
             value = row.findViewById(R.id.adj_value),
             resetV = row.findViewById(R.id.adj_reset_v), cycle = row.findViewById(R.id.adj_cycle);
        // landscape: no chip column, the narrow slider column carries everything
        chips.setVisibility(land ? View.GONE : View.VISIBLE);
        resetV.setVisibility(land ? View.VISIBLE : View.GONE);
        cycle.setVisibility(land ? View.VISIBLE : View.GONE);
        row.removeAllViews();
        row.setOrientation(land ? android.widget.LinearLayout.VERTICAL : android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(land ? W : M, land ? M : W);
        if (!land) rlp.topMargin = dp(4);
        row.setLayoutParams(rlp);
        ((OrientableSeekBar) sbAdjust).setVertical(land);
        if (land) {
            // top: reset, value, high end ... slider ... low end, parameter switch
            row.addView(resetV, new android.widget.LinearLayout.LayoutParams(dp(44), dp(44)));
            row.addView(value, new android.widget.LinearLayout.LayoutParams(dp(56), W));
            row.addView(right, new android.widget.LinearLayout.LayoutParams(dp(56), W));
            row.addView(sbAdjust, new android.widget.LinearLayout.LayoutParams(dp(52), 0, 1));
            row.addView(left, new android.widget.LinearLayout.LayoutParams(dp(56), W));
            row.addView(cycle, new android.widget.LinearLayout.LayoutParams(dp(44), dp(44)));
            ((TextView) value).setGravity(android.view.Gravity.CENTER);
        } else {
            row.addView(resetV);      // gone in portrait, kept in the tree
            row.addView(cycle);
            row.addView(left, new android.widget.LinearLayout.LayoutParams(dp(28), W));
            row.addView(sbAdjust, new android.widget.LinearLayout.LayoutParams(0, dp(52), 1));
            row.addView(right, new android.widget.LinearLayout.LayoutParams(dp(28), W));
            row.addView(value, new android.widget.LinearLayout.LayoutParams(dp(52), W));
            ((TextView) value).setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        }
    }

    private void selectAdj(int k) {
        adjSel = k;
        prefs.edit().putInt(KEY_ADJ, k).apply();
        for (int i = 0; i < 3; i++) {
            adjChips[i].setBackground(ContextCompat.getDrawable(this,
                    i == k ? R.drawable.bg_btn_active : R.drawable.bg_badge));
        }
        String[][] icons = { {"❄", "☀"}, {"☾", "☼"}, {"◐", "◑"} };
        ImageButton cyc = overlayRoot.findViewById(R.id.adj_cycle);
        if (cyc != null) {
            int[] ic = { R.drawable.ic_adj_warmth, R.drawable.ic_adj_bright, R.drawable.ic_adj_contrast };
            cyc.setImageResource(ic[k]);
        }
        adjLeft.setText(icons[k][0]);
        adjRight.setText(icons[k][1]);
        sbAdjust.setProgress(adjValue[k]);
        showAdjValue();
    }

    /** Number next to the slider: offset from neutral, contrast as a factor, or the camera's own value. */
    private void showAdjValue() {
        if (adjValueText == null) return;
        adjValueText.setText(String.format(Locale.US, "%+d", adjValue[adjSel] - 50));   // relative, 0 = neutral
    }

    /** After (re)opening a camera: hardware controls if it has them, else the shader. */
    private void bindImageControls() {
        hwBright = cameraOpen ? camera.getCtrl(UvcCamera.CTRL_BRIGHTNESS) : null;
        hwContrast = cameraOpen ? camera.getCtrl(UvcCamera.CTRL_CONTRAST) : null;
        Log.i(TAG, "image controls: brightness " + (hwBright != null ? "camera" : "software")
                + ", contrast " + (hwContrast != null ? "camera" : "software"));
        // every slider is relative: 50 = neutral (camera default / no change)
        adjValue[ADJ_BRIGHT] = prefs.getInt(KEY_SW_BRIGHT, 50);
        adjValue[ADJ_CONTRAST] = prefs.getInt(KEY_SW_CONTRAST, 50);
        for (int i = 0; i < 3; i++) applyAdj(i, adjValue[i]);
        if (sbAdjust != null) { sbAdjust.setProgress(adjValue[adjSel]); showAdjValue(); }
    }

    private void applyAdj(int which, int p) {
        if (which == ADJ_WARMTH) {
            applyWarmth(p);
        } else {
            int[] hw = which == ADJ_BRIGHT ? hwBright : hwContrast;
            int ctrl = which == ADJ_BRIGHT ? UvcCamera.CTRL_BRIGHTNESS : UvcCamera.CTRL_CONTRAST;
            // Relative scale, 50 = neutral. Upper half: camera default -> camera max.
            // Lower half: camera min -> default, but many cameras report min == default
            // (the endoscope: 128..255, default 128); then the lower half is done in the
            // shader instead, so the whole slider always does something.
            float sw = 0f;                                          // -1 .. +1 software part
            if (hw != null) {
                // the endoscope even reports brightness min=181 max=255 default=128:
                // neutral is the camera's default as-is, whatever range it claims
                int min = hw[0], max = hw[1], def = hw[2];
                int step = Math.max(1, (max - min) / 10);
                boolean hwAbove = max - def >= step, hwBelow = def - min >= step;
                int v = def;
                if (p >= 50) { if (hwAbove) v = def + Math.round((max - def) * (p - 50) / 50f); else sw = (p - 50) / 50f; }
                else         { if (hwBelow) v = min + Math.round((def - min) * p / 50f);       else sw = (p - 50) / 50f; }
                camera.setCtrl(ctrl, v);
            } else {
                sw = (p - 50) / 50f;
            }
            if (which == ADJ_BRIGHT) renderer.swBright = sw * 0.5f;              // -0.5 .. +0.5
            else renderer.swContrast = (float) Math.pow(2.0, sw);                // 0.5 .. 2
        }
        glView.requestRender();
    }

    private void saveAdj() {
        prefs.edit().putInt(KEY_WARMTH, adjValue[ADJ_WARMTH])
                .putInt(KEY_SW_BRIGHT, adjValue[ADJ_BRIGHT])
                .putInt(KEY_SW_CONTRAST, adjValue[ADJ_CONTRAST]).apply();
    }

    private static int toPercent(int[] hw, int v) {
        return Math.round(100f * (v - hw[0]) / (hw[1] - hw[0]));
    }

    private void resetImageControls() {
        adjValue[ADJ_WARMTH] = 50;
        adjValue[ADJ_BRIGHT] = 50;
        adjValue[ADJ_CONTRAST] = 50;
        for (int i = 0; i < 3; i++) applyAdj(i, adjValue[i]);
        sbAdjust.setProgress(adjValue[adjSel]);
        showAdjValue();
        saveAdj();
        Toast.makeText(this, R.string.toast_image_reset, Toast.LENGTH_SHORT).show();
    }

    private void rotate90() {
        if (recording) return;                 // output size is fixed for the file
        renderer.rotation = (renderer.rotation + 90) % 360;
        prefs.edit().putInt(KEY_ROTATION, renderer.rotation).apply();
        lastLayoutSig = null;                  // aspect changed: re-place the picture
        glView.requestRender();
    }

    private void showMirrorMenu(View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        int[] names = { R.string.mirror_off, R.string.mirror_h, R.string.mirror_v, R.string.mirror_hv };
        for (int i = 0; i < 4; i++) pm.getMenu().add(1, 21 + i, i, names[i]).setChecked(flipMode == i);
        pm.getMenu().setGroupCheckable(1, true, true);
        pm.setOnMenuItemClickListener(item -> { setFlipMode(item.getItemId() - 21); return true; });
        pm.show();
    }

    private void updateFlipButton() {
        if (btnFlip == null) return;
        btnFlip.setBackground(ContextCompat.getDrawable(this,
                flipMode != 0 ? R.drawable.bg_btn_active : R.drawable.bg_btn_round));
    }

    private void setFlipMode(int mode) {
        flipMode = mode;
        renderer.flipX = (mode & 1) != 0;
        renderer.flipY = (mode & 2) != 0;
        prefs.edit().putInt(KEY_FLIP_MODE, mode).apply();
        updateFlipButton();
        glView.requestRender();
        if (streaming) setStatus(streamingStatus(), R.color.text_ok);
    }

    private void openGallery() {
        // leaving the screen would stop the recording (onStop)
        if (recording) return;
        startActivity(new Intent(this, GalleryActivity.class));
    }

    /** Screen / viewport facts for the log and the problem report. */
    String screenInfo() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Configuration c = getResources().getConfiguration();
        String phys = "?";
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b = getWindowManager().getMaximumWindowMetrics().getBounds();
            double in = Math.hypot(b.width() / (double) dm.xdpi, b.height() / (double) dm.ydpi);
            phys = b.width() + "x" + b.height() + String.format(Locale.US, " (~%.1f\")", in);
        }
        View root = overlayRoot;
        return String.format(Locale.US,
                "display %s px, %d dpi (x%.0f y%.0f), density %.2f; window %dx%d dp, sw %ddp, %s; overlay %dx%d px",
                phys, dm.densityDpi, dm.xdpi, dm.ydpi, dm.density,
                c.screenWidthDp, c.screenHeightDp, c.smallestScreenWidthDp,
                c.orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait",
                root != null ? root.getWidth() : 0, root != null ? root.getHeight() : 0);
    }

    private void showNoCamera(boolean show) {
        groupNoCamera.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) tvFps.setVisibility(View.GONE);
    }

    private void setStatus(String text, int colorRes) {
        tvStatus.setText(text);
        tvStatus.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    private void countFps() {
        long now = System.currentTimeMillis();
        if (fpsWindowStart == 0) fpsWindowStart = now;
        frameCount++;
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 1000) {
            float fps = frameCount * 1000f / elapsed;
            frameCount = 0;
            fpsWindowStart = now;
            runOnUiThread(() -> tvFps.setText(String.format(Locale.US, "%.1f fps", fps)));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
