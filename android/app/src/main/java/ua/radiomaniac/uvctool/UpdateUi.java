package ua.radiomaniac.uvctool;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.Locale;

/** Dialogs around the self-updater, plus the "About" dialog. */
public final class UpdateUi {

    private static final String TAG = "UvcTool";

    private UpdateUi() {}

    /** "Version X is available" with release notes: Update / Later / Skip. */
    public static void offer(Activity a, Updater.Release r) {
        if (a.isFinishing()) return;
        String notes = r.notes == null || r.notes.trim().isEmpty() ? "" : "\n\n" + r.notes.trim();
        new MaterialAlertDialogBuilder(a)
                .setTitle(a.getString(R.string.update_available, r.version))
                .setMessage(a.getString(R.string.update_installed, BuildConfig.VERSION_NAME) + notes)
                .setPositiveButton(R.string.update_now, (d, w) -> downloadAndInstall(a, r))
                .setNegativeButton(R.string.update_later, null)
                .setNeutralButton(R.string.update_skip, (d, w) -> Updater.skip(a, r))
                .show();
    }

    public static void downloadAndInstall(Activity a, Updater.Release r) {
        // Android 8+: "install unknown apps" is granted per source app, once.
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(a)
                    .setMessage(R.string.update_need_permission)
                    .setPositiveButton(R.string.update_open_settings, (d, w) -> a.startActivity(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName()))))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        View v = LayoutInflater.from(a).inflate(R.layout.dialog_progress, null);
        LinearProgressIndicator bar = v.findViewById(R.id.progress);
        TextView tv = v.findViewById(R.id.tv_progress);
        AlertDialog dlg = new MaterialAlertDialogBuilder(a)
                .setTitle(a.getString(R.string.update_downloading, r.version))
                .setView(v)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            try {
                File apk = Updater.download(a, r, (done, total) -> a.runOnUiThread(() -> {
                    if (total > 0) {
                        bar.setIndeterminate(false);
                        bar.setProgressCompat((int) (done * 100 / total), true);
                    }
                    tv.setText(String.format(Locale.US, "%.1f / %s MB", done / 1048576.0,
                            total > 0 ? String.format(Locale.US, "%.1f", total / 1048576.0) : "?"));
                }));
                a.runOnUiThread(() -> {
                    dlg.dismiss();
                    try {
                        Updater.install(a, apk);
                    } catch (Exception e) {
                        Log.e(TAG, "install failed", e);
                        Toast.makeText(a, a.getString(R.string.update_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "update download failed", e);
                a.runOnUiThread(() -> {
                    dlg.dismiss();
                    Toast.makeText(a, a.getString(R.string.update_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        }, "update-download").start();
    }

    /** Manual check with feedback for every outcome. */
    public static void checkNow(Activity a) {
        Toast.makeText(a, R.string.update_checking, Toast.LENGTH_SHORT).show();
        Updater.check(a, true, (r, err) -> {
            if (a.isFinishing()) return;
            if (r != null) offer(a, r);
            else if (err != null) Toast.makeText(a, a.getString(R.string.update_check_failed, err), Toast.LENGTH_LONG).show();
            else Toast.makeText(a, R.string.update_none, Toast.LENGTH_SHORT).show();
        });
    }

    public static void about(Activity a) {
        TextView tv = new TextView(a);
        int pad = Math.round(22 * a.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);
        tv.setText(androidx.core.text.HtmlCompat.fromHtml(a.getString(R.string.about_html,
                BuildConfig.VERSION_NAME, Updater.REPO_URL), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY));
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setTextColor(0xDEFFFFFF);
        tv.setLinkTextColor(a.getColor(R.color.accent));
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.app_name)
                .setView(tv)
                .setPositiveButton(android.R.string.ok, null);
        if (Updater.enabled()) b.setNeutralButton(R.string.update_check, (d, w) -> checkNow(a));
        b.show();
    }
}
