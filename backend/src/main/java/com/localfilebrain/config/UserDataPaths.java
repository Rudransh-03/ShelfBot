package com.localfilebrain.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Single source of truth for where Rudo keeps ALL persistent data.
 *
 * Historically every durable path resolved relative to the JVM's current
 * working directory — which, in a packaged desktop build, pointed inside the
 * application bundle. That meant an auto-update (which replaces the bundle
 * wholesale) wiped the user's index, chats, and settings, and on macOS it
 * also broke the code-signature seal. This class removes that dependency: all
 * data now lives under a stable per-user OS directory.
 *
 * Resolution order for the data root:
 *   1. The {@code SHELFBOT_DATA_DIR} environment variable, when set. This is how
 *      the Electron main process hands the backend the exact directory it chose
 *      (so the desktop app and any CLI/test run agree on one location).
 *   2. The OS-standard per-application data directory otherwise:
 *        macOS   → ~/Library/Application Support/Rudo
 *        Windows → %LOCALAPPDATA%\Rudo   (falls back to %APPDATA%)
 *        Linux   → $XDG_DATA_HOME/Rudo   (falls back to ~/.local/share/Rudo)
 *
 * Nothing here ever resolves against the working directory or the install
 * directory.
 */
public final class UserDataPaths {

    /** Directory name under the OS application-data root. */
    public static final String APP_DIR_NAME = "Rudo";

    /** Environment variable the Electron layer uses to pin the data root. */
    public static final String ENV_DATA_DIR = "SHELFBOT_DATA_DIR";

    private final Path root;

    private UserDataPaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Resolves the data root from the environment override, else the OS default. */
    public static UserDataPaths resolve() {
        String override = System.getenv(ENV_DATA_DIR);
        if (override != null && !override.isBlank()) {
            return new UserDataPaths(Paths.get(override.trim()));
        }
        return new UserDataPaths(osDefaultRoot());
    }

    /** Explicit root — used by tests and by callers that already know the path. */
    public static UserDataPaths of(Path root) {
        return new UserDataPaths(root);
    }

    /**
     * The OS-standard per-application data directory for this platform. Package
     * visibility so tests can assert the mapping directly.
     */
    static Path osDefaultRoot() {
        String os   = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");

        if (os.contains("mac") || os.contains("darwin")) {
            return Paths.get(home, "Library", "Application Support", APP_DIR_NAME);
        }
        if (os.contains("win")) {
            String base = firstNonBlank(System.getenv("LOCALAPPDATA"), System.getenv("APPDATA"));
            if (base == null) base = Paths.get(home, "AppData", "Local").toString();
            return Paths.get(base, APP_DIR_NAME);
        }
        // Linux / other Unix — follow the XDG base-directory spec.
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg.trim(), APP_DIR_NAME);
        }
        return Paths.get(home, ".local", "share", APP_DIR_NAME);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    // ── Subpaths — the canonical location of every durable artifact ──────────

    public Path root()        { return root; }
    public Path metadataDb()  { return root.resolve("shelfbot-metadata.db"); }
    public Path chatDb()      { return root.resolve("shelfbot-chats.db"); }
    public Path vectorIndex() { return root.resolve("shelfbot-vector-index"); }
    public Path configFile()  { return root.resolve("config.properties"); }
    public Path logsDir()     { return root.resolve("logs"); }
    public Path cacheDir()    { return root.resolve("cache"); }
    public Path tempDir()     { return root.resolve("tmp"); }

    /**
     * Creates the root and its standard subdirectories if they don't exist.
     * Safe to call repeatedly (fresh install creates the structure; existing
     * install is a no-op).
     */
    public void ensureDirectories() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(logsDir());
        Files.createDirectories(cacheDir());
        Files.createDirectories(tempDir());
    }

    @Override
    public String toString() {
        return "UserDataPaths[" + root + "]";
    }
}
