package com.localfilebrain.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final String CONFIG_FILE = "config.properties";

    private final Properties props;
    /** OS-standard per-user data root every durable path resolves against. */
    private final UserDataPaths dataPaths;

    private AppConfig(Properties props, UserDataPaths dataPaths) {
        this.props     = props;
        this.dataPaths = dataPaths;
    }

    public static AppConfig load() {
        UserDataPaths dataPaths = UserDataPaths.resolve();
        try {
            dataPaths.ensureDirectories();
        } catch (IOException e) {
            throw new ConfigurationException("Could not create data directory: " + dataPaths.root(), e);
        }

        Properties props = new Properties();
        // Config resolution, in priority order:
        //   1. The user data directory (authoritative; where the UI writes and
        //      where migration relocates any prior config).
        //   2. A legacy config.properties in the working directory — dev / CLI /
        //      test runs that pre-date the data-dir move. We only READ this; we
        //      never write there, and migration folds it into the data dir.
        //   3. The shipped classpath default (fresh install).
        Path dataConfig   = dataPaths.configFile();
        Path legacyConfig = Paths.get(CONFIG_FILE);
        Path externalConfig = Files.exists(dataConfig) ? dataConfig
                            : (Files.exists(legacyConfig) ? legacyConfig : null);

        if (externalConfig != null) {
            try (InputStream in = Files.newInputStream(externalConfig)) {
                props.load(in);
                log.info("Loaded config from: {}", externalConfig.toAbsolutePath());
            } catch (IOException e) {
                throw new ConfigurationException("Failed to read config file: " + externalConfig, e);
            }
        } else {
            try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                if (in != null) {
                    props.load(in);
                    log.info("Loaded config from classpath (data dir: {})", dataPaths.root());
                } else {
                    log.warn("No config.properties found. Using defaults only. (data dir: {})", dataPaths.root());
                }
            } catch (IOException e) {
                throw new ConfigurationException("Failed to read classpath config", e);
            }
        }
        AppConfig config = new AppConfig(props, dataPaths);
        // Sync the active market so currency/date formatting matches the user's
        // region — on every load, so a settings change takes effect on reload.
        RegionProfile.setActive(config.getRegion());
        return config;
    }

    /**
     * Builds a config directly from explicit properties, bypassing file
     * resolution — for tests, so they don't depend on WHERE config.properties
     * lives (which moved to the OS data dir). Uses the resolved data paths but
     * never reads/writes a config file.
     */
    public static AppConfig fromProperties(Properties props) {
        return new AppConfig(props, UserDataPaths.resolve());
    }

    // -------------------------------------------------------------------------
    // Ingestion
    // -------------------------------------------------------------------------

    /**
     * Returns every directory that should be scanned for files.
     *
     * Resolution order:
     *   1. {@code files.root.paths} — comma-separated list (preferred for multi-root setups)
     *   2. {@code files.root.path}  — legacy single-folder key (kept for backward compatibility)
     *   3. Built-in defaults — the user's Desktop, Downloads, and Documents folders
     *      (only the ones that actually exist on this machine)
     *
     * Always returns at least one directory. Throws {@link ConfigurationException} if
     * no usable directory can be resolved.
     */
    public List<Path> getFilesRootPaths() {
        String multi = getOrDefault("files.root.paths", "");
        if (!multi.isBlank()) {
            return parsePathList(multi, "files.root.paths");
        }

        String single = getOrDefault("files.root.path", "");
        if (!single.isBlank()) {
            Path path = Paths.get(single);
            if (!Files.isDirectory(path)) {
                throw new ConfigurationException(
                        "files.root.path does not point to a directory: " + path.toAbsolutePath());
            }
            return List.of(path);
        }

        return defaultUserRoots();
    }

    /**
     * Backward-compatible accessor returning the first configured root.
     * Existing call-sites that only display or log a single path remain valid;
     * scanning code should prefer {@link #getFilesRootPaths()}.
     */
    public Path getFilesRootPath() {
        return getFilesRootPaths().get(0);
    }

    private List<Path> parsePathList(String csv, String key) {
        List<Path> paths = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            Path path = Paths.get(trimmed);
            if (!Files.isDirectory(path)) {
                throw new ConfigurationException(
                        key + " entry is not a directory: " + path.toAbsolutePath());
            }
            paths.add(path);
        }
        if (paths.isEmpty()) {
            throw new ConfigurationException(key + " is set but contains no valid entries");
        }
        return paths;
    }

    private List<Path> defaultUserRoots() {
        Path home = Paths.get(System.getProperty("user.home"));
        List<Path> defaults = new ArrayList<>();
        for (String name : new String[] { "Desktop", "Downloads", "Documents" }) {
            Path candidate = home.resolve(name);
            if (Files.isDirectory(candidate)) {
                defaults.add(candidate);
            }
        }
        if (defaults.isEmpty()) {
            throw new ConfigurationException(
                    "No files.root.paths configured and no default Desktop/Downloads/Documents "
                    + "folders were found under " + home.toAbsolutePath()
                    + ". Please set files.root.paths in config.properties.");
        }
        return defaults;
    }

    /** The per-user data root. Every durable path lives under this directory. */
    public Path getDataRoot() {
        return dataPaths.root();
    }

    public Path getMetadataDbPath() {
        return resolveDataPath("metadata.db.path", dataPaths.metadataDb());
    }

    /** Local chat-thread DB. Kept beside the metadata DB in the data root. */
    public Path getChatDbPath() {
        return resolveDataPath("chat.db.path", dataPaths.chatDb());
    }

    /** Where the UI-editable {@code config.properties} is read from and written to. */
    public Path getConfigFilePath() {
        return dataPaths.configFile();
    }

    /**
     * Resolves a configurable path property: an absolute override is honored as
     * given; a relative one (or the absence of one) resolves against the data
     * root so nothing ever lands in the working / install directory.
     */
    private Path resolveDataPath(String key, Path defaultAbsolute) {
        String v = getOrDefault(key, "");
        if (v.isBlank()) return defaultAbsolute;
        Path p = Paths.get(v);
        return p.isAbsolute() ? p : dataPaths.root().resolve(p).normalize();
    }

    /**
     * Max deadline-extraction LLM calls per (UTC) day. Client-side pacing so a
     * one-shot scan of a huge freshly-added library can't burn the whole day's
     * budget at once — it processes this many batched calls, then resumes on the
     * next pass once the day rolls over. Each call covers many documents (they're
     * batched), so the per-day document throughput is far higher than this number.
     */
    public int getDeadlineDailyCallBudget() {
        return getInt("deadline.daily.call.budget", 25);
    }

    public int getChunkSizeChars() {
        return getInt("chunk.size.chars", 1800);
    }

    /** The user's market/jurisdiction ("US"/"UK"/"EU"/"IN"), captured at
     *  onboarding. Drives currency, digit grouping and date order (and, later,
     *  jurisdiction context for extraction). Defaults to IN. */
    public RegionProfile getRegion() {
        return RegionProfile.of(getOrDefault("user.region", "IN"));
    }

    /** The owner's own identities (their name and business, comma-separated) so the
     *  aggregator knows who "you" are — anything matching is never a client or vendor.
     *  Empty by default; the aggregator then guesses the owner heuristically. */
    public java.util.List<String> getOwnerNames() {
        String v = getOrDefault("user.owner.names", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    public int getChunkOverlapChars() {
        return getInt("chunk.overlap.chars", 200);
    }

    public String[] getSupportedExtensions() {
        String value = getOrDefault(
                "supported.extensions",
                // Documents
                "pdf,docx,doc,txt,md,xlsx,xls,pptx,ppt,odt,ods,odp,html,htm,rtf,csv,"
                // Images — OCR'd via Tesseract when it's installed on the
                // host, so users can search photos of IDs / receipts / etc.
                + "jpg,jpeg,png,tif,tiff,bmp,webp,gif"
        );
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim().toLowerCase();
        return parts;
    }

    public long getMaxFileSizeBytes() {
        return getLong("max.file.size.bytes", 50L * 1024 * 1024);
    }

    // -------------------------------------------------------------------------
    // Embedding
    // -------------------------------------------------------------------------

    /**
     * Which embedding backend to use. "local" (default) runs bge-small-en-v1.5
     * on-device so file contents never leave the machine during indexing.
     * "openai" falls back to the cloud embedding API and still requires an
     * OpenAI key (which is needed for the answer LLM regardless).
     */
    public String getEmbeddingProvider() {
        String v = getOrDefault("embedding.provider", "local").toLowerCase();
        return v.equals("openai") ? "openai" : "local";
    }

    /** Where the local model files are cached. Defaults to ~/.shelfbot/models/ . */
    public Path getLocalEmbeddingModelPath() {
        String def = Paths.get(System.getProperty("user.home"), ".shelfbot", "models", "bge-small-en-v1.5")
                .toString();
        return Paths.get(getOrDefault("embedding.model.path", def));
    }

    /**
     * Returns the OpenAI key, or an empty string when none is configured
     * (the common case for proxy mode — the key lives on the proxy server
     * and never reaches the desktop). Callers that genuinely need a key
     * (i.e. direct-mode clients) should check for blank.
     */
    public String getOpenAiApiKey() {
        return getOrDefault("openai.api.key", "");
    }

    public int getEmbeddingBatchSize() {
        return getInt("embedding.batch.size", 100);
    }

    // -------------------------------------------------------------------------
    // Auth proxy
    // -------------------------------------------------------------------------

    /**
     * "proxy" (default) routes every OpenAI call through our auth proxy
     * server — the desktop app must hold a valid JWT obtained at sign-in.
     * "direct" calls api.openai.com straight from the user's machine using
     * the legacy {@code openai.api.key} config entry; useful only in
     * developer / offline scenarios because it ships your API key on the
     * user's device.
     */
    public String getApiMode() {
        String v = getOrDefault("api.mode", "proxy").toLowerCase();
        return v.equals("direct") ? "direct" : "proxy";
    }

    /**
     * Base URL of the auth proxy. Resolution order:
     *   1. {@code SHELFBOT_PROXY_URL} env var — how the Electron app hands this
     *      backend the chosen URL (production = the deployed proxy, dev =
     *      localhost). Packaged builds ship no config.properties, so this is the
     *      production source of truth.
     *   2. {@code api.proxy.url} in config.properties — standalone / dev runs.
     *   3. {@code http://localhost:8787} default.
     */
    public String getProxyUrl() {
        String env = System.getenv("SHELFBOT_PROXY_URL");
        String url = (env != null && !env.isBlank())
                ? env.trim()
                : getOrDefault("api.proxy.url", "http://localhost:8787");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // -------------------------------------------------------------------------
    // Vector store (embedded Lucene HNSW)
    // -------------------------------------------------------------------------

    /**
     * Filesystem directory where the Lucene HNSW index lives. Defaults to
     * {@code shelfbot-vector-index/} alongside the metadata DB. Auto-created
     * on first run.
     */
    public Path getVectorIndexPath() {
        return resolveDataPath("vector.index.path", dataPaths.vectorIndex());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String require(String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException("Required config key missing or empty: " + key);
        }
        if (value.startsWith("${") && value.endsWith("}")) {
            String envName = value.substring(2, value.length() - 1);
            value = System.getenv(envName);
            if (value == null || value.isBlank()) {
                throw new ConfigurationException(
                        "Required config key '" + key + "' references env var ${"
                        + envName + "} which is not set");
            }
        }
        return value.trim();
    }

    private String getOrDefault(String key, String defaultValue) {
        String value = props.getProperty(key);
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }

    private int getInt(String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) {
            log.warn("Invalid integer for '{}': '{}'. Using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private long getLong(String key, long defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException e) {
            log.warn("Invalid long for '{}': '{}'. Using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public static class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message) { super(message); }
        public ConfigurationException(String message, Throwable cause) { super(message, cause); }
    }
}
