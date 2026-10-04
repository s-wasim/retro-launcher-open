package com.retro.launcher.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 2.4.1. This gate decides whether the drawer, the settings screens and the
 * screen-time figures rebuild, so getting it wrong shows up as a panel that
 * silently stops updating — no crash, no log, just stale content nobody
 * notices until they do. Every rule in {@link StaleGate}'s contract is pinned
 * here, including the ones whose failure mode is "deferring bought nothing".
 */
public class StaleGateTest {

    // ---- the first show ---------------------------------------------------

    @Test public void contentStartsStaleSoTheFirstShowAlwaysBuilds() {
        StaleGate gate = new StaleGate();
        assertTrue(gate.isStale());
        assertTrue("the first show must build", gate.onVisibilityChanged(true));
    }

    @Test public void theFirstShowClearsTheFlag() {
        StaleGate gate = new StaleGate();
        gate.onVisibilityChanged(true);
        assertFalse(gate.isStale());
    }

    // ---- the saving ------------------------------------------------------

    @Test public void aSecondShowWithNoChangeBuildsNothing() {
        // If this ever returns true, deferring has bought nothing: every
        // swipe back to the drawer would pay the full rebuild again.
        StaleGate gate = new StaleGate();
        gate.onVisibilityChanged(true);
        gate.onVisibilityChanged(false);
        assertFalse(gate.onVisibilityChanged(true));
    }

    @Test public void repeatedShowsNeverRebuildWithoutAnInvalidation() {
        StaleGate gate = new StaleGate();
        assertTrue(gate.onVisibilityChanged(true));
        for (int i = 0; i < 50; i++) {
            gate.onVisibilityChanged(false);
            assertFalse("rebuilt on show " + i + " with nothing changed",
                    gate.onVisibilityChanged(true));
        }
    }

    // ---- going away -------------------------------------------------------

    @Test public void becomingInvisibleNeverBuilds() {
        StaleGate gate = new StaleGate();
        assertFalse(gate.onVisibilityChanged(false));
    }

    @Test public void becomingInvisibleWhileStaleLeavesItStale() {
        StaleGate gate = new StaleGate();
        assertTrue(gate.isStale());
        gate.onVisibilityChanged(false);
        assertTrue("hiding must not clear the debt", gate.isStale());
        assertTrue(gate.onVisibilityChanged(true));
    }

    // ---- invalidation off screen -----------------------------------------

    @Test public void invalidatingOffScreenOnlyMarks() {
        // The whole point: a package install must not rebuild a drawer
        // nobody is looking at.
        StaleGate gate = new StaleGate();
        gate.onVisibilityChanged(true);
        assertFalse("rebuilt while hidden", gate.invalidate(false));
        assertTrue(gate.isStale());
    }

    @Test public void theDebtIsPaidOnTheNextShow() {
        StaleGate gate = new StaleGate();
        gate.onVisibilityChanged(true);
        gate.invalidate(false);
        assertTrue(gate.onVisibilityChanged(true));
        assertFalse(gate.isStale());
    }

    @Test public void manyInvalidationsOffScreenCostOneRebuild() {
        // Ten packages installed while the drawer is closed is one rebuild,
        // not ten.
        StaleGate gate = new StaleGate();
        gate.onVisibilityChanged(true);
        int rebuilds = 0;
        for (int i = 0; i < 10; i++) if (gate.invalidate(false)) rebuilds++;
        assertEquals(0, rebuilds);
        assertTrue(gate.onVisibilityChanged(true));
        assertFalse(gate.onVisibilityChanged(true));
    }

    // ---- invalidation on screen -------------------------------------------

    @Test public void invalidatingOnScreenBuildsImmediately() {
        // A change the user is looking at must not wait for them to navigate
        // away and back.
        StaleGate gate = new StaleGate();
        gate.onVisibilityChanged(true);
        assertTrue(gate.invalidate(true));
    }

    @Test public void invalidatingOnScreenLeavesNothingOwed() {
        StaleGate gate = new StaleGate();
        gate.onVisibilityChanged(true);
        gate.invalidate(true);
        assertFalse(gate.isStale());
        assertFalse("already rebuilt; the next show owes nothing",
                gate.onVisibilityChanged(true));
    }

    @Test public void invalidatingOnScreenBeforeAnyShowStillBuildsOnce() {
        // setPalette can land before the panel has ever been visible.
        StaleGate gate = new StaleGate();
        assertTrue(gate.invalidate(true));
        assertFalse(gate.isStale());
    }

    // ---- the whole lifecycle ---------------------------------------------

    @Test public void aRealisticSessionRebuildsOnlyWhenItMustd() {
        StaleGate gate = new StaleGate();
        int rebuilds = 0;

        // Cold start: onCreate sets a palette while nothing is on screen.
        if (gate.invalidate(false)) rebuilds++;
        assertEquals("onCreate must not build", 0, rebuilds);

        // User swipes to the drawer for the first time.
        if (gate.onVisibilityChanged(true)) rebuilds++;
        assertEquals(1, rebuilds);

        // Back home, and back to the drawer twice more, nothing changed.
        gate.onVisibilityChanged(false);
        if (gate.onVisibilityChanged(true)) rebuilds++;
        gate.onVisibilityChanged(false);
        if (gate.onVisibilityChanged(true)) rebuilds++;
        assertEquals("unchanged content rebuilt again", 1, rebuilds);

        // An app is installed while the drawer is open.
        if (gate.invalidate(true)) rebuilds++;
        assertEquals(2, rebuilds);

        // Two more installed after going home.
        gate.onVisibilityChanged(false);
        if (gate.invalidate(false)) rebuilds++;
        if (gate.invalidate(false)) rebuilds++;
        assertEquals("background installs rebuilt a hidden panel", 2, rebuilds);

        // Next visit pays for both at once.
        if (gate.onVisibilityChanged(true)) rebuilds++;
        assertEquals(3, rebuilds);
    }

    @Test public void twoGatesAreIndependent() {
        StaleGate a = new StaleGate(), b = new StaleGate();
        a.onVisibilityChanged(true);
        assertFalse(a.isStale());
        assertTrue("gates must not share state", b.isStale());
    }
}
