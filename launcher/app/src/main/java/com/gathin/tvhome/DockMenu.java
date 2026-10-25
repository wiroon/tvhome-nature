package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small glass menu above the Dock, opened by a long press on an app: move, remove, add. Back closes it. */
public class DockMenu extends FrameLayout {
    private final float dp;
    private final Typeface regular, medium;
    private final LinearLayout panel;
    private final TextView title;
    private final Runnable onClose;

    public DockMenu(Context c, Runnable onClose) {
        super(c);
        this.onClose = onClose;
        dp = c.getResources().getDisplayMetrics().density;
        Typeface r, m;
        try { r = c.getResources().getFont(R.font.plex_regular); m = c.getResources().getFont(R.font.plex_medium); }
        catch (Exception e) { r = Typeface.DEFAULT; m = Typeface.DEFAULT_BOLD; }
        regular = r;
        medium = m;
        setVisibility(GONE);
        setClickable(true);
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF2161A22);
        g.setCornerRadius(22 * dp);
        g.setStroke((int) dp, 0x33FFFFFF);
        panel.setBackground(g);
        int pad = (int) (14 * dp);
        panel.setPadding(pad, pad, pad, pad);
        LayoutParams lp = new LayoutParams((int) (300 * dp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (150 * dp);
        addView(panel, lp);
        title = new TextView(c);
        title.setTextColor(0xB3FFFFFF);
        title.setTextSize(15);
        title.setTypeface(medium);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding((int) (6 * dp), 0, 0, (int) (6 * dp));
    }

    /** Rows: label and action; a null action is a disabled row. */
    public void show(String heading, String[] labels, Runnable[] actions) {
        panel.removeAllViews();
        title.setText(heading);
        panel.addView(title);
        View first = null;
        for (int i = 0; i < labels.length; i++) {
            final Runnable a = actions[i];
            TextView t = new TextView(getContext());
            t.setText(labels[i]);
            t.setTextSize(18);
            t.setTypeface(medium);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
            t.setFocusable(a != null);
            t.setClickable(a != null);
            t.setTextColor(a == null ? 0x55FFFFFF : 0xFFFFFFFF);
            GradientDrawable n = new GradientDrawable();
            n.setCornerRadius(14 * dp);
            n.setColor(0x00000000);
            GradientDrawable f = new GradientDrawable();
            f.setCornerRadius(14 * dp);
            f.setColor(0xFFFFFFFF);
            t.setBackground(n);
            t.setOnFocusChangeListener((v, foc) -> {
                v.setBackground(foc ? f : n);
                ((TextView) v).setTextColor(foc ? 0xFF10131A : 0xFFFFFFFF);
            });
            if (a != null) t.setOnClickListener(v -> a.run());
            panel.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (46 * dp)));
            if (first == null && a != null) first = t;
        }
        setVisibility(VISIBLE);
        if (first != null) first.requestFocus();
    }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void close() {
        setVisibility(GONE);
        onClose.run();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (isOpen() && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
