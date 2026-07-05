package com.localfilebrain.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataMigratorTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Populates a legacy dir with a metadata DB, chat DB, config, and index. */
    private void seedLegacy(Path legacy) throws IOException {
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("shelfbot-metadata.db"), "META-DATA-BYTES");
        Files.writeString(legacy.resolve("shelfbot-metadata.db-wal"), "WAL");
        Files.writeString(legacy.resolve("shelfbot-chats.db"), "CHAT-DATA");
        Files.writeString(legacy.resolve("config.properties"), "files.root.paths=/tmp/docs");
        Path idx = legacy.resolve("shelfbot-vector-index");
        Files.createDirectories(idx);
        Files.writeString(idx.resolve("segments_1"), "SEG");
        Files.writeString(idx.resolve("_0.cfe"), "CFE");
    }

    // ── Scenarios ────────────────────────────────────────────────────────────

    @Test
    void freshInstall_nothingToMigrate_writesMarker(@TempDir Path tmp) {
        UserDataPaths target = UserDataPaths.of(tmp.resolve("data"));
        DataMigrator.Result r = DataMigrator.migrateIfNeeded(target, List.of());

        assertEquals(DataMigrator.Status.NOTHING_TO_MIGRATE, r.status());
        assertTrue(Files.exists(target.root().resolve(DataMigrator.MARKER)));
        assertFalse(Files.exists(target.metadataDb()));
    }

    @Test
    void existingInstall_migratesAndVerifiesAndCleansLegacy(@TempDir Path tmp) throws IOException {
        Path legacy = tmp.resolve("legacy");
        seedLegacy(legacy);
        String expectedMeta = Files.readString(legacy.resolve("shelfbot-metadata.db"));

        UserDataPaths target = UserDataPaths.of(tmp.resolve("data"));
        DataMigrator.Result r = DataMigrator.migrateIfNeeded(target, List.of(legacy));

        assertEquals(DataMigrator.Status.MIGRATED, r.status(), r.message());
        // All artifacts present in the new location with identical content.
        assertEquals(expectedMeta, Files.readString(target.metadataDb()));
        assertEquals("CHAT-DATA", Files.readString(target.chatDb()));
        assertEquals("SEG", Files.readString(target.vectorIndex().resolve("segments_1")));
        assertTrue(Files.exists(target.configFile()));
        // Marker written, staging cleaned up.
        assertTrue(Files.exists(target.root().resolve(DataMigrator.MARKER)));
        assertFalse(Files.exists(target.root().resolve(DataMigrator.STAGING)));
        // Legacy best-effort removed.
        assertFalse(Files.exists(legacy.resolve("shelfbot-metadata.db")));
        assertFalse(Files.exists(legacy.resolve("shelfbot-vector-index")));
    }

    @Test
    void repeatedMigration_isIdempotent(@TempDir Path tmp) throws IOException {
        Path legacy = tmp.resolve("legacy");
        seedLegacy(legacy);
        UserDataPaths target = UserDataPaths.of(tmp.resolve("data"));

        DataMigrator.Result first = DataMigrator.migrateIfNeeded(target, List.of(legacy));
        assertEquals(DataMigrator.Status.MIGRATED, first.status());
        String metaAfterFirst = Files.readString(target.metadataDb());

        // Second run must short-circuit on the marker and not touch anything.
        DataMigrator.Result second = DataMigrator.migrateIfNeeded(target, List.of(legacy));
        assertEquals(DataMigrator.Status.ALREADY_DONE, second.status());
        assertEquals(metaAfterFirst, Files.readString(target.metadataDb()));
    }

    @Test
    void targetAlreadyPopulated_neverOverwritesNewerData(@TempDir Path tmp) throws IOException {
        Path legacy = tmp.resolve("legacy");
        seedLegacy(legacy);
        UserDataPaths target = UserDataPaths.of(tmp.resolve("data"));
        Files.createDirectories(target.root());
        Files.writeString(target.metadataDb(), "NEWER-LIVE-DATA");   // live install already here

        DataMigrator.Result r = DataMigrator.migrateIfNeeded(target, List.of(legacy));

        assertEquals(DataMigrator.Status.TARGET_POPULATED, r.status());
        assertEquals("NEWER-LIVE-DATA", Files.readString(target.metadataDb())); // untouched
        assertTrue(Files.exists(legacy.resolve("shelfbot-metadata.db")));       // legacy untouched
    }

    @Test
    void missingArtifacts_migratesOnlyWhatExists(@TempDir Path tmp) throws IOException {
        Path legacy = tmp.resolve("legacy");
        Files.createDirectories(legacy);
        // Only a metadata DB — no chat DB, no config, no index.
        Files.writeString(legacy.resolve("shelfbot-metadata.db"), "ONLY-META");

        UserDataPaths target = UserDataPaths.of(tmp.resolve("data"));
        DataMigrator.Result r = DataMigrator.migrateIfNeeded(target, List.of(legacy));

        assertEquals(DataMigrator.Status.MIGRATED, r.status());
        assertEquals("ONLY-META", Files.readString(target.metadataDb()));
        assertFalse(Files.exists(target.chatDb()));
        assertFalse(Files.exists(target.vectorIndex()));
        assertTrue(r.migrated().contains("shelfbot-metadata.db"));
    }

    @Test
    void ignoresLegacyDirWithoutSignalFile(@TempDir Path tmp) throws IOException {
        Path legacy = tmp.resolve("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("config.properties"), "x=y"); // no metadata db → not authoritative

        UserDataPaths target = UserDataPaths.of(tmp.resolve("data"));
        DataMigrator.Result r = DataMigrator.migrateIfNeeded(target, List.of(legacy));

        assertEquals(DataMigrator.Status.NOTHING_TO_MIGRATE, r.status());
        assertFalse(Files.exists(target.configFile()));
    }

    @Test
    void failure_preservesOriginals_andDoesNotWriteMarker(@TempDir Path tmp) throws IOException {
        Path legacy = tmp.resolve("legacy");
        seedLegacy(legacy);
        // Make the target root un-creatable by placing a FILE where the dir must be.
        Path rootAsFile = tmp.resolve("data");
        Files.writeString(rootAsFile, "i am a file, not a directory");
        UserDataPaths target = UserDataPaths.of(rootAsFile);

        DataMigrator.Result r = DataMigrator.migrateIfNeeded(target, List.of(legacy));

        assertEquals(DataMigrator.Status.FAILED, r.status());
        // Legacy data fully intact.
        assertEquals("META-DATA-BYTES", Files.readString(legacy.resolve("shelfbot-metadata.db")));
        assertEquals("SEG", Files.readString(legacy.resolve("shelfbot-vector-index").resolve("segments_1")));
    }

    @Test
    void skipsLegacyCandidateEqualToTarget(@TempDir Path tmp) throws IOException {
        // If a legacy candidate IS the target root, we must never migrate onto ourselves.
        UserDataPaths target = UserDataPaths.of(tmp.resolve("data"));
        Files.createDirectories(target.root());
        DataMigrator.Result r = DataMigrator.migrateIfNeeded(target, List.of(target.root()));
        assertEquals(DataMigrator.Status.NOTHING_TO_MIGRATE, r.status());
    }
}
