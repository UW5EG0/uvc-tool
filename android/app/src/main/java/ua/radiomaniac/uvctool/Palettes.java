package ua.radiomaniac.uvctool;

/**
 * False-colour palettes for monochrome sources (thermal cameras, grey endoscopes):
 * brightness -> colour through a 256-entry lookup table the shader samples.
 */
public final class Palettes {

    public static final int NONE = 0;       // camera colours as they are
    public static final int WHITE_HOT = 1;  // grey, bright = hot
    public static final int BLACK_HOT = 2;  // inverted grey
    public static final int IRONBOW = 3;
    public static final int RAINBOW = 4;
    public static final int COUNT = 5;

    private Palettes() {}

    // control points: position 0..1, r, g, b (0..255)
    private static final float[][] IRON = {
            {0.00f,   0,   0,   0}, {0.15f,  30,   0,  90}, {0.30f, 110,   0, 155},
            {0.45f, 190,  30, 130}, {0.60f, 235,  90,  40}, {0.75f, 250, 160,   0},
            {0.90f, 255, 225,  60}, {1.00f, 255, 255, 255}};
    private static final float[][] RAINBOW_PTS = {
            {0.000f,   0,   0, 128}, {0.125f,   0,   0, 255}, {0.375f,   0, 255, 255},
            {0.625f, 255, 255,   0}, {0.875f, 255,   0,   0}, {1.000f, 128,   0,   0}};

    /** RGBA, 256 x 1. */
    public static byte[] lut(int palette) {
        byte[] out = new byte[256 * 4];
        for (int i = 0; i < 256; i++) {
            float x = i / 255f;
            float r, g, b;
            switch (palette) {
                case BLACK_HOT: r = g = b = 255 * (1 - x); break;
                case IRONBOW:   { float[] c = interp(IRON, x); r = c[0]; g = c[1]; b = c[2]; break; }
                case RAINBOW:   { float[] c = interp(RAINBOW_PTS, x); r = c[0]; g = c[1]; b = c[2]; break; }
                default:        r = g = b = 255 * x;
            }
            out[i * 4]     = (byte) Math.round(r);
            out[i * 4 + 1] = (byte) Math.round(g);
            out[i * 4 + 2] = (byte) Math.round(b);
            out[i * 4 + 3] = (byte) 255;
        }
        return out;
    }

    private static float[] interp(float[][] pts, float x) {
        for (int k = 1; k < pts.length; k++) {
            if (x <= pts[k][0]) {
                float t = (x - pts[k - 1][0]) / (pts[k][0] - pts[k - 1][0]);
                return new float[]{
                        pts[k - 1][1] + t * (pts[k][1] - pts[k - 1][1]),
                        pts[k - 1][2] + t * (pts[k][2] - pts[k - 1][2]),
                        pts[k - 1][3] + t * (pts[k][3] - pts[k - 1][3])};
            }
        }
        float[] l = pts[pts.length - 1];
        return new float[]{l[1], l[2], l[3]};
    }
}
