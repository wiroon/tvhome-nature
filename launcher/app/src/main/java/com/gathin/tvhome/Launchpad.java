package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The all-apps overlay opened from the Dock: a dimmed full-screen grid of every launchable app. Back closes it. */
public class Launchpad extends FrameLayout {
    private static final int COLUMNS = 6;
    private final Runnable onClose;
    private final float dp;
    private final GridLayout grid;
    private final ScrollView scroll;

    public Launchpad(Context c, Runnable onClose) {
        super(c);
        this.onClose = onClose;
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF0A0D13);
        setVisibility(GONE);
        setClickable(true);

        TextView title = new TextView(c);
        title.setText(Tx.t("แอปทั้งหมด", "All apps"));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(28);
        try { title.setTypeface(c.getResources().getFont(R.font.plex_medium)); } catch (Exception ignored) { }
        LayoutParams tp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        tp.leftMargin = (int) (56 * dp);
        tp.topMargin = (int) (30 * dp);
        addView(title, tp);

        scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        grid = new GridLayout(c);
        grid.setColumnCount(COLUMNS);
        scroll.addView(grid, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams sp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sp.topMargin = (int) (84 * dp);
        sp.leftMargin = (int) (40 * dp);
        sp.rightMargin = (int) (40 * dp);
        sp.bottomMargin = (int) (16 * dp);
        addView(scroll, sp);
    }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    private java.util.function.Consumer<String> picker;

    /** Pick one app for the Dock: the tapped app is handed to {@code pick} instead of being launched. */
    public void openPicker(Set<String> hidden, java.util.function.Consumer<String> pick) {
        picker = pick;
        open(hidden);
    }

    public void open(Set<String> hidden) {
        grid.removeAllViews();
        Context c = getContext();
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(c.getPackageName()) || hidden.contains(p) || !seen.add(p)) continue;
                found.add(ri);
            }
        }
        Collections.sort(found, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        for (ResolveInfo ri : found) grid.addView(cell(pm, ri));
        setVisibility(VISIBLE);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    public void close() {
        picker = null;
        setVisibility(GONE);
        onClose.run();
    }

    private View cell(PackageManager pm, ResolveInfo ri) {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);
        box.setClickable(true);
        int pad = (int) (8 * dp);
        box.setPadding(pad, pad, pad, pad);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = (int) (136 * dp);
        lp.height = (int) (116 * dp);
        lp.setMargins((int) (6 * dp), (int) (4 * dp), (int) (6 * dp), (int) (4 * dp));
        box.setLayoutParams(lp);

        ImageView icon = new ImageView(c);
        icon.setImageDrawable(ri.loadIcon(pm));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(15 * dp);
        icon.setBackground(mask);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 15 * dp); }
        });
        box.addView(icon, new LinearLayout.LayoutParams((int) (62 * dp), (int) (62 * dp)));

        TextView label = new TextView(c);
        label.setText(ri.loadLabel(pm));
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(14);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        try { label.setTypeface(c.getResources().getFont(R.font.plex_regular)); } catch (Exception ignored) { }
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = (int) (8 * dp);
        box.addView(label, llp);

        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(0x33FFFFFF);
        focusBg.setStroke((int) (2 * dp), 0xCCFFFFFF);
        focusBg.setCornerRadius(18 * dp);
        box.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? focusBg : null);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start();
            if (f) v.getParent().requestChildFocus(v, v);
        });
        final Intent launch = new Intent(Intent.ACTION_MAIN)
                .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final String pkg = ri.activityInfo.packageName;
        box.setOnClickListener(v -> {
            if (picker != null) {
                java.util.function.Consumer<String> pk = picker;
                picker = null;
                setVisibility(GONE);
                pk.accept(pkg);
                onClose.run();
                return;
            }
            try { c.startActivity(launch); } catch (Exception ignored) { }
            setVisibility(GONE);
            onClose.run();
        });
        return box;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (isOpen() && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
