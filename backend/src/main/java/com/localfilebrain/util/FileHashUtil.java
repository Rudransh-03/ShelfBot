package com.localfilebrain.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility to compute SHA-256 content hashes of files.
 *
 * Used to detect when a file's content has actually changed (not just its
 * timestamp), avoiding unnecessary re-indexing of files that were touched
 * but not modified.
 */
public final class FileHashUtil {

    private FileHashUtil() {}

    /**
     * Computes the SHA-256 hash of the file at the given path.
     *
     * @param path file to hash
     * @return lowercase hex string of the SHA-256 digest
     * @throws IOException if the file cannot be read
     */
    public static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — this cannot happen
            throw new AssertionError("SHA-256 not available", e);
        }

        try (InputStream in = Files.newInputStream(path);
             DigestInputStream dis = new DigestInputStream(in, digest)) {
            // Read in 64 KB chunks — efficient for large files without loading into RAM
            byte[] buffer = new byte[65536];
            while (dis.read(buffer) != -1) {
                // DigestInputStream updates the digest as we read
            }
        }

        return bytesToHex(digest.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
