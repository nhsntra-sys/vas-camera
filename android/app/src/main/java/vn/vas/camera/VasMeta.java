package vn.vas.camera;

import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dữ liệu gắn ở cuối file JPEG (sau ảnh): thông tin khung + bản sạch của các vùng khung đè lên.
 * Cấu trúc: [JPEG][json dài 4 byte][json][ảnh cắt 1][ảnh cắt 2]...[độ dài phần thêm 8 byte]["VASCAM01"]
 */
public final class VasMeta {
    private static final byte[] MAGIC = "VASCAM01".getBytes(StandardCharsets.US_ASCII);

    public Stamp.Data data;
    public final List<Rect> rects = new ArrayList<>();
    public final List<byte[]> crops = new ArrayList<>();
    public int width, height;
    public int imageEnd;   // số byte của phần ảnh JPEG (không tính phần thêm)

    public byte[] encode() throws Exception {
        JSONObject j = new JSONObject();
        j.put("v", 1);
        j.put("w", width);
        j.put("h", height);
        j.put("data", data.toJson());
        JSONArray arr = new JSONArray();
        for (int i = 0; i < rects.size(); i++) {
            Rect r = rects.get(i);
            JSONObject o = new JSONObject();
            o.put("x", r.left); o.put("y", r.top); o.put("w", r.width()); o.put("h", r.height());
            o.put("len", crops.get(i).length);
            arr.put(o);
        }
        j.put("crops", arr);
        byte[] js = j.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(ByteBuffer.allocate(4).putInt(js.length).array());
        bo.write(js);
        for (byte[] c : crops) bo.write(c);
        int payload = bo.size();
        bo.write(ByteBuffer.allocate(8).putLong(payload).array());
        bo.write(MAGIC);
        return bo.toByteArray();
    }

    /** Trả về null nếu file không có dữ liệu khung VAS. */
    public static VasMeta parse(byte[] file) {
        try {
            int n = file.length;
            if (n < 16) return null;
            if (!Arrays.equals(Arrays.copyOfRange(file, n - 8, n), MAGIC)) return null;
            long payload = ByteBuffer.wrap(file, n - 16, 8).getLong();
            if (payload <= 4 || payload > n - 16) return null;
            int start = (int) (n - 16 - payload);
            int jl = ByteBuffer.wrap(file, start, 4).getInt();
            if (jl <= 0 || jl > payload - 4) return null;
            JSONObject j = new JSONObject(new String(file, start + 4, jl, StandardCharsets.UTF_8));
            VasMeta m = new VasMeta();
            m.imageEnd = start;
            m.width = j.optInt("w");
            m.height = j.optInt("h");
            m.data = Stamp.Data.fromJson(j.getJSONObject("data"));
            JSONArray arr = j.optJSONArray("crops");
            int off = start + 4 + jl;
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int len = o.getInt("len");
                if (off + len > n - 16) return null;
                m.rects.add(new Rect(o.getInt("x"), o.getInt("y"), o.getInt("x") + o.getInt("w"), o.getInt("y") + o.getInt("h")));
                m.crops.add(Arrays.copyOfRange(file, off, off + len));
                off += len;
            }
            return m;
        } catch (Throwable t) {
            return null;
        }
    }
}
