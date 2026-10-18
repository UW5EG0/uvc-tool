package ua.radiomaniac.uvctool;

import android.app.RecoverableSecurityException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen viewer for one saved photo or video, with pinch / double-tap zoom.
 * Swipe left/right (at zoom 1) moves through the same list the gallery shows.
 * Videos: play/pause, scrub, and frame-by-frame stepping while paused, so a
 * single frame can be zoomed and inspected.
 */
public class ViewerActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "UvcTool";
    /** One frame at the endoscope's real ~16.9 fps. */
    private static final int FRAME_MS = 59;

    private ZoomLayout zoom;
    private ImageView photo;
    private TextureView video;
    private View topBar, videoBar;
    private TextView tvTitle, tvSub, tvZoom, tvTime;
    private ImageButton btnPlay;
    private SeekBar seek;

    private List<MediaRepo.Item> items = Collections.emptyList();
    private int index = -1;
    private MediaRepo.Item current;

    private MediaPlayer player;
    private Surface surface;
    private boolean prepared = false;
    private boolean userSeeking = false;
    private RectF contentRect;
    private int videoW, videoH;
    private boolean chromeVisible = true;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());
    private ActivityResultLauncher<IntentSenderRequest> deleteLauncher;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player != null && prepared && !userSeeking) {
                int pos = player.getCurrentPosition();
                seek.setProgress(pos);
                tvTime.setText(MediaRepo.formatDuration(pos) + " / " + MediaRepo.formatDuration(player.getDuration()));
            }
            ui.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_viewer);

        zoom = findViewById(R.id.zoom);
        photo = findViewById(R.id.iv_photo);
        video = findViewById(R.id.tv_video);
        topBar = findViewById(R.id.top_bar);
        videoBar = findViewById(R.id.video_bar);
        tvTitle = findViewById(R.id.tv_title);
        tvSub = findViewById(R.id.tv_sub);
        tvZoom = findViewById(R.id.tv_zoom);
        tvTime = findViewById(R.id.tv_time);
        btnPlay = findViewById(R.id.btn_play);
        seek = findViewById(R.id.sb_seek);

        video.setSurfaceTextureListener(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_share).setOnClickListener(v -> share());
        findViewById(R.id.btn_delete).setOnClickListener(v -> confirmDelete());
        btnPlay.setOnClickListener(v -> togglePlay());
        findViewById(R.id.btn_step_back).setOnClickListener(v -> step(-1));
        findViewById(R.id.btn_step_fwd).setOnClickListener(v -> step(+1));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null && prepared) {
                    player.seekTo(p, MediaPlayer.SEEK_CLOSEST);
                    tvTime.setText(MediaRepo.formatDuration(p) + " / " + MediaRepo.formatDuration(player.getDuration()));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
                if (player != null && player.isPlaying()) { player.pause(); updatePlayIcon(); }
            }
            @Override public void onStopTrackingTouch(SeekBar sb) { userSeeking = false; }
        });

        zoom.setListener(new ZoomLayout.Listener() {
            @Override public void onContentRect(RectF r) { contentRect = r; applyVideoTransform(); }
            @Override public void onZoomChanged(float s) {
                if (s > 1.01f) {
                    tvZoom.setText(String.format(Locale.US, "%.1f×", s));
                    tvZoom.setVisibility(View.VISIBLE);
                } else tvZoom.setVisibility(View.GONE);
            }
            @Override public void onSingleTap() { setChrome(!chromeVisible); }
            @Override public void onSwipe(int dir) { show(index + dir); }
        });

        deleteLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), r -> {
                    if (r.getResultCode() == RESULT_OK) afterDelete();
                });

        Uri start = getIntent().getData();
        io.execute(() -> {
            List<MediaRepo.Item> list = MediaRepo.query(this);
            ui.post(() -> {
                items = list;
                int at = 0;
                for (int i = 0; i < list.size(); i++) if (list.get(i).uri.equals(start)) { at = i; break; }
                if (list.isEmpty()) { finish(); return; }
                show(at);
            });
        });
        ui.post(progressTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) { player.pause(); updatePlayIcon(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        if (surface != null) { surface.release(); surface = null; }
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ showing items

    private void show(int i) {
        if (i < 0 || i >= items.size() || i == index) return;
        index = i;
        current = items.get(i);
        releasePlayer();

        tvTitle.setText(dateFmt.format(new Date(current.dateMs)));
        String kind = getString(current.video ? R.string.viewer_video : R.string.viewer_photo);
        tvSub.setText(String.format(Locale.US, "%s · %d×%d · %d/%d",
                kind, current.width, current.height, i + 1, items.size()));

        if (current.video) {
            photo.setImageDrawable(null);
            photo.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            videoBar.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
            seek.setProgress(0);
            tvTime.setText("0:00 / " + MediaRepo.formatDuration(current.durationMs));
            zoom.setContentSize(current.width, current.height);
            if (surface != null) openPlayer();      // else onSurfaceTextureAvailable does it
        } else {
            video.setVisibility(View.GONE);
            videoBar.setVisibility(View.GONE);
            photo.setVisibility(View.VISIBLE);
            photo.setImageDrawable(null);
            MediaRepo.Item it = current;
            io.execute(() -> {
                Bitmap b = null;
                try (InputStream in = getContentResolver().openInputStream(it.uri)) {
                    b = BitmapFactory.decodeStream(in);
                } catch (Exception e) {
                    Log.e(TAG, "viewer: decode failed " + it.name, e);
                }
                Bitmap fb = b;
                ui.post(() -> {
                    if (current != it) return;
                    if (fb == null) { Toast.makeText(this, R.string.viewer_open_failed, Toast.LENGTH_SHORT).show(); return; }
                    photo.setImageBitmap(fb);
                    zoom.setContentSize(fb.getWidth(), fb.getHeight());
                });
            });
        }
    }

    private void setChrome(boolean visible) {
        chromeVisible = visible;
        topBar.animate().alpha(visible ? 1f : 0f).setDuration(150)
                .withStartAction(() -> { if (visible) topBar.setVisibility(View.VISIBLE); })
                .withEndAction(() -> { if (!visible) topBar.setVisibility(View.GONE); });
        if (current != null && current.video) videoBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ video

    private void openPlayer() {
        if (current == null || !current.video || surface == null) return;
        MediaRepo.Item it = current;
        try {
            player = new MediaPlayer();
            player.setDataSource(this, it.uri);
            player.setSurface(surface);
            player.setOnPreparedListener(mp -> {
                if (current != it) return;
                prepared = true;
                videoW = mp.getVideoWidth();
                videoH = mp.getVideoHeight();
                if (videoW > 0 && videoH > 0) zoom.setContentSize(videoW, videoH);
                seek.setMax(mp.getDuration());
                mp.start();
                updatePlayIcon();
            });
            player.setOnCompletionListener(mp -> updatePlayIcon());
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "viewer: MediaPlayer error " + what + "/" + extra);
                Toast.makeText(this, R.string.viewer_open_failed, Toast.LENGTH_SHORT).show();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "viewer: cannot open video " + it.name, e);
            Toast.makeText(this, R.string.viewer_open_failed, Toast.LENGTH_SHORT).show();
            releasePlayer();
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        updatePlayIcon();
    }

    private void togglePlay() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) player.pause();
        else {
            if (player.getCurrentPosition() >= player.getDuration() - FRAME_MS) player.seekTo(0);
            player.start();
        }
        updatePlayIcon();
    }

    /** Pause and move exactly one frame. */
    private void step(int dir) {
        if (player == null || !prepared) return;
        if (player.isPlaying()) player.pause();
        int pos = player.getCurrentPosition() + dir * FRAME_MS;
        pos = Math.max(0, Math.min(player.getDuration(), pos));
        player.seekTo(pos, MediaPlayer.SEEK_CLOSEST);
        seek.setProgress(pos);
        updatePlayIcon();
    }

    private void updatePlayIcon() {
        boolean playing = player != null && prepared && player.isPlaying();
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    /** TextureView stretches the frame to its size; letterbox it to the fitted rect instead. */
    private void applyVideoTransform() {
        if (contentRect == null || video.getWidth() == 0) return;
        Matrix m = new Matrix();
        m.setScale(contentRect.width() / video.getWidth(), contentRect.height() / video.getHeight());
        m.postTranslate(contentRect.left, contentRect.top);
        video.setTransform(m);
        video.invalidate();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        applyVideoTransform();
        if (current != null && current.video && player == null) openPlayer();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { applyVideoTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        if (surface != null) { surface.release(); surface = null; }
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ------------------------------------------------------------------ share / delete

    private void share() {
        if (current == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(current.mime());
        send.putExtra(Intent.EXTRA_STREAM, current.uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.viewer_share)));
    }

    private void confirmDelete() {
        if (current == null) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(current.video ? R.string.viewer_delete_video_q : R.string.viewer_delete_photo_q)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.viewer_delete, (d, w) -> delete())
                .show();
    }

    private void delete() {
        releasePlayer();
        try {
            int n = getContentResolver().delete(current.uri, null, null);
            if (n > 0) { afterDelete(); return; }
        } catch (SecurityException e) {
            // A file left over from an earlier install is no longer "ours": ask the system.
            try {
                android.content.IntentSender sender;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    sender = MediaStore.createDeleteRequest(getContentResolver(),
                            Collections.singletonList(current.uri)).getIntentSender();
                } else if (e instanceof RecoverableSecurityException) {
                    sender = ((RecoverableSecurityException) e).getUserAction().getActionIntent().getIntentSender();
                } else throw e;
                deleteLauncher.launch(new IntentSenderRequest.Builder(sender).build());
                return;
            } catch (Exception e2) {
                Log.e(TAG, "viewer: delete request failed", e2);
            }
        }
        Toast.makeText(this, R.string.viewer_delete_failed, Toast.LENGTH_SHORT).show();
    }

    private void afterDelete() {
        Toast.makeText(this, R.string.viewer_deleted, Toast.LENGTH_SHORT).show();
        java.util.ArrayList<MediaRepo.Item> list = new java.util.ArrayList<>(items);
        list.remove(index);
        items = list;
        if (items.isEmpty()) { finish(); return; }
        int next = Math.min(index, items.size() - 1);
        index = -1;                       // force show() to rebind
        show(next);
    }
}
