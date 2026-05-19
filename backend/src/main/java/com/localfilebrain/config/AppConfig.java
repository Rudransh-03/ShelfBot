package com.localfilebrain.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final String CONFIG_FILE = "config.properties";

    private final Properties props;

    private AppConfig(Properties props) { this.props = props; }

    public static AppConfig load() {
        Properties props = new Properties();
        Path externalConfig = Paths.get(CONFIG_FILE);
        if (Files.exists(externalConfig)) {
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
                    log.info("Loaded config from classpath");
                } else {
                    log.warn("No config.properties found. Using defaults only.");
                }
            } catch (IOException e) {
                throw new ConfigurationException("Failed to read classpath config", e);
            }
        }
        return new AppConfig(props);
    }

    // -------------------------------------------------------------------------
    // Ingestion
    // -------------------------------------------------------------------------

    public Path getFilesRootPath() {
        String value = require("files.root.path");
        Path path = Paths.get(value);
        if (!Files.isDirectory(path)) {
            throw new ConfigurationException(
                    "files.root.path does not point to a directory: " + path.toAbsolutePath());
        }
        return path;
    }

    public Path getMetadataDbPath() {
        return Paths.get(getOrDefault("metadata.db.path", "shelfbot-metadata.db"));
    }

    public int getChunkSizeChars() {
        return getInt("chunk.size.chars", 1800);
    }

    public int getChunkOverlapChars() {
        return getInt("chunk.overlap.chars", 200);
    }

    public String[] getSupportedExtensions() {
        String value = getOrDefault(
                "supported.extensions",
                "pdf,docx,doc,txt,md,xlsx,xls,pptx,ppt,odt,ods,odp,html,htm,rtf,csv"
        );
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim().toLowerCase();
        return parts;
    }

    public long getMaxFileSizeBytes() {
        return getLong("max.file.size.bytes", 50L * 1024 * 1024);
    }

    // -------------------------------------------------------------------------
    // Embedding (OpenAI)
    // -------------------------------------------------------------------------

    public String getOpenAiApiKey() {
        return require("openai.api.key");
    }

    public int getEmbeddingBatchSize() {
        return getInt("embedding.batch.size", 100);
    }

    // -------------------------------------------------------------------------
    // ChromaDB
    // -------------------------------------------------------------------------

    public String getChromaDbUrl() {
        return getOrDefault("chromadb.url", "http://[::1]:8000");
    }

    public String getChromaDbCollection() {
        return getOrDefault("chromadb.collection", "shelfbot");
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
