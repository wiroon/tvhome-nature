package com.gathin.tvhome;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A looping local video behind Home (the "live" wallpaper). One clip at a time from files/videos/, chosen in the
 * wallpaper manager. It plays only while Home is on screen: leaving Home releases the decoder, and after 30 minutes
 * with no key it stops and the still wallpaper shows again (so a panel is not left on one moving picture all night).
 * Silent, no network. If the clip cannot be played the still wallpaper simply stays.
 */
final class VideoBackground implements TextureView.SurfaceTextureListener {
    private static final long IDLE_MS = 30L * 60_000L;

    private final Context ctx;
    private final FrameLayout root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextureView view;
    private MediaPlayer player;
    private boolean active, surfaceReady, idleStopped;
    private String playing = "";
    private String wanted = "";

    VideoBackground(Context ctx, FrameLayout root) {
        this.ctx = ctx;
        this.root = root;
    }

    /** The clips on the TV, sorted by name. */
    static List<File> clips(Context c) {
        File dir = Config.dir(c);
        File[] fs = dir == null ? null : new File(dir, "videos").listFiles((d, n) -> n.toLowerCase().endsWith(".mp4") && !n.startsWith("."));
        List<File> out = new ArrayList<>();
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs) if (f.length() > 0) out.add(f);
        }
        return out;
    }

    private final Runnable idleStop = () -> {
        idleStopped = true;
        release();
    };

    /** The clip the rotation chose; it starts now if Home is on screen, otherwise when Home comes back. */
    void show(File f) {
        wanted = f.getAbsolutePath();
        idleStopped = false;
        if (active) { resetIdle(); release(); play(); }
    }

    /** The rotation moved on to a still picture. */
    void hide() {
        wanted = "";
        release();
    }

    void start() {
        active = true;
        idleStopped = false;
        resetIdle();
        play();
    }

    void stop() {
        active = false;
        ui.removeCallbacks(idleStop);
        release();
    }

    void close() { stop(); }

    /** A key was pressed: wake the clip if the idle timer had stopped it, and restart the timer. */
    void touch() {
        if (!active) return;
        resetIdle();
        if (idleStopped) { idleStopped = false; play(); }
    }

    private void resetIdle() {
        ui.removeCallbacks(idleStop);
        ui.postDelayed(idleStop, IDLE_MS);
    }

    // ---- playback ------------------------------------------------------------------------------------------

    private void play() {
        if (wanted.isEmpty() || !active || !new File(wanted).isFile()) { release(); return; }
        if (view == null) {
            view = new TextureView(ctx);
            view.setSurfaceTextureListener(this);
            view.setAlpha(0f);
            view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Above the still wallpaper (child 0), below everything else.
            root.addView(view, Math.min(1, root.getChildCount()),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        playing = wanted;
        if (surfaceReady) startPlayer();
    }

    private void startPlayer() {
        if (playing.isEmpty() || view == null || view.getSurfaceTexture() == null) return;
        releasePlayer();
        try {
            final MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setDataSource(playing);
            mp.setSurface(new Surface(view.getSurfaceTexture()));
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                p.start();
                view.animate().alpha(1f).setDuration(1200).start();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                android.util.Log.w("TvHome.Video", "playback error " + what + "/" + extra);
                ui.post(this::release);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            android.util.Log.w("TvHome.Video", "cannot play", e);
            release();
        }
    }

    private void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) { }
            p.release();
        }
    }

    /** Stop the decoder and hide the view so the still wallpaper shows; the view itself stays for the next start. */
    private void release() {
        releasePlayer();
        playing = "";
        if (view != null) { view.animate().cancel(); view.setAlpha(0f); }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surfaceReady = true;
        if (!playing.isEmpty()) startPlayer();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surfaceReady = false;
        releasePlayer();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
}
