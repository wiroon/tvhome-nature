package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver that replaces the TV's own: the pictures ticked in the wallpaper manager, full screen, one after
 * another with a slow cross-fade and a very slow drift (so no pixel stays lit for long). Any key wakes the TV back
 * to where it was. No network. Set it with: settings put secure screensaver_components com.gathin.tvhome/.SlideshowDream
 */
public class SlideshowDream extends DreamService {
    private static final long SHOW_MS = 30_000L;
    private static final long FADE_MS = 2_500L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final List<File> queue = new ArrayList<>();
    private Map<String, String> captions;
    private final ImageView[] views = new ImageView[2];
    private TextView caption;
    private int front = 0;
    private int index = 0;
    private boolean running, loading;
    private int w, h;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        DisplayMetrics m = getResources().getDisplayMetrics();
        w = m.widthPixels;
        h = m.heightPixels;
        float dp = m.density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(this);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            views[i] = v;
            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        Typeface tf;
        try { tf = getResources().getFont(R.font.plex_regular); } catch (Exception e) { tf = Typeface.DEFAULT; }
        caption = new TextView(this);
        caption.setTextColor(0xCCFFFFFF);
        caption.setTextSize(14);
        caption.setTypeface(tf);
        caption.setShadowLayer(4 * dp, 0, dp, 0xCC000000);
        caption.setSingleLine(true);
        caption.setMaxWidth((int) (w * 0.55f));
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setAlpha(0f);
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        capLp.leftMargin = (int) (48 * dp);
        capLp.bottomMargin = (int) (36 * dp);
        root.addView(caption, capLp);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextColor(0xCCFFFFFF);
        clock.setTextSize(30);
        clock.setTypeface(tf);
        clock.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        clockLp.rightMargin = (int) (48 * dp);
        clockLp.bottomMargin = (int) (30 * dp);
        root.addView(clock, clockLp);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        File dir = Config.dir(this);
        File wp = dir == null ? null : new File(dir, "wallpapers");
        captions = WallpaperCaptions.read(wp);
        Set<String> off = BgManager.disabled(this);
        List<File> all = WallpaperRotation.images(wp);
        for (File f : all) if (!off.contains(f.getName())) queue.add(f);
        if (queue.isEmpty()) queue.addAll(all);
        Collections.shuffle(queue);
        running = true;
        next();
    }

    private void next() {
        if (!running || loading || queue.isEmpty()) return;
        final File f = queue.get(index++ % queue.size());
        loading = true;
        decoder.execute(() -> {
            final Bitmap b = decode(f);
            ui.post(() -> {
                loading = false;
                if (!running) { if (b != null) b.recycle(); return; }
                if (b == null) { ui.postDelayed(this::next, 200); return; }
                show(b, captions == null ? null : captions.get(f.getName()));
                ui.postDelayed(this::next, SHOW_MS);
            });
        });
    }

    private void show(Bitmap b, final String text) {
        final ImageView in = views[1 - front], out = views[front];
        in.setImageBitmap(b);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setTranslationX(0f);
        in.animate().cancel();
        in.animate().alpha(1f).setDuration(FADE_MS).start();
        // a slow zoom and drift across the whole time the picture is on
        float dir = (index % 2 == 0) ? 1f : -1f;
        in.animate().scaleX(1.07f).scaleY(1.07f).translationX(dir * w * 0.012f).setDuration(SHOW_MS + FADE_MS)
                .setInterpolator(new LinearInterpolator()).start();
        out.animate().cancel();
        out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            Drawable d = out.getDrawable();
            out.setImageDrawable(null);
            if (d instanceof BitmapDrawable) {
                Bitmap ob = ((BitmapDrawable) d).getBitmap();
                if (ob != null && !ob.isRecycled()) ob.recycle();
            }
        }).start();
        front = 1 - front;
        // The name changes when the pictures cross over, not before.
        caption.animate().cancel();
        caption.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            caption.setText(text == null ? "" : text);
            caption.animate().alpha(text == null ? 0f : 1f).setDuration(FADE_MS / 2).start();
        }).start();
    }

    private Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        for (ImageView v : views) { v.animate().cancel(); v.setImageDrawable(null); }
        decoder.shutdownNow();
        super.onDreamingStopped();
    }
}
