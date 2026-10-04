package com.retro.launcher.core;

/**
 * One resolved colour set. The prototype names six roles plus derived ink;
 * see DESIGN_NOTES §3 for the full table.
 */
public final class Palette {

    public final String id;
    public final String label;
    public final boolean dark;

    public final int bg;    // screen background
    public final int tile;  // icon body
    public final int p;     // primary accent — borders, active fills
    public final int a;     // secondary accent
    public final int s;     // shadow / recessed
    public final int h;     // highlight, near-white
    public final int ink;   // text

    /**
     * {@code a} and {@code p} as *text*, lifted to {@link Contrast#AA} where
     * the stored role does not reach it against {@code bg}. Derived once
     * here rather than at every paint: there are ten palettes, they are
     * immutable, and {@code Tint} would otherwise redo the search on every
     * view of every panel it walks.
     *
     * <p>Only the text case is adjusted. The stored {@code a} and {@code p}
     * keep painting fills, borders and the quantization ramp unchanged — see
     * {@link Contrast} for why that separation is the whole point.
     */
    private final int aText;
    private final int pText;
    private final int hText;

    Palette(String id, String label, boolean dark,
            int bg, int tile, int p, int a, int s, int h, int ink) {
        this.id = id; this.label = label; this.dark = dark;
        this.bg = bg; this.tile = tile; this.p = p;
        this.a = a; this.s = s; this.h = h; this.ink = ink;

        // Toward whichever end of the palette is further from the
        // background. Both dark-mode roles already clear the floor, so this
        // resolves to a no-op there without needing to ask whether it is
        // dark mode.
        int anchor = Contrast.luminance(bg) >= Contrast.luminance(s) ? s : h;
        this.aText = Contrast.legible(a, bg, anchor, Contrast.AA);
        this.pText = Contrast.legible(p, bg, anchor, Contrast.AA);
        this.hText = Contrast.legible(h, bg, anchor, Contrast.AA);
    }

    /**
     * The secondary accent, safe to draw as text on {@code bg} or
     * {@link #veil()}. Identical to {@code a} in every dark palette.
     *
     * <p>Use {@code a} directly only for a fill, or for text sitting on
     * {@code tile} rather than on the background — {@code tile} is dark in
     * every palette, so a role darkened for a light background would be
     * *less* legible there, not more.
     */
    public int aText() { return aText; }

    /** The primary accent as text, on the same terms as {@link #aText()}. */
    public int pText() { return pText; }

    /**
     * The highlight as text, on the same terms again — the worst of the three
     * in light mode, at 1.05–1.16 against {@code bg}, because {@code h} is
     * near-white by definition and a light background is too.
     *
     * <p>There is no way to keep it reading as a *highlight* on a light
     * background: nothing is brighter than the background to be bright
     * against. Emphasis there has to come from depth instead, which is what
     * blending toward {@code s} gives it.
     */
    public int hText() { return hText; }

    /**
     * The translucent background used by the clock widget, dock and scrubber.
     * The prototype writes it as an 8-digit CSS hex — bg plus alpha D9 in dark
     * and E0 in light. Android wants ARGB, so the alpha moves to the front.
     */
    public int veil() {
        int alpha = dark ? 0xD9 : 0xE0;
        return (alpha << 24) | (bg & 0x00FFFFFF);
    }

    /**
     * The six role colours (excluding {@code ink}), sorted ascending by
     * luminance — the ramp {@link Quantize} posterizes wallpaper pixels and
     * app icons against.
     */
    public int[] ramp() {
        int[] r = { bg, tile, p, a, s, h };
        for (int i = 1; i < r.length; i++) {
            int v = r[i], j = i - 1;
            while (j >= 0 && Quantize.luminance(r[j]) > Quantize.luminance(v)) {
                r[j + 1] = r[j];
                j--;
            }
            r[j + 1] = v;
        }
        return r;
    }
}
