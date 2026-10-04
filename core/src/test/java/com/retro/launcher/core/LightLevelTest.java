package com.retro.launcher.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 2.5.1's auto theme. This decides whether the whole launcher is light or
 * dark, so its failure mode is not subtle — but the ways it can be wrong are:
 * a rule that flips twice across one sunset, one that darkens the screen at
 * noon because a reading is missing, or one where the cloud term can never
 * actually reach the threshold and the feature silently does nothing.
 */
public class LightLevelTest {

    /** SkyRenderer.sunAlt values. 0 is sunrise and sunset exactly. */
    private static final float NOON = 1f, MIDNIGHT = -1f, HORIZON = 0f;
    private static final float CLEAR = 0f, OVERCAST = 1f, UNKNOWN = Float.NaN;

    // ---- the headline rule ------------------------------------------------

    @Test public void aClearDayIsLight() {
        assertFalse(LightLevel.isDark(NOON, CLEAR));
    }

    @Test public void nightIsDark() {
        assertTrue(LightLevel.isDark(MIDNIGHT, CLEAR));
    }

    @Test public void aThoroughlyOvercastDayIsDark() {
        // The clause that makes the cloud term worth having at all.
        assertTrue(LightLevel.isDark(NOON, OVERCAST));
    }

    @Test public void anOvercastNightIsStillDark() {
        assertTrue(LightLevel.isDark(MIDNIGHT, OVERCAST));
    }

    // ---- the fail-safe direction -----------------------------------------

    @Test public void aMissingCloudReadingNeverDarkensTheDay() {
        // No network, no location, no fix yet. Reading that as "overcast"
        // would turn every offline launch at noon dark.
        assertFalse(LightLevel.isDark(NOON, UNKNOWN));
        assertEquals(LightLevel.score(NOON, CLEAR), LightLevel.score(NOON, UNKNOWN), 0f);
    }

    @Test public void aMissingSunAltitudeReadsAsFullDaylight() {
        assertFalse(LightLevel.isDark(Float.NaN, CLEAR));
        assertFalse(LightLevel.isDark(Float.NaN, OVERCAST));
    }

    @Test public void cloudCoverOutOfRangeIsClampedNotTrusted() {
        assertEquals(LightLevel.score(NOON, OVERCAST), LightLevel.score(NOON, 4f), 1e-6f);
        assertEquals(LightLevel.score(NOON, CLEAR), LightLevel.score(NOON, -3f), 1e-6f);
    }

    // ---- monotonicity: the property a two-rule design would not have ------

    @Test public void moreCloudNeverMakesItLighter() {
        for (int alt = -10; alt <= 10; alt++) {
            float previous = Float.MAX_VALUE;
            for (int c = 0; c <= 100; c++) {
                float s = LightLevel.score(alt / 10f, c / 100f);
                assertTrue("cloud " + c + "% at alt " + alt + " raised the score",
                        s <= previous + 1e-6f);
                previous = s;
            }
        }
    }

    @Test public void aHigherSunNeverMakesItDarker() {
        for (int c = 0; c <= 10; c++) {
            float previous = -1f;
            for (int alt = -10; alt <= 10; alt++) {
                float s = LightLevel.score(alt / 10f, c / 10f);
                assertTrue("alt " + alt + " lowered the score", s >= previous - 1e-6f);
                previous = s;
            }
        }
    }

    @Test public void theScoreIsAlwaysInRange() {
        for (int alt = -20; alt <= 20; alt++) {
            for (int c = -2; c <= 12; c++) {
                float s = LightLevel.score(alt / 10f, c / 10f);
                assertFalse(Float.isNaN(s));
                assertTrue("alt " + alt + " cloud " + c + " -> " + s, s >= 0f && s <= 1f);
            }
        }
    }

    // ---- it flips exactly once ------------------------------------------

    @Test public void theThemeFlipsOnceAcrossAClearDay() {
        // A rule that oscillated would strobe the whole UI. Walk a full
        // synthetic day and count the transitions: dark, light, dark.
        int flips = 0;
        boolean previous = LightLevel.isDark(SkyRenderer.sunAlt(0f), CLEAR);
        assertTrue("midnight must start dark", previous);
        for (int i = 1; i <= 24 * 60; i++) {
            boolean dark = LightLevel.isDark(SkyRenderer.sunAlt(i / 60f), CLEAR);
            if (dark != previous) flips++;
            previous = dark;
        }
        assertEquals("the theme must change exactly twice a day", 2, flips);
        assertTrue("and end the day dark", previous);
    }

    @Test public void theClearSkyFlipLandsNearTheHorizonNotHoursOff() {
        // The whole point of using sun altitude instead of a clock time: the
        // switch has to coincide with the sky the user is looking at.
        float lastDark = Float.NaN, firstLight = Float.NaN;
        for (int i = 0; i <= 24 * 60; i++) {
            float hour = i / 60f;
            float alt = SkyRenderer.sunAlt(hour);
            if (LightLevel.isDark(alt, CLEAR)) { if (Float.isNaN(firstLight)) lastDark = hour; }
            else if (Float.isNaN(firstLight)) firstLight = hour;
        }
        // SolarClock anchors sunrise at 6.2. Within twenty minutes of it.
        assertEquals(SolarClock.SUNRISE_ANCHOR, firstLight, 0.34f);
        assertTrue(lastDark < firstLight);
    }

    @Test public void cloudPullsTheEveningFlipEarlier() {
        float clearFlip = flipHour(CLEAR), cloudyFlip = flipHour(0.7f);
        assertTrue("cloud must not delay dusk: clear " + clearFlip + " cloudy " + cloudyFlip,
                cloudyFlip < clearFlip);
    }

    /** The hour at which the afternoon turns dark under this much cloud. */
    private static float flipHour(float cover) {
        for (int i = 12 * 60; i <= 24 * 60; i++) {
            float hour = i / 60f;
            if (LightLevel.isDark(SkyRenderer.sunAlt(hour), cover)) return hour;
        }
        return 24f;
    }

    // ---- the tipping point, which Settings shows the user ----------------

    @Test public void theTippingPointIsReachableAtNoon() {
        // If this were above 1 the cloud rule could never fire in daylight
        // and the feature would be decoration.
        float tip = LightLevel.cloudTippingPoint(NOON);
        assertFalse("no amount of cloud darkens noon", Float.isNaN(tip));
        assertTrue("tipping point out of range: " + tip, tip > 0f && tip < 1f);
    }

    @Test public void theTippingPointAgreesWithTheRuleItDescribes() {
        for (int alt = -10; alt <= 10; alt++) {
            float a = alt / 10f;
            float tip = LightLevel.cloudTippingPoint(a);
            if (Float.isNaN(tip)) {
                assertTrue("said 'already dark' but a clear sky is light at alt " + a,
                        LightLevel.isDark(a, CLEAR));
                continue;
            }
            assertFalse("dark just below its own tipping point at alt " + a,
                    LightLevel.isDark(a, Math.max(0f, tip - 0.02f)));
            assertTrue("not dark just above its own tipping point at alt " + a,
                    LightLevel.isDark(a, Math.min(1f, tip + 0.02f)));
        }
    }

    @Test public void thereIsNoTippingPointWhenItIsAlreadyDark() {
        assertTrue(Float.isNaN(LightLevel.cloudTippingPoint(MIDNIGHT)));
    }

    @Test public void aLowerSunNeedsLessCloudToTipOver() {
        float high = LightLevel.cloudTippingPoint(NOON);
        float low = LightLevel.cloudTippingPoint(0.25f);
        assertFalse(Float.isNaN(low));
        assertTrue("a low sun should tip on less cloud: " + low + " vs " + high, low < high);
    }
}
