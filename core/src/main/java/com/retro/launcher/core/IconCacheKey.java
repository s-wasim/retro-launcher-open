package com.retro.launcher.core;

/**
 * What an icon is cached under, and where.
 *
 * <p>Pure string arithmetic so the part that is easy to get silently wrong —
 * a filename that collides, or one that escapes its directory — is testable on
 * a bare JDK. The bitmaps themselves live in {@code icons/IconCache} and
 * {@code icons/DiskIconCache}, which are the only things here that need an
 * Android type.
 *
 * <h3>2.4.1: permanent, and grouped by package</h3>
 * The tree lives under {@code filesDir}, not {@code cacheDir}, and nothing in
 * it expires. {@code cacheDir} is storage Android may reclaim whenever it
 * likes, and the 24-hour TTL meant every icon was re-rendered from
 * {@code PackageManager} once a day whatever happened — both of which put the
 * slow path back on a cold start, which is exactly what the cache exists to
 * remove.
 *
 * <p>Nothing expiring means staleness has to be handled where it actually
 * originates: an app changing its own icon. That arrives as
 * {@code ACTION_PACKAGE_ADDED} with {@code EXTRA_REPLACING}, which names the
 * package — so entries are filed in a per-package subdirectory and that one
 * directory is deleted. A hashed filename cannot be reversed to the package
 * that owns it, which is why the package is a directory level rather than
 * another field in the name.
 *
 * <p><b>{@link #key} deliberately carries no pixel size.</b> Before 2.1.2 it
 * did, because the cache stored icons already upscaled to whatever the drawer,
 * the dock or the sheet had asked for — three or four entries per app, each
 * one a 130x130 ARGB_8888 bitmap at 67 KB. The cache now stores the icon at
 * the resolution it was <em>drawn</em> at (24x24 converted, 16x16 mark),
 * 2.3 KB, and the upscale happens in the ImageView with filtering off. Same
 * nearest-neighbour pixels on screen, one entry per app instead of four, and
 * a whole device's worth of apps fits in the budget instead of sixty.
 *
 * <p>That works because both of those stages are grid-snapped pixel art: a
 * mark is whole {@code drawRect}s on a 16x16 grid and a converted icon is a
 * 24x24 quantized square, so upscaling either by nearest-neighbour in the
 * ImageView reproduces {@code createScaledBitmap(…, false)} pixel for pixel.
 * The letter tile is not — it is antialiased text, whose glyph shape at 130px
 * is not recoverable from a smaller bitmap — so it keeps the size in its key
 * and uses {@link #sizedKey}. That is stage 3, which only fires for an app
 * with no icon of its own at all, so on a normal device it is a handful of
 * entries and usually none.
 */
public final class IconCacheKey {

    private IconCacheKey() {}

    /** Bump when the stored bitmap's meaning changes — a different source
     *  resolution, a different quantizer — so old files are ignored rather
     *  than decoded into something that no longer matches. The directory name
     *  carries it, so a bump orphans the old tree and {@code sweep()} reclaims
     *  it.
     *
     *  <p>2 in 2.4.1: the tree moved from {@code cacheDir} to {@code filesDir}
     *  and gained a per-package level, so a v1 tree is not a v2 tree even
     *  though the PNGs inside it would still decode. */
    public static final int FORMAT_VERSION = 2;

    /** Stage 1 of {@code PixelArtIcons}: a hand-drawn mark. */
    public static final String STAGE_MARK   = "m";
    /** Stage 2: the app's own icon, quantized through the palette ramp. */
    public static final String STAGE_ICON   = "i";
    /** Stage 3: the letter tile, for an app with no icon at all. */
    public static final String STAGE_LETTER = "t";

    /**
     * The in-memory cache key.
     *
     * @param component {@code pkg/activity}, or {@code pkg/activity@serial}
     * @param paletteId the resolved palette, because every stage colours
     *                  through it — a cached icon from another palette is the
     *                  wrong icon, not a stale one
     * @param dark      light/dark within that palette
     * @param stage     one of the {@code STAGE_*} constants
     */
    public static String key(String component, String paletteId, boolean dark, String stage) {
        return nz(component) + '|' + nz(paletteId) + '|' + (dark ? 'd' : 'l') + '|' + nz(stage);
    }

    /**
     * {@link #key} plus the pixel size, for {@link #STAGE_LETTER} — the one
     * stage whose output is not grid-snapped pixel art and so cannot be
     * reconstructed by upscaling a smaller cached copy. See the class note.
     */
    public static String sizedKey(String component, String paletteId, boolean dark,
                                  String stage, int sizePx) {
        return key(component, paletteId, dark, stage) + '|' + sizePx;
    }

    /**
     * A filename for {@link #key}. Component keys contain {@code /}, {@code .}
     * and {@code @}, none of which belong in a filename, so the name is a
     * 64-bit FNV-1a hash of the key rendered as hex — fixed length, no
     * separator to escape, and no path component that could escape the cache
     * directory.
     *
     * <p>A hash can collide. At 64 bits and a few hundred icons the odds are
     * around one in 10^14, and a collision costs one app wearing another's
     * icon until that package next changes — not a crash, and not a security
     * boundary, since everything in this directory is our own.
     */
    public static String fileName(String key) {
        return hex(fnv1a(nz(key))) + ".png";
    }

    /** The cache subdirectory, versioned so {@link #FORMAT_VERSION} bumps
     *  orphan the old tree rather than mixing formats inside one. */
    public static String directoryName() {
        return "icons-v" + FORMAT_VERSION;
    }

    /**
     * The per-package subdirectory an entry is filed under.
     *
     * <p>Hashed for the same reason the filename is: a package name is a
     * dotted path and a directory name must not be able to contain a
     * separator, a {@code ..}, or anything else that would resolve outside the
     * tree. Same FNV-1a, so two packages colliding is as unlikely as two keys
     * colliding, and costs the same — one extra directory cleared when either
     * app changes.
     *
     * @param packageName the owning package; null or blank files under a
     *                    shared bucket rather than throwing, since a
     *                    malformed component must not take the launcher down
     */
    public static String packageDirName(String packageName) {
        String pkg = packageName == null ? "" : packageName.trim();
        return hex(fnv1a(pkg));
    }

    /** The package part of a {@link #key} component — everything before the
     *  first {@code /}. Kept here so the cache and {@link ComponentKey} cannot
     *  disagree about what a component's package is. */
    public static String packageOf(String component) {
        return ComponentKey.packageOf(component);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static long fnv1a(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** Zero-padded to 16 so every name is the same length. */
    private static String hex(long v) {
        StringBuilder b = new StringBuilder(16);
        for (int shift = 60; shift >= 0; shift -= 4) {
            b.append(Character.forDigit((int) ((v >>> shift) & 0xF), 16));
        }
        return b.toString();
    }
}
