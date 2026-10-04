package com.retro.launcher;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;

import com.retro.launcher.core.ComponentKey;
import com.retro.launcher.core.LockRoute;
import com.retro.launcher.core.Metrics;
import com.retro.launcher.core.Palette;
import com.retro.launcher.core.PaletteResolver;
import com.retro.launcher.core.UsageMath;
import com.retro.launcher.core.Weather;
import com.retro.launcher.data.AppEntry;
import com.retro.launcher.data.AppRepository;
import com.retro.launcher.data.Prefs;
import com.retro.launcher.data.UsageRepository;
import com.retro.launcher.data.WeatherRepository;
import com.retro.launcher.icons.DiskIconCache;
import com.retro.launcher.icons.IconCache;
import com.retro.launcher.icons.IconSource;
import com.retro.launcher.icons.PixelArtIcons;
import com.retro.launcher.lock.ShizukuLock;
import com.retro.launcher.shade.ShadeService;
import com.retro.launcher.core.SkyRenderer;
import com.retro.launcher.core.SolarClock;
import com.retro.launcher.core.SolarTimes;
import com.retro.launcher.sky.SkyView;
import com.retro.launcher.ui.BottomSheet;
import com.retro.launcher.ui.DockView;
import com.retro.launcher.ui.DrawerPanel;
import com.retro.launcher.ui.HintOverlay;
import com.retro.launcher.ui.HomePanel;
import com.retro.launcher.ui.LauncherRoot;
import com.retro.launcher.ui.ScreenTimePanel;
import com.retro.launcher.ui.SearchOverlay;
import com.retro.launcher.ui.SettingsPanel;
import com.retro.launcher.ui.SetupScreen;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeActivity extends Activity {

    private static final int REQ_LOCATION = 1;
    private static final int REQ_SHIZUKU = 2;

    private LauncherRoot root;
    private SkyView sky;
    private HomePanel home;
    private DrawerPanel drawer;
    private SettingsPanel settings;
    private ScreenTimePanel screenTime;
    private SearchOverlay search;
    private BottomSheet sheet;
    private SetupScreen setupScreen;
    private HintOverlay hintOverlay;
    private AppRepository appRepository;
    private Prefs prefs;
    private Metrics metrics;
    private WeatherRepository weatherRepository;
    private UsageRepository usageRepository;

    /** Today's screen time as of the last {@link #refreshUsage()}, kept so
     *  the over-limit state can be re-evaluated after a limit change without
     *  paying for another {@code UsageStatsManager} query. */
    private long todayMillis;

    /**
     * 3.0.1. One low-priority thread for the reads that used to run on the
     * UI thread ahead of the first frame back home: the day's usage events,
     * the Screen Time panel's week, and the location providers' last fix.
     * Each can take from a few to several hundred milliseconds depending on
     * the device and the day, and none of them decides what the first frame
     * looks like — each one's result is posted back and applied when it
     * lands. Single-threaded so two usage reads can never race each other
     * to the screen out of order.
     */
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "launcher-io");
        t.setDaemon(true);
        return t;
    });

    private Palette palette;
    private IconSource icons;
    /** API 33+ only; null below that, where onBackPressed still runs. */
    private OnBackInvokedCallback backCallback;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // 2.1.3: the enumeration is cached, and this broadcast is the
            // main thing that invalidates it.
            if (appRepository != null) appRepository.invalidate();

            // 2.4.1: the icon cache is permanent, so this broadcast is also
            // the only thing that can make a stored icon wrong. An update
            // arrives as PACKAGE_ADDED with EXTRA_REPLACING and the app may
            // have changed its icon; a removal leaves entries nobody wants.
            // Both cases are the same action here: forget that package.
            String changed = packageOf(intent);
            if (changed != null && icons != null) icons.invalidatePackage(changed);

            if (drawer != null) drawer.refresh();
        }
    };

    /** The package a {@code PACKAGE_*} broadcast is about. The filter carries
     *  {@code addDataScheme("package")}, so the name is the URI's
     *  scheme-specific part. */
    private static String packageOf(Intent intent) {
        if (intent == null || intent.getData() == null) return null;
        String pkg = intent.getData().getSchemeSpecificPart();
        return pkg == null || pkg.isEmpty() ? null : pkg;
    }

    /**
     * The sky's render thread is stopped in {@link #onPause()}, which covers
     * the ordinary lock — but a launcher is the activity most likely to be
     * left resumed while the screen goes dark, and on some OEM builds it is
     * not paused for a screen-off at all. This is the guarantee that the
     * per-pixel renderer is never running against a screen nobody is looking
     * at. {@code ACTION_SCREEN_OFF} and {@code ON} are protected system
     * broadcasts and cannot be registered for in the manifest, so they are
     * registered here alongside the package receiver.
     */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (sky == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                sky.pause();
            } else if (hasWindowFocus()) {
                // Only resume if we are actually the thing on screen; the
                // screen coming on over a lock screen or another app is not
                // our cue to start rendering again. onResume covers that.
                // 3.0.1: and bring the theme and the time up to date first,
                // since on this path neither onResume nor a focus change runs.
                refreshPalette();
                refreshTime();
                resumeSky();
            }
        }
    };

    private final Handler ticker = new Handler(Looper.getMainLooper());

    /**
     * 2.1.3: rescheduled onto the next wall-clock minute rather than a flat
     * 60 seconds from now.
     *
     * <p>The old fixed delay drifted by however long the tick itself took
     * plus whatever the scheduler added, so the displayed minute flipped at
     * an arbitrary offset into it and the drift grew all day — the clock
     * could sit a full minute behind the one on the lock screen. Aligning
     * costs nothing and fixes that. It also means one wake per minute at a
     * predictable instant, which is the kind the scheduler can group with
     * other work rather than waking the CPU on its own for.
     */
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            refreshPalette();
            refreshSkyLocation();
            refreshTime();
            // Off the UI thread; lands through onFixChanged only if it moved.
            weatherRepository.refreshFix(HomeActivity.this::runInBackground, HomeActivity.this::onFixChanged);
            // Cheap: the repository's own policy decides whether this minute
            // is one where a fetch is actually due.
            weatherRepository.refresh(false, HomeActivity.this::onSkyChanged);
            // 3.0.2: the launcher's own time counts now (§9 delta 48), so
            // today's figures move while the user sits here. Only marks them
            // stale; the panel re-reads now only if it is on screen.
            screenTime.invalidateUsage();
            scheduleMinuteTick();
        }
    };

    /** Posts {@link #minuteTick} for the start of the next minute. The +50ms
     *  is so a scheduler that fires a hair early still reads the new minute
     *  rather than re-rendering the old one and waiting another whole minute
     *  to correct itself. */
    private void scheduleMinuteTick() {
        long now = System.currentTimeMillis();
        long untilNextMinute = 60_000L - Math.floorMod(now, 60_000L);
        ticker.removeCallbacks(minuteTick);
        ticker.postDelayed(minuteTick, untilNextMinute + 50L);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        goEdgeToEdge();

        prefs = new Prefs(this);
        weatherRepository = new WeatherRepository(this, prefs);
        usageRepository = new UsageRepository(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        metrics = new Metrics(dm.widthPixels, dm.density, dm.scaledDensity);

        appRepository = new AppRepository(this, getPackageManager(), prefs);
        // 2.1.2 gave the cache a disk tier so a cold start reads icons back
        // instead of re-rendering every one. 2.4.1 moves it from getCacheDir()
        // to getFilesDir(): cacheDir is storage Android may reclaim whenever
        // it likes, and an icon cache the system can empty is one that
        // periodically is not there — which puts the full render back on the
        // next Home press, the exact stutter the cache exists to remove.
        // getFilesDir() can still throw on a device whose storage is not
        // mounted yet; the cache degrades to memory-only rather than taking
        // the launcher down with it.
        DiskIconCache diskIcons = null;
        try {
            diskIcons = new DiskIconCache(getFilesDir());
        } catch (RuntimeException ignored) {
        }
        icons = new PixelArtIcons(getResources(), getPackageManager(), new IconCache(diskIcons));

        sky = new SkyView(this);

        root = new LauncherRoot(this);
        home = new HomePanel(this, metrics, prefs, icons);
        sheet = new BottomSheet(this, metrics);
        drawer = new DrawerPanel(this, metrics, prefs, appRepository, icons, sheet);
        drawer.setOnHomeListener(() -> root.goTo(LauncherRoot.VIEW_HOME));

        settings = new SettingsPanel(this, metrics, prefs);
        settings.setOnCloseListener(() -> root.goTo(LauncherRoot.VIEW_HOME));
        settings.setOnPrefsChangedListener(() -> {
            boolean moved = refreshPalette();
            refreshTime();
            // Re-render selection state even when the resolved palette itself
            // didn't change (e.g. AUTO -> an explicit choice that resolves
            // to the same colours right now). When it did change,
            // refreshPalette has already rebuilt Settings, and a second full
            // rebuild on the same tap was pure cost.
            if (!moved) settings.setPalette(palette);
        });
        settings.setDockActionListener(new SettingsPanel.DockActionListener() {
            @Override public void onReplace(int slotIndex) { openDockSheet(slotIndex); }
            @Override public void onAdd() { openDockSheet(-1); }
        });
        settings.setPermissionActionListener(new SettingsPanel.PermissionActionListener() {
            @Override public void onRequestLocation() { requestLocation(); }
            @Override public void onOpenUsageAccessSettings() { openUsageAccessSettings(); }
            @Override public void onEnableDeviceLock() { requestLockCapability(); }
            @Override public void onSetDefaultLauncher() { requestDefaultLauncher(); }
            @Override public void onEnableNotificationShade() { openAccessibilitySettings(); }
            @Override public void onEnableShizukuLock() { enableShizukuLock(); }
        });

        screenTime = new ScreenTimePanel(this, metrics, prefs);
        screenTime.setOnCloseListener(() -> root.goTo(LauncherRoot.VIEW_HOME));
        screenTime.setOnNeedsUsage(this::loadPanelUsage);
        // The limit moved, not the usage: re-judge the reading in hand. This
        // fires on every step of a slider drag, so it must never query.
        screenTime.setOnLimitChangedListener(this::applyUsage);

        // The weather region opens a weather app (DESIGN_NOTES §9 row 8). With
        // none installed it used to do nothing; now it asks for a fresh
        // reading instead. The repository's 10-minute floor means leaning on
        // it cannot turn into a poll.
        home.clock.setOnNoWeatherApp(() -> weatherRepository.refresh(true, this::onSkyChanged));
        home.clock.setOnWeatherLongPress(() -> weatherRepository.refresh(true, this::onSkyChanged));

        home.dock.setOnSlotActionListener(new DockView.SlotActionListener() {
            @Override public void onReplace(int slotIndex) { openDockSheet(slotIndex); }
            @Override public void onRemove(int slotIndex) { removeDockSlot(slotIndex); }
            @Override public void onAppInfo(String component) { openAppInfo(component); }
            @Override public void onAdd() { openDockSheet(-1); }
        });

        root.setPanels(home, settings, drawer, screenTime);

        search = new SearchOverlay(this, metrics, appRepository);
        // V9 §8: long-press searches, double-tap locks. The gesture that
        // takes the phone off the screen is the harder one to fire by
        // accident, which is why the destructive-feeling one is the tap.
        root.setLongPressListener(() -> {
            search.setPalette(palette);
            search.open();
        });
        root.setDoubleTapListener(this::lockDevice);
        root.setOnStatusBarSwipeListener(this::expandStatusBar);
        // 2.1.3: throttle the wallpaper renderer while a settled panel is
        // covering it. See LauncherRoot.SkyCoverListener for why this reports
        // on settle rather than on the gesture.
        root.setSkyCoverListener(covered -> sky.setObscured(covered));

        home.setOnRequestDefaultLauncherListener(this::requestDefaultLauncher);

        setupScreen = new SetupScreen(this, metrics);
        setupScreen.setListener(new SetupScreen.Listener() {
            @Override public void onGrantUsageAccess() { openUsageAccessSettings(); }
            @Override public void onGrantLocation() { requestLocation(); }
            @Override public void onContinue() { showHint(); }
        });

        hintOverlay = new HintOverlay(this, metrics);
        hintOverlay.setOnDismissListener(this::dismissFirstRun);

        FrameLayout stack = new FrameLayout(this);
        stack.addView(sky);   // z=0, behind everything, never moves
        stack.addView(root);
        stack.addView(sheet);   // overlay, above every panel
        stack.addView(search);  // above the sheet: double-tap wins
        stack.addView(setupScreen);
        stack.addView(hintOverlay);
        setContentView(stack);

        // 3.0.1: only what the first frame needs. The time, the usage read,
        // the permission sweep and the drawer's invalidation all ran here and
        // then again in onResume, which always follows; onResume now owns
        // them. The over-limit state comes from the last reading of today, so
        // a cold start over the limit shows it from its first frame.
        refreshPalette();
        refreshSkyLocation();
        settings.setDockEntries(home.dock.entries());
        todayMillis = usageSnapshot();
        applyUsage();

        hintOverlay.setVisibility(View.GONE);
        setupScreen.setVisibility(prefs.hintShown() ? View.GONE : View.VISIBLE);

        registerPackageReceiver();
        registerScreenReceiver();
        registerBackCallback();
    }

    /**
     * The filter carries only {@code ACTION_PACKAGE_ADDED} and
     * {@code ACTION_PACKAGE_REMOVED}, both protected system broadcasts, so
     * targetSdk 34+ does not demand an export flag here. Passing
     * {@code RECEIVER_NOT_EXPORTED} anyway on API 33+ says what is meant
     * rather than relying on that exemption: nothing outside the system has
     * any business reaching this receiver.
     */
    private void registerPackageReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageChangeFilter(), RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageChangeFilter());
        }
    }

    /** {@code SCREEN_OFF}/{@code SCREEN_ON} are protected system broadcasts,
     *  so like the package filter this needs no export flag; passing
     *  {@code RECEIVER_NOT_EXPORTED} on API 33+ says so rather than relying on
     *  the exemption. */
    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            // No Settings app to resolve it — nothing else we can do.
        }
    }

    private void showHint() {
        setupScreen.setVisibility(View.GONE);
        hintOverlay.setVisibility(View.VISIBLE);
    }

    private void dismissFirstRun() {
        hintOverlay.setVisibility(View.GONE);
        prefs.putBool(Prefs.K_HINT, true);
    }

    private static IntentFilter packageChangeFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        // 2.4.1: a component enabled or disabled, which can change which
        // activity the drawer shows and which icon it carries. Cheap to
        // listen for and the cache is permanent now, so a missed change is
        // permanent too.
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        return f;
    }

    /** Drop a pinned slot without going through the picker sheet — the sheet
     *  is for choosing an app, and removal is not a choice of app. */
    private void removeDockSlot(int slotIndex) {
        List<String> next = new ArrayList<>(home.dock.entries());
        if (slotIndex < 0 || slotIndex >= next.size()) return;
        next.remove(slotIndex);
        prefs.setDock(next);
        home.dock.setEntries(next);
        settings.setDockEntries(next);
    }

    /** The system's App Info page for a dock component. Same destination as
     *  the drawer's MORE DETAILS row, reached without a drawer row to hang
     *  an {@link AppEntry} off. */
    private void openAppInfo(String component) {
        String pkg = ComponentKey.packageOf(component);
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", pkg, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // A device with no Settings app to show. Nothing useful to do.
        }
    }

    /** slotIndex -1 means "add"; otherwise the slot being replaced. */
    private void openDockSheet(int slotIndex) {
        sheet.setPalette(palette);
        sheet.open(slotIndex < 0 ? "ADD TO DOCK" : "REPLACE DOCK SLOT " + (slotIndex + 1));

        List<String> current = new ArrayList<>(home.dock.entries());
        if (slotIndex >= 0 && slotIndex < current.size()) {
            String removed = current.get(slotIndex);
            sheet.addRow("REMOVE FROM DOCK", false, "", () -> {
                List<String> next = new ArrayList<>(home.dock.entries());
                next.remove(removed);
                prefs.setDock(next);
                home.dock.setEntries(next);
                settings.setDockEntries(next);
                sheet.close();
            });
        }

        for (AppEntry app : appRepository.load()) {
            if (app.diagnostic) continue;
            String component = app.component();
            boolean inDock = current.contains(component);
            // A clone and the app it clones carry the same label exactly, so
            // without the badge (V9 §11) this picker would show two rows the
            // user cannot tell apart.
            sheet.addRow(app.isClone() ? app.label + " \u2605" : app.label,
                    inDock, "IN DOCK", () -> {
                List<String> next = new ArrayList<>(home.dock.entries());
                if (slotIndex >= 0 && slotIndex < next.size()) {
                    next.set(slotIndex, component);
                } else if (!next.contains(component) && next.size() < 5) {
                    next.add(component);
                }
                prefs.setDock(next);
                home.dock.setEntries(next);
                settings.setDockEntries(next);
                sheet.close();
            });
        }
    }

    /**
     * Lay out behind the system bars.
     *
     * The launcher has always drawn fullscreen, so nothing about the
     * *intent* changed here — only the API that expresses it. The
     * {@code SYSTEM_UI_FLAG_*} route has been deprecated since API 30 and is
     * an outright no-op from 35, where edge-to-edge is compulsory and the
     * decor no longer fits itself to the bars for you. Below 30 the flags are
     * still the only way to say it.
     *
     * Either way the six panels keep receiving the insets through their own
     * {@code onApplyWindowInsets} overrides, which is what actually keeps
     * content out from under the status bar and the gesture pill.
     */
    private void goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** Current time as a decimal hour, the unit every time-driven system uses. */
    private float decimalHour() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY)
                + c.get(Calendar.MINUTE) / 60f
                + c.get(Calendar.SECOND) / 3600f;
    }

    /**
     * The sky's own hour, warped onto {@link SolarClock}'s anchors exactly as
     * {@code SkyView} warps it, expressed as {@link SkyRenderer#sunAlt}'s
     * -1..1 proxy.
     *
     * <p>2.5.1's AUTO theme reads this rather than a clock time, which is
     * what makes the screen go dark as the drawn sun sets instead of at some
     * fixed hour that is wrong for most of the year. With no location fix
     * there is nothing to warp against and this falls back to the same fixed
     * 6.2/18.4 table the wallpaper does, so the two never disagree.
     */
    private float skySunAltitude() {
        float realHour = decimalHour();
        SolarTimes t = weatherRepository.solarTimes();
        float hour = t == null ? realHour
                : SolarClock.warp(realHour, t.sunriseHour, t.sunsetHour, t.tomorrowSunriseHour);
        return SkyRenderer.sunAlt(hour);
    }

    /**
     * Cloud cover for the theme, or {@code NaN} when there is
     * no reading at all.
     *
     * <p>NaN rather than the synthetic value {@code weatherRepository.current}
     * invents for the wallpaper: a made-up sky is fine to draw and not fine
     * to change the user's theme on. {@link com.retro.launcher.core.LightLevel}
     * reads NaN as clear, so a launcher with no network never darkens itself
     * at noon over weather it does not have.
     */
    private float skyCloudCover() {
        if (!weatherRepository.hasReading()) return Float.NaN;
        return weatherRepository.current(decimalHour()).cloudCover;
    }

    /** Both halves of "the sky moved": the palette may flip light/dark, and
     *  the widget and wallpaper both read the new reading. */
    private void onSkyChanged() {
        refreshPalette();
        refreshTime();
    }

    /** A new location fix landed from {@link #background}: the sun's times,
     *  the moon's window and which way up it is drawn may all have moved,
     *  and with them the AUTO palette. */
    private void onFixChanged() {
        refreshSkyLocation();
        onSkyChanged();
    }

    /** @return whether the palette changed, so every panel was re-coloured */
    private boolean refreshPalette() {
        Palette next = PaletteResolver.resolve(prefs.palette(), prefs.theme(),
                decimalHour(), skySunAltitude(), skyCloudCover());
        boolean changed = palette == null
                || !palette.id.equals(next.id) || palette.dark != next.dark;
        if (changed) {
            palette = next;
            home.setPalette(palette);
            drawer.setPalette(palette);
            settings.setPalette(palette);
            screenTime.setPalette(palette);
            if (search != null) search.setPalette(palette);
        }
        return changed;
    }

    /**
     * The moon's phase is the same everywhere; which way up it looks, and
     * what real time maps onto the sky gradient, are not. Both come from the
     * coarse fix and solar times the weather repository already keeps.
     */
    private void refreshSkyLocation() {
        double[] fix = weatherRepository.fix();
        sky.setLocation(fix == null ? Float.NaN : (float) fix[0],
                         fix == null ? Float.NaN : (float) fix[1]);
        sky.setSolarTimes(weatherRepository.solarTimes());
    }

    private void refreshTime() {
        Calendar now = Calendar.getInstance();
        home.setTime(now);

        // The sky always gets a value — a synthetic one when we have no
        // reading — but the widget must not present invented weather as a
        // measurement, so it gets null and renders "--°" instead.
        Weather w = weatherRepository.current(decimalHour());
        Weather shown = weatherRepository.hasReading() ? w : null;
        home.setWeather(shown);
        settings.setWeather(shown);
        sky.setWeather(w);
    }

    /** Starts the sky's render thread. Every lifecycle path that resumes the
     *  sky goes through here. */
    private void resumeSky() {
        if (sky == null) return;
        sky.resume();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void refreshPermissionStatus() {
        boolean usageGranted = hasUsageAccess();
        boolean locationGranted = hasLocationPermission();
        settings.setPermissionStatus(locationGranted, usageGranted);
        setupScreen.setGranted(usageGranted, locationGranted);

        settings.setDeviceLockStatus(lockRoute());
        settings.setNotificationShadeStatus(ShadeService.isEnabled(this));
        settings.setShizukuLockStatus(prefs.shizukuLockEnabled(), ShizukuLock.hasPermission());

        boolean defaultLauncher = isDefaultLauncher();
        settings.setDefaultLauncherStatus(defaultLauncher);
        home.setDefaultLauncherPromptVisible(!defaultLauncher);
    }

    /** Both in one prompt. The system shows a single dialog with a
     *  precise/approximate choice; granting only approximate still works,
     *  which is why LocationSource.hasPermission accepts either. */
    private void requestLocation() {
        requestPermissions(new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    /** Which of the two lock routes is available right now — see
     *  {@link LockRoute} for why the order matters. */
    private LockRoute lockRoute() {
        return LockRoute.choose(
                ShizukuLock.isAvailable(prefs.shizukuLockEnabled()),
                ShadeService.canLockScreen(this));
    }

    /**
     * Double-tap-home-to-lock (DESIGN_NOTES §9 deltas 19 and 25; V9 §8 moved
     * it off the long press, which now opens search).
     *
     * <p>Shizuku first, then the accessibility global action. Both are the
     * power button by another name and leave the fingerprint reader working.
     * The device-admin {@code lockNow()} that used to sit underneath them is
     * gone in V9 §10: it raised the strong-auth-required flag, so Android
     * demanded the PIN on the next unlock — it locked people out of their own
     * fingerprint — and it cost an activated device admin to do it.
     *
     * <p>With neither route set up there is nothing to call, so the tap sends
     * the user somewhere they can fix that rather than doing nothing. Routes
     * are re-read on every {@link #onResume()}, same as the other
     * permission-adjacent flows in this activity.
     */
    private void lockDevice() {
        if (ShizukuLock.isAvailable(prefs.shizukuLockEnabled()) && ShizukuLock.lock()) return;
        if (ShadeService.lockScreen()) return;
        requestLockCapability();
    }

    /** The SHIZUKU LOCK row's fix action: turn the toggle on (if it was
     *  off) and (re-)request permission — this also covers "paired before,
     *  needs re-pairing after a reboot", which looks identical to Shizuku's
     *  API as "not permitted yet". */
    private void enableShizukuLock() {
        if (!prefs.shizukuLockEnabled()) prefs.putBool(Prefs.K_SHIZUKU, true);
        ShizukuLock.requestPermission(REQ_SHIZUKU);
        refreshPermissionStatus();
    }

    /**
     * Sets up a lock route, without locking — this is what the DEVICE LOCK row
     * in Settings calls, where locking the phone on a tap would be a surprise.
     *
     * <p>Accessibility settings, on every version. On API 28+ that is where
     * the global-action route is switched on. Below 28 there is no global
     * action to switch on and Shizuku is the only lock route the device has,
     * but the same screen still enables the shade swipe, and sending someone
     * to a device-admin activation dialog instead — which is what happened
     * here before V9 §10 — would hand back a lock that refuses the
     * fingerprint. The SHIZUKU LOCK row directly below is the other half of
     * the answer, and the caption says so.
     */
    private void requestLockCapability() {
        openAccessibilitySettings();
    }

    /** DESIGN_NOTES §9 delta 20: RoleManager on 29+, a PackageManager
     *  comparison below that — API 26's floor predates RoleManager. */
    private boolean isDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            return rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME);
        }
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName);
    }

    /**
     * Opens the system screen where this app can be picked as the home app.
     *
     * <p>Not {@code RoleManager.createRequestRoleIntent(ROLE_HOME)}, which is
     * what the previous build used and why the button appeared dead: ROLE_HOME
     * is marked non-requestable in the platform's role definitions, so the
     * system's request-role activity finishes immediately without ever drawing
     * a dialog. Third-party launchers have to send the user to the settings
     * screen instead, on every API level.
     *
     * <p>Three tries, narrowest first: the dedicated home-app screen, then the
     * default-apps list, then Settings itself. OEM builds vary in which of the
     * first two they ship.
     */
    private void requestDefaultLauncher() {
        if (startSafely(new Intent(Settings.ACTION_HOME_SETTINGS))) return;
        if (startSafely(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) return;
        startSafely(new Intent(Settings.ACTION_SETTINGS));
    }

    /**
     * Starts {@code intent}, reporting whether it went, so a caller can fall
     * through to its next candidate.
     *
     * <p>Deliberately no {@code resolveActivity} pre-check. Under API 30+
     * package visibility that call can answer null for an activity that would
     * have launched perfectly well, and a false negative here means a dead
     * button — the exact failure this method exists to end. Attempting the
     * start and catching is both the honest test and what the rest of this
     * activity already does.
     */
    private boolean startSafely(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    /** Sends the user to Accessibility settings to switch on the shade
     *  fallback — see {@link ShadeService}. */
    /**
     * 3.0.0: a disclosure first, then the settings screen.
     *
     * <p>Google Play requires this of any app using the Accessibility API
     * that is not itself an accessibility tool: say in the app, before the
     * user is sent to turn it on, exactly what the service is used for and
     * what it does not touch, and get an affirmative tap. Without it the
     * app is rejected at review, whatever the Play Console declaration says.
     */
    private void openAccessibilitySettings() {
        sheet.setPalette(palette);
        sheet.open("ACCESSIBILITY");
        sheet.addText("MINIMAL RETRO LAUNCHER USES ANDROID'S ACCESSIBILITY SERVICE FOR "
                + "TWO THINGS ONLY: LOCKING THE SCREEN WHEN YOU DOUBLE-TAP HOME, AND "
                + "OPENING THE NOTIFICATION SHADE WHEN YOU SWIPE DOWN.");
        sheet.addText("IT DOES NOT READ YOUR SCREEN, YOUR NOTIFICATIONS OR ANYTHING YOU "
                + "TYPE, AND NOTHING IT SEES IS STORED OR SENT ANYWHERE.");
        sheet.addRow("AGREE AND OPEN SETTINGS", false, "", () -> {
            sheet.close();
            startSafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        sheet.addRow("NO THANKS", false, "", sheet::close);
    }

    /**
     * Swipe-down-opens-the-shade (DESIGN_NOTES §9 delta 21). Two routes, in
     * order of how little they ask of the user.
     *
     * <p>First the reflection into {@code StatusBarManager}, guarded by the
     * EXPAND_STATUS_BAR permission in the manifest. It needs no setup at all,
     * and still works on pre-Android-12 and on a number of OEM builds. It is
     * also a denylisted non-SDK interface, so at this app's targetSdk the
     * lookup itself throws on a current AOSP device — which is exactly why the
     * previous build's swipe did nothing, silently.
     *
     * <p>Then {@link ShadeService}, the accessibility fallback, which does
     * work everywhere but only once the user has switched it on. While it is
     * off this method still ends in a no-op; the Settings row is where that
     * gets explained and fixed.
     */
    private void expandStatusBar() {
        if (expandViaStatusBarManager()) return;
        ShadeService.expandNotificationShade();
    }

    private boolean expandViaStatusBarManager() {
        try {
            Object statusBarService = getSystemService("statusbar");
            if (statusBarService == null) return false;
            Class<?> statusBarManager = Class.forName("android.app.StatusBarManager");
            statusBarManager.getMethod("expandNotificationsPanel").invoke(statusBarService);
            return true;
        } catch (Throwable ignored) {
            // Blocked, absent, or refused — fall through to the service.
            return false;
        }
    }

    /** Pulls fresh device usage data and drives the panel, the over-limit
     *  wallpaper desaturation, and the clock widget's marker from it. */
    /**
     * What the <em>home screen</em> needs from usage: today's total, which
     * decides the over-limit dot and how far the wallpaper desaturates.
     *
     * <p>2.4.1: one {@code UsageStatsManager} query, not five. The seven-day
     * sweep, the pickup count and the most-used ranking are the Screen Time
     * panel's, and are now fetched by {@link #loadPanelUsage()} when that
     * panel is first shown — see {@code ScreenTimePanel}'s note. A seven-day
     * {@code queryEvents} on the UI thread is not something a Home press
     * should ever wait for.
     */
    private void refreshUsage() {
        // 3.0.1: off the UI thread. queryEvents returns every event since
        // midnight, and on a heavy day it was the single biggest stall on the
        // way back home. Until it lands, the reading already in hand stands;
        // a caller that changed the rule rather than the usage (the limit
        // slider) calls applyUsage directly and never waits.
        final long now = System.currentTimeMillis();
        runInBackground(() -> {
            long read = usageRepository.todayMillis(now);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                boolean moved = read / 60_000L != todayMillis / 60_000L;
                todayMillis = read;
                if (moved) saveUsageSnapshot(now, read);
                applyUsage();
                screenTime.invalidateUsage();
            });
        });
    }

    /** {@link #background}, tolerating a call that arrives after onDestroy
     *  has shut it down. */
    private void runInBackground(Runnable r) {
        try {
            background.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Destroyed; nobody is left to show the answer to.
        }
    }

    /** Today's last known total, or 0 if the stored one is from another day. */
    private long usageSnapshot() {
        long dayStart = UsageMath.startOfDay(System.currentTimeMillis(), TimeZone.getDefault());
        if (prefs.getLong(Prefs.K_USAGE_DAY, Long.MIN_VALUE) != dayStart) return 0L;
        return prefs.getLong(Prefs.K_USAGE_MS, 0L);
    }

    private void saveUsageSnapshot(long now, long millis) {
        prefs.putLong(Prefs.K_USAGE_DAY, UsageMath.startOfDay(now, TimeZone.getDefault()));
        prefs.putLong(Prefs.K_USAGE_MS, millis);
    }

    /** Drives the over-limit dot and the wallpaper's desaturation from
     *  {@link #todayMillis}. All cheap; no query. */
    private void applyUsage() {
        int limit = prefs.limit();
        boolean over = UsageMath.isOverLimit(todayMillis, limit);
        float overage = UsageMath.usageFraction(todayMillis, limit) - 1f;
        sky.setDesaturation(Math.max(0f, Math.min(1f, overage)));
        home.clock.setOverLimit(over);
    }

    /** The Screen Time panel's own figures, read when it is about to be seen. */
    private void loadPanelUsage() {
        // 3.0.1: off the UI thread, and two scans rather than four. The panel
        // opens with the figures it last had and they update in place.
        final long now = System.currentTimeMillis();
        runInBackground(() -> {
            UsageRepository.PanelFigures f = usageRepository.panelFigures(now, 6);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                screenTime.setUsage(f.todayMillis, f.last7Millis, f.pickups, f.mostUsed);
                // 3.0.2: the panel's reading is the newer one, and the dot
                // and the desaturation should not lag behind the number
                // printed above them.
                if (f.todayMillis / 60_000L != todayMillis / 60_000L) {
                    todayMillis = f.todayMillis;
                    saveUsageSnapshot(now, f.todayMillis);
                    applyUsage();
                }
            });
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION) {
            refreshPermissionStatus();
            // Just granted: go and get a reading now rather than waiting out
            // the freshness window with an empty widget.
            weatherRepository.refresh(true, this::onSkyChanged);
            weatherRepository.refreshFix(this::runInBackground, this::onFixChanged);
        }
    }

    /**
     * 3.0.1: split in two around the first frame.
     *
     * <p>Everything here runs before Android draws the home screen again, so
     * everything here is time the user spends looking at the frame from when
     * they left — the old clock, the old colours, a frozen sky. That used to
     * include a day's worth of usage events, three or four location reads,
     * six Settings rebuilds and a Binder sweep of every permission, which on
     * a busy phone added up to seconds.
     *
     * <p>Now only what decides the look of that first frame runs here, in the
     * order the eye needs it: the palette (the reason the theme could be an
     * hour stale — nothing on this path re-resolved it; the minute tick did,
     * up to a minute later), the sky's inputs, the time. The rest waits until
     * that frame is on screen ({@link #afterFirstFrame}), and the slow reads
     * among it go to {@link #background}.
     */
    @Override protected void onResume() {
        super.onResume();
        refreshPalette();
        refreshSkyLocation();
        // Render the current minute immediately, then fall into step with the
        // wall clock rather than 60 seconds from this instant.
        refreshTime();
        scheduleMinuteTick();
        resumeSky();
        afterFirstFrame(this::onResumeSettled);
    }

    /** The half of {@link #onResume} nothing on the first frame depends on. */
    private void onResumeSettled() {
        if (isDestroyed()) return;
        drawer.refresh();
        refreshPermissionStatus();
        refreshUsage();
        weatherRepository.refreshFix(this::runInBackground, this::onFixChanged);
        weatherRepository.refresh(false, this::onSkyChanged);
    }

    /**
     * Runs {@code r} on the UI thread once the next frame has been drawn.
     *
     * <p>A frame callback fires inside the frame, before its layout and
     * draw; a message posted from there queues behind the rest of that
     * frame's work, so it runs just after the frame is on its way to the
     * screen.
     */
    private void afterFirstFrame(Runnable r) {
        android.view.Choreographer.getInstance().postFrameCallback(t -> ticker.post(r));
    }

    /**
     * The safety net under {@link #screenReceiver}'s {@code SCREEN_OFF} pause.
     *
     * <p>That pause is deliberately unconditional, because the case it exists
     * for is a ROM that leaves the launcher resumed behind a dark screen. But
     * an activity that was never paused is never resumed either, so on such a
     * ROM {@code onResume} is not guaranteed to be the thing that starts the
     * renderer again — and a wallpaper frozen until the next app switch is a
     * worse bug than the one being fixed. Regaining focus always happens when
     * the launcher becomes visible, whatever the OEM did with the lifecycle.
     * {@code resume()} is idempotent, so the overlap with onResume costs
     * nothing.
     */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (sky == null) return;
        if (hasFocus) {
            // 3.0.1: focus comes back without a resume too — the shade pulled
            // down and let go, a dialog dismissed, a ROM that never paused
            // the launcher for a screen-off. The theme and the time follow
            // the moment the home screen is back in front, not at the next
            // minute tick. Both are cheap when nothing moved.
            refreshPalette();
            refreshTime();
            resumeSky();
        } else {
            sky.pause();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(minuteTick);
        sky.pause();
    }

    /**
     * 2.1.2. A launcher is the process the system most wants to keep resident
     * and, being idle in the background most of the time, the one it will
     * happily trim to get there. Handing the icon bitmaps back on request is
     * how the launcher stays alive rather than being killed outright and
     * cold-starting on the next Home press — which is the "always on" the
     * caching work is really for.
     *
     * <p>Only the memory tier goes. Every byte of it is reconstructible from
     * the disk tier for the price of a ~300-byte PNG decode, so this is close
     * to free to undo, and dropping the files too would mean re-rendering
     * every icon from PackageManager on the way back.
     */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND && icons != null) icons.onTrimMemory();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        background.shutdownNow();
        unregisterReceiver(packageReceiver);
        unregisterReceiver(screenReceiver);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
    }

    /**
     * Only the configuration changes that cannot alter the layout are handled
     * here; see the {@code configChanges} list in the manifest for why the
     * ones that can are deliberately left to recreate the activity.
     */
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        refreshPalette();
    }

    /**
     * Predictive back, API 33+.
     *
     * At targetSdk 36 the platform stops calling {@link #onBackPressed()}
     * altogether, so without this the override below would go quietly dead
     * and back would start closing the launcher — the one thing a home screen
     * must never do. Registered for the activity's whole life at
     * {@code PRIORITY_DEFAULT}, which also means the system never runs its
     * "swipe back to leave the app" animation here; there is nothing behind a
     * launcher to go back to.
     */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        backCallback = this::handleBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }

    /** Back must never leave the home screen. The pre-33 path. */
    @Deprecated
    @Override public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (search.isOpen()) {
            search.close();
        } else if (sheet.isOpen()) {
            sheet.close();
        } else if (root.currentView() != LauncherRoot.VIEW_HOME) {
            root.goTo(LauncherRoot.VIEW_HOME);
        }
    }
}
