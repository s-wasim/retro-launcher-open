package com.retro.launcher.core;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * One test per shipped release, pinning the single behaviour that release
 * exists for.
 *
 * <p>The per-class suites cover each feature in depth; this one is the
 * cross-cutting check that a later change has not quietly undone an earlier
 * one. It is deliberately organised by version rather than by class, because
 * that is the question being asked — "does everything built so far still
 * work?" — and because a regression here names the release it broke rather
 * than the file.
 *
 * <p>Only what {@code :core} can see. The app module's behaviour — where the
 * icons are written, when panels build, how often the ticker fires — needs an
 * Android runtime, and adding one would mean a test-framework dependency this
 * project does not carry (HANDOFF §0 row 4). Where a rule was worth testing,
 * 2.4.1 moved it into {@code :core} instead: {@link StaleGate} is the deferred
 * panel rule, {@link IconCacheKey} is the cache layout.
 */
public class ReleaseRegressionTest {

    // ---- 2.1.2 — icons cache at source resolution, keyed without size -----

    @Test public void v212_theIconCacheStillKeysWithoutPixelSize() {
        // The memory win: one entry per app, not one per size the drawer, the
        // dock and the sheet each happen to ask for. If a size creeps back
        // into the key, the 67 KB-per-icon problem returns with it.
        String a = IconCacheKey.key("com.x/.M", "gb", true, IconCacheKey.STAGE_ICON);
        String b = IconCacheKey.key("com.x/.M", "gb", true, IconCacheKey.STAGE_ICON);
        assertEquals(a, b);
        assertFalse(a.matches(".*\\d{2,}.*"));
    }

    @Test public void v212_theLetterTileIsStillTheOneStageKeyedPerSize() {
        assertNotEquals(
                IconCacheKey.sizedKey("com.x/.M", "gb", true, IconCacheKey.STAGE_LETTER, 96),
                IconCacheKey.sizedKey("com.x/.M", "gb", true, IconCacheKey.STAGE_LETTER, 130));
    }

    @Test public void v212_cacheFilenamesStillCannotEscapeTheirDirectory() {
        for (String component : new String[] {
                "../../etc/passwd", "com.x/../../y", "a/b/c", "com.x/.M@95" }) {
            String name = IconCacheKey.fileName(
                    IconCacheKey.key(component, "gb", true, IconCacheKey.STAGE_ICON));
            assertFalse(name, name.contains("/"));
            assertFalse(name, name.contains(".."));
        }
    }

    // ---- 2.1.3 — the sky is paced to the scene ----------------------------

    @Test public void v213_theSkyIsStillPacedByWhatIsMoving() {
        SkyConditions rain = new SkyConditions(12f, 12f, Float.NaN, Float.NaN,
                0.8f, 0.6f, 0.5f, Precip.RAIN, false, 12);
        SkyConditions clear = new SkyConditions(12f, 12f, Float.NaN, Float.NaN,
                0f, 0f, 0.5f, Precip.NONE, false, 20);
        assertEquals(FrameBudget.FAST_MS, FrameBudget.animationIntervalMs(rain, 108, 230));
        assertEquals(FrameBudget.SLOW_MS, FrameBudget.animationIntervalMs(clear, 108, 230));
        assertTrue("the slow rate must stay well below the old fixed 30fps",
                FrameBudget.SLOW_MS > 33L * 4);
    }

    @Test public void v213_nothingEverAsksForMoreFramesThanTheOldFixedRate() {
        // This change may only ever remove work.
        assertTrue(FrameBudget.FAST_MS >= 33L);
        assertTrue(FrameBudget.SLOW_MS > FrameBudget.FAST_MS);
        assertTrue(FrameBudget.IDLE_MS > FrameBudget.SLOW_MS);
    }

    @Test public void v213_aPanelOverTheSkyStillThrottlesIt() {
        SkyConditions rain = new SkyConditions(12f, 12f, Float.NaN, Float.NaN,
                0.8f, 0.6f, 0.5f, Precip.RAIN, false, 12);
        assertEquals(FrameBudget.OBSCURED_FLOOR_MS,
                FrameBudget.intervalMs(rain, 108, 230, true));
    }

    // ---- 2.2.1 — the clock text --------------------------------------------

    @Test public void v221_theClockStillWrapsTwelveHourTimeCorrectly() {
        assertEquals("12:00 AM", ClockText.time(0, 0, true));
        assertEquals("12:00 PM", ClockText.time(12, 0, true));
        assertEquals("00:00", ClockText.time(0, 0, false));
    }

    // ---- 2.3.1 — thunderstorms are graded ---------------------------------

    @Test public void v231_capeStillMayNotDeclareAStorm() {
        // The load-bearing negative rule. A hot humid afternoon reads 3000+
        // J/kg under a cloudless sky.
        for (float cape = 0f; cape <= 8000f; cape += 250f) {
            assertEquals("cape " + cape, ThunderIntensity.NONE,
                    ThunderIntensity.levelFor(false, 0, cape));
        }
    }

    @Test public void v231_theThreeThunderCodesStillGradeDifferently() {
        assertTrue(ThunderIntensity.codeFloor(95) < ThunderIntensity.codeFloor(96));
        assertTrue(ThunderIntensity.codeFloor(96) < ThunderIntensity.codeFloor(99));
    }

    @Test public void v231_aTextOnlyStormStillGetsARealNumber() {
        assertEquals(ThunderIntensity.LIGHT, ThunderIntensity.levelFor(true, 95, null));
        assertTrue(ThunderIntensity.scalar(ThunderIntensity.levelFor(true, 95, null)) > 0f);
    }

    // ---- 2.3.2 / 2.3.4 — the moon window ----------------------------------

    @Test public void v234_everyMoonWindowIsWholeAcrossAYear() {
        // 2.3.3 measured 25 moonless days a year before the window could
        // cross midnight, and 0 after; 2.3.4 then made the window an
        // instant-keyed up-period so a set can never precede its own rise.
        // A NaN end, or a set at or before the rise, draws no moon at all.
        String[] zones = { "Europe/Berlin", "Asia/Karachi", "America/New_York" };
        float[] lats = { 52.5f, 24.9f, 40.7f };
        float[] lons = { 13.4f, 67.0f, -74.0f };
        for (int z = 0; z < zones.length; z++) {
            ZoneId zone = ZoneId.of(zones[z]);
            LocalDate d = LocalDate.of(2026, 1, 1);
            for (int i = 0; i < 365; i++) {
                LocalDate day = d.plusDays(i);
                long noon = day.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli();
                LunarMath.LunarTimes t = LunarMath.moonWindow(lats[z], lons[z], noon, zone);
                if (t == null) continue;
                String where = zones[z] + " " + day;
                assertFalse(where + " has a NaN rise", Float.isNaN(t.moonriseHour));
                assertFalse(where + " has a NaN set", Float.isNaN(t.moonsetHour));
                assertTrue(where + " sets before it rises: "
                                + t.moonriseHour + ".." + t.moonsetHour,
                        t.moonsetHour > t.moonriseHour);
            }
        }
    }

    @Test public void v234_aKnownWindowIsAlwaysDrawableByBodyPath() {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 200; i++) {
            LocalDate day = d.plusDays(i);
            long noon = day.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli();
            LunarMath.LunarTimes t = LunarMath.moonWindow(52.5f, 13.4f, noon, zone);
            if (t == null) continue;
            // A moment just inside the window must map onto the arc, not NaN.
            float inside = t.moonriseHour + (t.moonsetHour - t.moonriseHour) * 0.5f;
            float pos = BodyPath.moonT(inside, t.moonriseHour, t.moonsetHour);
            assertFalse("moonT went NaN mid-window on " + day, Float.isNaN(pos));
            assertTrue("moonT out of range on " + day + ": " + pos,
                    pos >= 0f && pos <= 1f);
        }
    }

    // ---- 2.4.1 — cleanup, permanence, deferred panels ---------------------

    @Test public void v241_theIconCacheHasNoExpiryApiLeft() {
        // Permanence is the feature: a TTL coming back should break a test
        // before it reaches a device.
        for (java.lang.reflect.Method m : IconCacheKey.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            assertFalse(n, n.contains("fresh") || n.contains("expire") || n.contains("ttl"));
        }
    }

    @Test public void v241_everyEntryForAPackageSharesOneDirectory() {
        // What makes per-package invalidation a single directory delete.
        String dir = IconCacheKey.packageDirName("com.whatsapp");
        assertEquals(dir, IconCacheKey.packageDirName(
                IconCacheKey.packageOf("com.whatsapp/.Main")));
        assertEquals(dir, IconCacheKey.packageDirName(
                IconCacheKey.packageOf("com.whatsapp/.Other@95")));
    }

    @Test public void v241_deferredPanelsStillDeferAndStillCatchUp() {
        StaleGate gate = new StaleGate();
        assertFalse("onCreate must not build", gate.invalidate(false));
        assertTrue("the first show must build", gate.onVisibilityChanged(true));
        assertFalse("an unchanged second show must not", gate.onVisibilityChanged(true));
    }

    @Test public void v241_theDeadClassesAreStillGone() throws Exception {
        for (String gone : new String[] {
                "com.retro.launcher.icons.InstrumentedIconSource",
                "com.retro.launcher.icons.GeneratedTileIcons",
                "com.retro.launcher.icons.PosterizedIcons" }) {
            try {
                Class.forName(gone);
                fail(gone + " came back");
            } catch (ClassNotFoundException expected) {
                // good
            }
        }
    }

    // ---- 2.5.1 — the sky decides the theme ---------------------------------

    @Test public void v251_theAutoThemeIsStillDrivenByTheSkyAndNotTheClock() {
        // The release's headline change. If this ever reads a clock hour
        // again, the screen stops going dark when the drawn sun sets.
        assertFalse(PaletteResolver.darkFor(PaletteResolver.TIME, 1f, 0f));
        assertTrue(PaletteResolver.darkFor(PaletteResolver.TIME, -1f, 0f));
    }

    @Test public void v251_cloudStillDarkensBroadDaylight() {
        // The other half of the rule, and the half that silently does nothing
        // if the cloud weight and the threshold drift apart.
        assertFalse(LightLevel.isDark(1f, 0.2f));
        assertTrue(LightLevel.isDark(1f, 1f));
    }

    @Test public void v251_aMissingWeatherReadingStillCannotDarkenNoon() {
        // The fail-safe direction: no network must never mean a dark screen
        // at midday.
        assertFalse(LightLevel.isDark(1f, Float.NaN));
    }

    @Test public void v251_theRetiredSystemThemeStillUpgradesRatherThanFallingThrough() {
        // Every install before 2.5.1 has "system" stored as its theme.
        for (float alt : new float[]{ -1f, -0.2f, 0.2f, 1f }) {
            assertEquals("alt " + alt,
                    PaletteResolver.darkFor(PaletteResolver.TIME, alt, 0f),
                    PaletteResolver.darkFor(PaletteResolver.SYSTEM, alt, 0f));
        }
    }

    @Test public void v251_theIconDitherIsStillExactlyWhatItWas() {
        // The icons' own lookup must be untouched, or every icon already on
        // disk disagrees with a freshly rendered one.
        int[] ramp = Palettes.get(Palettes.GB, true).ramp();
        for (int i = 0; i < 64; i++) {
            int argb = 0xFF000000 | (i * 2654435761L > 0 ? i * 37 : i * 41);
            assertEquals(Quantize.nearestIndex(argb, ramp, i & 7, i >> 3),
                    Quantize.nearestIndex(argb, ramp, i & 7, i >> 3, Quantize.ICON_DITHER));
        }
    }

    @Test public void v251_theMoonUpTestIsStillWrittenInExactlyOnePlace() {
        // BodyPath.isUp is the one place the rule lives. A second copy of it
        // is how a window crossing midnight gets handled correctly in one
        // place and not the other.
        assertTrue(BodyPath.isUp(0f));
        assertTrue(BodyPath.isUp(1f));
        assertTrue(BodyPath.isUp(0.5f));
        assertFalse(BodyPath.isUp(Float.NaN));
        assertFalse(BodyPath.isUp(-0.01f));
        assertFalse(BodyPath.isUp(1.01f));
    }

    // ---- 3.0.2 — screen time agrees with Digital Wellbeing ----------------

    @Test public void v302_aSecondScreenOfTheSameAppDoesNotEndItsTime() {
        // The undercount: old PAUSED -> new RESUMED -> old STOPPED, and the
        // old screen's stop ending the app. Ten minutes in the chat must be
        // ten minutes, not the 300 ms before it opened.
        long min = 60_000L;
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                new ForegroundSpans.Event("chat", "Home", ForegroundSpans.ACTIVITY_RESUMED, 0),
                new ForegroundSpans.Event("chat", "Home", ForegroundSpans.ACTIVITY_PAUSED, 300),
                new ForegroundSpans.Event("chat", "Chat", ForegroundSpans.ACTIVITY_RESUMED, 400),
                new ForegroundSpans.Event("chat", "Home", ForegroundSpans.ACTIVITY_STOPPED, 700),
                new ForegroundSpans.Event(null, ForegroundSpans.SCREEN_NON_INTERACTIVE, 10 * min)
        ), 0, 60 * min);
        long total = 0;
        for (UsageMath.Interval iv : UsageMath.union(UsageMath.intersect(r.apps, r.awake))) {
            total += iv.endMillis - iv.startMillis;
        }
        assertEquals(10 * min, total);
    }
}
