package com.retro.launcher.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The {@link Weather} and {@link SkyConditions} value types, which carry
 * everything the wallpaper draws from.
 *
 * <p>Both grew a second constructor as features landed — thunder intensity in
 * 2.3.1 — and both keep the older boolean-taking form for callers with no
 * intensity to supply. That pairing is the risk these tests exist for: an
 * invariant that holds on one constructor and not the other is the kind of
 * bug that only shows on the code path nobody exercised.
 */
public class WeatherModelTest {

    private static Weather graded(int level) {
        return new Weather(20, "STORM", 0.9f, 0.5f, Precip.RAIN, 80, level);
    }

    // ---- thunder: the boolean and the level cannot disagree ---------------

    @Test public void theBooleanIsExactlyLevelAboveZero() {
        for (int level = 0; level <= ThunderIntensity.MAX; level++) {
            Weather w = graded(level);
            assertEquals("level " + level, level > 0, w.thunder);
        }
    }

    @Test public void theBooleanConstructorNeverMakesAStormThatRendersAsNothing() {
        // A storm with intensity 0 would set thunder true and then draw no
        // lightning at all — the worst of both.
        Weather w = new Weather(20, "STORM", 0.9f, 0.5f, Precip.RAIN, true, 80);
        assertTrue(w.thunder);
        assertTrue(w.thunderLevel > ThunderIntensity.NONE);
        assertTrue(w.thunderScalar() > 0f);
    }

    @Test public void theBooleanConstructorWithNoStormIsCompletelyQuiet() {
        Weather w = new Weather(20, "CLEAR", 0.1f, 0f, Precip.NONE, false, 0);
        assertFalse(w.thunder);
        assertEquals(ThunderIntensity.NONE, w.thunderLevel);
        assertEquals(0f, w.thunderScalar(), 0f);
    }

    @Test public void anOutOfRangeLevelIsClampedNotTrusted() {
        // Levels are persisted and outlive the code that wrote them.
        assertEquals(ThunderIntensity.MAX, graded(99).thunderLevel);
        assertEquals(ThunderIntensity.NONE, graded(-5).thunderLevel);
        assertFalse(graded(-5).thunder);
    }

    @Test public void theScalarStaysInRangeForEveryLevel() {
        for (int level = -3; level <= 12; level++) {
            float s = graded(level).thunderScalar();
            assertTrue("level " + level + " -> " + s, s >= 0f && s <= 1f);
        }
    }

    // ---- the derived scalar ----------------------------------------------

    @Test public void aThunderstormIsAlwaysTheTopOfTheScale() {
        assertEquals(1.0f, Weather.derive(0f, 0f, true), 0f);
        assertEquals(1.0f, Weather.derive(1f, 1f, true), 0f);
    }

    @Test public void dryAndWetRangesNeverOverlap() {
        // The V9 fix: a cloud-only sky must never cross into a rain label.
        for (int i = 0; i <= 100; i++) {
            float cover = i / 100f;
            assertTrue("cover " + cover + " reached the wet range",
                    Weather.derive(cover, 0f, false) <= 0.62f);
        }
        for (int i = 1; i <= 100; i++) {
            float precip = i / 100f;
            assertTrue("precip " + precip + " fell into the dry range",
                    Weather.derive(0f, precip, false) > 0.62f);
        }
    }

    @Test public void moreCloudNeverLowersTheScalar() {
        float previous = -1f;
        for (int i = 0; i <= 100; i++) {
            float w = Weather.derive(i / 100f, 0f, false);
            assertTrue(w >= previous);
            previous = w;
        }
    }

    @Test public void theScalarIsAlwaysInRange() {
        for (int c = 0; c <= 10; c++)
            for (int p = 0; p <= 10; p++)
                for (boolean t : new boolean[]{ true, false }) {
                    float w = Weather.derive(c / 10f, p / 10f, t);
                    assertTrue(w >= 0f && w <= 1f);
                }
    }

    // ---- temperature ------------------------------------------------------

    @Test public void fahrenheitConvertsAtTheKnownAnchors() {
        assertEquals(32, new Weather(0, "", 0f, 0f, Precip.NONE, false, 0).tempIn("F"));
        assertEquals(212, new Weather(100, "", 0f, 0f, Precip.NONE, false, 0).tempIn("F"));
        assertEquals(-40, new Weather(-40, "", 0f, 0f, Precip.NONE, false, 0).tempIn("F"));
    }

    @Test public void anUnknownUnitFallsBackToCelsius() {
        Weather w = new Weather(21, "", 0f, 0f, Precip.NONE, false, 0);
        assertEquals(21, w.tempIn("C"));
        assertEquals(21, w.tempIn("K"));
        assertEquals(21, w.tempIn(null));
        assertEquals(21, w.tempIn(""));
    }

    // ---- SkyConditions ----------------------------------------------------

    private static SkyConditions sky(float intensity) {
        return new SkyConditions(12f, 12f, Float.NaN, Float.NaN,
                0.5f, 0.2f, 0.5f, Precip.RAIN, 20, intensity);
    }

    @Test public void anyIntensityAboveZeroIsAStorm() {
        assertTrue(sky(0.01f).thunder);
        assertTrue(sky(1f).thunder);
        assertFalse(sky(0f).thunder);
    }

    @Test public void theBooleanFormAlwaysProducesADrawableStorm() {
        SkyConditions c = new SkyConditions(12f, 12f, Float.NaN, Float.NaN,
                0.5f, 0.2f, 0.5f, Precip.RAIN, true, 20);
        assertTrue(c.thunder);
        assertTrue("a storm that renders as nothing", c.thunderIntensity > 0f);
    }

    @Test public void theBooleanFormWithNoStormHasNoIntensity() {
        SkyConditions c = new SkyConditions(12f, 12f, Float.NaN, Float.NaN,
                0.5f, 0.2f, 0.5f, Precip.RAIN, false, 20);
        assertFalse(c.thunder);
        assertEquals(0f, c.thunderIntensity, 0f);
    }

    @Test public void intensityIsClampedIntoRange() {
        assertEquals(1f, sky(4f).thunderIntensity, 0f);
        assertEquals(0f, sky(-1f).thunderIntensity, 0f);
    }

    @Test public void everyFieldSurvivesTheConstructor() {
        SkyConditions c = new SkyConditions(7.5f, 8.25f, 3f, 19f,
                0.4f, 0.6f, 0.75f, Precip.SNOW, -4, 0.6f);
        assertEquals(7.5f, c.hour, 0f);
        assertEquals(8.25f, c.realHour, 0f);
        assertEquals(3f, c.moonriseHour, 0f);
        assertEquals(19f, c.moonsetHour, 0f);
        assertEquals(0.4f, c.cloudCover, 0f);
        assertEquals(0.6f, c.precip, 0f);
        assertEquals(0.75f, c.moonPhase, 0f);
        assertEquals(Precip.SNOW, c.type);
        assertEquals(-4, c.tempC);
        assertEquals(0.6f, c.thunderIntensity, 1e-6f);
    }
}
