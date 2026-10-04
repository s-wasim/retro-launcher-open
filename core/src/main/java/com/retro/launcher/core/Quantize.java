package com.retro.launcher.core;

/**
 * Posterizes a colour to the nearest entry in a luminance-sorted ramp, biased
 * by the shared {@link Bayer} matrix so flat regions still band instead of
 * banding-then-snapping to one flat tone. Used by the wallpaper's optional
 * palette tint and by {@code PosterizedIcons} — same ramp, same matrix, so
 * icons and wallpaper agree on what "this palette's grey" looks like.
 */
public final class Quantize {

    private Quantize() {}

    public static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return Math.round(0.299f * r + 0.587f * g + 0.114f * b);
    }

    /**
     * How far the Bayer matrix pushes a pixel's luminance before the ramp
     * lookup, in luminance units. Icons have always used this and must keep
     * using exactly this — the cached icon tree is keyed on the palette, not
     * on a dither setting, so a change here would silently make every stored
     * icon disagree with a freshly rendered one.
     */
    public static final float ICON_DITHER = 32f;

    /** Index into {@code ramp} nearest {@code argb}'s luminance, dithered by (x, y). */
    public static int nearestIndex(int argb, int[] ramp, int x, int y) {
        return nearestIndex(argb, ramp, x, y, ICON_DITHER);
    }

    /**
     * 2.5.1. The same lookup with the dither amount opened up, for the custom
     * wallpaper's tweak sliders. {@code 0} snaps every pixel to its nearest
     * ramp entry with no ordered dithering at all, which posterizes a photo
     * into flat bands; larger values trade banding for the classic 4x4
     * cross-hatch texture.
     *
     * @param ditherStrength luminance units of Bayer bias; 0 disables it
     */
    public static int nearestIndex(int argb, int[] ramp, int x, int y, float ditherStrength) {
        float bias = ditherStrength == 0f ? 0f : Bayer.bias(x, y) * ditherStrength;
        float lum = luminance(argb) + bias;

        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < ramp.length; i++) {
            float d = Math.abs(luminance(ramp[i]) - lum);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }
}
