package com.retro.launcher.core;

/**
 * Keeps text legible against the background it is drawn on.
 *
 * <h3>The problem this exists for</h3>
 * The ten palettes in {@link Palettes} were authored as *palettes* — six
 * colours that have to sit next to each other and look like one machine — and
 * two of those roles double as text. That works in dark mode and does not
 * work in light mode, because the roles are not mirror images of each other:
 * the dark sets put {@code a} and {@code p} *above* {@code bg} in luminance
 * and the light sets put them barely below it. Measured as WCAG contrast
 * against their own background:
 *
 * <pre>
 *            a      p            a      p
 *   GB light 1.11   1.51   dark 13.92   7.12
 *   AM light 1.10   1.86   dark 14.13  10.59
 *   C64light 1.24   3.04   dark 11.94   4.85
 *   MONO l.  1.31   2.49   dark 11.71   7.38
 *   PLASMA   1.58   3.10   dark  9.69   6.00
 * </pre>
 *
 * Every light figure is below AA's 4.5, and the first two are below 2, which
 * is the point at which text stops being dim and starts being absent. The
 * dark figures are all fine. So this is not a palette that needs rebalancing
 * — it is light mode specifically, and only where a role is used as text.
 *
 * <h3>Why the fix is not "darken the palette"</h3>
 * {@code a} and {@code p} are load-bearing as *fills*: the weather dot, the
 * over-limit marker, the usage bars, the widget and dock borders, the toggle
 * track, and — through {@link Palette#ramp()} — every quantized icon and
 * every converted wallpaper pixel. Darkening the stored colour would repaint
 * all of that and invalidate the icon cache, to fix text. So the stored roles
 * do not move; {@link Palette} derives a *separate* colour for the text case
 * and leaves the fills reading exactly as they did.
 *
 * <h3>Why it blends toward a palette colour rather than toward black</h3>
 * The obvious darkening is multiplicative — scale RGB down until the ratio is
 * met — and it ruins these palettes. A pale tint is pale because all three
 * channels are high, so scaling them keeps their ratios and lands on grey:
 * Game Boy's {@code #eaf8a8} becomes {@code #636947}, a muddy olive with none
 * of the green left. Blending toward the palette's own {@code s} shadow
 * instead — the deep, saturated end of the same hue family, already in the
 * set and already chosen by hand — lands on {@code #556a3b}, which still
 * reads as Game Boy green. Same for the rest: Plasma's secondary goes to
 * {@code #aa423e} rather than {@code #8b5449}.
 *
 * <p>The anchor is {@code s} on a light background and {@code h} on a dark
 * one, which is the general rule: blend toward whichever end of the palette
 * is further from the background. In practice the dark half never blends at
 * all — every dark-mode figure above already clears the floor — so this is
 * symmetric by construction rather than by a light-mode special case.
 */
public final class Contrast {

    private Contrast() {}

    /** WCAG AA for body text. The launcher's secondary lines are 10–13sp,
     *  which is not "large text", so the 3.0 bar does not apply to them. */
    public static final float AA = 4.5f;

    /**
     * Relative luminance, WCAG 2.x — the gamma-expanded one, not
     * {@link Quantize#luminance}'s perceptual byte. The two are not
     * interchangeable and neither can replace the other: Quantize's is a
     * cheap 0–255 sort key for the ramp and has to stay exactly as it is,
     * because the icon cache is keyed on the ramp order it produces.
     */
    public static double luminance(int argb) {
        return 0.2126 * channel((argb >> 16) & 0xFF)
             + 0.7152 * channel((argb >> 8) & 0xFF)
             + 0.0722 * channel(argb & 0xFF);
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
    public static double ratio(int fg, int bg) {
        double a = luminance(fg), b = luminance(bg);
        if (a < b) { double t = a; a = b; b = t; }
        return (a + 0.05) / (b + 0.05);
    }

    /**
     * {@code fg} if it already clears {@code target} against {@code bg},
     * otherwise the first blend of {@code fg} toward {@code anchor} that
     * does.
     *
     * <p>Stepped in 32nds rather than solved for, because contrast is not
     * monotonic in the blend parameter for every possible pair — an anchor on
     * the same side of the background as {@code fg} would dip before it
     * climbs — and a search that walks from {@code fg} outward returns the
     * *closest* colour that works either way. Thirty-two steps is under 0.5%
     * of the ratio's range and the whole thing runs ten times at class-init,
     * so there is nothing to optimise.
     *
     * @param anchor the palette end to blend toward; returned outright if
     *               even it cannot reach the target, which is the best this
     *               can do without leaving the palette
     */
    public static int legible(int fg, int bg, int anchor, float target) {
        if (ratio(fg, bg) >= target) return fg;
        for (int i = 1; i <= 32; i++) {
            int c = mix(fg, anchor, i / 32f);
            if (ratio(c, bg) >= target) return c;
        }
        return anchor;
    }

    /** Per-channel linear blend, alpha taken from {@code from}. */
    static int mix(int from, int to, float t) {
        int out = from & 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int a = (from >> shift) & 0xFF, b = (to >> shift) & 0xFF;
            out |= (Math.round(a + (b - a) * t) & 0xFF) << shift;
        }
        return out;
    }
}
