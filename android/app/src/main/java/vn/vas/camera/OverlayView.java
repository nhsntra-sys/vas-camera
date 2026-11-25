package vn.vas.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Lớp phủ trên khung ngắm: dấu chìm trực tiếp + vòng lấy nét. */
public final class OverlayView extends View {
    public interface DataSource { Stamp.Data current(); }

    public DataSource source;
    public float aspect = 3f / 4f;   // rộng / cao của ảnh khi cầm dọc
    public int rot = 0;
    private float fx = -1, fy = -1;
    private long focusAt = 0;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Runnable tick = new Runnable() {
        @Override public void run() { invalidate(); postDelayed(this, 1000); }
    };

    public OverlayView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFD24A);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

    /** Vùng ảnh thật bên trong khung ngắm (PreviewView FIT_CENTER). */
    public RectF contentRect() {
        float w = getWidth(), h = getHeight();
        float cw = w, ch = w / aspect;
        if (ch > h) { ch = h; cw = h * aspect; }
        box.set((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
        return box;
    }

    public void showFocus(float x, float y) {
        fx = x; fy = y; focusAt = System.currentTimeMillis();
        invalidate();
        postDelayed(this::invalidate, 1100);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (source == null || getWidth() == 0) return;
        RectF r = contentRect();
        c.save();
        c.clipRect(r);
        c.translate(r.left, r.top);
        Stamp.drawRotated(c, r.width(), r.height(), rot, source.current());
        c.restore();
        if (fx >= 0 && System.currentTimeMillis() - focusAt < 1000) {
            float d = getResources().getDisplayMetrics().density;
            ring.setStrokeWidth(2 * d);
            c.drawCircle(fx, fy, 36 * d, ring);
        }
    }
}
