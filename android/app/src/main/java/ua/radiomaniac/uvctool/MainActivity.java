package ua.radiomaniac.uvctool;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.opengl.GLSurfaceView;
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
    private static final String KEY_FLIP = "flip_x";
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
    private ImageButton btnFlip, btnWb, btnRotate, btnShutter, btnMode;
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
        renderer.flipX = prefs.getBoolean(KEY_FLIP, false);
        renderer.rotation = prefs.getInt(KEY_ROTATION, 0);
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
        btnFlip       = overlay.findViewById(R.id.btn_flip);
        btnWb         = overlay.findViewById(R.id.btn_wb);
        btnShutter    = overlay.findViewById(R.id.btn_photo);
        btnMode       = overlay.findViewById(R.id.btn_mode);
        btnRotate     = overlay.findViewById(R.id.btn_rotate);
        tvRec         = overlay.findViewById(R.id.tv_rec);
        tvStatus.setOnClickListener(v -> showSourceSheet());
        btnGallery    = overlay.findViewById(R.id.btn_gallery);
        btnGallery.setOnClickListener(v -> {
            // leaving the screen would stop the recording (onStop); the button is
            // disabled while recording, this is just a guard
            if (recording) return;
            startActivity(new Intent(this, GalleryActivity.class));
        });

        logPanel = new LogPanel(overlay.findViewById(R.id.panel_log), overlay.findViewById(R.id.tv_log));
        logPanel.start();
        ImageButton btnLog = overlay.findViewById(R.id.btn_log);
        btnLog.setOnClickListener(v -> {
            boolean shown = logPanel.toggle();
            btnLog.setBackground(ContextCompat.getDrawable(this,
                    shown ? R.drawable.bg_btn_active : R.drawable.bg_badge));
        });
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

        updateFlipButton();
        btnFlip.setOnClickListener(v -> {
            renderer.flipX = !renderer.flipX;
            prefs.edit().putBoolean(KEY_FLIP, renderer.flipX).apply();
            updateFlipButton();
            glView.requestRender();
        });

        btnRotate.setOnClickListener(v -> {
            renderer.rotation = (renderer.rotation + 90) % 360;
            prefs.edit().putInt(KEY_ROTATION, renderer.rotation).apply();
            glView.requestRender();
        });

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

        btnWb.setOnClickListener(v -> {
            boolean show = panelWb.getVisibility() != View.VISIBLE;
            panelWb.setVisibility(show ? View.VISIBLE : View.GONE);
            btnWb.setBackground(ContextCompat.getDrawable(this,
                    show ? R.drawable.bg_btn_active : R.drawable.bg_btn_round));
        });

        SeekBar sbWarmth = overlay.findViewById(R.id.sb_warmth);
        sbWarmth.setProgress(prefs.getInt(KEY_WARMTH, 50));
        sbWarmth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                applyWarmth(p);
                glView.requestRender();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putInt(KEY_WARMTH, sb.getProgress()).apply();
            }
        });

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
        landscapeSet.constrainWidth(R.id.panel_wb, dp(260));
        landscapeSet.connect(R.id.panel_wb, ConstraintSet.END, R.id.panel_controls, ConstraintSet.START, dp(8));
        landscapeSet.connect(R.id.panel_wb, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, dp(16));

        // log toggle sits left of the control column; log panel never covers the controls
        landscapeSet.connect(R.id.btn_log, ConstraintSet.END, R.id.panel_controls, ConstraintSet.START, dp(8));
        landscapeSet.connect(R.id.panel_log, ConstraintSet.END, R.id.panel_controls, ConstraintSet.START, dp(8));
        landscapeSet.constrainPercentHeight(R.id.panel_log, 0.75f);

        // gallery thumbnail: left of the control column, bottom, above the WB slider
        landscapeSet.clear(R.id.btn_gallery, ConstraintSet.START);
        landscapeSet.connect(R.id.btn_gallery, ConstraintSet.END, R.id.panel_controls, ConstraintSet.START, dp(8));
        landscapeSet.connect(R.id.btn_gallery, ConstraintSet.BOTTOM, R.id.panel_wb, ConstraintSet.TOP, dp(8));
    }

    private void applyOrientation(int orientation) {
        boolean land = orientation == Configuration.ORIENTATION_LANDSCAPE;
        (land ? landscapeSet : portraitSet).applyTo(overlayRoot);
        android.widget.LinearLayout panel = overlayRoot.findViewById(R.id.panel_controls);
        panel.setOrientation(land ? android.widget.LinearLayout.VERTICAL
                                  : android.widget.LinearLayout.HORIZONTAL);
        // Spacing along the panel axis: 14dp between buttons, 18dp around the shutter.
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            boolean isShutter = child.getId() == R.id.btn_photo;
            boolean afterShutter = i > 0 && panel.getChildAt(i - 1).getId() == R.id.btn_photo;
            int before = (i == 0) ? 0 : (isShutter || afterShutter ? dp(18) : dp(14));
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            lp.leftMargin = lp.rightMargin = lp.topMargin = lp.bottomMargin = 0;
            if (land) lp.topMargin = before; else lp.leftMargin = before;
            child.setLayoutParams(lp);
        }
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
        return (m != null ? m.sizeName() + " " + m.formatName() : getString(R.string.status_streaming)) + "  ▾";
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

    private void updateFlipButton() {
        btnFlip.setBackground(ContextCompat.getDrawable(this,
                renderer.flipX ? R.drawable.bg_btn_active : R.drawable.bg_btn_round));
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
