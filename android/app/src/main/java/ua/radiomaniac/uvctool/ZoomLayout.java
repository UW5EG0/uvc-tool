package ua.radiomaniac.uvctool;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Pinch / double-tap zoom and pan for its single child (photo or video alike).
 *
 * The child fills this layout; the picture inside it is fitted (letterboxed) to
 * {@link #setContentSize}. Zoom is applied as a view transform of the child, and
 * panning is clamped to the picture, not to the black bars around it.
 */
public class ZoomLayout extends FrameLayout {

    public interface Listener {
        /** Picture rectangle at zoom 1, in child coordinates (re-sent on every layout). */
        default void onContentRect(RectF rect) {}
        default void onZoomChanged(float scale) {}
        default void onSingleTap() {}
        /** Horizontal fling at zoom 1: +1 = next item (finger moved left), -1 = previous. */
        default void onSwipe(int direction) {}
    }

    private static final float MAX_SCALE = 10f;
    private static final float DOUBLE_TAP_SCALE = 3f;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Listener listener = new Listener() {};

    private int contentW, contentH;
    private final RectF fit = new RectF();      // picture rect at scale 1 (child coords)
    private float scale = 1f, tx = 0f, ty = 0f;
    private ValueAnimator anim;

    public ZoomLayout(Context c) { this(c, null); }

    public ZoomLayout(Context c, AttributeSet a) {
        super(c, a);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAround(scale * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        scaleDetector.setQuickScaleEnabled(false);   // double-tap-drag would fight our double tap
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                listener.onSingleTap();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (scale > 1.01f) animateTo(1f, e.getX(), e.getY());
                else animateTo(DOUBLE_TAP_SCALE, e.getX(), e.getY());
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress() || scale <= 1.01f) return false;
                tx -= dx; ty -= dy;
                apply();
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scale > 1.01f || e1 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > dp(60) && Math.abs(dx) > 1.5f * Math.abs(dy) && Math.abs(vx) > dp(300)) {
                    listener.onSwipe(dx < 0 ? +1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setListener(Listener l) { listener = l != null ? l : new Listener() {}; }

    public float getScale() { return scale; }

    /** Size of the picture (photo pixels or video frame). Resets the zoom. */
    public void setContentSize(int w, int h) {
        contentW = w; contentH = h;
        computeFit();
        reset();
    }

    public void reset() {
        if (anim != null) anim.cancel();
        scale = 1f; tx = 0f; ty = 0f;
        apply();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        computeFit();
        reset();                  // rotation: start again from the fitted picture
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) { return true; }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && anim != null) anim.cancel();
        scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        return true;
    }

    // ------------------------------------------------------------------ internals

    private void computeFit() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (contentW <= 0 || contentH <= 0) { fit.set(0, 0, w, h); }
        else {
            float k = Math.min((float) w / contentW, (float) h / contentH);
            float cw = contentW * k, ch = contentH * k;
            fit.set((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f);
        }
        listener.onContentRect(new RectF(fit));
    }

    /** Keep the focus point (screen coords) fixed while changing scale. */
    private void zoomAround(float newScale, float fx, float fy) {
        newScale = Math.max(1f, Math.min(MAX_SCALE, newScale));
        float k = newScale / scale;
        tx = fx - (fx - tx) * k;
        ty = fy - (fy - ty) * k;
        scale = newScale;
        apply();
    }

    private void animateTo(float target, float fx, float fy) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(scale, target);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> zoomAround((float) a.getAnimatedValue(), fx, fy));
        anim.start();
    }

    /** Clamp translation to the picture and push the transform to the child. */
    private void apply() {
        int w = getWidth(), h = getHeight();
        float pw = fit.width() * scale, ph = fit.height() * scale;
        if (pw <= w) tx = (w - pw) / 2f - fit.left * scale;
        else tx = Math.max(w - (fit.right * scale), Math.min(-fit.left * scale, tx));
        if (ph <= h) ty = (h - ph) / 2f - fit.top * scale;
        else ty = Math.max(h - (fit.bottom * scale), Math.min(-fit.top * scale, ty));

        View child = getChildCount() > 0 ? getChildAt(0) : null;
        if (child != null) {
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setTranslationX(tx);
            child.setTranslationY(ty);
        }
        listener.onZoomChanged(scale);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
