package ua.radiomaniac.uvctool;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Short synthesized feedback sounds (no audio files):
 *   photo        — one bell-like "ding"
 *   rec start    — two rising tones
 *   rec stop     — two falling tones
 */
public final class Beeper {

    private static final String TAG = "UvcTool";
    private static final int RATE = 44100;

    private final short[] ding, recStart, recStop;

    public Beeper() {
        ding = bell(1568, 0.32f);                                  // G6
        recStart = concat(tone(880, 0.09f), silence(0.045f), tone(1319, 0.11f));   // A5 -> E6
        recStop  = concat(tone(1319, 0.09f), silence(0.045f), tone(880, 0.11f));   // E6 -> A5
    }

    public void photo()    { play(ding); }
    public void recStart() { play(recStart); }
    public void recStop()  { play(recStop); }

    private void play(short[] pcm) {
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            t.write(pcm, 0, pcm.length);
            t.setNotificationMarkerPosition(pcm.length);
            t.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override public void onMarkerReached(AudioTrack track) { track.release(); }
                @Override public void onPeriodicNotification(AudioTrack track) {}
            });
            t.play();
        } catch (Exception e) {
            Log.w(TAG, "beep failed: " + e);
        }
    }

    // ------------------------------------------------------------------ synthesis

    /** Bell: fundamental + soft partials, sharp attack, exponential decay. */
    private static short[] bell(float f, float sec) {
        int n = (int) (RATE * sec);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1.0, t / 0.003) * Math.exp(-t * 11.0);
            double v = Math.sin(2 * Math.PI * f * t)
                    + 0.35 * Math.sin(2 * Math.PI * f * 2.0 * t) * Math.exp(-t * 8.0)
                    + 0.15 * Math.sin(2 * Math.PI * f * 3.01 * t) * Math.exp(-t * 14.0);
            out[i] = (short) (env * v / 1.5 * 0.6 * Short.MAX_VALUE);
        }
        return out;
    }

    /** Clean tone with short fade in/out (no clicks). */
    private static short[] tone(float f, float sec) {
        int n = (int) (RATE * sec);
        int fade = (int) (RATE * 0.008);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double env = Math.min(1.0, Math.min(i, n - 1 - i) / (double) fade);
            out[i] = (short) (env * 0.5 * Short.MAX_VALUE * Math.sin(2 * Math.PI * f * i / RATE));
        }
        return out;
    }

    private static short[] silence(float sec) { return new short[(int) (RATE * sec)]; }

    private static short[] concat(short[]... parts) {
        int n = 0;
        for (short[] p : parts) n += p.length;
        short[] out = new short[n];
        int o = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }
}
