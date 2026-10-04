package com.retro.launcher.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ForegroundSpansTest {

    private static final long MIN = 60_000L;
    private static final long SEC = 1_000L;

    private static final int RESUMED = ForegroundSpans.ACTIVITY_RESUMED;
    private static final int PAUSED = ForegroundSpans.ACTIVITY_PAUSED;
    private static final int STOPPED = ForegroundSpans.ACTIVITY_STOPPED;

    private static ForegroundSpans.Event e(String pkg, int type, long ts) {
        return new ForegroundSpans.Event(pkg, type, ts);
    }

    /** An activity's event: {@code "pkg/Cls"}. */
    private static ForegroundSpans.Event a(String component, int type, long ts) {
        int slash = component.indexOf('/');
        return new ForegroundSpans.Event(component.substring(0, slash),
                component.substring(slash + 1), type, ts);
    }

    private static ForegroundSpans.Event screen(int type, long ts) {
        return new ForegroundSpans.Event(null, type, ts);
    }

    private static long totalFor(List<UsageMath.Interval> spans, String pkg) {
        long sum = 0;
        for (UsageMath.Interval iv : UsageMath.merge(spans)) {
            if (pkg.equals(iv.pkg)) sum += iv.endMillis - iv.startMillis;
        }
        return sum;
    }

    private static long total(ForegroundSpans.Result r) {
        long sum = 0;
        for (UsageMath.Interval iv : UsageMath.union(UsageMath.intersect(r.apps, r.awake))) {
            sum += iv.endMillis - iv.startMillis;
        }
        return sum;
    }

    // ---- 3.0.2: the reported undercount ---------------------------------

    @Test public void navigatingBetweenTwoScreensOfOneAppKeepsCounting() {
        // The bug: Android stops the old screen *after* resuming the new one,
        // and the 2.x machine let that stop end the whole app's span. Opening
        // a chat lost the chat; going back lost the list.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("chat/Home", RESUMED, 0),
                a("chat/Home", PAUSED, 2 * MIN),
                a("chat/Conversation", RESUMED, 2 * MIN + 300),
                a("chat/Home", STOPPED, 2 * MIN + 600),
                a("chat/Conversation", PAUSED, 20 * MIN),
                a("chat/Home", RESUMED, 20 * MIN + 300),
                a("chat/Conversation", STOPPED, 20 * MIN + 600),
                a("chat/Home", PAUSED, 25 * MIN),
                a("launcher/Home", RESUMED, 25 * MIN + 300),
                a("chat/Home", STOPPED, 25 * MIN + 600)
        ), 0, 100 * MIN);
        assertEquals(25 * MIN + 600, totalFor(r.apps, "chat"));
    }

    @Test public void aSecondInstanceOfTheSameScreenIsNotEndedByTheFirstStopping() {
        // Settings opens SubSettings over SubSettings: same package, same
        // class. The first instance's stop arrives while the class is resumed.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("settings/Sub", RESUMED, 0),
                a("settings/Sub", PAUSED, 1 * MIN),
                a("settings/Sub", RESUMED, 1 * MIN + 300),
                a("settings/Sub", STOPPED, 1 * MIN + 600),
                a("settings/Sub", PAUSED, 9 * MIN),
                a("launcher/Home", RESUMED, 9 * MIN + 300),
                a("settings/Sub", STOPPED, 9 * MIN + 600)
        ), 0, 100 * MIN);
        assertEquals(9 * MIN + 600, totalFor(r.apps, "settings"));
    }

    @Test public void theLauncherIsCountedLikeAnyOtherApp() {
        // §9 delta 48: Digital Wellbeing counts the home screen, so we do.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("launcher/Home", RESUMED, 0),
                a("launcher/Home", PAUSED, 3 * MIN),
                a("app/Main", RESUMED, 3 * MIN),
                a("launcher/Home", STOPPED, 3 * MIN),
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 10 * MIN)
        ), 0, 100 * MIN);
        assertEquals(3 * MIN, totalFor(r.apps, "launcher"));
        assertEquals(7 * MIN, totalFor(r.apps, "app"));
        assertEquals(10 * MIN, total(r));
    }

    @Test public void pictureInPictureCountsForTheFloatingApp() {
        // A PiP activity is paused, not stopped, for as long as it floats.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("video/Watch", RESUMED, 0),
                a("video/Watch", PAUSED, 5 * MIN),
                a("maps/Main", RESUMED, 5 * MIN),
                a("video/Watch", STOPPED, 12 * MIN),
                a("maps/Main", PAUSED, 20 * MIN),
                a("maps/Main", STOPPED, 20 * MIN)
        ), 0, 100 * MIN);
        assertEquals(12 * MIN, totalFor(r.apps, "video"));
        assertEquals(15 * MIN, totalFor(r.apps, "maps"));
        // The seven minutes both were on screen count once in the total.
        assertEquals(20 * MIN, total(r));
    }

    @Test public void splitScreenCountsBothAppsAndTheTotalOnce() {
        // API 29+ multi-resume: both halves are resumed together.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("a/Main", RESUMED, 0),
                a("b/Main", RESUMED, 1 * MIN),
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 11 * MIN)
        ), 0, 100 * MIN);
        assertEquals(11 * MIN, totalFor(r.apps, "a"));
        assertEquals(10 * MIN, totalFor(r.apps, "b"));
        assertEquals(11 * MIN, total(r));
    }

    @Test public void anAppBehindAnotherAppsDialogStaysVisible() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", RESUMED, 0),
                a("app/Main", PAUSED, 4 * MIN),
                a("perm/Dialog", RESUMED, 4 * MIN),
                a("perm/Dialog", PAUSED, 5 * MIN),
                a("app/Main", RESUMED, 5 * MIN),
                a("perm/Dialog", STOPPED, 5 * MIN),
                a("app/Main", PAUSED, 8 * MIN),
                a("app/Main", STOPPED, 8 * MIN)
        ), 0, 100 * MIN);
        assertEquals(8 * MIN, totalFor(r.apps, "app"));
        assertEquals(1 * MIN, totalFor(r.apps, "perm"));
        assertEquals(8 * MIN, total(r));
    }

    // ---- The window's opening state -------------------------------------

    @Test public void anAppInUseAtMidnightIsCreditedFromMidnight() {
        // Its resume was yesterday; the first we hear of it today is a pause.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", PAUSED, 10 * MIN),
                a("launcher/Home", RESUMED, 10 * MIN),
                a("app/Main", STOPPED, 10 * MIN + 400)
        ), 0, 100 * MIN);
        assertEquals(10 * MIN + 400, totalFor(r.apps, "app"));
    }

    @Test public void anOrphanStopAloneIsCreditedFromTheWindowStart() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", STOPPED, 3 * SEC)
        ), 0, 100 * MIN);
        assertEquals(3 * SEC, totalFor(r.apps, "app"));
    }

    @Test public void anOrphanAfterTheScreenWentOffIsNotBackdated() {
        // Once the screen has closed everything, nothing can have been on
        // screen since the window opened.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 5 * MIN),
                screen(ForegroundSpans.SCREEN_INTERACTIVE, 30 * MIN),
                a("app/Main", PAUSED, 40 * MIN),
                a("app/Main", STOPPED, 40 * MIN)
        ), 0, 100 * MIN);
        assertEquals(0L, totalFor(r.apps, "app"));
    }

    @Test public void anOrphanInAWindowThatOpenedDarkIsNotBackdated() {
        // The screen came on at 07:00, so nothing was on screen at midnight;
        // a stray stop at 07:05 must not claim the five minutes before it.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.SCREEN_INTERACTIVE, 420 * MIN),
                a("app/Main", STOPPED, 425 * MIN)
        ), 0, 430 * MIN);
        assertEquals(0L, totalFor(r.apps, "app"));
    }

    @Test public void aScreenThatWasOffAtTheWindowStartIsNotAssumedAwake() {
        // First screen event is the screen coming on: it was off before.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.SCREEN_INTERACTIVE, 420 * MIN),
                a("app/Main", RESUMED, 421 * MIN)
        ), 0, 430 * MIN);
        assertEquals(1, r.awake.size());
        assertEquals(420 * MIN, r.awake.get(0).startMillis);
        assertEquals(9 * MIN, total(r));
    }

    @Test public void aLockedScreenAtTheWindowStartIsNotAwakeUntilUnlocked() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.KEYGUARD_HIDDEN, 30 * MIN)
        ), 0, 60 * MIN);
        assertEquals(1, r.awake.size());
        assertEquals(30 * MIN, r.awake.get(0).startMillis);
    }

    @Test public void anAwakeScreenAtTheWindowStartIsAwakeFromIt() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 10 * MIN)
        ), 0, 60 * MIN);
        assertEquals(1, r.awake.size());
        assertEquals(0L, r.awake.get(0).startMillis);
        assertEquals(10 * MIN, r.awake.get(0).endMillis);
    }

    // ---- Screen off, and never inventing time ---------------------------

    @Test public void anAppLeftRunningAcrossAScreenOffStopsAtTheScreenOff() {
        // The 2.x symptom that must stay fixed: a background app credited
        // with every minute since the screen went dark.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", RESUMED, 10 * MIN),
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 20 * MIN)
        ), 0, 600 * MIN);
        assertEquals(10 * MIN, totalFor(r.apps, "app"));
    }

    @Test public void nothingIsCarriedAcrossAScreenOffWithoutANewResume() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", RESUMED, 10 * MIN),
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 20 * MIN),
                screen(ForegroundSpans.SCREEN_INTERACTIVE, 30 * MIN),
                a("app/Main", PAUSED, 31 * MIN),
                a("app/Main", STOPPED, 31 * MIN)
        ), 0, 600 * MIN);
        assertEquals(10 * MIN, totalFor(r.apps, "app"));
    }

    @Test public void anAppNeverStoppedWithTheScreenNowOffIsClosedAtTheScreenOff() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 5 * MIN),
                screen(ForegroundSpans.SCREEN_INTERACTIVE, 10 * MIN),
                a("app/Main", RESUMED, 12 * MIN),
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 15 * MIN)
        ), 0, 600 * MIN);
        assertEquals(3 * MIN, totalFor(r.apps, "app"));
    }

    @Test public void anAppResumedWhileTheScreenIsOffIsDroppedAtTheEnd() {
        // An alarm or a call UI resuming in the dark, still open when the
        // window ends: the device stopped telling us anything.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 5 * MIN),
                a("alarm/Ring", RESUMED, 50 * MIN)
        ), 0, 100 * MIN);
        assertEquals(0L, totalFor(r.apps, "alarm"));
    }

    @Test public void anAppStillOnScreenWithTheScreenOnRunsToTheWindowEnd() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.SCREEN_INTERACTIVE, 5 * MIN),
                a("app/Main", RESUMED, 10 * MIN)
        ), 0, 30 * MIN);
        assertEquals(20 * MIN, totalFor(r.apps, "app"));
    }

    @Test public void keyguardShownClosesEverything() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", RESUMED, 0),
                a("other/Main", RESUMED, 1 * MIN),
                screen(ForegroundSpans.KEYGUARD_SHOWN, 7 * MIN)
        ), 0, 100 * MIN);
        assertEquals(7 * MIN, totalFor(r.apps, "app"));
        assertEquals(6 * MIN, totalFor(r.apps, "other"));
    }

    @Test public void deviceShutdownClosesEverything() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", RESUMED, 0),
                screen(ForegroundSpans.DEVICE_SHUTDOWN, 4 * MIN)
        ), 0, 100 * MIN);
        assertEquals(4 * MIN, totalFor(r.apps, "app"));
    }

    @Test public void aStopWhileTheSameScreenIsResumedIsAnotherInstancesAndIgnored() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", RESUMED, 0),
                a("app/Main", STOPPED, 8 * MIN),
                a("app/Main", PAUSED, 10 * MIN),
                a("app/Main", STOPPED, 10 * MIN)
        ), 0, 100 * MIN);
        assertEquals(10 * MIN, totalFor(r.apps, "app"));
    }

    @Test public void aStopForAScreenNeverOpenedLaterInTheDayIsIgnored() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", RESUMED, 0),
                a("app/Main", PAUSED, 1 * MIN),
                a("app/Main", STOPPED, 1 * MIN),
                a("app/Main", STOPPED, 50 * MIN)
        ), 0, 100 * MIN);
        assertEquals(1 * MIN, totalFor(r.apps, "app"));
    }

    // ---- API 26–28: no ACTIVITY_STOPPED ---------------------------------

    @Test public void withoutStopEventsAPauseEndsTheSpan() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", RESUMED, 10 * MIN),
                e("a", PAUSED, 25 * MIN)
        ), 0, 100 * MIN, false);
        assertEquals(15 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void withoutStopEventsAMidnightOrphanPauseIsStillCredited() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", PAUSED, 6 * MIN)
        ), 0, 100 * MIN, false);
        assertEquals(6 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void aDayWithNoScreenEventsTreatsTheWholeWindowAsAwake() {
        // API 26–27 emit no screen events and no stops. Clipping to an empty
        // awake list would zero the day.
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", RESUMED, 10 * MIN),
                e("a", PAUSED, 40 * MIN)
        ), 0, 100 * MIN, false);
        assertEquals(30 * MIN, totalFor(r.apps, "a"));
        assertEquals(1, r.awake.size());
        assertEquals(0L, r.awake.get(0).startMillis);
        assertEquals(100 * MIN, r.awake.get(0).endMillis);
    }

    // ---- Bookkeeping ----------------------------------------------------

    @Test public void eventsWithoutAClassNameAreTreatedAsOneActivityPerPackage() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                e("a", RESUMED, 0),
                e("a", PAUSED, 5 * MIN),
                e("a", STOPPED, 5 * MIN)
        ), 0, 100 * MIN);
        assertEquals(5 * MIN, totalFor(r.apps, "a"));
    }

    @Test public void awakeWindowsTrackTheScreen() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 10 * MIN),
                screen(ForegroundSpans.SCREEN_INTERACTIVE, 30 * MIN),
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 50 * MIN)
        ), 0, 100 * MIN);
        assertEquals(2, r.awake.size());
        assertEquals(0L, r.awake.get(0).startMillis);
        assertEquals(10 * MIN, r.awake.get(0).endMillis);
        assertEquals(30 * MIN, r.awake.get(1).startMillis);
        assertEquals(50 * MIN, r.awake.get(1).endMillis);
    }

    @Test public void pickupsCountKeyguardDismissals() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                screen(ForegroundSpans.KEYGUARD_HIDDEN, 1 * MIN),
                screen(ForegroundSpans.KEYGUARD_SHOWN, 2 * MIN),
                screen(ForegroundSpans.KEYGUARD_HIDDEN, 3 * MIN),
                screen(ForegroundSpans.KEYGUARD_HIDDEN, 4 * MIN)
        ), 0, 100 * MIN);
        assertEquals(3, r.pickups);
    }

    @Test public void aSpanStraddlingMidnightIsOneUnsplitInterval() {
        // Splitting is UsageMath.dailyTotals' job, not this machine's.
        long midnight = 1_700_000_000_000L;
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", RESUMED, midnight - 10 * MIN),
                a("app/Main", PAUSED, midnight + 10 * MIN),
                a("app/Main", STOPPED, midnight + 10 * MIN)
        ), midnight - 60 * MIN, midnight + 60 * MIN);
        assertEquals(1, r.apps.size());
        assertEquals(midnight - 10 * MIN, r.apps.get(0).startMillis);
        assertEquals(midnight + 10 * MIN, r.apps.get(0).endMillis);
    }

    @Test public void zeroLengthSpansAreDropped() {
        ForegroundSpans.Result r = ForegroundSpans.scan(Arrays.asList(
                a("app/Main", RESUMED, 5 * MIN),
                a("app/Main", PAUSED, 5 * MIN),
                a("app/Main", STOPPED, 5 * MIN)
        ), 0, 100 * MIN);
        assertTrue(r.apps.isEmpty());
    }

    @Test public void anEmptyEventStreamProducesNothingButTheAwakeWindow() {
        ForegroundSpans.Result r = ForegroundSpans.scan(
                new ArrayList<>(), 0, 100 * MIN);
        assertTrue(r.apps.isEmpty());
        assertEquals(0, r.pickups);
        assertEquals(1, r.awake.size());
    }

    @Test public void anEmptyWindowProducesNothingAtAll() {
        ForegroundSpans.Result r = ForegroundSpans.scan(
                new ArrayList<>(), 100 * MIN, 100 * MIN);
        assertTrue(r.apps.isEmpty());
        assertTrue(r.awake.isEmpty());
    }

    @Test public void awakeAtStartIgnoresActivityEvents() {
        assertFalse(ForegroundSpans.awakeAtStart(Arrays.asList(
                a("app/Main", RESUMED, 0),
                screen(ForegroundSpans.SCREEN_INTERACTIVE, 1))));
        assertTrue(ForegroundSpans.awakeAtStart(Arrays.asList(
                a("app/Main", RESUMED, 0))));
    }

    /** 3.0.1: UsageRepository drops irrelevant events before scanning. That
     *  is only sound if dropping them changes nothing, which this pins. */
    @Test public void droppingIrrelevantEventsChangesNothing() {
        List<ForegroundSpans.Event> all = new ArrayList<>(Arrays.asList(
                a("a/Main", RESUMED, 10 * MIN),
                e("a", 5, 11 * MIN),    // CONFIGURATION_CHANGE
                e("b", 12, 12 * MIN),   // NOTIFICATION_INTERRUPTION
                e("a", 11, 13 * MIN),   // STANDBY_BUCKET_CHANGED
                screen(ForegroundSpans.KEYGUARD_HIDDEN, 14 * MIN),
                e("c", 19, 15 * MIN),   // FOREGROUND_SERVICE_START
                a("a/Main", PAUSED, 20 * MIN),
                a("a/Main", STOPPED, 20 * MIN),
                screen(ForegroundSpans.SCREEN_NON_INTERACTIVE, 30 * MIN)));
        List<ForegroundSpans.Event> kept = new ArrayList<>();
        for (ForegroundSpans.Event ev : all) {
            if (ForegroundSpans.isRelevant(ev.type)) kept.add(ev);
        }
        assertEquals(5, kept.size());

        ForegroundSpans.Result full = ForegroundSpans.scan(all, 0, 60 * MIN);
        ForegroundSpans.Result lean = ForegroundSpans.scan(kept, 0, 60 * MIN);
        assertEquals(totalFor(full.apps, "a"), totalFor(lean.apps, "a"));
        assertEquals(full.apps.size(), lean.apps.size());
        assertEquals(full.awake.size(), lean.awake.size());
        assertEquals(full.pickups, lean.pickups);
    }
}
