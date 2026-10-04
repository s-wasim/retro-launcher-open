package com.retro.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;

/**
 * How a paid feature looks in the open-source build: the control itself,
 * dimmed but still legible, under a small {@code <PAID>} chip, and a touch
 * layer that answers any tap on it with a short notice.
 *
 * <p>Taps are intercepted rather than the child being disabled, because a
 * disabled toggle swallows the tap silently and a control that does nothing
 * with no explanation reads as broken. Drags are not taken:
 * {@link #onInterceptTouchEvent} claims the gesture, but the ScrollView and
 * {@link LauncherRoot} above still intercept a scroll or a panel swipe first,
 * exactly as they do for any other clickable row.
 *
 * <p>This is presentation only. There is no implementation behind any gated
 * control: the open-source build does not contain the paid features.
 */
public final class PaidGate extends FrameLayout {

    /** Low enough to read as unavailable, high enough to still read. */
    private static final float DIM = 0.38f;

    private static final String NOTICE = "NOT AVAILABLE IN THE OPEN-SOURCE VERSION";

    private PaidGate(Context c) { super(c); }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) { return true; }

    /**
     * Returns {@code row} wrapped in a gate. Takes over the row's own layout
     * params, so margins set by the caller survive the wrap.
     *
     * @param title       what the feature is called, for accessibility
     * @param chipGravity where the chip sits, e.g. {@code END|CENTER_VERTICAL}
     *                    for a one-line row, {@code END|TOP} for a card
     */
    public static View wrap(View row, String title, Metrics metrics, Palette palette,
                            int chipGravity) {
        Context c = row.getContext();

        PaidGate gate = new PaidGate(c);
        ViewGroup.LayoutParams original = row.getLayoutParams();
        if (original != null) gate.setLayoutParams(original);

        row.setAlpha(DIM);
        gate.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView chip = chip(c, metrics, palette);
        LayoutParams chipLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        chipLp.gravity = chipGravity;
        int inset = Math.round(metrics.cqw(1f));
        chipLp.setMargins(inset, inset, inset, inset);
        gate.addView(chip, chipLp);

        gate.setContentDescription(title + ", paid feature. Not available in the open-source version.");
        gate.setOnClickListener(v -> Toast.makeText(c, NOTICE, Toast.LENGTH_SHORT).show());
        return gate;
    }

    /** The banner itself. */
    private static TextView chip(Context c, Metrics metrics, Palette palette) {
        TextView chip = new TextView(c);
        chip.setText("<PAID>");
        chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                metrics.textPx(DrawerPanel.SIZE_CAPTION_CQW, DrawerPanel.SIZE_CAPTION_MIN));
        chip.setTextColor(palette.pText());
        int padH = Math.round(metrics.cqw(1.6f));
        int padV = Math.round(metrics.cqw(0.6f));
        chip.setPadding(padH, padV, padH, padV);
        chip.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.bg);
        bg.setStroke(Math.max(1, Math.round(metrics.cqw(0.5f))), palette.p);
        bg.setCornerRadius(metrics.cqw(1f));
        chip.setBackground(bg);
        chip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return chip;
    }
}
