package com.retro.launcher.core;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 2.1.2. The cache key decides two things that fail quietly if they are
 * wrong: whether two different icons can land on one filename, and whether an
 * entry ever expires. Both are pure string and time arithmetic, so both are
 * testable without an emulator — which is the point of keeping them out of
 * {@code icons/}.
 */
public class IconCacheKeyTest {

    private static final String PKG = "com.whatsapp/.Main";

    // ---- the key ---------------------------------------------------------

    @Test public void theKeyCarriesNoPixelSize() {
        // The whole 2.1.2 memory win: one entry per app, not one per size the
        // drawer, the dock and the sheet each happen to ask for.
        String key = IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON);
        assertFalse(key.contains("130"));
        assertFalse(key.matches(".*\\d{2,}.*"));
    }

    @Test public void theLetterTileKeepsItsSizeBecauseItIsNotGridSnapped() {
        // Stage 3 is antialiased text: a 130px glyph is not recoverable by
        // upscaling a 64px one, so it is the one stage that stays per-size.
        assertNotEquals(
                IconCacheKey.sizedKey(PKG, "dusk", true, IconCacheKey.STAGE_LETTER, 130),
                IconCacheKey.sizedKey(PKG, "dusk", true, IconCacheKey.STAGE_LETTER, 140));
    }

    @Test public void aSizedKeyNeverCollidesWithItsUnsizedForm() {
        assertNotEquals(
                IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_LETTER),
                IconCacheKey.sizedKey(PKG, "dusk", true, IconCacheKey.STAGE_LETTER, 130));
    }

    @Test public void adifferentPaletteIsADifferentIcon() {
        assertNotEquals(
                IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON),
                IconCacheKey.key(PKG, "noon", true, IconCacheKey.STAGE_ICON));
    }

    @Test public void lightAndDarkWithinOnePaletteAreDifferentIcons() {
        assertNotEquals(
                IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON),
                IconCacheKey.key(PKG, "dusk", false, IconCacheKey.STAGE_ICON));
    }

    @Test public void eachStageIsItsOwnEntry() {
        Set<String> keys = new HashSet<>();
        keys.add(IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_MARK));
        keys.add(IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON));
        keys.add(IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_LETTER));
        assertEquals(3, keys.size());
    }

    @Test public void aCloneDoesNotShareItsOriginalsEntry() {
        assertNotEquals(
                IconCacheKey.key("com.whatsapp/.Main", "dusk", true, IconCacheKey.STAGE_ICON),
                IconCacheKey.key("com.whatsapp/.Main@95", "dusk", true, IconCacheKey.STAGE_ICON));
    }

    @Test public void nullsDoNotThrow() {
        assertNotNull(IconCacheKey.key(null, null, false, null));
        assertNotNull(IconCacheKey.fileName(null));
    }

    // ---- the filename ----------------------------------------------------

    @Test public void theFilenameHasNoPathSeparatorsLeftInIt() {
        // The component key is full of '/', '.' and '@'; none may survive
        // into a name that gets resolved against the cache directory.
        String name = IconCacheKey.fileName(
                IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON));
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
        assertFalse(name.contains(".."));
        assertTrue(name.endsWith(".png"));
    }

    @Test public void everyFilenameIsTheSameLength() {
        assertEquals(
                IconCacheKey.fileName("a").length(),
                IconCacheKey.fileName("a very much longer key than that one").length());
        assertEquals(20, IconCacheKey.fileName("a").length()); // 16 hex + ".png"
    }

    @Test public void theFilenameIsStableAcrossCalls() {
        String key = IconCacheKey.key(PKG, "dusk", true, IconCacheKey.STAGE_ICON);
        assertEquals(IconCacheKey.fileName(key), IconCacheKey.fileName(key));
    }

    @Test public void distinctKeysGetDistinctFilenames() {
        Set<String> names = new HashSet<>();
        String[] palettes = { "dawn", "noon", "dusk", "night", "storm" };
        String[] stages = {
                IconCacheKey.STAGE_MARK, IconCacheKey.STAGE_ICON, IconCacheKey.STAGE_LETTER };
        int expected = 0;
        for (int i = 0; i < 200; i++) {
            for (String palette : palettes) {
                for (String stage : stages) {
                    for (boolean dark : new boolean[] { true, false }) {
                        names.add(IconCacheKey.fileName(
                                IconCacheKey.key("com.app" + i + "/.Main", palette, dark, stage)));
                        expected++;
                    }
                }
            }
        }
        assertEquals("a collision in 6000 realistic keys", expected, names.size());
    }

    @Test public void theDirectoryCarriesTheFormatVersion() {
        assertEquals("icons-v" + IconCacheKey.FORMAT_VERSION, IconCacheKey.directoryName());
    }

    // ---- the per-package directory (2.4.1) --------------------------------

    @Test public void eachPackageGetsItsOwnDirectory() {
        assertNotEquals(IconCacheKey.packageDirName("com.whatsapp"),
                IconCacheKey.packageDirName("com.instagram.android"));
    }

    @Test public void theDirectoryIsStableForOnePackage() {
        assertEquals(IconCacheKey.packageDirName("com.whatsapp"),
                IconCacheKey.packageDirName("com.whatsapp"));
    }

    @Test public void aPackageDirectoryCannotEscapeTheTree() {
        // A package name is a dotted path and the value goes straight into a
        // File(). Nothing that could resolve outside the cache directory may
        // survive into it.
        for (String hostile : new String[] {
                "../../etc", "a/b", "..", ".", "com.x/../../y", "\\windows" }) {
            String dir = IconCacheKey.packageDirName(hostile);
            assertFalse(dir, dir.contains("/"));
            assertFalse(dir, dir.contains("\\"));
            assertFalse(dir, dir.contains(".."));
            assertEquals(16, dir.length());
        }
    }

    @Test public void aMissingPackageNameDoesNotThrow() {
        assertNotNull(IconCacheKey.packageDirName(null));
        assertNotNull(IconCacheKey.packageDirName(""));
        assertEquals(IconCacheKey.packageDirName(null), IconCacheKey.packageDirName("  "));
    }

    @Test public void everyEntryForOnePackageSharesOneDirectory() {
        // This is what makes invalidatePackage a single directory delete: all
        // of an app's stages, palettes and sizes must land together.
        String pkg = "com.whatsapp";
        String dir = IconCacheKey.packageDirName(pkg);
        for (String palette : new String[] { "dawn", "dusk", "night" }) {
            for (String stage : new String[] {
                    IconCacheKey.STAGE_MARK, IconCacheKey.STAGE_ICON, IconCacheKey.STAGE_LETTER }) {
                for (boolean dark : new boolean[] { true, false }) {
                    String component = pkg + "/.Main";
                    assertEquals(dir, IconCacheKey.packageDirName(
                            IconCacheKey.packageOf(component)));
                }
            }
        }
    }

    @Test public void theComponentsPackageIsWhatTheDirectoryIsKeyedOn() {
        assertEquals("com.whatsapp", IconCacheKey.packageOf("com.whatsapp/.Main"));
        // A clone lives in another profile but is still the same package, so
        // it shares the directory and is invalidated with its original.
        assertEquals("com.whatsapp", IconCacheKey.packageOf("com.whatsapp/.Main@95"));
        assertEquals(IconCacheKey.packageDirName(IconCacheKey.packageOf("com.whatsapp/.Main")),
                IconCacheKey.packageDirName(IconCacheKey.packageOf("com.whatsapp/.Main@95")));
    }

    @Test public void theFormatVersionMovedWhenTheLayoutDid() {
        // v1 was flat under cacheDir with a TTL; v2 is per-package under
        // filesDir and permanent. A v1 tree must not be read as a v2 tree.
        assertTrue(IconCacheKey.FORMAT_VERSION >= 2);
        assertEquals("icons-v" + IconCacheKey.FORMAT_VERSION, IconCacheKey.directoryName());
    }

    @Test public void nothingInTheKeyApiTalksAboutExpiry() throws Exception {
        // The permanence is the feature: if a TTL ever comes back, it should
        // be a deliberate decision that breaks this test first.
        for (java.lang.reflect.Method m : IconCacheKey.class.getDeclaredMethods()) {
            assertFalse(m.getName(), m.getName().toLowerCase().contains("fresh"));
            assertFalse(m.getName(), m.getName().toLowerCase().contains("expire"));
        }
        for (java.lang.reflect.Field f : IconCacheKey.class.getDeclaredFields()) {
            assertFalse(f.getName(), f.getName().contains("TTL"));
        }
    }
}
