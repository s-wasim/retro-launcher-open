package com.retro.launcher.core;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Every palette, in both themes, held to the properties the rest of the app
 * silently assumes.
 *
 * <p>Nothing here tests a colour for being pretty. These are the structural
 * facts other code depends on: that a ramp is usable by the quantizer, that
 * ids are unique because they go into the icon cache key, that ink is visible
 * against its own background. A palette that violated any of them would look
 * broken rather than merely wrong, and only on whichever palette nobody tried.
 */
public class PaletteIntegrityTest {

    private static Palette[] all() {
        Palette[] out = new Palette[Palettes.IDS.length * 2];
        int i = 0;
        for (String id : Palettes.IDS) {
            out[i++] = Palettes.get(id, false);
            out[i++] = Palettes.get(id, true);
        }
        return out;
    }

    @Test public void everyDeclaredIdResolves() {
        for (String id : Palettes.IDS) {
            assertNotNull(id, Palettes.get(id, false));
            assertNotNull(id, Palettes.get(id, true));
        }
    }

    @Test public void idsAreUniqueBecauseTheIconCacheKeysOnThem() {
        Set<String> seen = new HashSet<>();
        for (String id : Palettes.IDS) {
            assertTrue("duplicate palette id: " + id, seen.add(id));
        }
        assertEquals(Palettes.IDS.length, seen.size());
    }

    @Test public void aResolvedPaletteReportsTheIdItWasAskedFor() {
        // IconCacheKey stores palette.id; if it disagreed with the request,
        // two palettes would share cache entries.
        for (String id : Palettes.IDS) {
            assertEquals(id, Palettes.get(id, false).id);
            assertEquals(id, Palettes.get(id, true).id);
        }
    }

    @Test public void darkAndLightAreActuallyDifferent() {
        for (String id : Palettes.IDS) {
            Palette light = Palettes.get(id, false);
            Palette dark = Palettes.get(id, true);
            assertFalse(id, light.dark);
            assertTrue(id, dark.dark);
            assertNotEquals("light and dark " + id + " share a background",
                    light.bg, dark.bg);
        }
    }

    @Test public void everyRampIsUsableByTheQuantizer() {
        // Quantize.nearestIndex indexes into this; an empty ramp is an
        // ArrayIndexOutOfBounds on the first icon converted.
        for (Palette p : all()) {
            int[] ramp = p.ramp();
            assertNotNull(p.id, ramp);
            assertTrue(p.id + " has an empty ramp", ramp.length > 0);
        }
    }

    @Test public void everyRampEntryIsOpaque() {
        // The converted icon reapplies the source alpha over the ramp colour,
        // so a ramp entry carrying its own alpha would double-apply it.
        for (Palette p : all()) {
            for (int c : p.ramp()) {
                assertEquals(p.id + " ramp entry is not opaque: " + Integer.toHexString(c),
                        0xFF, (c >>> 24) & 0xFF);
            }
        }
    }

    @Test public void everyRoleColourIsOpaque() {
        for (Palette p : all()) {
            for (int c : new int[]{ p.bg, p.tile, p.p, p.a, p.s, p.h, p.ink }) {
                assertEquals(p.id, 0xFF, (c >>> 24) & 0xFF);
            }
        }
    }

    @Test public void theVeilIsTranslucentSoTheWallpaperShowsThrough() {
        // The clock and the panels sit on veil(); fully opaque would hide the
        // sky the whole app is built around.
        for (Palette p : all()) {
            int alpha = (p.veil() >>> 24) & 0xFF;
            assertTrue(p.id + " veil is opaque", alpha < 0xFF);
            assertTrue(p.id + " veil is invisible", alpha > 0);
        }
    }

    @Test public void inkIsReadableAgainstItsOwnBackground() {
        for (Palette p : all()) {
            assertTrue(p.id + ": ink is indistinguishable from bg",
                    luminanceGap(p.ink, p.bg) > 40);
        }
    }

    @Test public void theRampSpansARealRange() {
        // A ramp whose entries are all the same colour quantizes every icon
        // to a flat square.
        for (Palette p : all()) {
            int[] ramp = p.ramp();
            int min = 255, max = 0;
            for (int c : ramp) {
                int l = luminance(c);
                min = Math.min(min, l);
                max = Math.max(max, l);
            }
            assertTrue(p.id + " ramp is flat (" + min + ".." + max + ")", max - min > 30);
        }
    }

    @Test public void resolvingIsDeterministic() {
        for (String id : Palettes.IDS) {
            Palette a = Palettes.get(id, true), b = Palettes.get(id, true);
            assertEquals(id, a.bg, b.bg);
            assertEquals(id, a.ink, b.ink);
            assertArrayEquals(id, a.ramp(), b.ramp());
        }
    }

    @Test public void anUnknownIdStillYieldsAUsablePalette() {
        // Prefs can hold an id from a build that had more palettes than this
        // one. A null here is a crash on the first frame.
        Palette p = Palettes.get("no-such-palette", false);
        assertNotNull(p);
        assertTrue(p.ramp().length > 0);
    }

    @Test public void theAutoPaletteCoversEveryHourOfTheDay() {
        for (int i = 0; i < 24 * 4; i++) {
            float hour = i / 4f;
            String id = PaletteResolver.autoIdFor(hour);
            assertNotNull("hour " + hour, id);
            assertNotNull("hour " + hour + " -> " + id, Palettes.get(id, false));
            assertFalse("hour " + hour + " has no label",
                    PaletteResolver.autoLabelFor(hour).isEmpty());
        }
    }

    // ---- Bayer, which every quantized pixel goes through -------------------

    @Test public void theBayerMatrixIsFourByFour() {
        assertEquals(4, Bayer.M.length);
        for (int[] row : Bayer.M) assertEquals(4, row.length);
    }

    @Test public void theBayerMatrixIsAPermutationOfZeroToFifteen() {
        Set<Integer> seen = new HashSet<>();
        for (int[] row : Bayer.M) for (int v : row) seen.add(v);
        assertEquals("an ordered dither matrix must use each level once", 16, seen.size());
        for (int i = 0; i < 16; i++) assertTrue("missing level " + i, seen.contains(i));
    }

    @Test public void theBiasIsCentredAndBounded() {
        float min = 1f, max = -1f;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                float b = Bayer.bias(x, y);
                min = Math.min(min, b);
                max = Math.max(max, b);
            }
        }
        assertTrue("bias out of range: " + min + ".." + max, min >= -1f && max <= 1f);
        assertTrue("bias is one-sided", min < 0f && max > 0f);
    }

    @Test public void theBiasTilesEveryFourPixels() {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(Bayer.bias(x, y), Bayer.bias(x + 4, y), 0f);
                assertEquals(Bayer.bias(x, y), Bayer.bias(x, y + 4), 0f);
            }
        }
    }

    @Test public void theBiasHandlesNegativeCoordinates() {
        // px() can be called with an off-buffer coordinate during a bolt.
        for (int y = -8; y < 0; y++)
            for (int x = -8; x < 0; x++) {
                float b = Bayer.bias(x, y);
                assertFalse(Float.isNaN(b));
                assertTrue(b >= -1f && b <= 1f);
            }
    }

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return Math.round(0.2126f * r + 0.7152f * g + 0.0722f * b);
    }

    private static int luminanceGap(int a, int b) {
        return Math.abs(luminance(a) - luminance(b));
    }
}
