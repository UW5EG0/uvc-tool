package ua.radiomaniac.uvctool;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;


import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Grid of the photos and videos this app saved, newest first. */
public class GalleryActivity extends AppCompatActivity {

    private static final int THUMB_PX = 320;

    private final List<MediaRepo.Item> items = new ArrayList<>();
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private GridView grid;
    private TextView tvTitle, tvEmpty;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // like a video player: every screen of the app keeps the display on
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_gallery);
        grid = findViewById(R.id.grid);
        tvTitle = findViewById(R.id.tv_title);
        tvEmpty = findViewById(R.id.tv_empty);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // Optional: lets the gallery also show files saved by an earlier install of the
        // app (MediaStore stops counting them as ours after a reinstall). Asked once;
        // without it the gallery still shows everything this install saved.
        String[] perms = android.os.Build.VERSION.SDK_INT >= 33
                ? new String[]{ android.Manifest.permission.READ_MEDIA_IMAGES,
                                android.Manifest.permission.READ_MEDIA_VIDEO }
                : new String[]{ android.Manifest.permission.READ_EXTERNAL_STORAGE };
        android.content.SharedPreferences sp = getSharedPreferences("gallery", MODE_PRIVATE);
        boolean granted = checkSelfPermission(perms[0]) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!granted && !sp.getBoolean("media_perm_asked", false)) {
            sp.edit().putBoolean("media_perm_asked", true).apply();
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
                    res -> reload()).launch(perms);
        }

        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Intent i = new Intent(this, ViewerActivity.class);
            i.setData(items.get(pos).uri);
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();       // the viewer may have deleted something
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void reload() {
        io.execute(() -> {
            List<MediaRepo.Item> list = MediaRepo.query(this);
            ui.post(() -> {
                items.clear();
                items.addAll(list);
                adapter.notifyDataSetChanged();
                tvTitle.setText(getString(R.string.gallery_title_n, items.size()));
                tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private class Adapter extends BaseAdapter {
        private final SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).uri.hashCode(); }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_gallery, parent, false);
            // square cells whatever the column width (portrait / landscape)
            int side = grid.getColumnWidth();
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (side > 0 && lp.height != side) { lp.height = side; v.setLayoutParams(lp); }

            MediaRepo.Item it = items.get(pos);
            ImageView iv = v.findViewById(R.id.iv_thumb);
            View badge = v.findViewById(R.id.badge_video);
            TextView tvDur = v.findViewById(R.id.tv_duration);
            TextView tvDate = v.findViewById(R.id.tv_date);

            badge.setVisibility(it.video ? View.VISIBLE : View.GONE);
            if (it.video) tvDur.setText(MediaRepo.formatDuration(it.durationMs));
            tvDate.setText(fmt.format(new Date(it.dateMs)));

            String key = it.uri.toString();
            iv.setTag(key);
            Bitmap b = cache.get(key);
            if (b != null) { iv.setImageBitmap(b); return v; }
            iv.setImageDrawable(null);
            io.execute(() -> {
                Bitmap t = MediaRepo.thumbnail(GalleryActivity.this, it, THUMB_PX);
                if (t == null) return;
                cache.put(key, t);
                ui.post(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(t); });
            });
            return v;
        }
    }
}
