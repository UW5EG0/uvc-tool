package ua.radiomaniac.uvctool;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Self-update from GitHub releases (uw5eg0/uvc-tool).
 *
 * check(): asks the GitHub API for the latest release, at most once a day unless forced.
 * download() + install(): fetch the APK, verify its SHA-256 when GitHub publishes one,
 * and hand it to PackageInstaller. Android itself refuses an APK signed with a
 * different key, so a tampered download cannot replace the app.
 *
 * Disabled entirely when BuildConfig.SELF_UPDATE is false (e.g. F-Droid builds).
 */
public final class Updater {

    private static final String TAG = "UvcTool";
    public static final String REPO = "uw5eg0/uvc-tool";
    public static final String REPO_URL = "https://github.com/" + REPO;
    private static final String API_LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final String PREFS = "updater";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_SKIPPED = "skipped_tag";
    private static final long CHECK_INTERVAL_MS = 24L * 3600 * 1000;

    static final String ACTION_INSTALL_STATUS = "ua.radiomaniac.uvctool.INSTALL_STATUS";

    public static final class Release {
        public final String tag, version, notes, pageUrl, apkUrl, apkName, sha256;
        public final long apkSize;
        Release(String tag, String notes, String pageUrl, String apkUrl, String apkName,
                long apkSize, String sha256) {
            this.tag = tag; this.version = tag.startsWith("v") ? tag.substring(1) : tag;
            this.notes = notes; this.pageUrl = pageUrl; this.apkUrl = apkUrl;
            this.apkName = apkName; this.apkSize = apkSize; this.sha256 = sha256;
        }
    }

    public interface CheckCallback {
        /** Main thread. release == null: no newer version (or error, see error). */
        void onResult(Release release, String error);
    }

    public interface ProgressCallback {
        void onProgress(long done, long total);   // worker thread
    }

    private Updater() {}

    public static boolean enabled() { return BuildConfig.SELF_UPDATE; }

    /**
     * @param force ignore the once-a-day limit and the "skip this version" choice
     *              (manual "check for updates").
     */
    public static void check(Context ctx, boolean force, CheckCallback cb) {
        Context app = ctx.getApplicationContext();
        Handler ui = new Handler(Looper.getMainLooper());
        if (!enabled()) { ui.post(() -> cb.onResult(null, null)); return; }
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return;

        new Thread(() -> {
            Release r = null; String err = null;
            try {
                r = fetchLatest();
                p.edit().putLong(KEY_LAST_CHECK, now).apply();
                if (r != null) {
                    boolean newer = compare(r.version, BuildConfig.VERSION_NAME) > 0;
                    boolean skipped = !force && r.tag.equals(p.getString(KEY_SKIPPED, null));
                    Log.i(TAG, "update check: latest=" + r.tag + " installed=" + BuildConfig.VERSION_NAME
                            + " newer=" + newer + " skipped=" + skipped);
                    if (!newer || skipped) r = null;
                }
            } catch (Exception e) {
                Log.w(TAG, "update check failed: " + e);
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            Release fr = r; String fe = err;
            ui.post(() -> cb.onResult(fr, fe));
        }, "update-check").start();
    }

    public static void skip(Context ctx, Release r) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SKIPPED, r.tag).apply();
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_LATEST).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "UvcTool/" + BuildConfig.VERSION_NAME);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;                    // no releases yet
            if (code != 200) throw new Exception("GitHub HTTP " + code);
            JSONObject j = new JSONObject(readAll(c.getInputStream()));
            if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null;
            JSONArray assets = j.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name");
                if (!name.endsWith(".apk")) continue;
                String digest = a.optString("digest", "");        // "sha256:<hex>"
                String sha = digest.startsWith("sha256:") ? digest.substring(7) : null;
                return new Release(j.getString("tag_name"), j.optString("body", ""),
                        j.optString("html_url", REPO_URL + "/releases"),
                        a.getString("browser_download_url"), name, a.optLong("size", -1), sha);
            }
            return null;
        } finally {
            c.disconnect();
        }
    }

    /** Worker thread. Returns the downloaded, verified APK. */
    public static File download(Context ctx, Release r, ProgressCallback progress) throws Exception {
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("cannot create " + dir);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File out = new File(dir, "update.apk");

        HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        c.setInstanceFollowRedirects(true);                     // github -> objects CDN
        c.setRequestProperty("User-Agent", "UvcTool/" + BuildConfig.VERSION_NAME);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("download HTTP " + code);
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.apkSize;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0; int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, total);
                }
            }
        } finally {
            c.disconnect();
        }
        String got = hex(md.digest());
        if (r.sha256 != null && !r.sha256.equalsIgnoreCase(got)) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new Exception("SHA-256 mismatch");
        }
        Log.i(TAG, "update downloaded: " + out.length() + " bytes sha256=" + got
                + (r.sha256 != null ? " (verified)" : " (no published digest)"));
        return out;
    }

    /** Main thread. Result arrives in UpdateReceiver (confirmation dialog, then success/failure). */
    public static void install(Context ctx, File apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // lets later self-updates skip the dialog once we are the installer of record
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(ctx, UpdateReceiver.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i, flags);
            s.commit(pend.getIntentSender());
        }
    }

    /** -1 / 0 / +1 for dotted numeric versions ("0.10.2" > "0.9.9"). */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (Exception e) { return 0; }
    }

    private static String readAll(InputStream in) throws Exception {
        // collect bytes first: decoding per chunk would split multi-byte UTF-8 (Cyrillic notes)
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format(Locale.US, "%02x", v));
        return sb.toString();
    }
}
