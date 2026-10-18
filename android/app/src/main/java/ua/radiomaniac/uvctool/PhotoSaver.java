package ua.radiomaniac.uvctool;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PhotoSaver {

    private static final String TAG = "UvcTool";

    private final Context context;

    public PhotoSaver(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Call from the GL thread right after the frame was drawn.
     * Reads back only the viewport the frame occupies (so no black bars),
     * exactly as displayed: flip / rotation / white balance already applied.
     */
    public boolean captureFromGl(int x, int y, int width, int height) {
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(x, y, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        buf.rewind();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buf);

        // GL reads bottom-up — flip vertically
        Matrix m = new Matrix();
        m.postScale(1f, -1f, width / 2f, height / 2f);
        Bitmap flipped = Bitmap.createBitmap(bitmap, 0, 0, width, height, m, true);
        bitmap.recycle();

        boolean ok = saveToMediaStore(flipped);
        flipped.recycle();
        return ok;
    }

    private boolean saveToMediaStore(Bitmap bitmap) {
        String filename = "UvcTool_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UvcTool");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        android.net.Uri uri = context.getContentResolver()
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Log.e(TAG, "PhotoSaver: failed to insert into MediaStore");
            return false;
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("null output stream");
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            Log.d(TAG, "PhotoSaver: saved " + filename);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "PhotoSaver: save failed", e);
            context.getContentResolver().delete(uri, null, null);
            return false;
        }
    }
}
