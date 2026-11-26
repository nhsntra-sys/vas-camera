package vn.vas.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mở ảnh chụp bằng VAS Camera, bật/tắt từng phần khung, lưu đè / lưu mới / chia sẻ. */
public class EditorActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Uri src;
    private VasMeta meta;
    private Stamp.Data d;
    private Bitmap clean;        // ảnh gốc không khung, độ phân giải đầy đủ
    private Bitmap base;         // bản thu nhỏ để xem trước
    private Bitmap shown;
    private ImageView iv;
    private ProgressBar spin;
    private LinearLayout toggles, actions;
    private final List<CheckBox> boxes = new ArrayList<>();
    private CheckBox all;
    private boolean working, dirty;
    private int renderSeq;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Stamp.init(this);
        Intent in = getIntent();
        src = in.getData();
        if (src == null && Intent.ACTION_SEND.equals(in.getAction())) {
            if (Build.VERSION.SDK_INT >= 33) src = in.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            else src = in.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        buildUi();
        if (src == null) { toast("Không mở được ảnh"); finish(); return; }
        load();
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private GradientDrawable pill(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private TextView button(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(Stamp.RC);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setBackground(pill(color, dp(12)));
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(12), dp(6));
        TextView back = button("✕", 0x33FFFFFF);
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText("Bật/tắt khung ảnh");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Stamp.RC);
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title);
        root.addView(top);

        FrameLayout stage = new FrameLayout(this);
        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        spin = new ProgressBar(this);
        stage.addView(spin, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.VERTICAL);
        toggles.setPadding(dp(16), dp(8), dp(16), 0);
        root.addView(toggles);

        actions = new LinearLayout(this);
        actions.setPadding(dp(12), dp(10), dp(12), dp(14));
        TextView share = button("Gửi (Zalo…)", 0xFF1E9BFF);
        share.setOnClickListener(v -> share());
        TextView save = button("Lưu đè", 0xFFFF7A1A);
        save.setOnClickListener(v -> save(true));
        TextView saveNew = button("Lưu bản mới", 0xFF2A2E37);
        saveNew.setOnClickListener(v -> save(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        actions.addView(share, lp);
        actions.addView(saveNew, new LinearLayout.LayoutParams(lp));
        actions.addView(save, new LinearLayout.LayoutParams(lp));
        actions.setVisibility(View.GONE);
        root.addView(actions);
        setContentView(root);
    }

    private CheckBox box(String text, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(Color.WHITE);
        c.setTextSize(15);
        c.setChecked(on);
        c.setMinHeight(dp(40));
        return c;
    }

    private void buildToggles() {
        toggles.removeAllViews();
        boxes.clear();
        all = box("Hiện khung (tắt = ảnh sạch)", d.showTime || d.showAddr || d.showLogo || d.showCode || (d.showNote && !d.note.isEmpty()) || (d.showGps && d.hasGps));
        toggles.addView(all);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(24), 0, 0, 0);
        List<CheckBox> items = new ArrayList<>();
        CheckBox cTime = box("Giờ & ngày", d.showTime);
        CheckBox cAddr = box("Địa chỉ", d.showAddr);
        CheckBox cLogo = box("Logo VAS", d.showLogo);
        CheckBox cCode = box("Mã xác thực", d.showCode);
        items.add(cTime); items.add(cAddr); items.add(cLogo); items.add(cCode);
        CheckBox cNote = null, cGps = null;
        if (d.note != null && !d.note.trim().isEmpty()) { cNote = box("Ghi chú", d.showNote); items.add(cNote); }
        if (d.hasGps) { cGps = box("Toạ độ GPS", d.showGps); items.add(cGps); }
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 2 == 0) { row = new LinearLayout(this); grid.addView(row); }
            row.addView(items.get(i), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        if (items.size() % 2 == 1 && row != null) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        toggles.addView(grid);
        boxes.addAll(items);

        final CheckBox fNote = cNote, fGps = cGps;
        CompoundButton.OnCheckedChangeListener each = (b, on) -> {
            d.showTime = cTime.isChecked();
            d.showAddr = cAddr.isChecked();
            d.showLogo = cLogo.isChecked();
            d.showCode = cCode.isChecked();
            if (fNote != null) d.showNote = fNote.isChecked();
            if (fGps != null) d.showGps = fGps.isChecked();
            boolean any = false;
            for (CheckBox c : boxes) any |= c.isChecked();
            all.setOnCheckedChangeListener(null);
            all.setChecked(any);
            all.setOnCheckedChangeListener(this::onAll);
            dirty = true;
            render();
        };
        for (CheckBox c : items) c.setOnCheckedChangeListener(each);
        all.setOnCheckedChangeListener(this::onAll);
    }

    private void onAll(CompoundButton b, boolean on) {
        for (CheckBox c : boxes) c.setChecked(on);   // mỗi ô tự gọi render
    }

    private void load() {
        io.execute(() -> {
            try {
                byte[] file;
                try (InputStream is = getContentResolver().openInputStream(src)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                    file = bo.toByteArray();
                }
                VasMeta m = VasMeta.parse(file);
                if (m == null) {
                    main.post(() -> new AlertDialog.Builder(this)
                            .setTitle("Không bật/tắt khung được")
                            .setMessage("Ảnh này không có dữ liệu khung. Chỉ ảnh chụp từ bản VAS Camera có tính năng bật/tắt khung trở đi mới làm được. Ảnh đã gửi qua Zalo, đã sửa bằng app khác, hoặc chụp khi tắt tuỳ chọn \"Cho phép bật/tắt khung\" cũng không được.")
                            .setPositiveButton("Đóng", (x, y) -> finish())
                            .setOnCancelListener(x -> finish())
                            .show());
                    return;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inMutable = true;
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap full = BitmapFactory.decodeByteArray(file, 0, m.imageEnd, o);
                if (full == null) throw new Exception("không đọc được ảnh");
                file = null;
                Canvas c = new Canvas(full);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                for (int i = 0; i < m.rects.size(); i++) {
                    byte[] cb = m.crops.get(i);
                    Bitmap cr = BitmapFactory.decodeByteArray(cb, 0, cb.length);
                    if (cr == null) continue;
                    Rect r = m.rects.get(i);
                    c.drawBitmap(cr, null, r, p);
                    cr.recycle();
                }
                int max = 1600;
                float k = Math.min(1f, (float) max / Math.max(full.getWidth(), full.getHeight()));
                Bitmap small = Bitmap.createScaledBitmap(full, Math.max(1, Math.round(full.getWidth() * k)), Math.max(1, Math.round(full.getHeight() * k)), true);
                main.post(() -> {
                    meta = m;
                    d = m.data.copy();
                    clean = full;
                    base = small;
                    buildToggles();
                    actions.setVisibility(View.VISIBLE);
                    render();
                });
            } catch (Throwable t) {
                main.post(() -> { toast("Không mở được ảnh: " + t.getMessage()); finish(); });
            }
        });
    }

    private void render() {
        if (base == null) return;
        final Stamp.Data snap = d.copy();
        final int seq = ++renderSeq;
        Bitmap p = base.copy(Bitmap.Config.ARGB_8888, true);
        Stamp.draw(new Canvas(p), p.getWidth(), p.getHeight(), snap);
        if (seq != renderSeq) { p.recycle(); return; }
        iv.setImageBitmap(p);
        if (shown != null && shown != p) shown.recycle();
        shown = p;
        spin.setVisibility(View.GONE);
    }

    /** Vẽ ảnh độ phân giải đầy đủ với các phần đang bật, ghi ra file tạm. */
    private File renderFull(File out, boolean withMeta) throws Exception {
        Bitmap f = clean.copy(Bitmap.Config.ARGB_8888, true);
        Stamp.draw(new Canvas(f), f.getWidth(), f.getHeight(), d);
        try (FileOutputStream fo = new FileOutputStream(out)) { f.compress(Bitmap.CompressFormat.JPEG, 95, fo); }
        f.recycle();
        Store.writeExif(out, d.time, d);
        if (withMeta) {
            VasMeta m = new VasMeta();
            m.data = d.copy();
            m.width = meta.width;
            m.height = meta.height;
            m.rects.addAll(meta.rects);
            m.crops.addAll(meta.crops);
            Store.appendMeta(out, m);
        }
        return out;
    }

    private void busy(boolean on) {
        working = on;
        main.post(() -> {
            spin.setVisibility(on ? View.VISIBLE : View.GONE);
            actions.setAlpha(on ? 0.5f : 1f);
        });
    }

    private void save(boolean overwrite) {
        if (clean == null || working) return;
        busy(true);
        final Stamp.Data snap = d.copy();
        io.execute(() -> {
            try {
                File tmp = renderFull(new File(getCacheDir(), "edit.jpg"), true);
                boolean ok = overwrite && Store.overwrite(this, src, tmp);
                if (ok) {
                    toast("Đã lưu đè ảnh");
                } else {
                    Uri u = Store.saveToGallery(this, tmp, Store.fileName(snap.time, "_" + (System.currentTimeMillis() / 1000 % 100000)), snap.time);
                    if (u != null) src = u;
                    toast(overwrite ? "Không ghi đè được ảnh này nên đã lưu thành ảnh mới" : "Đã lưu ảnh mới vào Bộ sưu tập");
                }
                tmp.delete();
                dirty = false;
            } catch (Throwable t) {
                toast("Lỗi lưu ảnh: " + t.getMessage());
            } finally { busy(false); }
        });
    }

    private void share() {
        if (clean == null || working) return;
        busy(true);
        final Stamp.Data snap = d.copy();
        io.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "share");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File o : old) o.delete();
                // Ảnh gửi đi KHÔNG kèm bản sạch, để người nhận không gỡ được khung
                File f = renderFull(new File(dir, Store.fileName(snap.time, "")), false);
                Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("image/jpeg");
                s.putExtra(Intent.EXTRA_STREAM, u);
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                main.post(() -> startActivity(Intent.createChooser(s, "Gửi ảnh")));
            } catch (Throwable t) {
                toast("Không gửi được: " + t.getMessage());
            } finally { busy(false); }
        });
    }

    @Override public void onBackPressed() {
        if (dirty) {
            new AlertDialog.Builder(this)
                    .setMessage("Chưa lưu thay đổi. Thoát luôn?")
                    .setNegativeButton("Ở lại", null)
                    .setPositiveButton("Thoát", (a, b) -> finish())
                    .show();
        } else super.onBackPressed();
    }
}
