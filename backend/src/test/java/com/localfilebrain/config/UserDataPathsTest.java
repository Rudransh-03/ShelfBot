package com.localfilebrain.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class UserDataPathsTest {

    @Test
    void ofResolvesAllSubpathsUnderRoot() {
        Path root = Paths.get(System.getProperty("java.io.tmpdir"), "rudo-test-root");
        UserDataPaths p = UserDataPaths.of(root);

        assertEquals(root.toAbsolutePath().normalize(), p.root());
        assertEquals(p.root().resolve("shelfbot-metadata.db"),   p.metadataDb());
        assertEquals(p.root().resolve("shelfbot-chats.db"),      p.chatDb());
        assertEquals(p.root().resolve("shelfbot-vector-index"),  p.vectorIndex());
        assertEquals(p.root().resolve("config.properties"),      p.configFile());
        assertEquals(p.root().resolve("logs"),                   p.logsDir());
        assertEquals(p.root().resolve("cache"),                  p.cacheDir());
    }

    @Test
    void macOsRootFollowsApplicationSupportConvention() {
        String os = System.getProperty("os.name");
        String home = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("user.home", "/Users/tester");
            Path root = UserDataPaths.osDefaultRoot();
            assertEquals(Paths.get("/Users/tester/Library/Application Support/Rudo"), root);
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("user.home", home);
        }
    }

    @Test
    void linuxRootFollowsXdgFallbackWhenEnvUnset() {
        // Only assert the ~/.local/share fallback when XDG_DATA_HOME isn't set
        // in the running environment (it's an env var we can't unset from Java).
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("XDG_DATA_HOME") == null);
        String os = System.getProperty("os.name");
        String home = System.getProperty("user.home");
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("user.home", "/home/tester");
            Path root = UserDataPaths.osDefaultRoot();
            assertEquals(Paths.get("/home/tester/.local/share/Rudo"), root);
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("user.home", home);
        }
    }

    @Test
    void ensureDirectoriesCreatesStructure() throws Exception {
        Path root = java.nio.file.Files.createTempDirectory("rudo-ensure");
        UserDataPaths p = UserDataPaths.of(root.resolve("nested"));
        p.ensureDirectories();
        assertTrue(java.nio.file.Files.isDirectory(p.root()));
        assertTrue(java.nio.file.Files.isDirectory(p.logsDir()));
        assertTrue(java.nio.file.Files.isDirectory(p.cacheDir()));
    }
}
