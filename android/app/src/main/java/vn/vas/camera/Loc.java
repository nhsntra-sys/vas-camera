package vn.vas.camera;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lấy GPS và đổi toạ độ ra địa chỉ (Geocoder của máy → Photon → Nominatim). */
public final class Loc implements LocationListener {
    public interface Listener { void onLocUpdate(); }

    private final Context ctx;
    private final SharedPreferences sp;
    private final LocationManager lm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private Location geoAt;
    private boolean busy, running;
    public Location last;
    public String status = "";
    public Listener listener;

    public Loc(Context c, SharedPreferences sp) {
        this.ctx = c.getApplicationContext();
        this.sp = sp;
        this.lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public String addr() { return sp.getString("addr", ""); }
    public boolean manual() { return sp.getBoolean("manual", false); }

    private boolean hasPerm() {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public void start() {
        if (running || lm == null || !hasPerm()) return;
        running = true;
        try {
            for (String pr : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.getAllProviders().contains(pr)) continue;
                Location l = lm.getLastKnownLocation(pr);
                if (l != null && (last == null || l.getTime() > last.getTime())) last = l;
                lm.requestLocationUpdates(pr, 3000, 5, this, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException ignored) { }
        if (last != null) { maybeGeocode(); notifyL(); }
    }

    public void stop() {
        running = false;
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception ignored) { }
    }

    @Override public void onLocationChanged(Location l) {
        boolean better = last == null
                || l.getTime() - last.getTime() > 20000
                || l.getAccuracy() <= last.getAccuracy() + 5
                || LocationManager.GPS_PROVIDER.equals(l.getProvider());
        if (better) last = l;
        maybeGeocode();
        notifyL();
    }
    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    private void maybeGeocode() {
        if (last == null) return;
        if (geoAt == null || geoAt.distanceTo(last) > 120 || (addr().isEmpty() && !busy)) geocode(false);
    }

    private void notifyL() { if (listener != null) listener.onLocUpdate(); }

    public void geocode(boolean force) {
        if (last == null || busy) return;
        if (manual() && !force) return;
        busy = true;
        geoAt = last;
        final double la = last.getLatitude(), lo = last.getLongitude();
        status = "Đang tra địa chỉ…";
        notifyL();
        ex.execute(() -> {
            String r = null;
            try { r = fromGeocoder(la, lo); } catch (Throwable ignored) { }
            if (r == null) try { r = fromPhoton(la, lo); } catch (Throwable ignored) { }
            if (r == null) try { r = fromNominatim(la, lo); } catch (Throwable ignored) { }
            final String res = r;
            main.post(() -> {
                busy = false;
                if (res != null && !res.isEmpty()) {
                    if (force || !manual()) sp.edit().putString("addr", res).putBoolean("manual", false).apply();
                    status = "";
                } else {
                    status = "Không tra được địa chỉ – kiểm tra mạng hoặc gõ tay";
                }
                notifyL();
            });
        });
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        String[] pre = {"Thành phố ", "Thành Phố ", "Tỉnh ", "Quận ", "Huyện ", "Thị xã ", "Thị trấn ", "TP. ", "TP "};
        for (String p : pre) {
            if (s.startsWith(p)) {
                String rest = s.substring(p.length()).trim();
                if (!rest.matches("\\d+")) return rest;
            }
        }
        return s;
    }

    private static String join(List<String> parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            p = p.trim();
            if (p.isEmpty() || out.contains(p)) continue;
            out.add(p);
        }
        return android.text.TextUtils.join(", ", out);
    }

    @SuppressWarnings("deprecation")
    private String fromGeocoder(double la, double lo) throws Exception {
        if (!Geocoder.isPresent()) return null;
        List<Address> list = new Geocoder(ctx, new Locale("vi", "VN")).getFromLocation(la, lo, 1);
        if (list == null || list.isEmpty()) return null;
        Address a = list.get(0);
        String line = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : null;
        if (line != null && !line.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String p : line.split(",")) {
                p = p.trim();
                if (p.isEmpty() || p.matches("\\d{4,6}") || p.equalsIgnoreCase("Việt Nam") || p.equalsIgnoreCase("Vietnam")) continue;
                parts.add(clean(p));
            }
            if (parts.size() > 3) parts.remove(1); // bỏ phường cho gọn giống Timemark
            String r = join(parts);
            if (r.contains(",")) return r;
        }
        List<String> parts = new ArrayList<>();
        String street = join(listOf(a.getSubThoroughfare(), a.getThoroughfare())).replace(", ", " ");
        parts.add(street);
        parts.add(clean(a.getSubAdminArea() != null ? a.getSubAdminArea() : a.getLocality()));
        parts.add(clean(a.getAdminArea()));
        String r = join(parts);
        return r.contains(",") ? r : null;
    }

    private static List<String> listOf(String... s) {
        List<String> l = new ArrayList<>();
        for (String x : s) if (x != null) l.add(x);
        return l;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "VASCamera/1.0 (Android)");
        c.setRequestProperty("Accept-Language", "vi");
        try (InputStream is = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static String fromPhoton(double la, double lo) throws Exception {
        JSONObject j = new JSONObject(get("https://photon.komoot.io/reverse?lat=" + la + "&lon=" + lo));
        JSONArray f = j.optJSONArray("features");
        if (f == null || f.length() == 0) return null;
        JSONObject p = f.getJSONObject(0).optJSONObject("properties");
        if (p == null) return null;
        String street = join(listOf(p.optString("housenumber", null), p.optString("street", null))).replace(", ", " ");
        String r = join(listOf(street, clean(p.optString("district", p.optString("locality", p.optString("county", ""))) ),
                clean(p.optString("city", p.optString("state", "")))));
        return r.contains(",") ? r : null;
    }

    private static String fromNominatim(double la, double lo) throws Exception {
        JSONObject j = new JSONObject(get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&accept-language=vi&lat=" + la + "&lon=" + lo));
        JSONObject a = j.optJSONObject("address");
        if (a == null) return null;
        String street = join(listOf(a.optString("house_number", null), a.optString("road", null))).replace(", ", " ");
        String dist = a.optString("city_district", a.optString("suburb", a.optString("quarter", a.optString("county", ""))));
        String r = join(listOf(street, clean(dist), clean(a.optString("city", a.optString("state", "")))));
        return r.contains(",") ? r : null;
    }
}
