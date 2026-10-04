package com.retro.launcher.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The contrast floor, and the property it exists to guarantee: no palette, in
 * either theme, may hand out a text colour that cannot be read on its own
 * background.
 *
 * <p>This is the class of bug that only shows up on the palette nobody tried,
 * in the theme nobody uses, which is exactly why it is tested here rather
 * than looked at on a phone. Before 2.5.2 the five light palettes returned
 * secondary text at 1.10–1.58 against their own background — below the point
 * where text is visible at all — and every one of them looked fine in dark
 * mode.
 */
public class ContrastTest {

    private static Palette[] all() {
        Palette[] out = new Palette[Palettes.IDS.length * 2];
        int i = 0;
        for (String id : Palettes.IDS) {
            out[i++] = Palettes.get(id, false);
            out[i++] = Palettes.get(id, true);
        }
        return out;
    }

    // ---- the maths ---------------------------------------------------------

    @Test public void blackOnWhiteIsTheMaximumRatio() {
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01);
    }

    @Test public void aColourAgainstItselfIsTheMinimumRatio() {
        assertEquals(1.0, Contrast.ratio(0xFF7C70DA, 0xFF7C70DA), 0.0001);
    }

    @Test public void theRatioIsSymmetric() {
        assertEquals(Contrast.ratio(0xFFDCEBB4, 0xFF1B3311),
                     Contrast.ratio(0xFF1B3311, 0xFFDCEBB4), 0.0001);
    }

    @Test public void alphaIsIgnoredBecauseTheCallerHasAlreadyComposited() {
        assertEquals(Contrast.ratio(0xFF102030, 0xFFDCEBB4),
                     Contrast.ratio(0x11102030, 0xFFDCEBB4), 0.0001);
    }

    @Test public void luminanceIsMonotonicInGrey() {
        double prev = -1;
        for (int v = 0; v <= 255; v += 5) {
            double l = Contrast.luminance(0xFF000000 | (v << 16) | (v << 8) | v);
            assertTrue("luminance went backwards at " + v, l > prev);
            prev = l;
        }
    }

    @Test public void mixEndpointsAreTheInputs() {
        int from = 0xFFEAF8A8, to = 0xFF1B3311;
        assertEquals(from, Contrast.mix(from, to, 0f));
        assertEquals(to & 0x00FFFFFF, Contrast.mix(from, to, 1f) & 0x00FFFFFF);
    }

    @Test public void mixKeepsTheSourceAlpha() {
        assertEquals(0x80, (Contrast.mix(0x80EAF8A8, 0xFF1B3311, 0.5f) >>> 24) & 0xFF);
    }

    // ---- legible() ---------------------------------------------------------

    @Test public void aColourThatAlreadyPassesIsReturnedUntouched() {
        int fg = 0xFF1B3311, bg = 0xFFDCEBB4;          // ink on Game Boy light
        assertTrue(Contrast.ratio(fg, bg) >= Contrast.AA);
        assertEquals(fg, Contrast.legible(fg, bg, 0xFF000000, Contrast.AA));
    }

    @Test public void aColourThatFailsIsLiftedToTheTarget() {
        Palette gb = Palettes.get(Palettes.GB, false);
        assertTrue("precondition: the stored role fails",
                Contrast.ratio(gb.a, gb.bg) < Contrast.AA);
        assertTrue(Contrast.ratio(gb.aText(), gb.bg) >= Contrast.AA);
    }

    @Test public void theResultIsTheClosestBlendThatWorks() {
        // One step back toward the original must fail, or the search
        // overshot and the text is darker than it needs to be.
        Palette gb = Palettes.get(Palettes.GB, false);
        int lifted = gb.aText();
        for (float t = 0.05f; t <= 1f; t += 0.05f) {
            int softer = Contrast.mix(gb.a, lifted, 1f - t);
            if (Contrast.ratio(softer, gb.bg) >= Contrast.AA) continue;
            return;                                     // found the boundary
        }
        fail("every softer blend also passes; the search overshot");
    }

    @Test public void anAnchorThatCannotReachTheTargetIsReturnedAsTheBestAvailable() {
        // Blending white toward white can never gain contrast on white.
        assertEquals(0xFFFFFFFF,
                Contrast.legible(0xFFFEFEFE, 0xFFFFFFFF, 0xFFFFFFFF, Contrast.AA));
    }

    @Test public void legibleIsIdempotent() {
        for (Palette p : all()) {
            int anchor = Contrast.luminance(p.bg) >= Contrast.luminance(p.s) ? p.s : p.h;
            assertEquals(p.id + "/" + p.dark,
                    p.aText(), Contrast.legible(p.aText(), p.bg, anchor, Contrast.AA));
        }
    }

    // ---- the guarantee over the real palettes -------------------------------

    @Test public void everyTextColourClearsAAAgainstItsOwnBackground() {
        for (Palette p : all()) {
            String who = p.id + (p.dark ? " dark" : " light");
            assertTrue(who + ": ink is " + Contrast.ratio(p.ink, p.bg),
                    Contrast.ratio(p.ink, p.bg) >= Contrast.AA);
            assertTrue(who + ": aText() is " + Contrast.ratio(p.aText(), p.bg),
                    Contrast.ratio(p.aText(), p.bg) >= Contrast.AA);
            assertTrue(who + ": pText() is " + Contrast.ratio(p.pText(), p.bg),
                    Contrast.ratio(p.pText(), p.bg) >= Contrast.AA);
            assertTrue(who + ": hText() is " + Contrast.ratio(p.hText(), p.bg),
                    Contrast.ratio(p.hText(), p.bg) >= Contrast.AA);
        }
    }

    @Test public void theSameGuaranteeHoldsOnTheVeilTheWidgetsActuallyUse() {
        // The clock and dock draw on veil(), not on bg. It is bg with alpha,
        // so compositing it over the two extremes the wallpaper can supply
        // brackets every background these text colours can land on.
        for (Palette p : all()) {
            for (int under : new int[]{ 0xFF000000, 0xFFFFFFFF }) {
                int veil = over(p.veil(), under);
                String who = p.id + (p.dark ? " dark" : " light")
                        + " on " + Integer.toHexString(under);
                assertTrue(who + ": aText() is " + Contrast.ratio(p.aText(), veil),
                        Contrast.ratio(p.aText(), veil) >= 3.0);
                assertTrue(who + ": ink is " + Contrast.ratio(p.ink, veil),
                        Contrast.ratio(p.ink, veil) >= 3.0);
            }
        }
    }

    @Test public void darkModeIsUntouched() {
        // The dark half already cleared the floor, so 2.5.2 must be a no-op
        // there. If this fails, a palette edit has quietly restyled dark mode
        // through a change that was only ever meant to fix light mode.
        for (String id : Palettes.IDS) {
            Palette d = Palettes.get(id, true);
            assertEquals(id + ": dark a moved", d.a, d.aText());
            assertEquals(id + ": dark p moved", d.p, d.pText());
            assertEquals(id + ": dark h moved", d.h, d.hText());
        }
    }

    @Test public void lightModeSecondaryActuallyMoved() {
        // The other direction: every light palette was broken, so every one
        // of them must have changed. A no-op here means the fix stopped
        // firing.
        for (String id : Palettes.IDS) {
            Palette l = Palettes.get(id, false);
            assertNotEquals(id + ": light a did not move", l.a, l.aText());
            assertNotEquals(id + ": light p did not move", l.p, l.pText());
        }
    }

    @Test public void theFillRolesAreNotDisturbed() {
        // aText()/pText() are derived, not stored. The quantization ramp and
        // every fill still read the original roles — if this failed, the
        // icon cache would be invalidated by a text fix.
        for (Palette p : all()) {
            assertEquals(p.id, Palettes.get(p.id, p.dark).a, p.a);
            assertEquals(p.id, Palettes.get(p.id, p.dark).p, p.p);
            assertArrayEquals(p.id, Palettes.get(p.id, p.dark).ramp(), p.ramp());
        }
    }

    @Test public void derivedTextColoursAreOpaque() {
        for (Palette p : all()) {
            assertEquals(p.id + " aText", 0xFF, (p.aText() >>> 24) & 0xFF);
            assertEquals(p.id + " pText", 0xFF, (p.pText() >>> 24) & 0xFF);
            assertEquals(p.id + " hText", 0xFF, (p.hText() >>> 24) & 0xFF);
        }
    }

    @Test public void theLiftNeverCollapsesSecondaryTextIntoInk() {
        // Legibility must not cost the hierarchy. Stated as "aText() differs
        // from ink wherever `a` already did", not as a flat inequality:
        // MONO dark and PLASMA dark deliberately author `a` and `ink` as the
        // same colour, and that is a palette decision predating all of this,
        // not something the lift is allowed to undo or to introduce.
        for (Palette p : all()) {
            if (p.a == p.ink) continue;
            assertNotEquals(p.id + (p.dark ? " dark" : " light")
                    + ": secondary text collapsed into ink", p.ink, p.aText());
        }
    }

    /** Straight source-over composite, used only to build the test's
     *  bracketing backgrounds. */
    private static int over(int src, int dst) {
        float a = ((src >>> 24) & 0xFF) / 255f;
        int out = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int s = (src >> shift) & 0xFF, d = (dst >> shift) & 0xFF;
            out |= Math.round(s * a + d * (1 - a)) << shift;
        }
        return out;
    }
}
