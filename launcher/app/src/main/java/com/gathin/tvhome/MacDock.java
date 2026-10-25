package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.animation.ValueAnimator;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.ArrayList;
import java.util.List;

/**
 * A macOS-style Dock along the bottom of Home. D-pad Left/Right moves the selection; the selected icon and its
 * neighbours grow with a cosine falloff (macOS magnification) and the label floats above it. OK bounces the icon
 * and starts the app. The last icon opens the all-apps overlay.
 */
public class MacDock extends View {
    /** Called for the all-apps icon. */
    public interface Host {
        void onAllApps();
        void onWallpaper();
        void onDockMenu();
    }

    private static final class Item {
        final String label;
        String pkg;               // set for apps
        final Drawable icon;      // null for the all-apps glyph
        final Intent intent;      // null for the all-apps glyph
        boolean wallpaper;        // the wallpaper manager entry
        android.graphics.Bitmap bmp;   // icon pre-rendered once (rounded, glossy), drawn scaled each frame
        Item(String label, Drawable icon, Intent intent) {
            this.label = label;
            this.icon = icon;
            this.intent = intent;
        }
    }

    private final float dp;
    private final float sp;
    private final List<Item> items = new ArrayList<>();
    private Host host;

    private final float base;       // icon size at rest, px
    private final float gap;
    private final float padX;
    private final float padY;
    private static final float MAG = 0.55f;        // focused icon = 1 + MAG
    private static final float RANGE = 2.0f;       // icons affected either side, in steps

    private float[] scales = new float[0];
    private LinearGradient bandShader;
    private float bandShaderTop = -1f;
    private int bmpSize;
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int selected = 0;
    private float pos = 0f;          // animated selection position (spring)
    private float mag = 0f;          // 0 unfocused .. 1 focused (animated)
    private float bounce = 0f;       // launch bounce, 0..1
    private final FloatValueHolder posHolder = new FloatValueHolder(0f);
    private final SpringAnimation posSpring;
    private ValueAnimator magAnim;
    private ValueAnimator bounceAnim;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private Typeface medium;

    public MacDock(Context c) { this(c, null); }

    public MacDock(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        sp = c.getResources().getDisplayMetrics().scaledDensity;
        base = 58 * dp;
        gap = 12 * dp;
        padX = 18 * dp;
        padY = 14 * dp;
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
        try { medium = c.getResources().getFont(R.font.plex_medium); } catch (Exception e) { medium = Typeface.DEFAULT_BOLD; }
        posSpring = new SpringAnimation(posHolder);
        posSpring.setSpring(new SpringForce(0f).setDampingRatio(0.72f).setStiffness(420f));
        posSpring.addUpdateListener((anim, value, velocity) -> { pos = value; invalidate(); });
        stroke.setStyle(Paint.Style.STROKE);
        shadow.setStyle(Paint.Style.FILL);
    }

    public void setHost(Host h) { host = h; }

    /** The pinned apps in order (special entries are not included). */
    public List<String> packages() {
        List<String> out = new ArrayList<>();
        for (Item it : items) if (it.pkg != null) out.add(it.pkg);
        return out;
    }

    public String selectedPackage() { return items.isEmpty() ? null : items.get(selected).pkg; }

    public String selectedLabel() { return items.isEmpty() ? "" : items.get(selected).label; }

    /** Keep the selection on {@code pkg} after the list changed. */
    public void select(String pkg) {
        for (int i = 0; i < items.size(); i++) if (pkg != null && pkg.equals(items.get(i).pkg)) { selected = i; pos = i; posHolder.setValue(i); posSpring.cancel(); invalidate(); return; }
    }

    /** Pinned apps (by package) followed by the all-apps icon. Missing apps are skipped. */
    public void setApps(List<String> packages) {
        items.clear();
        PackageManager pm = getContext().getPackageManager();
        for (String p : packages) {
            try {
                Intent i = pm.getLeanbackLaunchIntentForPackage(p);
                if (i == null) i = pm.getLaunchIntentForPackage(p);
                if (i == null) continue;
                Drawable icon = pm.getApplicationIcon(p);
                Item added = new Item(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0))), icon, i);
                added.pkg = p;
                items.add(added);
            } catch (Exception ignored) {
            }
        }
        Item wp = new Item(Tx.t("ภาพพื้นหลัง", "Wallpapers"), null, null);
        wp.wallpaper = true;
        items.add(wp);
        items.add(new Item(Tx.t("แอปทั้งหมด", "All apps"), null, null));
        selected = Math.min(selected, items.size() - 1);
        pos = selected;
        posHolder.setValue(pos);
        requestLayout();
        invalidate();
    }

    // ---- focus and keys -----------------------------------------------------------------------------------

    @Override protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gainFocus, direction, prev);
        removeCallbacks(rest);
        if (!gainFocus) { animateMag(0f, 180); return; }
        // The first focus after Home starts stays at normal size: icons grow only once the remote is used.
        if (firstFocus) { firstFocus = false; animateMag(0f, 1); return; }
        wake();
    }

    private static final long REST_MS = 8000L;
    private boolean firstFocus = true;
    private final Runnable rest = () -> animateMag(0f, 380);

    private void animateMag(float to, int ms) {
        if (magAnim != null) magAnim.cancel();
        magAnim = ValueAnimator.ofFloat(mag, to);
        magAnim.setDuration(ms);
        magAnim.addUpdateListener(a -> { mag = (float) a.getAnimatedValue(); invalidate(); });
        magAnim.start();
    }

    /** A key was used: grow the icons and start the countdown back to normal size. */
    private void wake() {
        removeCallbacks(rest);
        if (isFocused()) animateMag(1f, 200);
        postDelayed(rest, REST_MS);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(rest);
        super.onDetachedFromWindow();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) wake();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(selected - 1, e.getRepeatCount() > 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(selected + 1, e.getRepeatCount() > 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) { longFired = false; e.startTracking(); }
                return true;
            default:
                return super.onKeyDown(keyCode, e);
        }
    }

    private boolean longFired;

    @Override public boolean onKeyLongPress(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            longFired = true;
            if (selectedPackage() != null && host != null) host.onDockMenu();
            else activate();   // the special entries have no menu: a long press just opens them
            return true;
        }
        return super.onKeyLongPress(keyCode, e);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (longFired) { longFired = false; return true; }
            if (e.isTracking() && !e.isCanceled()) activate();
            return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    private void move(int to, boolean fast) {
        if (items.isEmpty()) return;
        to = Math.max(0, Math.min(items.size() - 1, to));
        if (to == selected) return;
        selected = to;
        if (!posSpring.isRunning()) posSpring.setStartValue(pos);
        posSpring.animateToFinalPosition(selected);
    }

    private void activate() {
        if (items.isEmpty()) return;
        final Item it = items.get(selected);
        if (it.intent == null) {
            if (host != null) { if (it.wallpaper) host.onWallpaper(); else host.onAllApps(); }
            return;
        }
        if (bounceAnim != null) bounceAnim.cancel();
        bounceAnim = ValueAnimator.ofFloat(0f, 1f);
        bounceAnim.setDuration(520);
        bounceAnim.addUpdateListener(a -> { bounce = (float) a.getAnimatedValue(); invalidate(); });
        bounceAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                bounce = 0f;
                invalidate();
                try {
                    getContext().startActivity(new Intent(it.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
        });
        bounceAnim.start();
    }

    // ---- geometry -----------------------------------------------------------------------------------------

    private float scaleFor(int i) {
        float d = Math.abs(i - pos);
        if (d >= RANGE) return 1f;
        double c = Math.cos(Math.PI / 2 * d / RANGE);
        return 1f + MAG * mag * (float) (c * c);
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension((int) (900 * dp * 0.84f), (int) ((base * (1 + MAG) + padY * 2 + 52 * dp) * 0.84f));
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    /** The whole view is drawn at its normal size, then scaled down by 0.84: more of the wallpaper shows. */
    private static final float K = 0.84f;

    private float lw() { return getWidth() / K; }

    private float lh() { return getHeight() / K; }

    @Override protected void onDraw(Canvas c) {
        c.save();
        c.scale(K, K);
        drawScaled(c);
        c.restore();
    }

    private void drawScaled(Canvas c) {
        int n = items.size();
        if (n == 0) return;
        if (scales.length != n) scales = new float[n];
        float[] s = scales;
        ensureBitmaps();
        float total = gap * (n - 1);
        for (int i = 0; i < n; i++) { s[i] = scaleFor(i); total += base * s[i]; }
        float startX = (lw() - total) / 2f;
        float bandH = base + padY * 2;
        float bandBottom = lh() - 8 * dp;
        float bandTop = bandBottom - bandH;

        // Glass band
        float bandL = startX - padX, bandR = startX + total + padX;
        rect.set(bandL, bandTop, bandR, bandBottom);
        fill.setStyle(Paint.Style.FILL);
        if (bandShader == null || bandShaderTop != bandTop) {
            bandShader = new LinearGradient(0, bandTop, 0, bandBottom, 0x40FFFFFF, 0x1FFFFFFF, Shader.TileMode.CLAMP);
            bandShaderTop = bandTop;
        }
        fill.setShader(bandShader);
        c.drawRoundRect(rect, 28 * dp, 28 * dp, fill);
        fill.setShader(null);
        fill.setColor(0x2E101418);
        c.drawRoundRect(rect, 28 * dp, 28 * dp, fill);
        stroke.setStrokeWidth(1.2f * dp);
        stroke.setColor(0x40FFFFFF);
        rect.inset(0.6f * dp, 0.6f * dp);
        c.drawRoundRect(rect, 28 * dp, 28 * dp, stroke);

        // Icons, bottom aligned on the band
        float x = startX;
        float iconBottom = bandBottom - padY;
        float selCx = 0, selTop = 0;
        for (int i = 0; i < n; i++) {
            float size = base * s[i];
            float cx = x + size / 2f;
            float lift = 0f;
            if (i == selected && bounce > 0f) {
                lift = (float) Math.abs(Math.sin(bounce * Math.PI * 2)) * 22 * dp * (1f - bounce);
            }
            float top = iconBottom - size - lift;
            drawItem(c, items.get(i), x, top, size, i == selected && mag > 0.01f);
            if (i == selected) { selCx = cx; selTop = top; }
            x += size + gap;
        }

        // Label above the selected icon
        if (mag > 0.05f) {
            Item it = items.get(selected);
            text.setTypeface(medium);
            text.setTextSize(17 * sp);
            text.setTextAlign(Paint.Align.CENTER);
            float tw = text.measureText(it.label);
            float ph = 34 * dp, pw = tw + 28 * dp;
            float ly = selTop - 12 * dp - ph;
            rect.set(selCx - pw / 2f, ly, selCx + pw / 2f, ly + ph);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor((int) (0xD9 * mag) << 24 | 0x1B1E24);
            c.drawRoundRect(rect, 12 * dp, 12 * dp, fill);
            text.setColor((int) (0xFF * mag) << 24 | 0xFFFFFF);
            c.drawText(it.label, selCx, ly + ph / 2f + 6 * dp, text);
        }
    }

    private void drawItem(Canvas c, Item it, float x, float top, float size, boolean selectedNow) {
        float r = size * 0.225f;
        // cheap soft shadow: two translucent rounded rects instead of a blurred shadow layer
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x24000000);
        rect.set(x, top + size * 0.07f, x + size, top + size * 1.07f);
        c.drawRoundRect(rect, r, r, fill);
        fill.setColor(0x14000000);
        rect.set(x + size * 0.05f, top + size * 0.12f, x + size * 0.95f, top + size * 1.13f);
        c.drawRoundRect(rect, r, r, fill);
        rect.set(x, top, x + size, top + size);
        if (it.bmp != null) c.drawBitmap(it.bmp, null, rect, bmpPaint);
        if (selectedNow) {
            stroke.setStrokeWidth(1.4f * dp);
            stroke.setColor(0x99FFFFFF);
            c.drawRoundRect(rect, r, r, stroke);
        }
    }

    /** Renders every icon once, at the biggest size it can reach, so a frame only scales a bitmap. */
    private void ensureBitmaps() {
        int target = Math.max(1, Math.round(base * (1 + MAG)));
        if (bmpSize == target) { boolean all = true; for (Item it : items) if (it.bmp == null) { all = false; break; } if (all) return; }
        bmpSize = target;
        for (Item it : items) it.bmp = render(it, target);
    }

    private android.graphics.Bitmap render(Item it, int size) {
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        float r = size * 0.225f;
        rect.set(0, 0, size, size);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (it.icon != null) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xFFF2F2F5);
            c.drawRect(rect, fill);
            int iw = it.icon.getIntrinsicWidth(), ih = it.icon.getIntrinsicHeight();
            if (iw > 0 && ih > 0 && iw > ih * 1.2f) {
                float w = size * (float) iw / ih;
                it.icon.setBounds((int) (size / 2f - w / 2f), 0, (int) (size / 2f + w / 2f), size);
            } else {
                it.icon.setBounds(0, 0, size, size);
            }
            it.icon.draw(c);
        } else if (it.wallpaper) {
            drawWallpaperGlyph(c, 0, 0, size);
        } else {
            drawAllAppsGlyph(c, 0, 0, size);
        }
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(0, 0, 0, size * 0.5f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rect.set(0, 0, size, size * 0.5f);
        c.drawRect(rect, fill);
        fill.setShader(null);
        c.restore();
        stroke.setStrokeWidth(Math.max(1f, size * 0.01f));
        stroke.setColor(0x33FFFFFF);
        rect.set(0, 0, size, size);
        c.drawRoundRect(rect, r, r, stroke);
        return bm;
    }

    private void drawAllAppsGlyph(Canvas c, float x, float top, float size) {
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(x, top, x + size, top + size, 0xFF5B6CFF, 0xFF9B5BFF, Shader.TileMode.CLAMP));
        rect.set(x, top, x + size, top + size);
        c.drawRect(rect, fill);
        fill.setShader(null);
        fill.setColor(0xF2FFFFFF);
        float cell = size * 0.17f, g = size * 0.08f;
        float start = x + (size - (cell * 3 + g * 2)) / 2f;
        float ty = top + (size - (cell * 3 + g * 2)) / 2f;
        for (int r = 0; r < 3; r++) {
            for (int q = 0; q < 3; q++) {
                rect.set(start + q * (cell + g), ty + r * (cell + g), start + q * (cell + g) + cell, ty + r * (cell + g) + cell);
                c.drawRoundRect(rect, cell * 0.28f, cell * 0.28f, fill);
            }
        }
    }

    private final Path gp = new Path();

    /** Landscape icon: soft sky, a pale sun, snow-capped far peak and green hills. */
    private void drawWallpaperGlyph(Canvas c, float x, float top, float size) {
        float s = size;
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(x, top, x, top + s, 0xFF7CC8F5, 0xFFD3F0DC, Shader.TileMode.CLAMP));
        rect.set(x, top, x + s, top + s);
        c.drawRect(rect, fill);
        fill.setShader(new android.graphics.RadialGradient(x + s * 0.72f, top + s * 0.27f, s * 0.22f, 0xAAFFF3B0, 0x00FFF3B0, Shader.TileMode.CLAMP));
        c.drawCircle(x + s * 0.72f, top + s * 0.27f, s * 0.22f, fill);
        fill.setShader(null);
        fill.setColor(0xFFFFE9A0);
        c.drawCircle(x + s * 0.72f, top + s * 0.27f, s * 0.085f, fill);
        gp.reset();
        gp.moveTo(x, top + s * 0.74f);
        gp.lineTo(x + s * 0.28f, top + s * 0.36f);
        gp.lineTo(x + s * 0.5f, top + s * 0.62f);
        gp.lineTo(x + s * 0.68f, top + s * 0.46f);
        gp.lineTo(x + s, top + s * 0.76f);
        gp.lineTo(x + s, top + s);
        gp.lineTo(x, top + s);
        gp.close();
        fill.setColor(0xFF6FA9A0);
        c.drawPath(gp, fill);
        gp.reset();
        gp.moveTo(x + s * 0.28f, top + s * 0.36f);
        gp.lineTo(x + s * 0.2f, top + s * 0.49f);
        gp.lineTo(x + s * 0.25f, top + s * 0.47f);
        gp.lineTo(x + s * 0.285f, top + s * 0.52f);
        gp.lineTo(x + s * 0.32f, top + s * 0.47f);
        gp.lineTo(x + s * 0.36f, top + s * 0.49f);
        gp.close();
        fill.setColor(0xF5FFFFFF);
        c.drawPath(gp, fill);
        gp.reset();
        gp.moveTo(x, top + s * 0.82f);
        gp.quadTo(x + s * 0.35f, top + s * 0.62f, x + s * 0.62f, top + s * 0.8f);
        gp.quadTo(x + s * 0.82f, top + s * 0.92f, x + s, top + s * 0.78f);
        gp.lineTo(x + s, top + s);
        gp.lineTo(x, top + s);
        gp.close();
        fill.setColor(0xFF3F8F6B);
        c.drawPath(gp, fill);
    }

}
