package com.retro.launcher.data;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.retro.launcher.core.ForegroundSpans;
import com.retro.launcher.core.UsageMath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Wraps {@link UsageStatsManager} and turns its event stream into the
 * {@link UsageMath.Interval} lists that class does its calendar-day
 * arithmetic on. Requires {@code PACKAGE_USAGE_STATS} — every method degrades
 * to empty/zero rather than throwing when access isn't granted.
 *
 * <p>All the judgement lives in {@link ForegroundSpans}, which is a pure
 * function and unit-tested; this class only reads events and hands them over.
 *
 * <p>3.0.2: the figures are meant to agree with the phone's own Digital
 * Wellbeing, and the owner measured them 40+ minutes short on a three-hour
 * day. An app's time is now the time any of its activities was on screen,
 * clipped to the spans in which the display was interactive and unlocked.
 * Today's total is the union of every app's time, so a minute two apps
 * shared — split screen, picture-in-picture — counts once. The launcher is
 * one of the apps (§9 delta 48): Digital Wellbeing counts the home screen,
 * and leaving it out was the other half of the gap.
 */
public final class UsageRepository {

    public static final class AppUsage {
        public final String pkg;
        /** The app's own label, as Digital Wellbeing shows it; null when the
         *  package is not visible to us, and the caller derives one. */
        public final String label;
        public final long millis;
        public AppUsage(String pkg, String label, long millis) {
            this.pkg = pkg;
            this.label = label;
            this.millis = millis;
        }
    }

    private final UsageStatsManager usm;
    private final PackageManager pm;

    public UsageRepository(Context context) {
        this.usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.pm = context.getPackageManager();
    }

    /** 3.0.2: read per call, not held from construction — a launcher lives
     *  for days, and a device that changed zone since must cut its days where
     *  Digital Wellbeing does. */
    private static TimeZone tz() {
        return TimeZone.getDefault();
    }

    /** One pass over the platform's stream, replayed through the pure machine. */
    private ForegroundSpans.Result scan(long start, long end) {
        List<ForegroundSpans.Event> raw = new ArrayList<>();
        if (usm != null && end > start) {
            UsageEvents events = usm.queryEvents(start, end);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                // 3.0.1: most of a day's events are configuration changes,
                // standby-bucket moves, notification interruptions and
                // foreground-service starts that the span scan ignores
                // anyway. Dropping them here skips an allocation each.
                int type = e.getEventType();
                if (!ForegroundSpans.isRelevant(type)) continue;
                // 3.0.2: the class name is what tells two screens of one app
                // apart; without it the second screen's arrival and the
                // first one's stop are indistinguishable.
                raw.add(new ForegroundSpans.Event(e.getPackageName(), e.getClassName(),
                        type, e.getTimeStamp()));
            }
        }
        // ACTIVITY_STOPPED is API 29+. Before it a pause is all there is.
        return ForegroundSpans.scan(raw, start, end,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
    }

    /**
     * Each app's on-screen time, merged per package and clipped to the
     * screen-awake windows. The per-app rows are this; the totals are its
     * {@link UsageMath#union}.
     */
    private static List<UsageMath.Interval> perApp(ForegroundSpans.Result r) {
        List<UsageMath.Interval> merged = UsageMath.merge(r.apps);
        List<UsageMath.Interval> awake = UsageMath.merge(r.awake);
        return UsageMath.intersect(merged, awake);
    }

    public long todayMillis(long nowMillis) {
        TimeZone tz = tz();
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        return UsageMath.totalForDay(UsageMath.union(perApp(scan(dayStart, nowMillis))), dayStart, tz);
    }

    /** Millis used on each of the last 7 calendar days, oldest to today. */
    public long[] last7DaysMillis(long nowMillis) {
        TimeZone tz = tz();
        long[] dayStarts = UsageMath.last7DayStarts(nowMillis, tz);
        List<UsageMath.Interval> week = UsageMath.union(perApp(scan(dayStarts[0], nowMillis)));
        long[] out = new long[7];
        for (int i = 0; i < 7; i++) out[i] = UsageMath.totalForDay(week, dayStarts[i], tz);
        return out;
    }

    /** Per-app totals for today, descending, capped at {@code limit} rows. */
    public List<AppUsage> mostUsedToday(long nowMillis, int limit) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz());
        return ranked(perApp(scan(dayStart, nowMillis)), limit);
    }

    /** Everything the Screen Time panel shows, from two scans instead of the
     *  four that asking {@link #todayMillis}, {@link #last7DaysMillis},
     *  {@link #pickupsToday} and {@link #mostUsedToday} separately costs. */
    public static final class PanelFigures {
        public final long todayMillis;
        public final long[] last7Millis;
        public final int pickups;
        public final List<AppUsage> mostUsed;

        PanelFigures(long todayMillis, long[] last7Millis, int pickups, List<AppUsage> mostUsed) {
            this.todayMillis = todayMillis;
            this.last7Millis = last7Millis;
            this.pickups = pickups;
            this.mostUsed = mostUsed;
        }
    }

    /** Slow — two {@code queryEvents} calls, one of them a week long. Call
     *  off the UI thread. */
    public PanelFigures panelFigures(long nowMillis, int mostUsedLimit) {
        TimeZone tz = tz();
        long dayStart = UsageMath.startOfDay(nowMillis, tz);
        ForegroundSpans.Result today = scan(dayStart, nowMillis);
        List<UsageMath.Interval> todaySpans = perApp(today);
        long total = UsageMath.totalForDay(UsageMath.union(todaySpans), dayStart, tz);
        return new PanelFigures(total, last7DaysMillis(nowMillis), today.pickups,
                ranked(todaySpans, mostUsedLimit));
    }

    private List<AppUsage> ranked(List<UsageMath.Interval> spans, int limit) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (UsageMath.Interval iv : spans) {
            totals.merge(iv.pkg, iv.endMillis - iv.startMillis, Long::sum);
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int rows = Math.min(limit, sorted.size());
        // Labels only for the rows that will be drawn: one Binder call each.
        List<AppUsage> out = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            Map.Entry<String, Long> e = sorted.get(i);
            out.add(new AppUsage(e.getKey(), label(e.getKey()), e.getValue()));
        }
        return out;
    }

    /**
     * 3.0.2: the app's real label. Deriving one from the package name, as
     * the rows used to, is what made them hard to check against Digital
     * Wellbeing — {@code com.instagram.android} read as ANDROID and
     * {@code com.facebook.katana} as KATANA. Every launchable app is visible
     * through the manifest's {@code <queries>}; anything else is null.
     */
    private String label(String pkg) {
        if (pm == null || pkg == null) return null;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(info);
            String label = l == null ? "" : l.toString().trim();
            return label.isEmpty() ? null : label;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Keyguard dismissals today. Below API 28 the platform emits no keyguard
     * events, so this is 0 — a truthful degradation, not a crash.
     */
    public int pickupsToday(long nowMillis) {
        long dayStart = UsageMath.startOfDay(nowMillis, tz());
        return scan(dayStart, nowMillis).pickups;
    }
}
