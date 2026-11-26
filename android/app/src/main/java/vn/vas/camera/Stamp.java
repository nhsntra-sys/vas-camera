package vn.vas.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Vẽ khung dấu chìm kiểu Timemark + logo VAS. Mọi kích thước tính theo cạnh ngắn / 960. */
public final class Stamp {
    public static Typeface RC = Typeface.SANS_SERIF;
    public static Typeface BC = Typeface.SANS_SERIF;
    public static Bitmap LOGO;
    private static final String[] WD = {"Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy"};
    private static final SecureRandom RND = new SecureRandom();

    public static final class Data {
        public long time;
        public String addr = "", note = "", comp = "", code = "";
        public boolean showCode = true, showGps = false, hasGps = false;
        public boolean showTime = true, showAddr = true, showLogo = true, showNote = true;
        public double lat, lon;

        public Data copy() {
            Data d = new Data();
            d.time = time; d.addr = addr; d.note = note; d.comp = comp; d.code = code;
            d.showCode = showCode; d.showGps = showGps; d.hasGps = hasGps;
            d.showTime = showTime; d.showAddr = showAddr; d.showLogo = showLogo; d.showNote = showNote;
            d.lat = lat; d.lon = lon;
            return d;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject j = new JSONObject();
            j.put("time", time); j.put("addr", addr); j.put("note", note); j.put("comp", comp); j.put("code", code);
            j.put("showCode", showCode); j.put("showGps", showGps); j.put("hasGps", hasGps);
            j.put("showTime", showTime); j.put("showAddr", showAddr); j.put("showLogo", showLogo); j.put("showNote", showNote);
            j.put("lat", lat); j.put("lon", lon);
            return j;
        }

        public static Data fromJson(JSONObject j) {
            Data d = new Data();
            d.time = j.optLong("time", System.currentTimeMillis());
            d.addr = j.optString("addr", ""); d.note = j.optString("note", ""); d.comp = j.optString("comp", ""); d.code = j.optString("code", "");
            d.showCode = j.optBoolean("showCode", true); d.showGps = j.optBoolean("showGps", false); d.hasGps = j.optBoolean("hasGps", false);
            d.showTime = j.optBoolean("showTime", true); d.showAddr = j.optBoolean("showAddr", true);
            d.showLogo = j.optBoolean("showLogo", true); d.showNote = j.optBoolean("showNote", true);
            d.lat = j.optDouble("lat", 0); d.lon = j.optDouble("lon", 0);
            return d;
        }
    }

    /** Các vùng ảnh mà khung có thể đè lên (khi bật hết), để lưu bản sạch. */
    public static List<Rect> regions(int W, int H, Data d) {
        Data f = d.copy();
        f.showTime = f.showAddr = f.showLogo = f.showNote = f.showCode = true;
        f.showGps = f.hasGps;
        float k = 0.25f;
        int w = Math.max(1, Math.round(W * k)), h = Math.max(1, Math.round(H * k));
        Bitmap a = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(a);
        c.scale(k, k);
        draw(c, W, H, f);
        int rb = a.getRowBytes();
        byte[] px = new byte[rb * h];
        a.copyPixelsToBuffer(ByteBuffer.wrap(px));
        a.recycle();
        float s = Math.min(W, H) / 960f;
        int stripX = Math.max(0, Math.min(w, (int) ((W - 60 * s) * k)));
        int top = h;
        outer:
        for (int y = 0; y < h; y++) for (int x = 0; x < stripX; x++) if (px[y * rb + x] != 0) { top = y; break outer; }
        int cTop = h, cBot = -1, cLeft = w;
        for (int y = 0; y < top; y++) for (int x = stripX; x < w; x++) if (px[y * rb + x] != 0) {
            if (y < cTop) cTop = y;
            if (y > cBot) cBot = y;
            if (x < cLeft) cLeft = x;
        }
        int m = Math.round(12 * s) + 4;
        List<Rect> out = new ArrayList<>();
        if (top < h) out.add(new Rect(0, Math.max(0, Math.round(top / k) - m), W, H));
        if (cBot >= 0) out.add(new Rect(Math.max(0, Math.round(cLeft / k) - m), Math.max(0, Math.round(cTop / k) - m), W,
                Math.min(H, Math.round((cBot + 1) / k) + m)));
        return out;
    }

    public static void init(Context c) {
        if (LOGO != null) return;
        try {
            RC = Typeface.createFromAsset(c.getAssets(), "rc.ttf");
            BC = Typeface.createFromAsset(c.getAssets(), "bc.ttf");
        } catch (Exception ignored) { }
        try (InputStream is = c.getAssets().open("logo.png")) {
            LOGO = BitmapFactory.decodeStream(is);
        } catch (Exception ignored) { }
    }

    public static String newCode() {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(a.charAt(RND.nextInt(a.length())));
        return sb.toString();
    }

    /** rot: 0, 90 (máy xoay ngược chiều kim đồng hồ) hoặc 270 (xoay cùng chiều). */
    public static void drawRotated(Canvas c, float W, float H, int rot, Data d) {
        if (rot == 90) {
            c.save(); c.translate(W, 0); c.rotate(90); draw(c, H, W, d); c.restore();
        } else if (rot == 270) {
            c.save(); c.translate(0, H); c.rotate(-90); draw(c, H, W, d); c.restore();
        } else {
            draw(c, W, H, d);
        }
    }

    public static void draw(Canvas c, float W, float H, Data d) {
        float s = Math.min(W, H) / 960f, x0 = 24 * s;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setColor(Color.WHITE);
        p.setShadowLayer(5 * s, 1 * s, 1 * s, 0x73000000);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(d.time);

        // Logo VAS góc phải dưới
        String comp = d.comp == null ? "" : d.comp.trim();
        float logoW = 150 * s;
        if (LOGO != null && d.showLogo) {
            float lh = logoW * LOGO.getHeight() / LOGO.getWidth();
            float lyB = H - (comp.isEmpty() ? 18 : 44) * s;
            Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            c.drawBitmap(LOGO, null, new RectF(W - 16 * s - logoW, lyB - lh, W - 16 * s, lyB), bp);
        }
        p.setTypeface(RC);
        float compW = 0;
        if (!comp.isEmpty()) {
            float fs = 19;
            p.setTextSize(fs * s);
            while (p.measureText(comp) > logoW + 40 * s && fs > 11) { fs--; p.setTextSize(fs * s); }
            compW = p.measureText(comp);
            p.setTextAlign(Paint.Align.RIGHT);
            if (d.showLogo) c.drawText(comp, W - 16 * s, H - 18 * s, p);
            p.setTextAlign(Paint.Align.LEFT);
        }
        float rightReserve = Math.max(logoW, compW) + 40 * s;

        // Địa chỉ (dòng cuối)
        p.setTextSize(37 * s);
        String addr = d.addr == null ? "" : d.addr.trim();
        List<String> al = addr.isEmpty() ? new ArrayList<>() : wrap(p, addr, W - x0 - rightReserve);
        if (al.size() > 2) al = al.subList(0, 2);
        float yA = H - 28 * s;
        for (int i = al.size() - 1; i >= 0; i--) { if (d.showAddr) c.drawText(al.get(i), x0 + 4 * s, yA, p); yA -= 44 * s; }
        float up = (Math.max(al.size(), 1) - 1) * 44 * s;
        if (d.showGps && d.hasGps) {
            p.setTextSize(24 * s);
            c.drawText(String.format(Locale.US, "%.6f, %.6f", d.lat, d.lon), x0 + 4 * s, H - 62 * s - up, p);
            up += 34 * s;
        }

        // Giờ lớn
        Paint keep = p;
        if (!d.showTime) { p = new Paint(p); p.setColor(Color.TRANSPARENT); p.clearShadowLayer(); }
        float tBase = H - 95 * s - up;
        String t = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        p.setTypeface(BC);
        p.setTextSize(129 * s);
        float mw = p.measureText(t);
        float tx = Math.min(1f, 208 * s / mw);
        c.save(); c.translate(x0 - 2 * s, tBase); c.scale(tx, 1); c.drawText(t, 0, 0, p); c.restore();
        float bx = x0 + mw * tx + 30 * s;

        // Vạch xanh
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(0xFF1E9BFF);
        if (d.showTime) c.drawRect(bx, tBase - 92 * s, bx + 4 * s, tBase + 8 * s, bar);

        // Ngày + thứ
        p.setTypeface(RC);
        p.setTextSize(36 * s);
        c.drawText(cal.get(Calendar.DAY_OF_MONTH) + " Tháng " + (cal.get(Calendar.MONTH) + 1) + "," + cal.get(Calendar.YEAR), bx + 19 * s, tBase - 66 * s, p);
        c.drawText(WD[cal.get(Calendar.DAY_OF_WEEK) - 1], bx + 19 * s, tBase + 3 * s, p);
        p = keep;

        // Ghi chú phía trên giờ
        String note = d.note == null ? "" : d.note.trim();
        if (!note.isEmpty() && d.showNote) {
            p.setTextSize(30 * s);
            List<String> nl = wrap(p, note, W - x0 * 2 - rightReserve);
            if (nl.size() > 3) nl = nl.subList(0, 3);
            float ny = tBase - 120 * s - (nl.size() - 1) * 38 * s;
            for (int i = 0; i < nl.size(); i++) c.drawText(nl.get(i), x0 + 4 * s, ny + i * 38 * s, p);
        }

        // Mã dọc mép phải
        if (d.showCode && d.code != null && !d.code.isEmpty()) {
            p.setTextSize(21 * s);
            p.setColor(0xEBFFFFFF);
            String txt = "©  " + d.code + "    VAS Verified";
            float tl = p.measureText(txt);
            c.save(); c.translate(W - 14 * s, H * 0.44f + tl / 2); c.rotate(-90); c.drawText(txt, 0, 0, p); c.restore();
        }
    }

    private static List<String> wrap(Paint p, String text, float maxW) {
        List<String> lines = new ArrayList<>();
        String cur = "";
        for (String w : text.split("\\s+")) {
            String t = cur.isEmpty() ? w : cur + " " + w;
            if (p.measureText(t) > maxW && !cur.isEmpty()) { lines.add(cur); cur = w; } else cur = t;
        }
        if (!cur.isEmpty()) lines.add(cur);
        return lines;
    }
}
