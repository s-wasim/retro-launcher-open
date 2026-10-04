package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.retro.launcher.core.DateFormatter;
import com.retro.launcher.core.LockRoute;
import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.StaleGate;
import com.retro.launcher.core.PaletteResolver;
import com.retro.launcher.core.Palettes;
import com.retro.launcher.core.Weather;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.theme.Tint;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tier 3 — the fourth panel. Four DESIGN_NOTES §7c sections (PALETTE,
 * CLOCK &amp; DATE, TEMPERATURE, DOCK) plus a native-only PERMISSIONS block
 * with live status and a fix button, so a skipped first-run setup
 * is recoverable without a reinstall. Every control writes straight through
 * {@link Prefs} and calls {@code onPrefsChanged} so the caller can refresh
 * whatever else depends on it (palette, clock, dock).
 *
 * <p>The paid features of the Play build are still shown here, in their
 * default state, behind {@link PaidGate}'s {@code <PAID>} chip. None of them
 * has an implementation in the open-source build.
 */
public final class SettingsPanel extends FrameLayout {

    public interface DockActionListener {
        void onReplace(int slotIndex);
        void onAdd();
    }

    public interface PermissionActionListener {
        void onRequestLocation();
        void onOpenUsageAccessSettings();
        void onEnableDeviceLock();
        void onSetDefaultLauncher();
        void onEnableNotificationShade();
        void onEnableShizukuLock();
    }

    private final Metrics metrics;
    private final Prefs prefs;

    private final LinearLayout header;
    private final int headerPadTop;
    private final ScrollView scroll;
    private final LinearLayout paletteSection;
    private final LinearLayout clockSection;
    private final LinearLayout tempSection;
    private final LinearLayout feedbackSection;
    private final LinearLayout dockSection;
    private final LinearLayout lockdownSection;
    private final LinearLayout permSection;
    private final LinearLayout wallpaperSection;
    private final LinearLayout customWpSection;

    private Runnable onPrefsChanged = () -> {};
    private Runnable onClose = () -> {};
    private DockActionListener dockListener;
    private PermissionActionListener permissionListener;

    private Palette palette;
    private Weather weather;
    private List<String> dockEntries = new ArrayList<>();
    private boolean locationGranted;
    private boolean usageGranted;
    private LockRoute lockRoute = LockRoute.NONE;
    private boolean isDefaultLauncher;
    private boolean shadeServiceEnabled;
    private boolean shizukuLockEnabled;
    private boolean shizukuLockPermitted;

    public SettingsPanel(Context context, Metrics metrics, Prefs prefs) {
        super(context);
        this.metrics = metrics;
        this.prefs = prefs;

        Tint.setRole(this, Tint.ROLE_BG);
        setBackgroundColor(0xFF000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        column.addView(header = buildHeader());
        headerPadTop = header.getPaddingTop();

        scroll = new ScrollView(context);
        // Vertical only: a swipe left across the settings body closes the
        // panel. The scrubber and toggles inside still claim their own drags.
        LauncherRoot.setVerticalScroller(scroll);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int sidePad = Math.round(metrics.cqw(4.5f));
        content.setPadding(sidePad, 0, sidePad, Math.round(metrics.cqw(8f)));

        content.addView(paletteSection = section());
        content.addView(clockSection = section());
        content.addView(tempSection = section());
        content.addView(feedbackSection = section());
        content.addView(dockSection = section());
        content.addView(lockdownSection = section());
        content.addView(permSection = section());
        content.addView(customWpSection = section());
        content.addView(wallpaperSection = section());

        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout section() {
        LinearLayout s = new LinearLayout(getContext());
        s.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(metrics.cqw(6f));
        s.setLayoutParams(lp);
        return s;
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(metrics.cqw(4.5f));
        int padTop = Math.round(metrics.cqw(5f));
        int padBottom = Math.round(metrics.cqw(3f));
        header.setPadding(padH, padTop, padH, padBottom);

        TextView title = new TextView(getContext());
        title.setText("SETTINGS");
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setAllCaps(true);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_TITLE_CQW, DrawerPanel.SIZE_TITLE_MIN));
        Tint.setRole(title, Tint.ROLE_INK);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(getContext());
        close.setText("CLOSE");
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        int closePad = Math.round(metrics.cqw(3f));
        close.setPadding(closePad, closePad, closePad, closePad);
        Tint.setRole(close, Tint.ROLE_P);
        close.setOnClickListener(v -> onClose.run());
        header.addView(close);

        // The header sits outside the no-swipe scroll content below it — mark
        // it no-swipe too, otherwise a tap that lands a hair off CLOSE reads
        // as the start of a horizontal drag and snaps the panel home anyway.
        LauncherRoot.setNoSwipe(header);

        return header;
    }

    @Override public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.graphics.Insets sys = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerPadTop + sys.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            // Otherwise the bottom of the body rests under the gesture pill.
            com.retro.launcher.util.Insets.padScrollerForSystemBars(scroll, insets, 0);
        }
        return super.onApplyWindowInsets(insets);
    }

    public void setOnCloseListener(Runnable r) { this.onClose = r; }
    public void setOnPrefsChangedListener(Runnable r) { this.onPrefsChanged = r; }
    public void setDockActionListener(DockActionListener l) { this.dockListener = l; }
    public void setPermissionActionListener(PermissionActionListener l) { this.permissionListener = l; }

    public void setPalette(Palette p) {
        this.palette = p;
        Tint.apply(this, p);
        invalidateContent();
    }

    private void rebuildContentNow() {
        rebuildAll();
    }

    // ---- 2.4.1: deferred content ------------------------------------------

    /**
     * Content is built the first time this panel is actually on screen, not
     * during {@code HomeActivity.onCreate}. See {@link StaleGate} for the
     * rules and why they live in {@code :core}.
     *
     * <p>{@code onVisibilityAggregated} rather than a hook in
     * {@link LauncherRoot}: it accounts for the window and every ancestor, so
     * it cannot report visible for a panel behind a closed drawer, and it
     * needs no cooperation from the slide machinery.
     */
    private final StaleGate content = new StaleGate();

    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (content.onVisibilityChanged(isVisible)) rebuildContentNow();
    }

    private void invalidateContent() {
        if (content.invalidate(isShown())) rebuildContentNow();
    }

    // ---- paid features: shown, never implemented ---------------------------

    /** A one-line row: chip at the end, over the dimmed control. */
    private View gate(View row, String title) {
        return PaidGate.wrap(row, title, metrics, palette, Gravity.END | Gravity.CENTER_VERTICAL);
    }

    /** What a gated toggle or chip row is handed: there is nothing to do. */
    private static <T> Consumer<T> inert() { return v -> {}; }

    /**
     * 3.0.1. Every status setter below is called on every return home and
     * every minute tick, and each used to tear down and rebuild its section
     * unconditionally — six rebuilds of a panel nobody was looking at, on the
     * UI thread, ahead of the first frame back. Now a setter returns early
     * when nothing changed, and a change to a hidden panel only marks it
     * stale: {@link #content} rebuilds everything the next time it is shown.
     */
    private void sectionChanged(Runnable rebuild) {
        if (isShown()) rebuild.run();
        else content.invalidate(false);
    }

    public void setWeather(Weather w) {
        Weather was = this.weather;
        this.weather = w;
        boolean same = was == w || (was != null && w != null
                && was.tempC == w.tempC && was.label.equals(w.label));
        if (!same) sectionChanged(this::rebuildTempSection);
    }

    public void setDockEntries(List<String> entries) {
        if (entries.equals(dockEntries)) return;
        this.dockEntries = entries;
        sectionChanged(this::rebuildDockSection);
    }

    public void setPermissionStatus(boolean locationGranted, boolean usageGranted) {
        if (locationGranted == this.locationGranted && usageGranted == this.usageGranted) return;
        this.locationGranted = locationGranted;
        this.usageGranted = usageGranted;
        sectionChanged(this::rebuildPermissionsSection);
    }

    /** DESIGN_NOTES §9 deltas 19 and 25: which route double-tap-home-to-lock
     *  has (V9 §8 moved it off the long press). */
    public void setDeviceLockStatus(LockRoute route) {
        if (route == this.lockRoute) return;
        this.lockRoute = route;
        sectionChanged(this::rebuildPermissionsSection);
    }

    /** DESIGN_NOTES §9 delta 20: whether this app is the default launcher. */
    public void setDefaultLauncherStatus(boolean isDefault) {
        if (isDefault == this.isDefaultLauncher) return;
        this.isDefaultLauncher = isDefault;
        sectionChanged(this::rebuildPermissionsSection);
    }

    /** DESIGN_NOTES §9 delta 21: whether the accessibility fallback that opens
     *  the notification shade is switched on. */
    public void setNotificationShadeStatus(boolean enabled) {
        if (enabled == this.shadeServiceEnabled) return;
        this.shadeServiceEnabled = enabled;
        sectionChanged(this::rebuildPermissionsSection);
    }

    /** Whether the SHIZUKU LOCK toggle is on, and whether a permitted
     *  session is currently reachable — two different
     *  things, since the toggle survives a reboot but the pairing does not. */
    public void setShizukuLockStatus(boolean enabled, boolean permitted) {
        if (enabled == this.shizukuLockEnabled && permitted == this.shizukuLockPermitted) return;
        this.shizukuLockEnabled = enabled;
        this.shizukuLockPermitted = permitted;
        sectionChanged(this::rebuildPermissionsSection);
    }

    private void rebuildAll() {
        rebuildPaletteSection();
        rebuildClockSection();
        rebuildTempSection();
        rebuildFeedbackSection();
        rebuildDockSection();
        rebuildLockdownSection();
        rebuildPermissionsSection();
        rebuildCustomWallpaperSection();
        rebuildWallpaperSection();
    }

    // ---- PALETTE -----------------------------------------------------

    private void rebuildPaletteSection() {
        paletteSection.removeAllViews();
        if (palette == null) return;

        paletteSection.addView(sectionHeader("PALETTE"));

        List<View> cards = new ArrayList<>();
        // AUTO is the only palette in this build; every fixed palette is shown
        // as a preview, gated card by card.
        cards.add(paletteCard(PaletteResolver.AUTO, true));
        for (String id : Palettes.IDS) {
            cards.add(PaidGate.wrap(paletteCard(id, false), "PALETTES",
                    metrics, palette, Gravity.END | Gravity.TOP));
        }
        addGrid(paletteSection, cards, 2);

        int gap = Math.round(metrics.cqw(3f));
        LinearLayout themeRow = singleSelectRow(
                new String[]{"AUTO", "LIGHT", "DARK"},
                new String[]{PaletteResolver.TIME, PaletteResolver.LIGHT, PaletteResolver.DARK},
                PaletteResolver.TIME, inert());
        addTopMargin(themeRow, gap);
        paletteSection.addView(gate(themeRow, "LIGHT / DARK"));

        LinearLayout tintRow = toggleRow("TINT WALLPAPER TO PALETTE", false, inert());
        addTopMargin(tintRow, gap);
        paletteSection.addView(gate(tintRow, "WALLPAPER TINT"));
    }

    private View paletteCard(String id, boolean auto) {
        boolean selected = auto;
        Palette shown = auto
                ? Palettes.get(PaletteResolver.autoIdFor(decimalHour()), palette.dark)
                : Palettes.get(id, palette.dark);

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(metrics.cqw(3f));
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(metrics.cqw(1.5f));
        int border = Math.max(1, Math.round(metrics.cqw(0.7f)));
        bg.setStroke(border, selected ? palette.p : ((0x55 << 24) | (palette.ink & 0x00FFFFFF)));
        bg.setColor(selected ? ((0x2E << 24) | (palette.p & 0x00FFFFFF)) : 0x00000000);
        card.setBackground(bg);

        TextView name = new TextView(getContext());
        name.setText(auto ? "AUTO / TIME" : shown.label);
        name.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        name.setAllCaps(true);
        name.setTextColor(palette.ink);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        card.addView(name);

        if (auto) {
            TextView note = new TextView(getContext());
            note.setText(PaletteResolver.autoLabelFor(decimalHour()) + " · " + shown.label);
            note.setTypeface(Typeface.MONOSPACE);
            note.setAllCaps(true);
            note.setTextColor(palette.pText());
            note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
            card.addView(note);
        }

        LinearLayout ramp = new LinearLayout(getContext());
        ramp.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rampLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rampLp.topMargin = Math.round(metrics.cqw(1.5f));
        ramp.setLayoutParams(rampLp);
        int[] chips = { shown.bg, shown.tile, shown.p, shown.a, shown.s, shown.h };
        int chipSize = Math.round(metrics.cqw(3.2f));
        int chipGap = Math.round(metrics.cqw(0.8f));
        for (int i = 0; i < chips.length; i++) {
            View chip = new View(getContext());
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setColor(chips[i]);
            chip.setBackground(chipBg);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(chipSize, chipSize);
            if (i > 0) clp.leftMargin = chipGap;
            ramp.addView(chip, clp);
        }
        card.addView(ramp);
        return card;
    }

    // ---- CLOCK & DATE --------------------------------------------------

    private void rebuildClockSection() {
        clockSection.removeAllViews();
        if (palette == null) return;

        clockSection.addView(sectionHeader("CLOCK & DATE"));

        int gap = Math.round(metrics.cqw(3f));
        LinearLayout hourRow = singleSelectRow(
                new String[]{"12-HOUR", "24-HOUR"}, new String[]{"12", "24"},
                prefs.hour12() ? "12" : "24",
                value -> { prefs.putBool(Prefs.K_HOUR12, "12".equals(value)); onPrefsChanged.run(); });
        addTopMargin(hourRow, gap);
        clockSection.addView(hourRow);

        LinearLayout zoneToggle = toggleRow("SECOND TIME ZONE", false, inert());
        addTopMargin(zoneToggle, gap);
        clockSection.addView(gate(zoneToggle, "SECOND TIME ZONE"));

        Calendar now = Calendar.getInstance();
        int fmtIdx = prefs.fmtIdx();
        for (int i = 0; i < DateFormatter.PRESETS.length; i++) {
            String pattern = DateFormatter.PRESETS[i];
            String preview = DateFormatter.format(pattern, now.get(Calendar.YEAR),
                    now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH),
                    now.get(Calendar.DAY_OF_WEEK) - 1);
            final int idx = i;
            View row = formatRow(pattern, preview, fmtIdx == i, () -> {
                prefs.putInt(Prefs.K_FMT_IDX, idx);
                onPrefsChanged.run();
            });
            addTopMargin(row, gap);
            clockSection.addView(row);
        }
        View customRow = formatRow("CUSTOM", "—", false, null);
        addTopMargin(customRow, gap);
        clockSection.addView(gate(customRow, "CUSTOM DATE FORMAT"));
    }

    /** A label on the left and a value on the right in the accent — the same
     *  shape as the dock rows below. */
    private View actionRow(String label, String value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = Math.round(metrics.cqw(1.5f));
        row.setPadding(0, padV, 0, padV);

        TextView left = new TextView(getContext());
        left.setText(label);
        left.setTypeface(Typeface.MONOSPACE);
        left.setAllCaps(true);
        left.setTextColor(palette.ink);
        left.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        row.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(getContext());
        right.setText(value);
        right.setTypeface(Typeface.MONOSPACE);
        right.setAllCaps(true);
        right.setTextColor(palette.aText());
        right.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        row.addView(right);
        return row;
    }

    /** @param onClick null for a row that is only shown, never chosen */
    private View formatRow(String left, String previewText, boolean selected, Runnable onClick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = Math.round(metrics.cqw(1.5f));
        row.setPadding(0, padV, 0, padV);

        TextView leftView = new TextView(getContext());
        leftView.setText(left);
        leftView.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        leftView.setTextColor(selected ? palette.p : palette.ink);
        leftView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        row.addView(leftView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView preview = new TextView(getContext());
        preview.setText(previewText);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextColor(palette.aText());
        preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        row.addView(preview);

        if (onClick != null) row.setOnClickListener(v -> onClick.run());
        return row;
    }

    // ---- TEMPERATURE -----------------------------------------------------

    private void rebuildTempSection() {
        tempSection.removeAllViews();
        if (palette == null) return;

        tempSection.addView(sectionHeader("TEMPERATURE"));

        int gap = Math.round(metrics.cqw(3f));
        LinearLayout unitRow = singleSelectRow(
                new String[]{"CELSIUS °C", "FAHRENHEIT °F"}, new String[]{"C", "F"},
                prefs.unit(),
                value -> { prefs.putString(Prefs.K_UNIT, value); onPrefsChanged.run(); });
        addTopMargin(unitRow, gap);
        tempSection.addView(unitRow);

        TextView caption = new TextView(getContext());
        // Null means no live reading — the sky is running on the synthetic
        // stand-in, and saying so beats implying the dash is a measurement.
        caption.setText(weather != null
                ? "OPEN-METEO — " + weather.label + " · " + weather.tempIn(prefs.unit()) + "°"
                : "NO READING — SYNTHETIC SKY · NEEDS LOCATION");
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setTextColor(palette.aText());
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        addTopMargin(caption, gap);
        tempSection.addView(caption);
    }

    // ---- CUSTOM WALLPAPER ----------------------------------------------

    private void rebuildCustomWallpaperSection() {
        customWpSection.removeAllViews();
        if (palette == null) return;
        customWpSection.addView(sectionHeader("CUSTOM WALLPAPER"));
        customWpSection.addView(gate(actionRow("CHOOSE IMAGE", "PICK"), "CUSTOM WALLPAPER"));
        customWpSection.addView(caption(
                "YOUR PHOTO, PIXELATED, IN PLACE OF THE SKY. THE SKY STOPS RENDERING "
                + "AND THE CLOCK SHOWS A SMALL SUN OR MOON. NO GALLERY PERMISSION."));
    }

    private TextView caption(String text) {
        TextView caption = new TextView(getContext());
        caption.setText(text);
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setTextColor(palette.aText());
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        addTopMargin(caption, Math.round(metrics.cqw(3f)));
        return caption;
    }

    // ---- WALLPAPER -----------------------------------------------------

    private void rebuildWallpaperSection() {
        wallpaperSection.removeAllViews();
        if (palette == null) return;
        wallpaperSection.addView(sectionHeader("WALLPAPER"));
        wallpaperSection.addView(gate(toggleRow("MANUAL WALLPAPER", false, inert()),
                "MANUAL WALLPAPER"));
        wallpaperSection.addView(caption("SET THE SKY'S WEATHER, TIME AND MOON YOURSELF "
                + "FROM SLIDERS, INSTEAD OF THE REAL READING."));
    }

    // ---- FEEDBACK ------------------------------------------------------

    private void rebuildFeedbackSection() {
        feedbackSection.removeAllViews();
        if (palette == null) return;
        feedbackSection.addView(sectionHeader("FEEDBACK"));
        feedbackSection.addView(gate(toggleRow("HAPTIC FEEDBACK", false, inert()),
                "HAPTIC FEEDBACK"));
    }

    // ---- DOCK --------------------------------------------------------

    private void rebuildDockSection() {
        dockSection.removeAllViews();
        if (palette == null) return;

        dockSection.addView(sectionHeader("DOCK — BOTTOM LEFT"));

        int gap = Math.round(metrics.cqw(2.5f));
        for (int i = 0; i < dockEntries.size(); i++) {
            View row = dockRow(DockView.labelFor(dockEntries.get(i)), false, i);
            addTopMargin(row, gap);
            dockSection.addView(row);
        }
        if (dockEntries.size() < 5) {
            View row = dockRow("EMPTY SLOT", true, -1);
            addTopMargin(row, gap);
            dockSection.addView(row);
        }

        TextView caption = new TextView(getContext());
        caption.setText("LONG-PRESS A DOCK SLOT ON THE HOME SCREEN TO REPLACE IT. MAX 5.");
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setTextColor(palette.aText());
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        addTopMargin(caption, Math.round(metrics.cqw(3f)));
        dockSection.addView(caption);
    }

    private View dockRow(String label, boolean empty, int slotIndex) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView glyph = new TextView(getContext());
        glyph.setGravity(Gravity.CENTER);
        glyph.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        glyph.setText(empty ? "+" : label.substring(0, 1));
        int glyphSize = Math.round(metrics.cqw(9f));
        GradientDrawable glyphBg = new GradientDrawable();
        glyphBg.setCornerRadius(metrics.cqw(1.2f));
        if (empty) {
            glyphBg.setStroke(Math.max(1, Math.round(metrics.cqw(0.6f))), palette.p,
                    metrics.dp(3f), metrics.dp(3f));
            glyph.setTextColor(palette.pText());
        } else {
            glyphBg.setColor(palette.tile);
            // On `tile`, so the stored accent — see ScreenTimePanel.mostUsedRow.
            glyph.setTextColor(palette.p);
        }
        glyph.setBackground(glyphBg);
        glyph.setLayoutParams(new LinearLayout.LayoutParams(glyphSize, glyphSize));
        row.addView(glyph);

        TextView labelView = new TextView(getContext());
        labelView.setText(label);
        labelView.setTypeface(Typeface.MONOSPACE);
        labelView.setAllCaps(true);
        labelView.setTextColor(palette.ink);
        labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = Math.round(metrics.cqw(3f));
        row.addView(labelView, labelLp);

        TextView action = new TextView(getContext());
        action.setText(empty ? "ADD" : "REPLACE");
        action.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        action.setTextColor(palette.pText());
        action.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        row.addView(action);

        row.setOnClickListener(v -> {
            if (dockListener == null) return;
            if (empty) dockListener.onAdd();
            else dockListener.onReplace(slotIndex);
        });
        return row;
    }

    // ---- SCREEN TIME LOCKDOWN ----------------------------------------

    private void rebuildLockdownSection() {
        lockdownSection.removeAllViews();
        if (palette == null) return;
        lockdownSection.addView(sectionHeader("SCREEN TIME LOCKDOWN"));
        lockdownSection.addView(gate(toggleRow("LOCK WHEN OVER THE LIMIT", false, inert()),
                "SCREEN TIME LOCKDOWN"));
        lockdownSection.addView(caption("MAKES THE DAILY LIMIT BINDING: OVER IT, ONLY YOUR "
                + "EMERGENCY APPS OPEN."));
    }

    // ---- PERMISSIONS -----------------------------------------------------

    private void rebuildPermissionsSection() {
        permSection.removeAllViews();
        if (palette == null) return;

        permSection.addView(sectionHeader("PERMISSIONS"));

        int gap = Math.round(metrics.cqw(2.5f));
        View locationRow = permissionRow("LOCATION", locationGranted,
                () -> { if (permissionListener != null) permissionListener.onRequestLocation(); });
        addTopMargin(locationRow, gap);
        permSection.addView(locationRow);

        View usageRow = permissionRow("USAGE ACCESS", usageGranted,
                () -> { if (permissionListener != null) permissionListener.onOpenUsageAccessSettings(); });
        addTopMargin(usageRow, gap);
        permSection.addView(usageRow);

        // Two states since V9 §10 retired the device admin and with it the
        // "PIN ONLY" middle state: both surviving routes leave the fingerprint
        // working, so a route that locks at all is a route that is done.
        View lockRow = permissionRow("DEVICE LOCK", lockRoute.settled(),
                lockRoute.status(), lockRoute.status(),
                () -> { if (permissionListener != null) permissionListener.onEnableDeviceLock(); });
        addTopMargin(lockRow, gap);
        permSection.addView(lockRow);

        View shadeRow = permissionRow("NOTIFICATION SHADE", shadeServiceEnabled, "ON", "ENABLE",
                () -> { if (permissionListener != null) permissionListener.onEnableNotificationShade(); });
        addTopMargin(shadeRow, gap);
        permSection.addView(shadeRow);

        // Three states again, same shape as DEVICE LOCK: off (never opted
        // in), on-but-not-permitted (opted in, needs (re-)pairing — the
        // normal state after a reboot on an unrooted device), and on.
        boolean shizukuGranted = shizukuLockEnabled && shizukuLockPermitted;
        String shizukuText = !shizukuLockEnabled ? "ENABLE" : "GRANT";
        View shizukuRow = permissionRow("SHIZUKU LOCK", shizukuGranted, "ON", shizukuText,
                () -> { if (permissionListener != null) permissionListener.onEnableShizukuLock(); });
        addTopMargin(shizukuRow, gap);
        permSection.addView(shizukuRow);

        // Always tappable, unlike the rows above: re-picking your home app is
        // a thing people want to do while already the default, and there is no
        // harm in opening the screen that shows it.
        View defaultRow = permissionRow("DEFAULT LAUNCHER", isDefaultLauncher, "DEFAULT", "SET", true,
                () -> { if (permissionListener != null) permissionListener.onSetDefaultLauncher(); });
        addTopMargin(defaultRow, gap);
        permSection.addView(defaultRow);

        TextView caption = new TextView(getContext());
        caption.setText("LOCATION IS FOR WEATHER, USAGE ACCESS FOR SCREEN TIME. "
                + "BOTH OPTIONAL.\n\n"
                + "DEVICE LOCK (DOUBLE-TAP HOME) AND THE SHADE SWIPE SHARE ONE "
                + "ACCESSIBILITY SWITCH. IT READS NOTHING.\n\n"
                + "SHIZUKU LOCK AVOIDS ACCESSIBILITY BUT NEEDS THE SHIZUKU APP, "
                + "RE-PAIRED AFTER EACH REBOOT. GRANT MEANS THE PAIRING LAPSED.");
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setTextColor(palette.aText());
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        addTopMargin(caption, Math.round(metrics.cqw(3f)));
        permSection.addView(caption);
    }

    private View permissionRow(String label, boolean granted, Runnable onFix) {
        return permissionRow(label, granted, "GRANTED", "FIX", onFix);
    }

    /** {@code grantedText}/{@code fixText} generalize this beyond runtime
     *  permissions — DEVICE LOCK, NOTIFICATION SHADE and DEFAULT LAUNCHER read
     *  "ON"/"ENABLE" and "DEFAULT"/"SET" through the same row rather than
     *  "GRANTED"/"FIX". */
    private View permissionRow(String label, boolean granted,
                                String grantedText, String fixText, Runnable onFix) {
        return permissionRow(label, granted, grantedText, fixText, false, onFix);
    }

    /**
     * {@code tappableWhenGranted} keeps the action live even once the thing is
     * done — DEFAULT LAUNCHER wants that, a granted runtime permission does not.
     *
     * <p>The whole row carries the click, not just the status word. The status
     * word alone is four to seven monospace characters, well under the 48dp
     * minimum target, which made these actions feel broken even where the
     * intent behind them was sound.
     */
    private View permissionRow(String label, boolean granted,
                                String grantedText, String fixText,
                                boolean tappableWhenGranted, Runnable onFix) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int touchPad = Math.round(metrics.cqw(2f));
        row.setPadding(0, touchPad, 0, touchPad);

        TextView labelView = new TextView(getContext());
        labelView.setText(label);
        labelView.setTypeface(Typeface.MONOSPACE);
        labelView.setTextColor(palette.ink);
        labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(getContext());
        status.setText(granted ? grantedText : fixText);
        status.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        status.setTextColor(granted ? palette.pText() : palette.aText());
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        row.addView(status);

        // Deliberately not marked no-swipe: LauncherRoot never intercepts a
        // tap, only a drag past 12dp, so the click is safe, and leaving the
        // row open means a sideways swipe across it still closes the panel.
        if (!granted || tappableWhenGranted) row.setOnClickListener(v -> onFix.run());

        return row;
    }

    // ---- shared row builders -----------------------------------------

    private TextView sectionHeader(String title) {
        TextView t = new TextView(getContext());
        t.setText(title);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setAllCaps(true);
        t.setTextColor(palette.pText());
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ACTION_CQW, DrawerPanel.SIZE_ACTION_MIN));
        return t;
    }

    /** One tappable value out of {@code values}, highlighted when it matches {@code current}. */
    private LinearLayout singleSelectRow(String[] labels, String[] values, String current,
                                          Consumer<String> onSelect) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        int gap = Math.round(metrics.cqw(2.5f));
        for (int i = 0; i < labels.length; i++) {
            boolean selected = values[i].equals(current);
            String value = values[i];
            View chip = buildChip(labels[i], selected, () -> onSelect.accept(value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = gap;
            row.addView(chip, lp);
        }
        return row;
    }

    private View buildChip(String label, boolean selected, Runnable onClick) {
        TextView chip = new TextView(getContext());
        chip.setText(label);
        chip.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        chip.setAllCaps(true);
        chip.setTextColor(selected ? palette.p : palette.ink);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_TAB_CQW, DrawerPanel.SIZE_TAB_MIN));
        int padH = Math.round(metrics.cqw(3f));
        int padV = Math.round(metrics.cqw(1.8f));
        chip.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(metrics.cqw(1f));
        bg.setStroke(Math.max(1, Math.round(metrics.cqw(0.6f))), selected ? palette.p : palette.ink);
        bg.setColor(selected ? ((0x2E << 24) | (palette.p & 0x00FFFFFF)) : 0x00000000);
        chip.setBackground(bg);
        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    private LinearLayout toggleRow(String label, boolean checked, Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(getContext());
        labelView.setText(label);
        labelView.setTypeface(Typeface.MONOSPACE);
        labelView.setAllCaps(true);
        labelView.setTextColor(palette.ink);
        labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_ROW_CQW, DrawerPanel.SIZE_ROW_MIN));
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        PixelToggle toggle = new PixelToggle(getContext(), metrics);
        toggle.setPalette(palette);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(onChange);
        row.addView(toggle);

        return row;
    }

    private void addGrid(LinearLayout parent, List<View> cards, int columns) {
        int gap = Math.round(metrics.cqw(3f));
        for (int i = 0; i < cards.size(); i += columns) {
            LinearLayout gridRow = new LinearLayout(getContext());
            gridRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowLp.topMargin = gap;
            gridRow.setLayoutParams(rowLp);
            for (int c = 0; c < columns; c++) {
                int idx = i + c;
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) cellLp.leftMargin = gap;
                if (idx < cards.size()) gridRow.addView(cards.get(idx), cellLp);
                else gridRow.addView(new View(getContext()), cellLp);
            }
            parent.addView(gridRow);
        }
    }

    private void addTopMargin(View v, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (v.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams existing = (LinearLayout.LayoutParams) v.getLayoutParams();
            existing.topMargin = margin;
            v.setLayoutParams(existing);
            return;
        }
        lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
        lp.topMargin = margin;
        v.setLayoutParams(lp);
    }

    /** Current time as a decimal hour, matching HomeActivity's own helper. */
    private float decimalHour() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY)
                + c.get(Calendar.MINUTE) / 60f
                + c.get(Calendar.SECOND) / 3600f;
    }
}
