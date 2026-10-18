package ua.radiomaniac.uvctool;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.appcompat.widget.AppCompatSeekBar;

/**
 * SeekBar that can be laid out vertically (minimum at the bottom). Used for the
 * picture-adjustment slider in landscape, where it runs along the control column.
 */
public class OrientableSeekBar extends AppCompatSeekBar {

    private boolean vertical;

    public OrientableSeekBar(Context c) { super(c); }
    public OrientableSeekBar(Context c, AttributeSet a) { super(c, a); }
    public OrientableSeekBar(Context c, AttributeSet a, int s) { super(c, a, s); }

    public void setVertical(boolean v) {
        if (vertical == v) return;
        vertical = v;
        requestLayout();
        invalidate();
    }

    @Override
    protected synchronized void onMeasure(int wSpec, int hSpec) {
        if (!vertical) { super.onMeasure(wSpec, hSpec); return; }
        super.onMeasure(hSpec, wSpec);
        setMeasuredDimension(getMeasuredHeight(), getMeasuredWidth());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (vertical) super.onSizeChanged(h, w, oh, ow); else super.onSizeChanged(w, h, ow, oh);
    }

    @Override
    protected synchronized void onDraw(Canvas c) {
        if (vertical) {
            c.rotate(-90);
            c.translate(-getHeight(), 0);
        }
        super.onDraw(c);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!vertical) return super.onTouchEvent(e);
        if (!isEnabled()) return false;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP: {
                int top = getPaddingLeft(), span = getHeight() - getPaddingLeft() - getPaddingRight();
                float f = 1f - (e.getY() - top) / Math.max(1, span);
                int p = Math.round(Math.max(0f, Math.min(1f, f)) * getMax());
                if (p != getProgress()) {
                    setProgress(p);
                    if (listener != null) listener.onProgressChanged(this, p, true);
                }
                onSizeChanged(getWidth(), getHeight(), 0, 0);   // re-place the thumb
                if (e.getAction() == MotionEvent.ACTION_UP && listener != null) listener.onStopTrackingTouch(this);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                return true;
        }
        return false;
    }

    private OnSeekBarChangeListener listener;

    @Override
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener l) {
        listener = l;
        super.setOnSeekBarChangeListener(l);
    }
}
