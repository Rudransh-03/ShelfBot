package com.localfilebrain.util;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonicalizes filesystem paths so that the same physical file always
 * produces the same string key in the metadata DB and the Lucene index.
 *
 * On macOS APFS (case-insensitive but case-preserving by default), the same
 * file is reachable through paths that only differ in case — e.g.
 * {@code /Users/x/Desktop/Others2/foo.pdf} vs
 * {@code /Users/x/Desktop/others2/foo.pdf}. Without normalization each
 * variant becomes its own row in {@code file_index}, the file appears twice
 * in the Library UI, and its chunks are duplicated in Lucene.
 *
 * {@link Path#toRealPath} resolves both symlinks and case-folding to the
 * actual on-disk spelling, which is exactly what we want as a stable key.
 * It does an I/O syscall and requires the file to exist; for inputs that
 * have already been deleted we fall back to a lexical normalization so the
 * caller can still look the row up for removal.
 */
public final class PathNormalizer {

    private PathNormalizer() {}

    /** Canonicalizes the given path string. See class javadoc. */
    public static String canonical(String absolutePath) {
        if (absolutePath == null) return null;
        return canonical(Paths.get(absolutePath));
    }

    /** Canonicalizes the given path. See class javadoc. */
    public static String canonical(Path path) {
        if (path == null) return null;
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }
}
