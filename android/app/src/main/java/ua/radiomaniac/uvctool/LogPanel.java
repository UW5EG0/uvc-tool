package ua.radiomaniac.uvctool;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;

/**
 * On-screen log: tails this process's own logcat (tag UvcTool), so it shows
 * Java *and* native (libuvc/JNI) lines without any extra plumbing.
 * An app may always read its own log lines, no permission needed.
 * Per-frame "frame seq" lines are counted, not shown.
 */
public class LogPanel {

    private static final String TAG = "UvcTool";
    private static final int MAX_LINES = 300;
    private static final long UI_THROTTLE_MS = 250;

    private final ScrollView scroll;
    private final TextView text;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private volatile boolean running = false;
    private volatile boolean dirty = false;
    private java.lang.Process proc;
    private Thread reader;

    private final Runnable flush = new Runnable() {
        @Override public void run() {
            if (!dirty) return;
            dirty = false;
            text.setText(snapshot());
            if (scroll.getVisibility() == View.VISIBLE) {
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    };

    public LogPanel(ScrollView scroll, TextView text) {
        this.scroll = scroll;
        this.text = text;
    }

    public void start() {
        if (running) return;
        running = true;
        reader = new Thread(this::readLoop, "LogPanel");
        reader.setDaemon(true);
        reader.start();
    }

    public void stop() {
        running = false;
        if (proc != null) proc.destroy();
    }

    public boolean toggle() {
        boolean show = scroll.getVisibility() != View.VISIBLE;
        scroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { dirty = true; ui.post(flush); }
        return show;
    }

    public synchronized String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    private void readLoop() {
        String[] cmd = {
                "logcat", "-v", "time", "--pid=" + Process.myPid(),
                "UvcTool:V", "libusb:V", "AndroidRuntime:E", "*:S"
        };
        try {
            proc = Runtime.getRuntime().exec(cmd);
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while (running && (line = br.readLine()) != null) {
                if (line.contains("frame seq=")) continue;
                append(shorten(line));
            }
        } catch (Exception e) {
            Log.w(TAG, "LogPanel: logcat reader failed", e);
        }
    }

    /** "10-17 16:12:19.076 D/UvcTool ( 5328): msg" -> "16:12:19.076 D msg" */
    private static String shorten(String l) {
        try {
            int sp = l.indexOf(' ');
            String time = l.substring(sp + 1, l.indexOf(' ', sp + 1));
            int lvlPos = l.indexOf(' ', sp + 1) + 1;
            char lvl = l.charAt(lvlPos);
            int msgPos = l.indexOf("): ");
            return time + " " + lvl + " " + (msgPos > 0 ? l.substring(msgPos + 3) : l);
        } catch (Exception e) {
            return l;
        }
    }

    private synchronized void append(String l) {
        lines.addLast(l);
        while (lines.size() > MAX_LINES) lines.removeFirst();
        if (!dirty) {
            dirty = true;
            ui.postDelayed(flush, UI_THROTTLE_MS);
        }
    }
}
