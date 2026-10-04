package com.retro.launcher.icons;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.retro.launcher.core.IconCacheKey;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The second tier of the icon cache: PNGs under {@code filesDir}, kept for the
 * life of the install.
 *
 * <h3>Why disk at all</h3>
 * Stage 2 of {@link PixelArtIcons} — the app's own icon, quantized through the
 * palette ramp — is the expensive one: a {@code PackageManager} drawable load
 * across a Binder call, an adaptive-icon crop, 576 nearest-ramp lookups and a
 * scale, per app. Before 2.1.2 it ran again on every cold start, because a
 * cold start has no memory tier. This is what keeps the APK small — no icon
 * ships in the package — while paying the generation cost once rather than
 * once a launch.
 *
 * <h3>2.4.1: filesDir, and nothing expires</h3>
 * Two changes, both aimed at the same thing: a cold start that costs nothing.
 *
 * <p>{@code cacheDir} is storage Android may reclaim at any time, without
 * warning and without the app running. An icon cache the system can empty is
 * an icon cache that periodically is not there, and the launcher then pays the
 * full render for every installed app on the next Home press — the exact
 * stutter the cache exists to remove. {@code filesDir} is app data and is only
 * cleared when the user clears it.
 *
 * <p>The 24-hour TTL is gone for the same reason: it guaranteed that every
 * icon was re-rendered from {@code PackageManager} once a day, so at least one
 * launch a day was always the slow one. What the TTL was really covering is an
 * app changing its own icon, and that has a precise signal —
 * {@code ACTION_PACKAGE_ADDED} carrying {@code EXTRA_REPLACING} — which names
 * the package. Entries are therefore filed in a per-package subdirectory and
 * {@link #invalidatePackage} drops exactly that one. Nothing else is ever
 * stale, so nothing else needs re-rendering.
 *
 * <p>A byte ceiling remains, because "never expires" is not "never grows": the
 * palette is part of the key, so a user who tries all ten palettes leaves ten
 * copies of every icon behind. {@link #sweep()} bounds that by deleting the
 * least recently written until the tree is back under {@link #BUDGET_BYTES}.
 *
 * <h3>What is not written here</h3>
 * Stage 1, the hand-drawn marks: a few dozen {@code drawRect} calls against a
 * 16x16 grid with no PackageManager round-trip, so a PNG decode would cost
 * more than redrawing them. They stay in the memory tier only. Stages 2 and 3
 * are both written.
 *
 * <h3>Threading</h3>
 * Reads happen on whatever thread asks — a decode of a ~300-byte PNG, well
 * inside a frame, and doing them asynchronously would mean an icon that pops
 * in after its row is already on screen. Writes go to a single background
 * thread, because they are housekeeping and nothing waits on them.
 */
public final class DiskIconCache {

    /**
     * Total bytes of PNG the tree may hold before {@link #sweep()} starts
     * deleting the oldest. A few hundred 24x24 PNGs come to well under a tenth
     * of this in one palette; the headroom is for a user who switches palettes,
     * since each one is a separate key.
     */
    private static final long BUDGET_BYTES = 4L * 1024L * 1024L;

    /** PNG is lossless, so quality is ignored for it — passed for the API's
     *  sake. Lossless matters: these are quantized to a handful of exact
     *  palette colours and JPEG ringing would undo the quantization. */
    private static final int PNG_QUALITY = 100;

    private final File dir;
    private final ExecutorService writer;

    /** Set once the directory turns out to be unusable — a full disk, a
     *  device whose files dir the system has yanked. Every method then no-ops
     *  and the cache is memory-only, which is exactly how it behaved before
     *  2.1.2. */
    private volatile boolean disabled;

    public DiskIconCache(File filesDir) {
        this.dir = new File(filesDir, IconCacheKey.directoryName());
        this.writer = Executors.newSingleThreadExecutor(lowPriorityThreads());
        if (!dir.isDirectory() && !dir.mkdirs()) disabled = true;
    }

    /**
     * Background work here is never urgent and must never compete with the UI
     * thread for a core, so the writer runs below the default priority.
     */
    private static ThreadFactory lowPriorityThreads() {
        return r -> {
            Thread t = new Thread(r, "IconDiskCache");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            return t;
        };
    }

    /** @return the cached bitmap, or null when nothing is stored for this key */
    public Bitmap read(String key, String packageName) {
        if (disabled) return null;
        File f = fileFor(key, packageName);
        try {
            if (!f.isFile()) return null;
        } catch (SecurityException e) {
            return null;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            // The stored PNG is already at the resolution we draw from, so
            // nothing may resample it on the way in.
            opts.inScaled = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), opts);
            if (bmp == null) delete(f);   // truncated by a kill mid-write
            return bmp;
        } catch (RuntimeException | OutOfMemoryError e) {
            delete(f);
            return null;
        }
    }

    /** Fire-and-forget. The caller already has the bitmap it needs. */
    public void write(String key, String packageName, Bitmap bitmap) {
        if (disabled || bitmap == null || bitmap.isRecycled()) return;
        File target = fileFor(key, packageName);
        try {
            writer.execute(() -> writeNow(target, bitmap));
        } catch (RuntimeException ignored) {
            // Executor shut down between the check and the submit.
        }
    }

    /**
     * Drops every icon belonging to one package.
     *
     * <p>This is the whole of the staleness story now that nothing expires.
     * Called for {@code PACKAGE_ADDED} (which is also how a <em>replace</em>
     * arrives), {@code PACKAGE_REMOVED} and {@code PACKAGE_CHANGED}: in the
     * first case the app may have a new icon, and in the others the entries
     * are simply no longer wanted. Deleting a directory that does not exist is
     * not an error, so an unknown package costs a stat and nothing else.
     */
    public void invalidatePackage(String packageName) {
        if (disabled || packageName == null || packageName.isEmpty()) return;
        File pkgDir = new File(dir, IconCacheKey.packageDirName(packageName));
        try {
            writer.execute(() -> deleteTree(pkgDir));
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Writes to a temporary name and renames on success, so a process death
     * mid-write leaves a stray {@code .tmp} rather than a half-written PNG
     * that {@link #read} would have to decode to discover was broken.
     */
    private void writeNow(File target, Bitmap bitmap) {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;

        File tmp = new File(target.getPath() + ".tmp");
        boolean ok = false;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            ok = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            ok = false;
        }
        if (!ok || !tmp.renameTo(target)) {
            delete(tmp);
        }
    }

    /**
     * Deletes half-written leftovers, then the least recently written entries
     * until the tree is back inside {@link #BUDGET_BYTES}. Cheap — a directory
     * walk and some {@code lastModified()} calls — but it still runs off the
     * UI thread, queued behind whatever writes are already pending.
     */
    public void sweep() {
        if (disabled) return;
        try {
            writer.execute(this::sweepNow);
        } catch (RuntimeException ignored) {
        }
    }

    private void sweepNow() {
        File[] packages = dir.listFiles();
        if (packages == null) return;

        java.util.List<File> entries = new java.util.ArrayList<>();
        long total = 0L;
        for (File pkgDir : packages) {
            File[] files = pkgDir.isDirectory() ? pkgDir.listFiles() : new File[]{ pkgDir };
            if (files == null) continue;
            for (File f : files) {
                // A .tmp is a write we did not finish; it is never a cache
                // hit, so it is only ever litter.
                if (f.getName().endsWith(".tmp")) { delete(f); continue; }
                entries.add(f);
                total += f.length();
            }
        }
        if (total <= BUDGET_BYTES) return;

        // Over budget: drop the least recently written until we are back
        // under. Sorting by mtime approximates least-recently-used — the real
        // thing would need a touch on every read, which is a write per icon
        // per frame and costs far more than it saves.
        entries.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : entries) {
            if (total <= BUDGET_BYTES) return;
            long size = f.length();
            if (delete(f)) total -= size;
        }
    }

    private File fileFor(String key, String packageName) {
        return new File(new File(dir, IconCacheKey.packageDirName(packageName)),
                IconCacheKey.fileName(key));
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        delete(f);
    }

    private static boolean delete(File f) {
        try {
            return f.delete();
        } catch (SecurityException e) {
            return false;
        }
    }
}
