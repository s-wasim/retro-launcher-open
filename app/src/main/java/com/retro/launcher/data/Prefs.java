package com.retro.launcher.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.retro.launcher.core.DateFormatter;
import com.retro.launcher.core.PaletteResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The prototype's persisted keys, one for one, minus {@code seconds} and
 * {@code blink} — the clock's colon is always solid and never shows
 * seconds, a fixed design decision, not a preference (issue #6, 2026-08-28).
 * Deliberately absent: the current view, tab and sheet — the launcher
 * always reopens on home. See DESIGN_NOTES §8.
 *
 * Every getter falls back to its own default independently, so a corrupt dock
 * list cannot take the palette down with it.
 *
 * <p>The open-source build has no paid features. The palette and theme are
 * always the shipped AUTO defaults, and the date format is always one of the
 * presets; nothing else that used to be paid is stored at all.
 */
public final class Prefs {

    private static final String FILE = "retro-launcher-v1";

    public static final String K_HOUR12   = "hour12";
    public static final String K_FMT_IDX  = "fmtIdx";
    public static final String K_UNIT     = "unit";
    public static final String K_DOCK     = "dock";
    public static final String K_CATS     = "cats";
    public static final String K_MEMBERS  = "memberships";
    public static final String K_LIMIT    = "limit";
    public static final String K_HINT     = "hint";
    public static final String K_SHIZUKU = "shizukuLock";

    // Tier 5. The last good weather reading and the fix it was taken at, so a
    // cold start shows yesterday's number instead of "--°" while the first
    // fetch is still in flight.
    public static final String K_WX_TEMP  = "wxTemp";
    public static final String K_WX_LABEL = "wxLabel";
    public static final String K_WX_W     = "wxW";
    public static final String K_WX_AT    = "wxAt";
    public static final String K_WX_LAT   = "wxLat";
    public static final String K_WX_LON   = "wxLon";

    // Tier 6. Today's solar times, cached by epoch day so a SolarMath
    // recomputation or a cheap Prefs read both cost nothing on the common
    // "already have today's" path.
    public static final String K_SOL_EPOCH_DAY = "solEpochDay";
    public static final String K_SOL_SUNRISE   = "solSunrise";
    public static final String K_SOL_SUNSET    = "solSunset";
    public static final String K_SOL_TOMORROW  = "solTomorrowSunrise";

    // V9. The four independent weather channels, cached alongside K_WX_W so
    // a cold-start restore does not have to migrate from the old scalar.
    public static final String K_WX_CLOUD   = "wxCloud";
    public static final String K_WX_PRECIP  = "wxPrecip";
    public static final String K_WX_TYPE    = "wxType";
    public static final String K_WX_THUNDER = "wxThunder";
    public static final String K_WX_PROB    = "wxProb";

    // 2.3.1. The storm's 0-5 level, alongside the pre-existing boolean rather
    // than replacing it: a cache written by 2.2.1 or earlier has the boolean
    // and not this, and must still restore as a storm.
    public static final String K_WX_THUNDER_LVL = "wxThunderLvl";

    /** 3.0.1: the last screen-time reading and the local day it belongs to,
     *  so a cold start can show the over-limit state on its first frame
     *  instead of waiting on a UsageStatsManager query. See
     *  HomeActivity.refreshUsage. */
    public static final String K_USAGE_DAY   = "usageSnapDay";
    public static final String K_USAGE_MS    = "usageSnapMs";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Always the hour-following AUTO palette. */
    public String  palette()   { return PaletteResolver.AUTO; }
    /** Always TIME: light and dark follow the sky. */
    public String  theme()     { return PaletteResolver.TIME; }
    public boolean hour12()    { return sp.getBoolean(K_HOUR12, true); }
    /** One of the presets. An index outside them — including the CUSTOM row
     *  an older paid build could store — falls back to the first. */
    public int     fmtIdx()    {
        int stored = sp.getInt(K_FMT_IDX, 0);
        return stored < 0 || stored >= DateFormatter.PRESETS.length ? 0 : stored;
    }
    public String  unit()      { return sp.getString(K_UNIT, "C"); }
    public int     limit()     { return sp.getInt(K_LIMIT, 240); }
    public boolean hintShown() { return sp.getBoolean(K_HINT, false); }

    /** Default off: a second app to install and a pairing that must be
     *  redone after every reboot is a real cost, so the user opts in. */
    public boolean shizukuLockEnabled() { return sp.getBoolean(K_SHIZUKU, false); }

    /** Dock is stored as a newline-joined component list, max 5. */
    public List<String> dock() {
        String raw = sp.getString(K_DOCK, "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        while (out.size() > 5) out.remove(out.size() - 1);
        return out;
    }

    public void setDock(List<String> components) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < components.size() && i < 5; i++) {
            if (i > 0) b.append('\n');
            b.append(components.get(i));
        }
        sp.edit().putString(K_DOCK, b.toString()).apply();
    }

    public List<String> categories() {
        String raw = sp.getString(K_CATS, "SOCIAL\nWORK\nMEDIA\nUTILITY");
        return new ArrayList<>(Arrays.asList(raw.split("\n")));
    }

    public void setCategories(List<String> cats) {
        sp.edit().putString(K_CATS, String.join("\n", cats)).apply();
    }

    /**
     * User-assigned category overrides, one line per app:
     * {@code pkg/activity=CAT1,CAT2}. An app with no line here falls back to
     * its auto-assigned category (DESIGN_NOTES §9 delta 3).
     */
    public java.util.Map<String, List<String>> memberships() {
        java.util.Map<String, List<String>> out = new java.util.HashMap<>();
        String raw = sp.getString(K_MEMBERS, "");
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String component = line.substring(0, eq);
            String cats = line.substring(eq + 1);
            out.put(component, cats.isEmpty()
                    ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList(cats.split(","))));
        }
        return out;
    }

    public void setMembership(String component, List<String> cats) {
        java.util.Map<String, List<String>> all = memberships();
        all.put(component, cats);
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, List<String>> e : all.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(e.getKey()).append('=').append(String.join(",", e.getValue()));
        }
        sp.edit().putString(K_MEMBERS, b.toString()).apply();
    }

    public void putString(String key, String value)  { sp.edit().putString(key, value).apply(); }
    public void putBool(String key, boolean value)   { sp.edit().putBoolean(key, value).apply(); }
    public void putInt(String key, int value)        { sp.edit().putInt(key, value).apply(); }
    public void putLong(String key, long value)      { sp.edit().putLong(key, value).apply(); }
    public void putFloat(String key, float value)    { sp.edit().putFloat(key, value).apply(); }

    public String getString(String key, String fallback) { return sp.getString(key, fallback); }
    public int    getInt(String key, int fallback)       { return sp.getInt(key, fallback); }
    public long   getLong(String key, long fallback)     { return sp.getLong(key, fallback); }
    public float  getFloat(String key, float fallback)   { return sp.getFloat(key, fallback); }
    public boolean getBool(String key, boolean fallback) { return sp.getBoolean(key, fallback); }
}
