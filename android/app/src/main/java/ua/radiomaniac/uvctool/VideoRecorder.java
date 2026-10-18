package ua.radiomaniac.uvctool;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * H.264 / MP4 recorder fed through a MediaCodec input Surface.
 *
 * Lifecycle (all on the GL thread except start()/stop() which are UI-thread safe):
 *   start()         -> creates encoder + muxer, exposes getInputSurface()
 *   renderer draws into that surface each frame (via an EGL surface it creates)
 *   drainEncoder()  -> after every frame, moves encoded packets into the muxer
 *   stop()          -> signals EOS, drains, releases
 *
 * The recorded picture is exactly what is on screen: flip, rotation and
 * white balance are already baked in by the renderer.
 */
public class VideoRecorder {

    private static final String TAG = "UvcTool";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int BITRATE = 4_000_000;
    private static final int FRAME_RATE = 17;        // camera gives ~16.6
    private static final int IFRAME_INTERVAL_S = 1;

    private final Context context;
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private Surface inputSurface;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private int trackIndex = -1;
    private boolean muxerStarted = false;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private long startNs = 0;

    public VideoRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isRecording() { return encoder != null; }
    public Surface getInputSurface() { return inputSurface; }
    public long elapsedMs() { return startNs == 0 ? 0 : (System.nanoTime() - startNs) / 1_000_000L; }

    /** @param width,height even numbers; use the rotated frame size (480x640 for 90/270). */
    public synchronized boolean start(int width, int height) {
        if (encoder != null) return true;
        try {
            // Without an explicit profile/level this phone's encoder writes
            // level_idc=10 (Level 1.0, max 176x144) into the SPS. Hardware
            // decoders (gallery thumbnails, seek previews) trust the level and
            // size their buffers for it -> grey frames. Ask for High@3.1, fall
            // back to Main@3.1, then to the encoder's defaults.
            encoder = MediaCodec.createEncoderByType(MIME);
            int[][] profiles = {
                    {MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, MediaCodecInfo.CodecProfileLevel.AVCLevel31},
                    {MediaCodecInfo.CodecProfileLevel.AVCProfileMain, MediaCodecInfo.CodecProfileLevel.AVCLevel31},
                    null};
            for (int[] pl : profiles) {
                // new MediaFormat(MediaFormat) is API 29; minSdk is 26.
                MediaFormat f = MediaFormat.createVideoFormat(MIME, width, height);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
                f.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_S);
                if (pl != null) {
                    f.setInteger(MediaFormat.KEY_PROFILE, pl[0]);
                    f.setInteger(MediaFormat.KEY_LEVEL, pl[1]);
                }
                try {
                    encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    Log.i(TAG, "encoder configured: " + f);
                    break;
                } catch (RuntimeException e) {
                    Log.w(TAG, "encoder rejected " + f + ": " + e);
                    encoder.reset();
                    if (pl == null) throw e;
                }
            }
            inputSurface = encoder.createInputSurface();
            encoder.start();

            String name = "UvcTool_" + System.currentTimeMillis() + ".mp4";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/UvcTool");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            pfd = context.getContentResolver().openFileDescriptor(uri, "rw");
            if (pfd == null) throw new IllegalStateException("openFileDescriptor failed");
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            trackIndex = -1;
            muxerStarted = false;
            startNs = System.nanoTime();
            Log.i(TAG, "VideoRecorder: started " + width + "x" + height + " -> " + name);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "VideoRecorder: start failed", e);
            releaseAll(true);
            return false;
        }
    }

    /** Call on the GL thread after each frame drawn into the input surface. */
    public synchronized void drainEncoder(boolean endOfStream) {
        if (encoder == null) return;
        if (endOfStream) encoder.signalEndOfInputStream();
        while (true) {
            int idx = encoder.dequeueOutputBuffer(info, endOfStream ? 10_000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
                continue;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IllegalStateException("format changed twice");
                trackIndex = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
            } else if (idx >= 0) {
                ByteBuffer buf = encoder.getOutputBuffer(idx);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && muxerStarted && buf != null) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex, buf, info);
                }
                encoder.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    /** Call on the GL thread (after the renderer has released its EGL surface). */
    public synchronized void stop() {
        if (encoder == null) return;
        try {
            drainEncoder(true);
        } catch (Exception e) {
            Log.w(TAG, "VideoRecorder: drain at stop failed", e);
        }
        releaseAll(false);
        Log.i(TAG, "VideoRecorder: stopped");
    }

    private void releaseAll(boolean failed) {
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
        try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        if (uri != null) {
            if (failed) {
                context.getContentResolver().delete(uri, null, null);
            } else {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, v, null, null);
            }
        }
        encoder = null; muxer = null; inputSurface = null; pfd = null; uri = null;
        muxerStarted = false; trackIndex = -1; startNs = 0;
    }
}
