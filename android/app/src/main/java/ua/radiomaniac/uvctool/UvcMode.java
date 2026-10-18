package ua.radiomaniac.uvctool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One streaming mode a camera advertises: pixel format + frame size + frame rates. */
public final class UvcMode {

    public static final int FOURCC_YUY2 = 0x32595559;
    public static final int FOURCC_UYVY = 0x59565955;
    public static final int FOURCC_MJPG = 0x47504a4d;
    public static final int FOURCC_NV12 = 0x3231564e;
    public static final int FOURCC_H264 = 0x34363248;

    public final int fourcc, width, height;
    /** The frame rate of this mode (single element; empty if the camera gave none). */
    public final int[] fps;

    public UvcMode(int fourcc, int width, int height, int[] fps) {
        this.fourcc = fourcc; this.width = width; this.height = height; this.fps = fps;
    }

    public int maxFps() { return fps.length > 0 ? fps[0] : 0; }

    /** Formats the app can display today. */
    public boolean supported() { return fourcc == FOURCC_YUY2; }

    public String formatName() {
        switch (fourcc) {
            case FOURCC_YUY2: return "YUYV";
            case FOURCC_UYVY: return "UYVY";
            case FOURCC_MJPG: return "MJPEG";
            case FOURCC_NV12: return "NV12";
            case FOURCC_H264: return "H.264";
            default:
                char[] c = { (char) (fourcc & 0xff), (char) ((fourcc >> 8) & 0xff),
                             (char) ((fourcc >> 16) & 0xff), (char) ((fourcc >> 24) & 0xff) };
                return new String(c).trim();
        }
    }

    public String sizeName() { return width + "×" + height; }

    /** "YUYV 640×480 · 30 к/с" (one mode per frame rate). */
    public String label() {
        return formatName() + " " + sizeName() + " · " + maxFps() + " к/с";
    }

    /** Persisted as "fourcc,w,h,fps". */
    public String key() { return String.format(Locale.US, "%d,%d,%d,%d", fourcc, width, height, maxFps()); }

    public boolean sameAs(String key) { return key != null && key.equals(key()); }

    /** Parses nativeGetModes() output: [fourcc, w, h, n, interval*n]*. */
    static List<UvcMode> parse(int[] raw) {
        List<UvcMode> out = new ArrayList<>();
        int i = 0;
        while (raw != null && i + 4 <= raw.length) {
            int fourcc = raw[i], w = raw[i + 1], h = raw[i + 2], n = raw[i + 3];
            i += 4;
            List<Integer> f = new ArrayList<>();
            for (int k = 0; k < n && i < raw.length; k++, i++) {
                int interval = raw[i];
                if (interval <= 0) continue;
                int v = Math.round(10_000_000f / interval);
                if (v > 0 && !f.contains(v)) f.add(v);
            }
            Collections.sort(f, Collections.reverseOrder());
            // one selectable mode per frame rate: 640×480 @30 and @5 are different choices
            if (f.isEmpty()) out.add(new UvcMode(fourcc, w, h, new int[0]));
            for (int v : f) out.add(new UvcMode(fourcc, w, h, new int[]{ v }));
        }
        return out;
    }

    /**
     * Default when nothing was chosen yet: among supported modes, the largest frame
     * that still offers at least 15 fps; if none does, the largest supported one.
     */
    static UvcMode pickDefault(List<UvcMode> modes) {
        UvcMode best = null, bestAny = null;
        for (UvcMode m : modes) {
            if (!m.supported()) continue;
            long px = (long) m.width * m.height;
            if (bestAny == null || px > (long) bestAny.width * bestAny.height) bestAny = m;
            if (m.maxFps() >= 15 && (best == null || px > (long) best.width * best.height)) best = m;
        }
        return best != null ? best : bestAny;
    }
}
