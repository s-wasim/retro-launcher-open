package com.retro.launcher.core;

/**
 * How much light there is outside, 0..1, from the sun's height and the cloud
 * cover — and therefore whether the launcher should be dark.
 *
 * <h3>Why one number instead of two rules</h3>
 * 2.5.1 changes the AUTO theme from "follow the system" to "follow the sky".
 * The obvious shape for that is two independent rules — a clock boundary at
 * dusk, plus a cloud-cover threshold that overrides it — but two rules means
 * two thresholds to tune against each other, and a hard edge at whichever
 * minute dusk is declared to fall on.
 *
 * <p>A single score avoids both. Thick cloud dims the score the same way a
 * setting sun does, so an overcast afternoon and a clear evening reach the
 * dark threshold by the same arithmetic rather than by two separate rules
 * that have to be kept consistent. There is no cloud "override": cloud is
 * simply one of the two things that make it dark.
 *
 * <h3>What the inputs are</h3>
 * {@code sunAltitude} is {@link SkyRenderer#sunAlt}'s proxy, not a real
 * elevation angle: 0 at sunrise and sunset, 1 at solar noon, -1 at solar
 * midnight. Feeding it the same {@link SolarClock}-warped hour the wallpaper
 * uses is what makes the theme flip in step with the sky the user is looking
 * at — the screen goes dark as the drawn sun sets, not a fixed number of
 * minutes off it.
 *
 * <p>{@code cloudCover} is {@link Weather}'s own 0..1 channel. {@code NaN}
 * (no reading yet, no network, no location) reads as a clear sky, so the
 * theme falls back to being purely solar rather than to something undefined.
 * That is deliberately the direction that fails safe: a missing reading
 * cannot darken the screen at noon.
 */
public final class LightLevel {

    private LightLevel() {}

    /**
     * Where the sun's contribution starts and finishes climbing. The band
     * straddles the horizon rather than starting at it, because light does
     * not stop when the disc touches the horizon and does not arrive the
     * instant it clears one.
     */
    private static final float DAY_FLOOR = -0.25f;
    private static final float DAY_CEIL  =  0.35f;

    /**
     * How much of the light a fully overcast sky takes away. Not 1: a heavy
     * overcast noon is dim, not night, and the remaining quarter is what
     * keeps {@link #isDark} from claiming otherwise.
     */
    private static final float CLOUD_WEIGHT = 0.75f;

    /**
     * Below this the launcher goes dark. Chosen so that under a clear sky the
     * flip lands within about ten minutes of sunrise and sunset — close
     * enough to read as "it went dark at dusk" — and so that it takes roughly
     * 83% cloud cover to reach it at solar noon.
     */
    public static final float DARK_BELOW = 0.38f;

    /**
     * @param sunAltitude {@link SkyRenderer#sunAlt}'s -1..1 proxy
     * @param cloudCover  0..1, or {@code NaN} for "unknown", which reads clear
     * @return ambient light, 0 (night) to 1 (clear solar noon)
     */
    public static float score(float sunAltitude, float cloudCover) {
        if (Float.isNaN(sunAltitude)) return 1f;
        float sun = SkyRenderer.smooth(DAY_FLOOR, DAY_CEIL, sunAltitude);
        float cloud = Float.isNaN(cloudCover) ? 0f : SkyRenderer.clamp01(cloudCover);
        return SkyRenderer.clamp01(sun * (1f - CLOUD_WEIGHT * cloud));
    }

    /** Whether the theme should be dark for this sky. */
    public static boolean isDark(float sunAltitude, float cloudCover) {
        return score(sunAltitude, cloudCover) < DARK_BELOW;
    }

    /**
     * The cloud cover at which this sun height tips into dark, or {@code NaN}
     * when it is already dark under a clear sky and no cloud is needed.
     *
     * <p>Exists for the Settings caption, which tells the user what the rule
     * is currently doing rather than making them infer it from a screen that
     * did or did not change colour.
     */
    public static float cloudTippingPoint(float sunAltitude) {
        float sun = SkyRenderer.smooth(DAY_FLOOR, DAY_CEIL, sunAltitude);
        if (sun < DARK_BELOW) return Float.NaN;             // already dark, clear sky
        // sun is a smoothstep, so it never exceeds 1 and this never exceeds
        // (1 - DARK_BELOW) / CLOUD_WEIGHT -- comfortably inside 0..1.
        return (1f - DARK_BELOW / sun) / CLOUD_WEIGHT;
    }
}
