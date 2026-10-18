package ua.radiomaniac.uvctool;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Photos (Pictures/UvcTool) and videos (Movies/UvcTool) this app saved, newest first.
 *
 * Plain MediaStore queries. On Android 10+ an app sees the files it created without
 * any storage permission, which is exactly the set we want.
 */
public final class MediaRepo {

    private static final String TAG = "UvcTool";

    public static final class Item {
        public final Uri uri;
        public final boolean video;
        public final String name;
        public final long dateMs;
        public final long durationMs;   // 0 for photos
        public final int width, height;

        Item(Uri uri, boolean video, String name, long dateMs, long durationMs, int w, int h) {
            this.uri = uri; this.video = video; this.name = name;
            this.dateMs = dateMs; this.durationMs = durationMs; this.width = w; this.height = h;
        }

        public String mime() { return video ? "video/mp4" : "image/jpeg"; }
    }

    private MediaRepo() {}

    public static List<Item> query(Context ctx) {
        List<Item> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        queryOne(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false,
                Environment.DIRECTORY_PICTURES + "/UvcTool", out);
        queryOne(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true,
                Environment.DIRECTORY_MOVIES + "/UvcTool", out);
        Collections.sort(out, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        return out;
    }

    /** Newest item or null. */
    public static Item latest(Context ctx) {
        List<Item> all = query(ctx);
        return all.isEmpty() ? null : all.get(0);
    }

    private static void queryOne(ContentResolver cr, Uri collection, boolean video,
                                 String relPath, List<Item> out) {
        String[] proj = video
                ? new String[]{ MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH,
                                MediaStore.MediaColumns.HEIGHT, MediaStore.Video.VideoColumns.DURATION }
                : new String[]{ MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH,
                                MediaStore.MediaColumns.HEIGHT };
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = { relPath + "%" };
        try (Cursor c = cr.query(collection, proj, sel, args, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                long dur = video ? c.getLong(5) : 0;
                out.add(new Item(ContentUris.withAppendedId(collection, id), video,
                        c.getString(1), c.getLong(2) * 1000L, dur, c.getInt(3), c.getInt(4)));
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaRepo query failed for " + collection, e);
        }
    }

    /**
     * Thumbnail; call off the main thread. MediaStore first; for videos the system
     * thumbnailer can fail on files recorded before the H.264 level fix, so fall
     * back to decoding the first frame ourselves.
     */
    public static Bitmap thumbnail(Context ctx, Item item, int size) {
        try {
            return ctx.getContentResolver().loadThumbnail(item.uri, new Size(size, size), null);
        } catch (Exception e) {
            Log.w(TAG, "loadThumbnail failed for " + item.name + ": " + e);
        }
        if (!item.video) return null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, item.uri);
            Bitmap f = mmr.getFrameAtTime(0);
            if (f == null) return null;
            float k = (float) size / Math.max(f.getWidth(), f.getHeight());
            Bitmap s = Bitmap.createScaledBitmap(f, Math.round(f.getWidth() * k),
                    Math.round(f.getHeight() * k), true);
            if (s != f) f.recycle();
            return s;
        } catch (Exception e) {
            Log.w(TAG, "frame thumbnail failed for " + item.name + ": " + e);
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    public static String formatDuration(long ms) {
        long s = (ms + 500) / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
