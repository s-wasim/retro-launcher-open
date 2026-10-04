package com.retro.launcher.core;

/**
 * Turns (user choice, theme preference, clock, sky) into one Palette.
 *
 * The hour thresholds are the prototype's autoPal() verbatim — see
 * DESIGN_NOTES §2a. They are decimal hours, so 4.6 means 04:36, not 04:60.
 */
public final class PaletteResolver {

    private PaletteResolver() {}

    public static final String AUTO   = "auto";
    public static final String LIGHT  = "light";
    public static final String DARK   = "dark";

    /**
     * 2.5.1. AUTO light/dark now follows the sky rather than the OS: dark
     * from dusk through dawn, and dark by day too once cloud cover dims things
     * far enough. See {@link LightLevel}.
     */
    public static final String TIME = "time";

    /**
     * What AUTO meant before 2.5.1 — follow the OS night mode. Retired as a
     * choice, but kept as a constant because it is the value already sitting
     * in every existing install's prefs, written there as the default. It
     * resolves as {@link #TIME} rather than being ignored, so an upgrade
     * moves to the new behaviour without the user having to go and re-pick
     * the setting they never changed.
     */
    public static final String SYSTEM = "system";

    public static String autoIdFor(float hour) {
        if (hour < 4.6f)  return Palettes.C64;
        if (hour < 7.6f)  return Palettes.AMBER;
        if (hour < 11f)   return Palettes.GB;
        if (hour < 16f)   return Palettes.MONO;
        if (hour < 18.6f) return Palettes.AMBER;
        if (hour < 20.4f) return Palettes.PLASMA;
        return Palettes.C64;
    }

    /** The note shown on the AUTO / TIME card in Settings. */
    public static String autoLabelFor(float hour) {
        if (hour < 4.6f)  return "NIGHT";
        if (hour < 7.6f)  return "SUNRISE";
        if (hour < 11f)   return "MORNING";
        if (hour < 16f)   return "MIDDAY";
        if (hour < 18.6f) return "GOLDEN HOUR";
        if (hour < 20.4f) return "DUSK";
        return "NIGHT";
    }

    /**
     * Whether the palette should be its dark variant.
     *
     * @param theme        {@link #TIME}, {@link #LIGHT}, {@link #DARK}, or the
     *                     retired {@link #SYSTEM}, which reads as TIME
     * @param sunAltitude  {@link SkyRenderer#sunAlt}'s -1..1 proxy for the
     *                     warped hour the wallpaper is drawing
     * @param cloudCover   0..1, or NaN when there is no reading
     */
    public static boolean darkFor(String theme, float sunAltitude, float cloudCover) {
        if (DARK.equals(theme))  return true;
        if (LIGHT.equals(theme)) return false;
        return LightLevel.isDark(sunAltitude, cloudCover);
    }

    /**
     * @param hour real local hour, which picks the palette
     * @param sunAltitude / cloudCover the sky, which picks light or dark
     */
    public static Palette resolve(String choice, String theme,
                                  float hour, float sunAltitude, float cloudCover) {
        String id = (choice == null || AUTO.equals(choice))
                ? autoIdFor(hour) : choice;
        return Palettes.get(id, darkFor(theme, sunAltitude, cloudCover));
    }
}
