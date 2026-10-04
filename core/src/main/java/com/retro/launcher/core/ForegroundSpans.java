package com.retro.launcher.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays the platform's usage-event stream into on-screen spans, as a pure
 * function.
 *
 * <p>3.0.2. The machine is keyed <em>per activity</em> ({@code pkg/class}),
 * not per package, and it measures the time an activity is <em>visible</em> —
 * from {@code ACTIVITY_RESUMED} until {@code ACTIVITY_STOPPED} — which is
 * what Digital Wellbeing reports. The 2.x machine held a single "focused
 * package" and let any {@code STOPPED} from that package end it, and Android's
 * lifecycle order for moving between two screens of one app is
 * <em>old PAUSED → new RESUMED → old STOPPED</em>: the old screen's late stop
 * carried the same package name, so everything after the first in-app
 * navigation was dropped until the next event. Opening a chat, a settings
 * page or a product listing cost that whole visit. That, plus the launcher's
 * own time being excluded (§9 delta 16, reversed by delta 48), is the gap the
 * owner measured against Samsung's Digital Wellbeing.
 *
 * <p>The rules:
 * <ul>
 * <li>{@code RESUMED} opens the activity's span, or marks an open one
 *     resumed again.</li>
 * <li>{@code PAUSED} leaves it open: a paused activity is still on screen —
 *     behind a dialog, in picture-in-picture, the unfocused half of a split
 *     screen, or for the few hundred milliseconds of a transition.</li>
 * <li>{@code STOPPED} closes it, but only if the activity's last word was
 *     {@code PAUSED}. An instance has to pause before it stops, so a stop
 *     arriving while the same class is resumed belongs to <em>another
 *     instance</em> of it — Settings opening one {@code SubSettings} over
 *     another — and the one on screen keeps counting.</li>
 * <li>Screen off, keyguard shown and shutdown close everything. Nothing is
 *     carried across them: an app is only counted again once the platform
 *     says it resumed.</li>
 * <li>Without {@code STOPPED} (API 26–28) visibility cannot be told apart
 *     from foreground, so {@code PAUSED} closes instead.</li>
 * </ul>
 *
 * <p>Several activities, of several packages, may be open at once, so
 * {@link Result#apps} can overlap across packages. That is correct per app
 * and wrong for a total; {@link UsageMath#union} is what the day's headline
 * is taken from, so an overlapping minute is still counted once.
 *
 * <p>The event-type values are {@code UsageEvents.Event}'s own public
 * constants, repeated here so this class stays free of any Android import
 * and therefore unit-testable.
 */
public final class ForegroundSpans {

    private ForegroundSpans() {}

    public static final int ACTIVITY_RESUMED = 1;        // == MOVE_TO_FOREGROUND
    public static final int ACTIVITY_PAUSED = 2;         // == MOVE_TO_BACKGROUND
    public static final int SCREEN_INTERACTIVE = 15;
    public static final int SCREEN_NON_INTERACTIVE = 16;
    public static final int KEYGUARD_SHOWN = 17;
    public static final int KEYGUARD_HIDDEN = 18;
    public static final int ACTIVITY_STOPPED = 23;       // API 29+
    public static final int DEVICE_SHUTDOWN = 26;

    /**
     * Whether {@link #scan} does anything with an event of this type. Every
     * other type falls through its {@code default}, so a caller may drop them
     * before building the list — which, on a busy day, is most of the stream.
     */
    public static boolean isRelevant(int type) {
        switch (type) {
            case ACTIVITY_RESUMED:
            case ACTIVITY_PAUSED:
            case ACTIVITY_STOPPED:
            case SCREEN_INTERACTIVE:
            case SCREEN_NON_INTERACTIVE:
            case KEYGUARD_SHOWN:
            case KEYGUARD_HIDDEN:
            case DEVICE_SHUTDOWN:
                return true;
            default:
                return false;
        }
    }

    /** One row of the platform's event stream, with nothing else attached. */
    public static final class Event {
        public final String pkg;
        /** The activity's class name; null for events that are not an
         *  activity's, and tolerated as null on one that is. */
        public final String cls;
        public final int type;
        public final long ts;
        public Event(String pkg, String cls, int type, long ts) {
            this.pkg = pkg;
            this.cls = cls;
            this.type = type;
            this.ts = ts;
        }
        /** An event with no class name: every activity of {@code pkg} is
         *  then treated as one. */
        public Event(String pkg, int type, long ts) {
            this(pkg, null, type, ts);
        }
    }

    public static final class Result {
        /** Visible spans per package, in the order they closed. One package's
         *  spans may touch; different packages' may overlap. */
        public final List<UsageMath.Interval> apps;
        /** Spans during which the display was interactive and unlocked. */
        public final List<UsageMath.Interval> awake;
        /** Keyguard dismissals in the window. */
        public final int pickups;
        Result(List<UsageMath.Interval> apps, List<UsageMath.Interval> awake, int pickups) {
            this.apps = apps;
            this.awake = awake;
            this.pickups = pickups;
        }
    }

    /** The pseudo-package awake windows ride under. Not a legal package name,
     *  so it can never collide with a real one. */
    public static final String AWAKE = "!awake";

    /** One activity currently on screen. */
    private static final class Open {
        final String pkg;
        final long since;
        /** False once it has paused: still visible, waiting on its stop. */
        boolean resumed;
        Open(String pkg, long since, boolean resumed) {
            this.pkg = pkg;
            this.since = since;
            this.resumed = resumed;
        }
    }

    /** {@link #scan(List, long, long, boolean)} for a device that reports
     *  {@code ACTIVITY_STOPPED} (API 29+). */
    public static Result scan(List<Event> events, long windowStart, long windowEnd) {
        return scan(events, windowStart, windowEnd, true);
    }

    /**
     * Replays {@code events} — which must be in timestamp order, as
     * {@code queryEvents} returns them — into visible spans, awake windows
     * and a pickup count.
     *
     * <p>What was already true when the window opened has to be inferred,
     * because the events that set it up are before {@code windowStart}:
     * <ul>
     * <li>The screen is taken as awake at the start unless the first screen
     *     event in the window is one that <em>ends</em> an off or locked
     *     state ({@code SCREEN_INTERACTIVE}, {@code KEYGUARD_HIDDEN}). With no
     *     screen event at all — below API 28, or a window spent entirely
     *     awake — it is awake throughout, since clipping to an empty list
     *     would wrongly zero the day.</li>
     * <li>An activity whose first event in the window is a pause or a stop,
     *     in a window that opened awake and before anything has closed the
     *     screen, was on screen when the window opened — the app in use at
     *     midnight. It is credited from {@code windowStart}. The 2.x machine
     *     dropped that time.</li>
     * </ul>
     *
     * @param stopReported whether the device emits {@code ACTIVITY_STOPPED}
     *     (API 29+). Without it a pause is the only end an activity gets.
     */
    public static Result scan(List<Event> events, long windowStart, long windowEnd,
                              boolean stopReported) {
        List<UsageMath.Interval> apps = new ArrayList<>();
        List<UsageMath.Interval> awake = new ArrayList<>();
        int pickups = 0;

        if (windowEnd <= windowStart) return new Result(apps, awake, 0);

        Map<String, Open> open = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        // Until the first screen-off, keyguard or shutdown, an activity we
        // have heard nothing from may have been on screen since windowStart —
        // unless the screen was off then, when nothing was.
        boolean awakeAtStart = awakeAtStart(events);
        boolean pristine = awakeAtStart;
        long awakeSince = awakeAtStart ? windowStart : -1L;

        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            long ts = clamp(e.ts, windowStart, windowEnd);
            switch (e.type) {
                case ACTIVITY_RESUMED: {
                    String key = key(e);
                    seen.add(key);
                    Open o = open.get(key);
                    if (o == null) open.put(key, new Open(e.pkg, ts, true));
                    else o.resumed = true;
                    break;
                }

                case ACTIVITY_PAUSED: {
                    String key = key(e);
                    boolean orphan = pristine && seen.add(key);
                    Open o = open.get(key);
                    if (!stopReported) {
                        if (o != null) {
                            addSpan(apps, o.pkg, o.since, ts);
                            open.remove(key);
                        } else if (orphan) {
                            addSpan(apps, e.pkg, windowStart, ts);
                        }
                    } else if (o != null) {
                        o.resumed = false;
                    } else if (orphan) {
                        open.put(key, new Open(e.pkg, windowStart, false));
                    }
                    break;
                }

                case ACTIVITY_STOPPED: {
                    String key = key(e);
                    boolean orphan = pristine && seen.add(key);
                    Open o = open.get(key);
                    if (o != null) {
                        // Resumed means another instance of this class is on
                        // screen; this stop is the one it replaced.
                        if (!o.resumed) {
                            addSpan(apps, o.pkg, o.since, ts);
                            open.remove(key);
                        }
                    } else if (orphan) {
                        addSpan(apps, e.pkg, windowStart, ts);
                    }
                    break;
                }

                case SCREEN_NON_INTERACTIVE:
                case KEYGUARD_SHOWN:
                case DEVICE_SHUTDOWN:
                    closeAll(open, apps, ts);
                    pristine = false;
                    if (awakeSince >= 0) {
                        addSpan(awake, AWAKE, awakeSince, ts);
                        awakeSince = -1L;
                    }
                    break;

                case KEYGUARD_HIDDEN:
                    pickups++;
                    if (awakeSince < 0) awakeSince = ts;
                    break;

                case SCREEN_INTERACTIVE:
                    if (awakeSince < 0) awakeSince = ts;
                    break;

                default:
                    break;
            }
        }

        if (awakeSince >= 0) {
            // The screen is still on, so whatever has not stopped really is
            // still on it and runs to the window's end.
            addSpan(awake, AWAKE, awakeSince, windowEnd);
            closeAll(open, apps, windowEnd);
        }
        // Otherwise the still-open spans are dropped on the floor, on purpose:
        // the device stopped telling us anything, and inventing time is worse
        // than losing it.

        return new Result(apps, awake, pickups);
    }

    /**
     * Whether the display was interactive when the window opened, read from
     * the first event that says anything about it. An event that ends an off
     * or locked state means it was not; one that starts it means it was.
     */
    static boolean awakeAtStart(List<Event> events) {
        for (int i = 0; i < events.size(); i++) {
            switch (events.get(i).type) {
                case SCREEN_INTERACTIVE:
                case KEYGUARD_HIDDEN:
                    return false;
                case SCREEN_NON_INTERACTIVE:
                case KEYGUARD_SHOWN:
                case DEVICE_SHUTDOWN:
                    return true;
                default:
                    break;
            }
        }
        return true;
    }

    private static String key(Event e) {
        return e.cls == null ? e.pkg : e.pkg + '/' + e.cls;
    }

    private static void closeAll(Map<String, Open> open, List<UsageMath.Interval> out, long ts) {
        for (Iterator<Open> it = open.values().iterator(); it.hasNext(); ) {
            Open o = it.next();
            addSpan(out, o.pkg, o.since, ts);
            it.remove();
        }
    }

    private static void addSpan(List<UsageMath.Interval> out, String pkg, long from, long to) {
        if (to > from) out.add(new UsageMath.Interval(pkg, from, to));
    }

    private static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
