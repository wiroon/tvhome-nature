package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Current weather plus the next hours of the day, drawn by hand in one view.
 *
 * Data: Open-Meteo (https://open-meteo.com, no key; data CC BY 4.0). One request when the view becomes
 * visible and the cached answer is older than REFRESH_MS, then a check every CHECK_MS while Home is on screen.
 * Nothing runs while Home is hidden. The last answer is cached so an offline start still shows something.
 * Location, place name, time zone and hour count come from config.xml (weather_*).
 */
public class WeatherStrip extends View {
    private static final long REFRESH_MS = 30L * 60_000L;
    private static final long CHECK_MS = 5L * 60_000L;
    private static final String PREFS = "weather_cache";

    private final float dp;
    private final float sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path path2 = new Path();
    private final RectF rect = new RectF();
    private Typeface light;
    private Typeface regular;
    private Typeface medium;

    private final double lat;
    private final double lon;
    private final String place;
    private final String tz;
    private final int hours;

    // Parsed answer.
    private long fetchedAt;
    private double nowTemp = Double.NaN;
    private int nowCode = -1;
    private boolean nowDay = true;
    private String[] hTime = new String[0];
    private double[] hTemp = new double[0];
    private int[] hPop = new int[0];
    private int[] hCode = new int[0];
    private boolean[] hDay = new boolean[0];
    private int firstHour;

    private boolean fetching;
    private final Runnable check = new Runnable() {
        @Override public void run() {
            maybeFetch();
            ui.postDelayed(this, CHECK_MS);
        }
    };

    public WeatherStrip(Context c) { this(c, null); }

    public WeatherStrip(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        sp = c.getResources().getDisplayMetrics().scaledDensity;
        lat = Double.parseDouble(c.getString(R.string.weather_lat));
        lon = Double.parseDouble(c.getString(R.string.weather_lon));
        place = c.getString(R.string.weather_place);
        tz = c.getString(R.string.weather_tz);
        hours = Math.max(3, Math.min(10, Integer.parseInt(c.getString(R.string.weather_hours))));
        try {
            light = c.getResources().getFont(R.font.plex_light);
            regular = c.getResources().getFont(R.font.plex_regular);
            medium = c.getResources().getFont(R.font.plex_medium);
        } catch (Exception e) {
            light = regular = medium = Typeface.DEFAULT;
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        loadCache();
    }

    // ---- lifecycle: poll only while visible ---------------------------------------------------------------

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getWindowVisibility() == VISIBLE) start();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (v == VISIBLE) start(); else stop();
    }

    private void start() {
        ui.removeCallbacks(check);
        check.run();
    }

    private void stop() {
        ui.removeCallbacks(check);
    }

    // ---- data ---------------------------------------------------------------------------------------------

    private void loadCache() {
        SharedPreferences sp0 = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp0.getString("json", null);
        fetchedAt = sp0.getLong("at", 0);
        if (json != null) {
            try { parse(json); } catch (Exception ignored) { fetchedAt = 0; }
        }
    }

    private void maybeFetch() {
        if (fetching) return;
        if (System.currentTimeMillis() - fetchedAt < REFRESH_MS) {
            locateFirstHour();
            invalidate();
            return;
        }
        fetching = true;
        new Thread(() -> {
            String body = null;
            try {
                // Coordinates are rounded in the config to about 1 km; no account, no key, no identifiers sent.
                String u = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&hourly=temperature_2m,precipitation_probability,weather_code,is_day"
                        + "&timezone=" + tz.replace("/", "%2F") + "&forecast_days=2";
                HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
                h.setConnectTimeout(10_000);
                h.setReadTimeout(10_000);
                if (h.getResponseCode() == 200) {
                    try (InputStream in = h.getInputStream()) {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                        body = bo.toString("UTF-8");
                    }
                }
                h.disconnect();
            } catch (Exception ignored) {
            }
            final String result = body;
            ui.post(() -> {
                fetching = false;
                if (result == null) return;
                try {
                    parse(result);
                    fetchedAt = System.currentTimeMillis();
                    getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("json", result).putLong("at", fetchedAt).apply();
                    invalidate();
                } catch (Exception ignored) {
                }
            });
        }, "weather").start();
    }

    private void parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONObject cur = o.getJSONObject("current");
        nowTemp = cur.getDouble("temperature_2m");
        nowCode = cur.getInt("weather_code");
        nowDay = cur.optInt("is_day", 1) == 1;
        JSONObject hr = o.getJSONObject("hourly");
        JSONArray t = hr.getJSONArray("time");
        JSONArray temp = hr.getJSONArray("temperature_2m");
        JSONArray pop = hr.getJSONArray("precipitation_probability");
        JSONArray code = hr.getJSONArray("weather_code");
        JSONArray day = hr.getJSONArray("is_day");
        int n = t.length();
        hTime = new String[n];
        hTemp = new double[n];
        hPop = new int[n];
        hCode = new int[n];
        hDay = new boolean[n];
        for (int i = 0; i < n; i++) {
            hTime[i] = t.getString(i);
            hTemp[i] = temp.optDouble(i, Double.NaN);
            hPop[i] = pop.optInt(i, 0);
            hCode[i] = code.optInt(i, 0);
            hDay[i] = day.optInt(i, 1) == 1;
        }
        locateFirstHour();
    }

    private void locateFirstHour() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH':00'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone(tz));
        String now = f.format(new Date());
        firstHour = 0;
        for (int i = 0; i < hTime.length; i++) {
            if (hTime[i].compareTo(now) >= 0) { firstHour = i; return; }
        }
        firstHour = Math.max(0, hTime.length - hours);
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private float leftW() { return 140 * dp; }
    private float colW() { return 60 * dp; }
    private float h() { return 92 * dp; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension((int) ((leftW() + colW() * hours + 14 * dp) * 0.8f), (int) (h() * 0.8f));
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    /** The whole view is drawn at its normal size, then scaled down by 0.8: more of the wallpaper shows. */
    private static final float K = 0.8f;

    private float lw() { return getWidth() / K; }

    private float lh() { return getHeight() / K; }

    @Override protected void onDraw(Canvas c) {
        c.save();
        c.scale(K, K);
        drawScaled(c);
        c.restore();
    }

    private void drawScaled(Canvas c) {
        float w = lw(), hh = lh();
        // Panel
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x4D000000);
        rect.set(0, 0, w, hh);
        c.drawRoundRect(rect, 22 * dp, 22 * dp, fill);
        stroke.setStrokeWidth(1 * dp);
        stroke.setColor(0x26FFFFFF);
        rect.set(0.5f * dp, 0.5f * dp, w - 0.5f * dp, hh - 0.5f * dp);
        c.drawRoundRect(rect, 22 * dp, 22 * dp, stroke);

        if (hTime.length == 0 || Double.isNaN(nowTemp)) {
            label(c, place + Tx.t("  กำลังโหลดพยากรณ์อากาศ...", "  loading the forecast..."), 18 * dp, hh / 2f + 6 * dp, 17, regular, 0xDEFFFFFF, Paint.Align.LEFT);
            return;
        }

        // Left: place, big temperature, icon, condition
        label(c, place, 18 * dp, 25 * dp, 14, medium, 0xCCFFFFFF, Paint.Align.LEFT);
        label(c, Math.round(nowTemp) + "°", 16 * dp, 63 * dp, 40, light, 0xFFFFFFFF, Paint.Align.LEFT);
        icon(c, nowCode, nowDay, 104 * dp, 48 * dp, 40 * dp);
        label(c, describe(nowCode), 18 * dp, 83 * dp, 14, regular, 0xDEFFFFFF, Paint.Align.LEFT);

        // Divider
        fill.setColor(0x33FFFFFF);
        c.drawRect(leftW() - 4 * dp, 14 * dp, leftW() - 3 * dp, hh - 14 * dp, fill);

        // Hourly columns
        for (int k = 0; k < hours; k++) {
            int i = firstHour + k;
            if (i >= hTime.length) break;
            float cx = leftW() + colW() * k + colW() / 2f;
            String hour = hTime[i].substring(11, 16);
            String tag = k == 0 ? Tx.t("ตอนนี้", "Now") : hour;
            label(c, tag, cx, 24 * dp, 13, k == 0 ? medium : regular, k == 0 ? 0xFFFFFFFF : 0xDEFFFFFF, Paint.Align.CENTER);
            icon(c, hCode[i], hDay[i], cx, 43 * dp, 32 * dp);
            label(c, Math.round(hTemp[i]) + "°", cx, 68 * dp, 18, medium, 0xFFFFFFFF, Paint.Align.CENTER);
            if (hPop[i] >= 20) {
                label(c, hPop[i] + "%", cx, 85 * dp, 12, regular, 0xFF7CC4FF, Paint.Align.CENTER);
            }
        }
    }

    private void label(Canvas c, String s, float x, float y, float sizeSp, Typeface tf, int color, Paint.Align al) {
        text.setShadowLayer(2.5f * dp, 0, 1f * dp, 0xB3000000);
        color = ((int) ((color >>> 24) * 0.9f) << 24) | (color & 0x00FFFFFF);   // text a little see-through, not faint
        text.setTypeface(tf);
        text.setTextSize(sizeSp * sp);
        text.setColor(color);
        text.setTextAlign(al);
        c.drawText(s, x, y, text);
    }

    private static String describe(int code) {
        if (code == 0) return Tx.t("ฟ้าโปร่ง", "Sunny");
        if (code == 1) return Tx.t("ท้องฟ้าแจ่มใส", "Clear sky");
        if (code == 2) return Tx.t("มีเมฆบางส่วน", "Partly cloudy");
        if (code == 3) return Tx.t("เมฆมาก", "Cloudy");
        if (code == 45 || code == 48) return Tx.t("มีหมอก", "Fog");
        if (code >= 51 && code <= 57) return Tx.t("ฝนปรอย", "Drizzle");
        if (code >= 61 && code <= 67) return Tx.t("ฝนตก", "Rain");
        if (code >= 71 && code <= 77) return Tx.t("หิมะ", "Snow");
        if (code >= 80 && code <= 82) return Tx.t("ฝนตกเป็นช่วง", "Showers");
        if (code >= 95) return Tx.t("พายุฟ้าคะนอง", "Thunderstorm");
        return "";
    }

    // ---- icons: macOS Weather style, drawn in a 100x100 box (no image files) -----------------------------

    private final Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cloudPath = new Path();

    private void icon(Canvas c, int code, boolean day, float cx, float cy, float size) {
        float u = size / 100f, ox = cx - size / 2f, oy = cy - size / 2f;
        if (code == 0 || code == 1) {
            if (day) sun(c, ox + 50 * u, oy + 50 * u, u, 1f); else moon(c, ox + 50 * u, oy + 50 * u, u, 1f);
        } else if (code == 2) {
            if (day) sun(c, ox + 36 * u, oy + 36 * u, u, 0.8f); else moon(c, ox + 36 * u, oy + 36 * u, u, 0.8f);
            cloud(c, ox + 8 * u, oy + 8 * u, u * 0.86f, 0xFFFFFFFF, 0xFFDCE3EE);
        } else if (code == 3) {
            cloud(c, ox, oy + 4 * u, u, 0xFFE9EDF4, 0xFFB9C3D3);
        } else if (code == 45 || code == 48) {
            cloud(c, ox, oy - 4 * u, u, 0xFFE9EDF4, 0xFFC5CDDA);
            ip.setStyle(Paint.Style.STROKE);
            ip.setStrokeCap(Paint.Cap.ROUND);
            ip.setStrokeWidth(4.5f * u);
            ip.setColor(0xFFC7CEDA);
            ip.setShader(null);
            c.drawLine(ox + 20 * u, oy + 76 * u, ox + 80 * u, oy + 76 * u, ip);
            c.drawLine(ox + 28 * u, oy + 85 * u, ox + 76 * u, oy + 85 * u, ip);
            c.drawLine(ox + 24 * u, oy + 94 * u, ox + 78 * u, oy + 94 * u, ip);
        } else if (code >= 95) {
            cloud(c, ox, oy - 8 * u, u, 0xFFC9D0DC, 0xFF8793A8);
            bolt(c, ox, oy, u);
        } else if (code >= 71 && code <= 77) {
            cloud(c, ox, oy - 8 * u, u, 0xFFE9EDF4, 0xFFB9C3D3);
            ip.setStyle(Paint.Style.FILL);
            ip.setShader(null);
            ip.setColor(0xFFFFFFFF);
            ip.setShadowLayer(2 * u, 0, 1 * u, 0x66000000);
            for (int i = 0; i < 3; i++) c.drawCircle(ox + (36 + 14 * i) * u, oy + (78 + (i == 1 ? 8 : 0)) * u, 4.2f * u, ip);
            ip.clearShadowLayer();
        } else {
            cloud(c, ox, oy - 8 * u, u, 0xFFDDE3EE, 0xFF9EAABD);
            ip.setStyle(Paint.Style.STROKE);
            ip.setStrokeCap(Paint.Cap.ROUND);
            ip.setStrokeWidth(5f * u);
            ip.setShader(null);
            for (int i = 0; i < 3; i++) {
                float x = ox + (34 + 16 * i) * u, y0 = oy + (70 + (i == 1 ? 6 : 0)) * u;
                ip.setShader(new LinearGradient(x, y0, x - 5 * u, y0 + 15 * u, 0xFF6DD3FF, 0xFF1E88E5, Shader.TileMode.CLAMP));
                c.drawLine(x, y0, x - 5 * u, y0 + 14 * u, ip);
            }
            ip.setShader(null);
        }
    }

    private void cloud(Canvas c, float ox, float oy, float u, int top, int bottom) {
        cloudPath.reset();
        cloudPath.addCircle(ox + 32 * u, oy + 54 * u, 14 * u, Path.Direction.CW);
        cloudPath.addCircle(ox + 50 * u, oy + 44 * u, 20 * u, Path.Direction.CW);
        cloudPath.addCircle(ox + 68 * u, oy + 52 * u, 15 * u, Path.Direction.CW);
        rect.set(ox + 14 * u, oy + 52 * u, ox + 86 * u, oy + 72 * u);
        cloudPath.addRoundRect(rect, 10 * u, 10 * u, Path.Direction.CW);
        ip.setStyle(Paint.Style.FILL);
        ip.setShader(new LinearGradient(0, oy + 24 * u, 0, oy + 72 * u, top, bottom, Shader.TileMode.CLAMP));
        ip.setShadowLayer(4 * u, 0, 3 * u, 0x40000000);
        c.drawPath(cloudPath, ip);
        ip.clearShadowLayer();
        ip.setShader(null);
    }

    private void sun(Canvas c, float cx, float cy, float u, float k) {
        ip.setStyle(Paint.Style.FILL);
        ip.setShader(new RadialGradient(cx, cy, 36 * u * k, 0x55FFD60A, 0x00FFD60A, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, 36 * u * k, ip);
        ip.setShader(new RadialGradient(cx - 5 * u * k, cy - 6 * u * k, 26 * u * k, 0xFFFFE680, 0xFFFFB300, Shader.TileMode.CLAMP));
        ip.setShadowLayer(4 * u, 0, 2 * u, 0x40000000);
        c.drawCircle(cx, cy, 22 * u * k, ip);
        ip.clearShadowLayer();
        ip.setShader(null);
    }

    private void moon(Canvas c, float cx, float cy, float u, float k) {
        path.reset();
        path.addCircle(cx, cy, 24 * u * k, Path.Direction.CW);
        path2.reset();
        path2.addCircle(cx + 11 * u * k, cy - 7 * u * k, 20 * u * k, Path.Direction.CW);
        path.op(path2, Path.Op.DIFFERENCE);
        ip.setStyle(Paint.Style.FILL);
        ip.setShader(new LinearGradient(cx - 20 * u, cy - 20 * u, cx + 10 * u, cy + 24 * u, 0xFFFFF6D6, 0xFFD6CEA0, Shader.TileMode.CLAMP));
        ip.setShadowLayer(4 * u, 0, 2 * u, 0x40000000);
        c.drawPath(path, ip);
        ip.clearShadowLayer();
        ip.setShader(null);
    }

    private void bolt(Canvas c, float ox, float oy, float u) {
        path.reset();
        path.moveTo(ox + 54 * u, oy + 56 * u);
        path.lineTo(ox + 40 * u, oy + 78 * u);
        path.lineTo(ox + 50 * u, oy + 78 * u);
        path.lineTo(ox + 45 * u, oy + 96 * u);
        path.lineTo(ox + 64 * u, oy + 72 * u);
        path.lineTo(ox + 54 * u, oy + 72 * u);
        path.close();
        ip.setShader(new LinearGradient(0, oy + 56 * u, 0, oy + 96 * u, 0xFFFFE066, 0xFFFF9F0A, Shader.TileMode.CLAMP));
        ip.setStyle(Paint.Style.FILL);
        c.drawPath(path, ip);
        ip.setShader(null);
    }
}
