package vn.vas.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SizeF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.location.Location;
import android.media.MediaActionSound;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Range;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = ExperimentalCamera2Interop.class)
public class MainActivity extends ComponentActivity implements Loc.Listener {
    private static final String RELEASES = "https://github.com/nhsntra-sys/vas-camera/releases/latest";
    private static final String APK_URL = RELEASES + "/download/VAS-Camera.apk";

    private SharedPreferences sp;
    private Loc loc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private PreviewView pv;
    private OverlayView ov;
    private View flashView;
    private TextView locTxt, flashBtn, modeBtn, updBtn;
    private LinearLayout zoomRow;
    private ImageView thumb;
    private View shutter, flipBtn, setBtn;

    private ProcessCameraProvider provider;
    private ExtensionsManager ext;
    private Camera camera;
    private ImageCapture ic;
    private boolean front = false;
    private String auxId = null;          // id camera phụ (góc rộng / tele) đang dùng, null = camera chính
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private int extMode = ExtensionMode.NONE;
    private int rot = 0, targetRot = Surface.ROTATION_0;
    private String code = Stamp.newCode();
    private Uri lastUri;
    private volatile boolean busy;
    private MediaActionSound snd;
    private OrientationEventListener orient;

    private static final class Stop {
        String label; String cam; float zoom; float shown;
        Stop(String l, String c, float z, float s) { label = l; cam = c; zoom = z; shown = s; }
    }
    private final List<Stop> stops = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();

    private ActivityResultLauncher<String[]> permLauncher;
    private ActivityResultLauncher<String> pickLauncher;

    // ------------------------------------------------------------------ vòng đời
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("vascam", MODE_PRIVATE);
        Stamp.init(this);
        loc = new Loc(this, sp);
        loc.listener = this;
        try { snd = new MediaActionSound(); snd.load(MediaActionSound.SHUTTER_CLICK); } catch (Throwable ignored) { }
        buildUi();

        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
            if (has(Manifest.permission.CAMERA)) startCamera();
            else toast("Cần cho phép camera để chụp ảnh");
            loc.start();
        });
        pickLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) stampExisting(uri);
        });

        orient = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int tr;
                if (o >= 60 && o < 120) tr = Surface.ROTATION_270;
                else if (o >= 240 && o < 300) tr = Surface.ROTATION_90;
                else if (o >= 330 || o < 30) tr = Surface.ROTATION_0;
                else return; // vùng giữa: giữ nguyên để không giật
                if (tr != targetRot) setRotation(tr);
            }
        };

        List<String> need = new ArrayList<>();
        if (!has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) { need.add(Manifest.permission.ACCESS_FINE_LOCATION); need.add(Manifest.permission.ACCESS_COARSE_LOCATION); }
        if (Build.VERSION.SDK_INT < 29 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (need.isEmpty()) startCamera(); else permLauncher.launch(need.toArray(new String[0]));
        checkUpdate();
    }

    @Override protected void onResume() {
        super.onResume();
        if (orient.canDetectOrientation()) orient.enable();
        loc.start();
        refreshLoc();
    }

    @Override protected void onPause() {
        orient.disable();
        loc.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (snd != null) snd.release();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (e.getRepeatCount() == 0) shoot();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private boolean has(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    // ------------------------------------------------------------------ giao diện
    private GradientDrawable pill(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView smallBtn(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setTypeface(Stamp.RC);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setMinHeight(dp(40));
        t.setBackground(pill(0x33FFFFFF, dp(20)));
        return t;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        // Thanh trên
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(6), dp(10), dp(6));
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(Stamp.LOGO);
        logo.setBackground(pill(Color.WHITE, dp(6)));
        logo.setPadding(dp(6), dp(2), dp(6), dp(2));
        logo.setAdjustViewBounds(true);
        top.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(30)));
        locTxt = new TextView(this);
        locTxt.setTextColor(0xFFDDE3EE);
        locTxt.setTextSize(14);
        locTxt.setTypeface(Stamp.RC);
        locTxt.setSingleLine(true);
        locTxt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        locTxt.setPadding(dp(10), 0, dp(6), 0);
        locTxt.setOnClickListener(v -> openSettings());
        top.addView(locTxt, new LinearLayout.LayoutParams(0, dp(44), 1));
        locTxt.setGravity(Gravity.CENTER_VERTICAL);
        updBtn = smallBtn("Có bản mới");
        updBtn.setBackground(pill(0xFFFF7A1A, dp(20)));
        updBtn.setVisibility(View.GONE);
        updBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Có bản VAS Camera mới")
                .setMessage("Bấm Tải về. Nếu khi cài máy báo \"Chưa cài đặt ứng dụng\", hãy gỡ VAS Camera cũ trước rồi mở lại file vừa tải để cài. Ảnh đã chụp vẫn còn nguyên trong Bộ sưu tập.")
                .setNegativeButton("Để sau", null)
                .setPositiveButton("Tải về", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL))))
                .show());
        top.addView(updBtn);
        TextView set = smallBtn("⚙");
        set.setTextSize(20);
        set.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(44), dp(44));
        slp.leftMargin = dp(6);
        top.addView(set, slp);
        setBtn = set;
        root.addView(top);

        // Hàng flash / chế độ
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        tools.setPadding(0, 0, 0, dp(6));
        flashBtn = smallBtn("⚡ Tắt");
        flashBtn.setOnClickListener(v -> cycleFlash());
        modeBtn = smallBtn("Thường");
        modeBtn.setVisibility(View.GONE);
        modeBtn.setOnClickListener(v -> cycleMode());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        tlp.setMargins(dp(6), 0, dp(6), 0);
        tools.addView(flashBtn, tlp);
        tools.addView(modeBtn, new LinearLayout.LayoutParams(tlp));
        root.addView(tools);

        // Khung ngắm
        FrameLayout stage = new FrameLayout(this);
        pv = new PreviewView(this);
        pv.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        pv.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        stage.addView(pv, new FrameLayout.LayoutParams(-1, -1));
        ov = new OverlayView(this);
        ov.source = this::currentData;
        stage.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        flashView = new View(this);
        flashView.setBackgroundColor(Color.WHITE);
        flashView.setAlpha(0f);
        stage.addView(flashView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        final ScaleGestureDetector sgd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(@NonNull ScaleGestureDetector d) {
                if (camera == null) return false;
                ZoomState z = camera.getCameraInfo().getZoomState().getValue();
                if (z == null) return false;
                float nz = Math.max(z.getMinZoomRatio(), Math.min(z.getMaxZoomRatio(), z.getZoomRatio() * d.getScaleFactor()));
                camera.getCameraControl().setZoomRatio(nz);
                return true;
            }
        });
        ov.setOnTouchListener((v, e) -> {
            sgd.onTouchEvent(e);
            if (e.getAction() == MotionEvent.ACTION_UP && !sgd.isInProgress() && e.getPointerCount() == 1
                    && e.getEventTime() - e.getDownTime() < 400) {
                focusAt(e.getX(), e.getY());
            }
            return true;
        });

        // Hàng zoom
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.setPadding(dp(8), dp(10), dp(8), dp(4));
        hs.addView(zoomRow);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(hs, hlp);

        // Hàng nút chụp
        FrameLayout bar = new FrameLayout(this);
        bar.setPadding(dp(28), dp(10), dp(28), dp(18));
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tb = pill(0xFF222222, dp(12));
        tb.setStroke(dp(2), 0x88FFFFFF);
        thumb.setBackground(tb);
        thumb.setClipToOutline(true);
        thumb.setOnClickListener(v -> openLast());
        bar.addView(thumb, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START | Gravity.CENTER_VERTICAL));

        View sh = new View(this) {
            final Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float r = getWidth() / 2f;
                pa.setStyle(Paint.Style.FILL);
                pa.setColor(isPressed() ? 0xFFCCCCCC : Color.WHITE);
                c.drawCircle(r, r, r, pa);
                pa.setColor(Color.BLACK);
                pa.setStyle(Paint.Style.STROKE);
                pa.setStrokeWidth(dp(3));
                c.drawCircle(r, r, r - dp(6), pa);
            }
            @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
        };
        sh.setClickable(true);
        sh.setContentDescription("Chụp ảnh");
        sh.setOnClickListener(v -> shoot());
        bar.addView(sh, new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER));
        shutter = sh;

        TextView flip = smallBtn("⟲");
        flip.setTextSize(24);
        flip.setBackground(pill(0x33FFFFFF, dp(26)));
        flip.setOnClickListener(v -> { front = !front; auxId = null; bindCamera(1f); });
        bar.addView(flip, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END | Gravity.CENTER_VERTICAL));
        flipBtn = flip;
        root.addView(bar, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        updateFlashLabel();
        refreshLoc();
    }

    private void setRotation(int tr) {
        targetRot = tr;
        rot = tr == Surface.ROTATION_90 ? 90 : tr == Surface.ROTATION_270 ? 270 : 0;
        if (ic != null) ic.setTargetRotation(tr);
        ov.rot = rot;
        ov.invalidate();
        float r = rot == 90 ? 90 : rot == 270 ? -90 : 0;
        for (View v : new View[]{thumb, flipBtn, setBtn}) v.animate().rotation(r).setDuration(200).start();
        for (TextView t : chips) t.animate().rotation(r).setDuration(200).start();
    }

    private Stamp.Data currentData() { return snapshot(System.currentTimeMillis()); }

    private Stamp.Data snapshot(long t) {
        Stamp.Data d = new Stamp.Data();
        d.time = t;
        d.addr = loc.addr();
        d.note = sp.getString("note", "");
        d.comp = sp.getString("comp", "VAS ENERGY TECHNOLOGY");
        d.code = code;
        d.showCode = sp.getBoolean("showCode", true);
        d.showGps = sp.getBoolean("showGps", false);
        Location l = loc.last;
        if (l != null) { d.hasGps = true; d.lat = l.getLatitude(); d.lon = l.getLongitude(); }
        return d;
    }

    private void refreshLoc() {
        if (locTxt == null) return;
        String a = loc.addr();
        String s;
        if (!loc.status.isEmpty() && a.isEmpty()) s = loc.status;
        else if (!a.isEmpty()) s = "📍 " + a;
        else if (loc.last != null) s = String.format(Locale.US, "📍 %.5f, %.5f", loc.last.getLatitude(), loc.last.getLongitude());
        else s = "📍 Đang lấy vị trí… (bật GPS)";
        locTxt.setText(s);
    }

    @Override public void onLocUpdate() { refreshLoc(); if (ov != null) ov.invalidate(); }

    // ------------------------------------------------------------------ camera
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
            } catch (Exception e) { toast("Không mở được camera: " + e.getMessage()); return; }
            ListenableFuture<ExtensionsManager> ef = ExtensionsManager.getInstanceAsync(this, provider);
            ef.addListener(() -> {
                try { ext = ef.get(); } catch (Throwable t) { ext = null; }
                updateModeButton();
            }, ContextCompat.getMainExecutor(this));
            bindCamera(1f);
        }, ContextCompat.getMainExecutor(this));
    }

    private CameraSelector baseSelector() {
        if (front) return CameraSelector.DEFAULT_FRONT_CAMERA;
        if (auxId == null) return CameraSelector.DEFAULT_BACK_CAMERA;
        final String id = auxId;
        return new CameraSelector.Builder().addCameraFilter(infos -> {
            List<CameraInfo> out = new ArrayList<>();
            for (CameraInfo i : infos) if (id.equals(camId(i))) out.add(i);
            return out;
        }).build();
    }

    private static String camId(CameraInfo i) {
        try { return Camera2CameraInfo.from(i).getCameraId(); } catch (Throwable t) { return ""; }
    }

    private void bindCamera(float zoomAfter) {
        if (provider == null) return;
        if (camera != null) camera.getCameraInfo().getZoomState().removeObservers(this);
        provider.unbindAll();
        boolean wide = "169".equals(sp.getString("aspect", "43"));
        int ar = wide ? AspectRatio.RATIO_16_9 : AspectRatio.RATIO_4_3;
        ov.aspect = wide ? 9f / 16f : 3f / 4f;
        AspectRatioStrategy ars = new AspectRatioStrategy(ar, AspectRatioStrategy.FALLBACK_RULE_AUTO);
        ResolutionSelector prs = new ResolutionSelector.Builder().setAspectRatioStrategy(ars).build();
        ResolutionSelector crs = new ResolutionSelector.Builder().setAspectRatioStrategy(ars)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build();
        Preview preview = new Preview.Builder().setResolutionSelector(prs).build();
        int q = sp.getInt("quality", 0);
        ic = new ImageCapture.Builder()
                .setResolutionSelector(crs)
                .setCaptureMode(q == 0 ? ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY : ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode)
                .setJpegQuality(100)
                .setTargetRotation(targetRot)
                .build();
        CameraSelector base = baseSelector();
        CameraSelector sel = base;
        boolean extOn = false;
        try {
            if (extMode != ExtensionMode.NONE && ext != null && ext.isExtensionAvailable(base, extMode)) {
                sel = ext.getExtensionEnabledCameraSelector(base, extMode);
                extOn = true;
            }
        } catch (Throwable ignored) { }
        try {
            camera = provider.bindToLifecycle(this, sel, preview, ic);
        } catch (Throwable t) {
            try {
                if (extOn) { extMode = ExtensionMode.NONE; updateModeButton(); toast("Máy không hỗ trợ chế độ này, dùng chế độ thường"); }
                if (auxId != null && !extOn) auxId = null;
                camera = provider.bindToLifecycle(this, baseSelector(), preview, ic);
            } catch (Throwable t2) { toast("Không mở được camera: " + t2.getMessage()); return; }
        }
        preview.setSurfaceProvider(pv.getSurfaceProvider());
        ov.invalidate();
        if (zoomAfter != 1f) camera.getCameraControl().setZoomRatio(zoomAfter);
        camera.getCameraInfo().getZoomState().observe(this, z -> highlightZoom());
        flashBtn.setVisibility(camera.getCameraInfo().hasFlashUnit() ? View.VISIBLE : View.GONE);
        buildStops();
    }

    // Tiêu cự tương đương 35mm, để biết camera nào là góc rộng / tele
    private static float eqFocal(CameraInfo info) {
        try {
            Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
            float[] fl = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF sz = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (fl == null || fl.length == 0 || sz == null) return 0;
            float diag = (float) Math.hypot(sz.getWidth(), sz.getHeight());
            return fl[0] * 43.27f / diag;
        } catch (Throwable t) { return 0; }
    }

    private static float[] zoomRange(CameraInfo info) {
        try {
            Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
            if (Build.VERSION.SDK_INT >= 30) {
                Range<Float> r = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (r != null) return new float[]{r.getLower(), r.getUpper()};
            }
            Float m = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            return new float[]{1f, m == null ? 1f : m};
        } catch (Throwable t) { return new float[]{1f, 1f}; }
    }

    private static String fmt(float z) {
        if (Math.abs(z - Math.round(z)) < 0.05f) return Math.round(z) + "x";
        return String.format(Locale.US, "%.1fx", z);
    }

    private void buildStops() {
        stops.clear();
        if (front) {
            ZoomState z = camera.getCameraInfo().getZoomState().getValue();
            stops.add(new Stop("1x", null, 1f, 1f));
            if (z != null && z.getMaxZoomRatio() >= 2f) stops.add(new Stop("2x", null, 2f, 2f));
        } else {
            CameraInfo mainInfo = null;
            List<CameraInfo> backs = new ArrayList<>();
            try {
                List<CameraInfo> all = provider.getAvailableCameraInfos();
                List<CameraInfo> d = CameraSelector.DEFAULT_BACK_CAMERA.filter(all);
                if (!d.isEmpty()) mainInfo = d.get(0);
                for (CameraInfo i : all) if (i.getLensFacing() == CameraSelector.LENS_FACING_BACK) backs.add(i);
            } catch (Throwable ignored) { }
            if (mainInfo == null) mainInfo = camera.getCameraInfo();
            float[] mr = zoomRange(mainInfo);
            float mainEq = eqFocal(mainInfo);
            String mainId = camId(mainInfo);
            // Góc rộng
            if (mr[0] < 0.95f) {
                stops.add(new Stop(fmt(mr[0]), null, mr[0], mr[0]));
            } else if (mainEq > 0) {
                CameraInfo best = null; float bestR = 1f;
                for (CameraInfo i : backs) {
                    if (camId(i).equals(mainId)) continue;
                    float e = eqFocal(i);
                    if (e <= 0) continue;
                    float r = e / mainEq;
                    if (r < 0.9f && r < bestR) { bestR = r; best = i; }
                }
                if (best != null) {
                    float shown = Math.round(bestR * 10f) / 10f;
                    stops.add(new Stop(fmt(shown), camId(best), zoomRange(best)[0], shown));
                }
            }
            stops.add(new Stop("1x", null, 1f, 1f));
            int added = 0;
            for (float z : new float[]{2f, 3f, 5f, 10f}) {
                if (z <= mr[1] + 0.01f && added < 3) { stops.add(new Stop(fmt(z), null, z, z)); added++; }
            }
            // Tele riêng (nếu camera chính không zoom tới)
            if (mainEq > 0) for (CameraInfo i : backs) {
                if (camId(i).equals(mainId)) continue;
                float e = eqFocal(i);
                if (e <= 0) continue;
                float r = Math.round(e / mainEq * 10f) / 10f;
                if (r > 1.5f && r > mr[1] + 0.1f) stops.add(new Stop(fmt(r), camId(i), 1f, r));
            }
        }
        zoomRow.removeAllViews();
        chips.clear();
        for (final Stop s : stops) {
            TextView t = smallBtn(s.label);
            t.setMinWidth(dp(48));
            t.setBackground(pill(0x55000000, dp(22)));
            t.setOnClickListener(v -> goStop(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            lp.setMargins(dp(4), 0, dp(4), 0);
            zoomRow.addView(t, lp);
            chips.add(t);
        }
        float r = rot == 90 ? 90 : rot == 270 ? -90 : 0;
        for (TextView t : chips) t.setRotation(r);
        highlightZoom();
    }

    private void goStop(Stop s) {
        if (camera == null || front && s.cam != null) return;
        boolean needRebind = (s.cam == null && auxId != null) || (s.cam != null && !s.cam.equals(auxId));
        if (needRebind) { auxId = s.cam; bindCamera(s.zoom); }
        else camera.getCameraControl().setZoomRatio(s.zoom);
    }

    private boolean sameCam(Stop s) {
        return (s.cam == null && auxId == null) || (s.cam != null && s.cam.equals(auxId));
    }

    private float effZoom(Stop s, float cur) {
        return s.cam == null ? cur : s.shown * cur / Math.max(0.01f, s.zoom);
    }

    private void highlightZoom() {
        if (camera == null || chips.isEmpty()) return;
        ZoomState z = camera.getCameraInfo().getZoomState().getValue();
        float cur = z == null ? 1f : z.getZoomRatio();
        int best = -1;
        for (int i = 0; i < stops.size(); i++) {
            Stop s = stops.get(i);
            if (!sameCam(s)) continue;
            if (best == -1 || s.shown <= effZoom(s, cur) + 0.05f) best = i;
        }
        for (int i = 0; i < chips.size(); i++) {
            TextView t = chips.get(i);
            Stop s = stops.get(i);
            if (i == best) {
                t.setText(fmt(effZoom(s, cur)));
                t.setTextColor(0xFFFFD24A);
                t.setBackground(pill(0x99000000, dp(22)));
            } else {
                t.setText(s.label);
                t.setTextColor(Color.WHITE);
                t.setBackground(pill(0x55000000, dp(22)));
            }
        }
    }

    private void focusAt(float x, float y) {
        if (camera == null) return;
        try {
            MeteringPoint p = pv.getMeteringPointFactory().createPoint(x, y);
            camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(p).build());
            ov.showFocus(x, y);
        } catch (Throwable ignored) { }
    }

    private void cycleFlash() {
        flashMode = flashMode == ImageCapture.FLASH_MODE_OFF ? ImageCapture.FLASH_MODE_AUTO
                : flashMode == ImageCapture.FLASH_MODE_AUTO ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF;
        if (ic != null) ic.setFlashMode(flashMode);
        updateFlashLabel();
    }

    private void updateFlashLabel() {
        flashBtn.setText(flashMode == ImageCapture.FLASH_MODE_OFF ? "⚡ Tắt" : flashMode == ImageCapture.FLASH_MODE_AUTO ? "⚡ Tự động" : "⚡ Bật");
    }

    private List<Integer> availableModes() {
        List<Integer> m = new ArrayList<>();
        m.add(ExtensionMode.NONE);
        if (ext == null) return m;
        CameraSelector base = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            if (ext.isExtensionAvailable(base, ExtensionMode.HDR)) m.add(ExtensionMode.HDR);
            if (ext.isExtensionAvailable(base, ExtensionMode.NIGHT)) m.add(ExtensionMode.NIGHT);
            if (ext.isExtensionAvailable(base, ExtensionMode.AUTO)) m.add(ExtensionMode.AUTO);
        } catch (Throwable ignored) { }
        return m;
    }

    private void updateModeButton() {
        List<Integer> m = availableModes();
        modeBtn.setVisibility(m.size() > 1 ? View.VISIBLE : View.GONE);
        modeBtn.setText(extMode == ExtensionMode.HDR ? "HDR" : extMode == ExtensionMode.NIGHT ? "🌙 Ban đêm"
                : extMode == ExtensionMode.AUTO ? "Tự động" : "Thường");
    }

    private void cycleMode() {
        List<Integer> m = availableModes();
        int i = m.indexOf(extMode);
        extMode = m.get((i + 1) % m.size());
        auxId = null;
        updateModeButton();
        bindCamera(1f);
    }

    // ------------------------------------------------------------------ chụp
    private void shoot() {
        if (ic == null || busy) return;
        busy = true;
        final Stamp.Data d = snapshot(System.currentTimeMillis());
        final Location gps = loc.last;
        code = Stamp.newCode();
        ov.invalidate();
        flashView.setAlpha(0.8f);
        flashView.animate().alpha(0f).setDuration(250).start();
        if (snd != null && sp.getBoolean("sound", true)) try { snd.play(MediaActionSound.SHUTTER_CLICK); } catch (Throwable ignored) { }
        ic.takePicture(io, new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureSuccess(@NonNull ImageProxy img) {
                try {
                    int deg = img.getImageInfo().getRotationDegrees();
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] jpg = new byte[buf.remaining()];
                    buf.get(jpg);
                    img.close();
                    Bitmap src = decode(jpg, targetSize());
                    process(src, deg, false, d, gps);
                } catch (Throwable t) {
                    try { img.close(); } catch (Throwable ignored) { }
                    toast("Lỗi xử lý ảnh: " + t.getMessage());
                } finally { busy = false; }
            }
            @Override public void onError(@NonNull ImageCaptureException e) {
                busy = false;
                toast("Chụp lỗi: " + e.getMessage());
            }
        });
    }

    /** Cạnh dài tối đa theo cài đặt chất lượng (0 = giữ nguyên). */
    private int targetSize() {
        int q = sp.getInt("quality", 0);
        return q == 1 ? 3000 : q == 2 ? 2000 : 0;
    }

    private int jpegQ() {
        int q = sp.getInt("quality", 0);
        return q == 0 ? 95 : q == 1 ? 92 : 88;
    }

    private static Bitmap decode(byte[] jpg, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        int sample = 1;
        long px = (long) o.outWidth * o.outHeight;
        while (px / ((long) sample * sample) > 26_000_000L) sample *= 2;          // tránh tràn bộ nhớ với cảm biến 50–200MP
        if (maxSide > 0) while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
    }

    private void process(Bitmap src, int deg, boolean mirror, Stamp.Data d, Location gps) throws Exception {
        if (src == null) throw new Exception("không đọc được ảnh");
        int max = targetSize();
        boolean swap = deg == 90 || deg == 270;
        float w0 = swap ? src.getHeight() : src.getWidth();
        float h0 = swap ? src.getWidth() : src.getHeight();
        float k = (max > 0 && Math.max(w0, h0) > max) ? max / Math.max(w0, h0) : 1f;
        int W = Math.round(w0 * k), H = Math.round(h0 * k);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Matrix m = new Matrix();
        m.postTranslate(-src.getWidth() / 2f, -src.getHeight() / 2f);
        if (mirror) m.postScale(-1, 1);
        m.postRotate(deg);
        m.postScale(k, k);
        m.postTranslate(W / 2f, H / 2f);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        src.recycle();
        Stamp.draw(c, W, H, d);

        File tmp = new File(getCacheDir(), "shot.jpg");
        try (FileOutputStream fo = new FileOutputStream(tmp)) { out.compress(Bitmap.CompressFormat.JPEG, jpegQ(), fo); }
        final Bitmap small = Bitmap.createScaledBitmap(out, Math.max(1, W / 8), Math.max(1, H / 8), true);
        out.recycle();
        writeExif(tmp, d.time, gps);
        String name = "VAS_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(d.time)) + ".jpg";
        Uri u = saveToGallery(tmp, name, d.time);
        tmp.delete();
        main.post(() -> {
            if (u != null) { lastUri = u; thumb.setImageBitmap(small); toast("Đã lưu vào Bộ sưu tập"); }
        });
    }

    private void writeExif(File f, long time, Location gps) {
        try {
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            String t = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date(time));
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, t);
            ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, t);
            ex.setAttribute(ExifInterface.TAG_DATETIME, t);
            ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
            ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "VAS Camera");
            ex.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            if (gps != null) ex.setGpsInfo(gps);
            ex.saveAttributes();
        } catch (Throwable ignored) { }
    }

    private Uri saveToGallery(File f, String name, long time) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            v.put(MediaStore.Images.Media.DATE_TAKEN, time);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("không tạo được ảnh trong Bộ sưu tập");
            try (OutputStream os = cr.openOutputStream(uri); InputStream is = new FileInputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = is.read(b)) > 0) os.write(b, 0, n);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
            dir.mkdirs();
            File dst = new File(dir, name);
            try (InputStream is = new FileInputStream(f); OutputStream os = new FileOutputStream(dst)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = is.read(b)) > 0) os.write(b, 0, n);
            }
            MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return Uri.fromFile(dst);
        }
    }

    private void openLast() {
        if (lastUri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, "image/jpeg");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) { toast("Mở Bộ sưu tập để xem ảnh"); }
    }

    /** Gắn khung cho ảnh có sẵn: giờ lấy từ EXIF của ảnh nếu có. */
    private void stampExisting(Uri uri) {
        final Location gps = loc.last;
        io.execute(() -> {
            try {
                byte[] data;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                    data = bo.toByteArray();
                }
                long time = System.currentTimeMillis();
                int deg = 0;
                try {
                    ExifInterface ex = new ExifInterface(new java.io.ByteArrayInputStream(data));
                    deg = ex.getRotationDegrees();
                    String dt = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                    if (dt == null) dt = ex.getAttribute(ExifInterface.TAG_DATETIME);
                    if (dt != null) {
                        Date pd = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dt);
                        if (pd != null) time = pd.getTime();
                    }
                } catch (Throwable ignored) { }
                Stamp.Data d = snapshot(time);
                code = Stamp.newCode();
                process(decode(data, targetSize()), deg, false, d, gps);
            } catch (Throwable t) { toast("Không gắn khung được: " + t.getMessage()); }
        });
    }

    // ------------------------------------------------------------------ cài đặt
    private void openSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), dp(8));
        scroll.addView(f);

        f.addView(label("Địa chỉ hiển thị"));
        EditText addr = edit(loc.addr(), "Tự động theo GPS");
        f.addView(addr);
        Button auto = new Button(this);
        auto.setText("📍 Lấy địa chỉ tự động");
        f.addView(auto);
        TextView info = label(loc.last != null ? String.format(Locale.US, "Toạ độ %.5f, %.5f (±%dm)", loc.last.getLatitude(), loc.last.getLongitude(), Math.round(loc.last.getAccuracy())) : "Chưa có GPS");
        f.addView(info);

        f.addView(label("Ghi chú (không bắt buộc)"));
        EditText note = edit(sp.getString("note", ""), "VD: Đo huyết áp đầu ca");
        f.addView(note);
        f.addView(label("Chữ dưới logo"));
        EditText comp = edit(sp.getString("comp", "VAS ENERGY TECHNOLOGY"), "");
        f.addView(comp);

        CheckBox cCode = check("Hiện mã xác thực dọc mép phải", sp.getBoolean("showCode", true));
        CheckBox cGps = check("Hiện toạ độ GPS", sp.getBoolean("showGps", false));
        CheckBox cSnd = check("Âm thanh khi chụp", sp.getBoolean("sound", true));
        f.addView(cCode); f.addView(cGps); f.addView(cSnd);

        f.addView(label("Chất lượng ảnh"));
        RadioGroup gq = new RadioGroup(this);
        String[] ql = {"Cao nhất (độ phân giải gốc của camera)", "Cao (cạnh dài 3000px)", "Nhẹ – gửi Zalo nhanh (2000px)"};
        for (int i = 0; i < 3; i++) { RadioButton r = new RadioButton(this); r.setText(ql[i]); r.setId(100 + i); gq.addView(r); }
        gq.check(100 + sp.getInt("quality", 0));
        f.addView(gq);

        f.addView(label("Khung hình"));
        RadioGroup ga = new RadioGroup(this);
        RadioButton a43 = new RadioButton(this); a43.setText("4:3 (như camera gốc)"); a43.setId(200);
        RadioButton a169 = new RadioButton(this); a169.setText("16:9"); a169.setId(201);
        ga.addView(a43); ga.addView(a169);
        ga.check("169".equals(sp.getString("aspect", "43")) ? 201 : 200);
        f.addView(ga);

        Button pick = new Button(this);
        pick.setText("Gắn khung cho ảnh có sẵn");
        f.addView(pick);
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) { }
        f.addView(label("Phiên bản " + ver));

        final String oldAddr = loc.addr();
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Cài đặt khung ảnh")
                .setView(scroll)
                .setNegativeButton("Huỷ", null)
                .setPositiveButton("Lưu", (di, w) -> {
                    String na = addr.getText().toString().trim();
                    SharedPreferences.Editor e = sp.edit();
                    if (!na.equals(oldAddr)) e.putString("addr", na).putBoolean("manual", !na.isEmpty());
                    int q = gq.getCheckedRadioButtonId() - 100;
                    String asp = ga.getCheckedRadioButtonId() == 201 ? "169" : "43";
                    boolean rebind = q != sp.getInt("quality", 0) || !asp.equals(sp.getString("aspect", "43"));
                    e.putString("note", note.getText().toString())
                            .putString("comp", comp.getText().toString())
                            .putBoolean("showCode", cCode.isChecked())
                            .putBoolean("showGps", cGps.isChecked())
                            .putBoolean("sound", cSnd.isChecked())
                            .putInt("quality", Math.max(0, q))
                            .putString("aspect", asp)
                            .apply();
                    if (na.isEmpty()) loc.geocode(true);
                    refreshLoc();
                    ov.invalidate();
                    if (rebind) bindCamera(1f);
                })
                .create();
        auto.setOnClickListener(v -> {
            sp.edit().putBoolean("manual", false).apply();
            if (loc.last == null) { toast("Đang chờ GPS…"); return; }
            loc.geocode(true);
            toast("Đang tra địa chỉ…");
            dlg.dismiss();
        });
        pick.setOnClickListener(v -> { dlg.dismiss(); pickLauncher.launch("image/*"); });
        dlg.show();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private EditText edit(String v, String hint) {
        EditText e = new EditText(this);
        e.setText(v);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    private CheckBox check(String s, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setChecked(v);
        return c;
    }

    // ------------------------------------------------------------------ cập nhật
    @SuppressWarnings("deprecation")
    private void checkUpdate() {
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/nhsntra-sys/vas-camera/releases/latest").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                String body;
                try (InputStream is = c.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                    body = bo.toString("UTF-8");
                }
                String tag = new JSONObject(body).optString("tag_name", "");
                int remote = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
                int mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                if (remote > mine) main.post(() -> updBtn.setVisibility(View.VISIBLE));
            } catch (Throwable ignored) { }
        });
    }
}
