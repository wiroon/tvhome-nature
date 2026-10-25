package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wallpaper manager opened from the Dock: tick the pictures to keep in the rotation, set how often Home changes
 * picture, and choose shuffle or in-order. OK toggles a picture, a long press shows it on Home straight away.
 * The choices live in SharedPreferences ("wallpaper_manager"), so pushing a new set of pictures does not lose them
 * (a picture that was never ticked off is simply included).
 */
public class BgManager extends FrameLayout {
    /** What the screen needs from Home. */
    public interface Host {
        void apply();                 // re-read the choices and change picture now
        void preview(String name);    // show this picture on Home now
        String current();             // the picture on screen
        int defaultMinutes();
        String defaultOrder();
    }

    // ---- shared with WallpaperPlayer ---------------------------------------------------------------------------
    private static final String PREFS = "wallpaper_manager";
    static final int[] MINUTES = {1, 3, 5, 10, 30, 60, 0};
    private static final String[] MIN_LABEL = {Tx.t("1 นาที", "1 min"), Tx.t("3 นาที", "3 min"), Tx.t("5 นาที", "5 min"), Tx.t("10 นาที", "10 min"), Tx.t("30 นาที", "30 min"), Tx.t("1 ชั่วโมง", "1 hour"), Tx.t("ไม่สลับ", "Never")};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** wallpapers/staged.txt: pictures put on the TV but not ticked yet. They stay out of the rotation until the person decides. */
    static final String STAGED_FILE = "staged.txt";

    static Set<String> disabled(Context c) {
        SharedPreferences p = prefs(c);
        Set<String> out = new HashSet<>(p.getStringSet("disabled", new HashSet<>()));
        Set<String> decided = p.getStringSet("decided", new HashSet<>());
        File dir = Config.dir(c);
        File staged = dir == null ? null : new File(new File(dir, "wallpapers"), STAGED_FILE);
        if (staged != null && staged.isFile() && staged.length() < 64 * 1024) {
            try {
                for (String line : java.nio.file.Files.readAllLines(staged.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                    String n = line.trim();
                    if (!n.isEmpty() && !n.startsWith("#") && !decided.contains(n)) out.add(n);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Minutes between pictures: the saved choice, else {@code def}. 0 means never change. */
    static int minutes(Context c, int def) { return prefs(c).getInt("minutes", def); }

    static String order(Context c, String def) { return prefs(c).getString("order", def); }

    // ---- screen ------------------------------------------------------------------------------------------------
    private final Host host;
    private final Runnable onClose;
    private final float dp;
    private final Typeface regular, medium;
    private final List<File> files = new ArrayList<>();
    private final Set<String> off = new HashSet<>();
    private final Map<String, String> names;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final LinearLayout timerChips = new LinearLayout(getContext());
    private final LinearLayout orderChips = new LinearLayout(getContext());
    private final LinearLayout actionChips = new LinearLayout(getContext());
    private final GridLayout grid;
    private final TextView summary, footer;
    private int minutesChoice;
    private String orderChoice;
    private boolean dirty;

    public BgManager(Context c, Host host, Runnable onClose) {
        super(c);
        this.host = host;
        this.onClose = onClose;
        dp = c.getResources().getDisplayMetrics().density;
        Typeface r, m;
        try { r = c.getResources().getFont(R.font.plex_regular); m = c.getResources().getFont(R.font.plex_medium); }
        catch (Exception e) { r = Typeface.DEFAULT; m = Typeface.DEFAULT_BOLD; }
        regular = r;
        medium = m;
        File dir = Config.dir(c) == null ? null : new File(Config.dir(c), "wallpapers");
        files.addAll(WallpaperRotation.images(dir));
        names = WallpaperCaptions.read(dir);
        setBackgroundColor(0xFF0A0D13);
        setVisibility(GONE);
        setClickable(true);

        addAt(text(Tx.t("ภาพพื้นหลัง", "Wallpapers"), 26, 0xFFFFFFFF, medium), Gravity.TOP | Gravity.START, 48, 14, 0);
        summary = text("", 15, 0xB3FFFFFF, regular);
        addAt(summary, Gravity.TOP | Gravity.END, 0, 24, 48);

        // timer row
        addAt(text(Tx.t("เปลี่ยนภาพทุก", "Change every"), 15, 0x99FFFFFF, regular), Gravity.TOP | Gravity.START, 48, 66, 0);
        timerChips.setOrientation(LinearLayout.HORIZONTAL);
        addAt(timerChips, Gravity.TOP | Gravity.START, 148, 58, 0);
        for (int i = 0; i < MINUTES.length; i++) {
            final int minutes = MINUTES[i];
            timerChips.addView(chip(MIN_LABEL[i], () -> { minutesChoice = minutes; save(); paint(); }));
        }
        // order + actions row
        addAt(text(Tx.t("ลำดับ", "Order"), 15, 0x99FFFFFF, regular), Gravity.TOP | Gravity.START, 48, 106, 0);
        orderChips.setOrientation(LinearLayout.HORIZONTAL);
        addAt(orderChips, Gravity.TOP | Gravity.START, 148, 98, 0);
        orderChips.addView(chip(Tx.t("สุ่ม", "Shuffle"), () -> { orderChoice = WallpaperRotation.ORDER_SHUFFLE; save(); paint(); }));
        orderChips.addView(chip(Tx.t("ตามลำดับ", "In order"), () -> { orderChoice = WallpaperRotation.ORDER_SEQUENTIAL; save(); paint(); }));
        actionChips.setOrientation(LinearLayout.HORIZONTAL);
        addAt(actionChips, Gravity.TOP | Gravity.END, 0, 98, 42);
        actionChips.addView(chip(Tx.t("เลือกทั้งหมด", "Select all"), () -> { off.clear(); save(); refreshCells(); paint(); }));
        actionChips.addView(chip(Tx.t("ล้าง (เหลือภาพที่แสดงอยู่)", "Clear (keep the one shown)"), () -> {
            String keep = host.current();
            if (keep == null || keep.isEmpty() || find(keep) == null) keep = files.isEmpty() ? "" : files.get(0).getName();
            off.clear();
            for (File f : files) if (!f.getName().equals(keep)) off.add(f.getName());
            save();
            refreshCells();
            paint();
        }));

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        grid = new GridLayout(c);
        grid.setColumnCount(5);
        scroll.addView(grid, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams sp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sp.topMargin = (int) (146 * dp);
        sp.bottomMargin = (int) (34 * dp);
        sp.leftMargin = (int) (40 * dp);
        sp.rightMargin = (int) (40 * dp);
        addView(scroll, sp);

        footer = text("", 14, 0xCCFFFFFF, regular);
        footer.setSingleLine(true);
        footer.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams fp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        fp.leftMargin = (int) (48 * dp);
        fp.rightMargin = (int) (48 * dp);
        fp.bottomMargin = (int) (8 * dp);
        addView(footer, fp);
    }

    private void addAt(View v, int gravity, int left, int top, int right) {
        LayoutParams p = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        p.leftMargin = (int) (left * dp);
        p.topMargin = (int) (top * dp);
        p.rightMargin = (int) (right * dp);
        addView(v, p);
    }

    private TextView text(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    private TextView chip(String label, Runnable onClick) {
        TextView t = text(label, 15, 0xFFFFFFFF, medium);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        int px = (int) (14 * dp), py = (int) (6 * dp);
        t.setPadding(px, py, px, py);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int) (4 * dp), 0, (int) (4 * dp), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> onClick.run());
        t.setOnFocusChangeListener((v, f) -> paint());
        return t;
    }

    private void styleChip(TextView t, boolean on) {
        boolean foc = t.isFocused();
        GradientDrawable g = round(foc ? 0xFFFFFFFF : on ? 0x44FFFFFF : 0x14FFFFFF, 18);
        if (on && !foc) g.setStroke((int) dp, 0x99FFFFFF);
        t.setBackground(g);
        t.setTextColor(foc ? 0xFF10131A : 0xFFFFFFFF);
    }

    private void paint() {
        for (int i = 0; i < timerChips.getChildCount(); i++) styleChip((TextView) timerChips.getChildAt(i), MINUTES[i] == minutesChoice);
        styleChip((TextView) orderChips.getChildAt(0), WallpaperRotation.ORDER_SHUFFLE.equals(orderChoice));
        styleChip((TextView) orderChips.getChildAt(1), WallpaperRotation.ORDER_SEQUENTIAL.equals(orderChoice));
        for (int i = 0; i < actionChips.getChildCount(); i++) styleChip((TextView) actionChips.getChildAt(i), false);
        int on = files.size() - countOff();
        summary.setText(Tx.t("เลือก ", "Selected ") + on + Tx.t(" จาก ", " of ") + files.size() + Tx.t(" ภาพ", " pictures"));
    }

    private int countOff() {
        int n = 0;
        for (File f : files) if (off.contains(f.getName())) n++;
        return n;
    }

    private File find(String name) {
        for (File f : files) if (f.getName().equals(name)) return f;
        return null;
    }

    private void save() {
        Set<String> decided = new HashSet<>();
        for (File f : files) decided.add(f.getName());
        prefs(getContext()).edit().putStringSet("disabled", new HashSet<>(off)).putStringSet("decided", decided)
                .putInt("minutes", minutesChoice).putString("order", orderChoice).apply();
        dirty = true;
    }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void open() {
        Context c = getContext();
        off.clear();
        off.addAll(disabled(c));
        minutesChoice = minutes(c, host.defaultMinutes());
        orderChoice = order(c, host.defaultOrder());
        dirty = false;
        buildCells();
        paint();
        setVisibility(VISIBLE);
        // Focus the timer chip that is on: the first thing a person comes here to change.
        for (int i = 0; i < MINUTES.length; i++) if (MINUTES[i] == minutesChoice) { timerChips.getChildAt(i).requestFocus(); break; }
        footer.setText(Tx.t("OK = เลือก/ไม่เลือกภาพ  ·  กด OK ค้าง = แสดงภาพนี้ทันที  ·  Back = เสร็จ", "OK = tick / untick  ·  hold OK = show it now  ·  Back = done"));
    }

    public void close() {
        decoder.shutdownNow();
        setVisibility(GONE);
        if (dirty) host.apply();
        onClose.run();
    }

    // ---- thumbnails ---------------------------------------------------------------------------------------------

    private final List<View> cells = new ArrayList<>();
    private ExecutorService thumbs = Executors.newSingleThreadExecutor();

    private void buildCells() {
        grid.removeAllViews();
        cells.clear();
        if (thumbs.isShutdown()) thumbs = Executors.newSingleThreadExecutor();
        if (files.isEmpty()) {
            TextView t = text(Tx.t("ยังไม่มีภาพ", "No pictures yet"), 18, 0x99FFFFFF, regular);
            grid.addView(t);
            return;
        }
        for (File f : files) {
            View cell = cell(f);
            cells.add(cell);
            grid.addView(cell);
        }
    }

    private View cell(File f) {
        Context c = getContext();
        FrameLayout box = new FrameLayout(c);
        box.setFocusable(true);
        box.setClickable(true);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = (int) (166 * dp);
        lp.height = (int) (96 * dp);
        lp.setMargins((int) (5 * dp), (int) (5 * dp), (int) (5 * dp), (int) (5 * dp));
        box.setLayoutParams(lp);
        box.setTag(f.getName());

        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(round(0x22FFFFFF, 12));
        img.setClipToOutline(true);
        img.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 12 * dp); }
        });
        box.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        thumbs.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 6;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b != null) img.post(() -> img.setImageBitmap(b));
        });

        View shade = new View(c);
        shade.setBackground(round(0xAA0A0D13, 12));
        shade.setTag("shade");
        box.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView tick = text("", 16, 0xFFFFFFFF, medium);
        tick.setGravity(Gravity.CENTER);
        tick.setTag("tick");
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams((int) (26 * dp), (int) (26 * dp), Gravity.TOP | Gravity.END);
        tp.setMargins(0, (int) (6 * dp), (int) (6 * dp), 0);
        box.addView(tick, tp);

        box.setOnClickListener(v -> toggle(f.getName()));
        box.setOnLongClickListener(v -> { host.preview(f.getName()); return true; });
        box.setOnFocusChangeListener((v, focused) -> {
            styleCell(box, f.getName());
            if (focused) {
                String cap = names.get(f.getName());
                footer.setText((cap != null ? cap : f.getName()) + Tx.t("   ·   OK = เลือก/ไม่เลือก   ·   กด OK ค้าง = แสดงทันที", "   ·   OK = tick / untick   ·   hold OK = show now"));
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
        });
        styleCell(box, f.getName());
        return box;
    }

    private void styleCell(FrameLayout box, String name) {
        boolean on = !off.contains(name);
        View shade = box.findViewWithTag("shade");
        TextView tick = box.findViewWithTag("tick");
        shade.setVisibility(on ? INVISIBLE : VISIBLE);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? 0xFF1E8E3E : 0x66000000);
        g.setStroke((int) (1.5f * dp), 0xCCFFFFFF);
        tick.setBackground(g);
        tick.setText(on ? "✓" : "");
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(0x00000000);
        ring.setCornerRadius(12 * dp);
        if (box.isFocused()) ring.setStroke((int) (3 * dp), 0xFFFFFFFF);
        box.setForeground(ring);
    }

    private void refreshCells() {
        for (View v : cells) styleCell((FrameLayout) v, (String) v.getTag());
    }

    private void toggle(String name) {
        if (off.contains(name)) {
            off.remove(name);
        } else {
            if (files.size() - countOff() <= 1) {
                footer.setText(Tx.t("ต้องเหลืออย่างน้อย 1 ภาพ", "At least one picture must stay on"));
                return;
            }
            off.add(name);
        }
        save();
        for (View v : cells) if (name.equals(v.getTag())) styleCell((FrameLayout) v, name);
        paint();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (isOpen() && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
