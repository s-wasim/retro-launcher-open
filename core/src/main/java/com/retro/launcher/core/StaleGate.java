package com.retro.launcher.core;

/**
 * Whether a panel's content needs rebuilding, and when.
 *
 * <h3>Why this is a class and not three copies of a boolean</h3>
 * 2.4.1 stopped every panel from building its view tree during
 * {@code HomeActivity.onCreate}. A launcher's whole job is to show one screen
 * and get out of the way, and a cold start was paying for the drawer, both
 * settings screens, the search overlay and the zone picker whether or not the
 * user ever swiped to any of them.
 *
 * <p>Deferring is three lines of state, but they are three lines with a rule
 * that is easy to get subtly wrong in each of the places that needs it:
 *
 * <ul>
 *   <li>Content starts stale, so the first show always builds.</li>
 *   <li>Becoming visible while stale builds, and clears the flag.</li>
 *   <li>Becoming visible while <em>fresh</em> builds nothing — otherwise
 *       deferring has bought nothing and every swipe pays full price.</li>
 *   <li>An invalidation while already on screen builds <em>now</em>, because
 *       a change the user is looking at must not wait for them to navigate
 *       away and back.</li>
 *   <li>An invalidation while off screen only marks — that is the whole
 *       point.</li>
 * </ul>
 *
 * <p>Keeping it here rather than in {@code ui/} means those rules are testable
 * on a bare JDK. A {@code View} subclass is not, and this is exactly the kind
 * of state machine where a wrong answer shows up as a panel that silently
 * stops updating rather than as a crash.
 *
 * <p>Not thread-safe, and deliberately so: every caller is the UI thread, and
 * synchronising would imply it is safe to call from somewhere it is not.
 */
public final class StaleGate {

    private boolean stale = true;

    /**
     * Call when the owner's visibility changes.
     *
     * @param visible whether the owner is now actually on screen
     * @return true if the caller should rebuild its content now
     */
    public boolean onVisibilityChanged(boolean visible) {
        if (!visible || !stale) return false;
        stale = false;
        return true;
    }

    /**
     * Call when something has changed that the content reflects.
     *
     * @param shown whether the owner is on screen right now
     * @return true if the caller should rebuild its content now, which is the
     *         case exactly when it is already being looked at
     */
    public boolean invalidate(boolean shown) {
        if (shown) {
            stale = false;
            return true;
        }
        stale = true;
        return false;
    }

    /** Whether a rebuild is owed. Exposed for tests and for a caller that
     *  wants to skip preparing arguments it would only throw away. */
    public boolean isStale() { return stale; }
}
