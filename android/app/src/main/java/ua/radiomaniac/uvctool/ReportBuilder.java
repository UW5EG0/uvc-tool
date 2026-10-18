package ua.radiomaniac.uvctool;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;

/**
 * Problem report: app/Android version, permissions, USB devices, camera state and
 * the in-app log, as GitHub-flavoured Markdown. Nothing is sent automatically;
 * the user reviews it in the browser (new-issue form) or shares it.
 */
public final class ReportBuilder {

    /** Prefilled-issue URLs above ~8 KB are refused by GitHub; leave room for encoding. */
    private static final int MAX_URL_BODY = 5500;

    public static final class CameraState {
        public UsbDevice device;
        public UvcMode mode;
        public List<UvcMode> modes;
        public String fps;
        public boolean streaming, recording, videoMode;
        public String screen;
    }

    private ReportBuilder() {}

    public static String title(String comment) {
        String c = comment == null ? "" : comment.trim().replace('\n', ' ');
        if (c.length() > 60) c = c.substring(0, 57) + "…";
        return "[report] " + (c.isEmpty() ? "Проблема" : c) + " (v" + BuildConfig.VERSION_NAME + ")";
    }

    public static String body(Context ctx, String comment, CameraState cam, String log) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Опис\n\n").append(comment == null || comment.trim().isEmpty() ? "_(без коментаря)_" : comment.trim()).append("\n\n");

        sb.append("### Система\n\n```\n");
        sb.append("UVC Tool ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE)
          .append(", ").append(BuildConfig.BUILD_TYPE).append(")\n");
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" [").append(Build.DEVICE).append("]\n");
        String miui = prop("ro.miui.ui.version.name");
        if (!miui.isEmpty()) sb.append("MIUI ").append(miui).append(' ').append(prop("ro.build.version.incremental")).append('\n');
        if (cam != null && cam.screen != null) sb.append("Screen ").append(cam.screen).append('\n');
        sb.append("ABI ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        sb.append("```\n\n");

        sb.append("### Дозволи\n\n```\n");
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null) {
                for (int i = 0; i < pi.requestedPermissions.length; i++) {
                    String p = pi.requestedPermissions[i];
                    if (p.startsWith(ctx.getPackageName())) continue;   // internal receiver permission
                    boolean granted = (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                    sb.append(granted ? "+ " : "- ").append(p.replace("android.permission.", "")).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("(error: ").append(e).append(")\n");
        }
        sb.append(ctx.getPackageManager().canRequestPackageInstalls() ? "+ " : "- ").append("install unknown apps\n");
        sb.append("```\n\n");

        sb.append("### USB-пристрої\n\n```\n");
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null || um.getDeviceList().isEmpty()) sb.append("(немає)\n");
        else for (UsbDevice d : um.getDeviceList().values()) {
            boolean cur = cam != null && cam.device != null && cam.device.getDeviceName().equals(d.getDeviceName());
            sb.append(cur ? "* " : "  ").append(UsbDeviceManager.key(d)).append(' ').append(d.getDeviceName())
              .append(" class=").append(d.getDeviceClass())
              .append(" perm=").append(um.hasPermission(d) ? "yes" : "no").append('\n');
            sb.append("    ").append(nz(safe(d::getManufacturerName))).append(" / ").append(nz(safe(d::getProductName))).append('\n');
            StringBuilder ifs = new StringBuilder();
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface f = d.getInterface(i);
                ifs.append(String.format(Locale.US, " %d:%d/%d/%d", f.getId(), f.getInterfaceClass(),
                        f.getInterfaceSubclass(), f.getAlternateSetting()));
            }
            sb.append("    if(id:class/sub/alt)").append(ifs).append('\n');
        }
        sb.append("```\n\n");

        if (cam != null) {
            sb.append("### Камера\n\n```\n");
            sb.append("streaming=").append(cam.streaming).append(" recording=").append(cam.recording)
              .append(" shutter=").append(cam.videoMode ? "video" : "photo").append('\n');
            sb.append("mode: ").append(cam.mode != null ? cam.mode.label() : "-").append("  measured: ").append(nz(cam.fps)).append('\n');
            if (cam.modes != null) for (UvcMode m : cam.modes)
                sb.append("  ").append(m.label()).append(m.supported() ? "" : " (unsupported)").append('\n');
            sb.append("```\n\n");
        }

        sb.append("### Лог\n\n```\n").append(log == null ? "" : log.trim()).append("\n```\n");
        return sb.toString();
    }

    /**
     * New-issue URL with title and body prefilled. The log is cut from the top
     * (oldest lines) so the URL stays under GitHub's limit; the full text is
     * meant to be on the clipboard as well.
     */
    public static Uri issueUrl(String title, String body) {
        String b = body;
        if (b.length() > MAX_URL_BODY) {
            int logAt = b.indexOf("### Лог");
            String head = logAt >= 0 ? b.substring(0, logAt) : "";
            String tail = b.substring(b.length() - Math.max(0, MAX_URL_BODY - head.length() - 200));
            int nl = tail.indexOf('\n');
            if (nl >= 0) tail = tail.substring(nl + 1);
            b = head + "### Лог (кінець; повний текст скопійовано — встав нижче, якщо потрібно)\n\n```\n" + tail;
        }
        return Uri.parse(Updater.REPO_URL + "/issues/new").buildUpon()
                .appendQueryParameter("title", title)
                .appendQueryParameter("body", b)
                .build();
    }

    private interface S { String get() throws Exception; }

    private static String safe(S s) { try { return s.get(); } catch (Exception e) { return null; } }

    private static String nz(String s) { return s == null || s.isEmpty() ? "?" : s; }

    private static String prop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l = r.readLine();
                return l == null ? "" : l.trim();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
