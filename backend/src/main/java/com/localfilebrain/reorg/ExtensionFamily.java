package com.localfilebrain.reorg;

import java.util.Map;
import java.util.Set;

/**
 * Groups file extensions into coarse compatibility families.
 *
 * Used by the reorg pipeline as a hard boundary: a {@code .jpg} should never
 * cluster with a {@code .pdf} even if their embeddings happen to be close.
 * Two extensions are compatible iff they belong to the same family. Unknown
 * extensions get their own singleton family — so the boundary only relaxes
 * when we're confident two formats are the same kind of artifact.
 */
public final class ExtensionFamily {

    public enum Family {
        DOCS, IMAGES, VIDEO, AUDIO, ARCHIVES, CODE, SHEETS, SLIDES, OTHER
    }

    // Lower-case extension → family. Built once at class load.
    private static final Map<String, Family> EXT_TO_FAMILY = buildMap();

    private static Map<String, Family> buildMap() {
        Map<String, Family> m = new java.util.HashMap<>();
        addAll(m, Family.DOCS,     "pdf", "docx", "doc", "rtf", "odt", "txt", "md", "pages", "epub", "tex");
        addAll(m, Family.IMAGES,   "jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "tiff", "tif",
                                   "svg", "bmp", "ico", "raw", "cr2", "nef");
        addAll(m, Family.VIDEO,    "mp4", "mov", "mkv", "avi", "webm", "m4v", "flv", "wmv", "mpg", "mpeg");
        addAll(m, Family.AUDIO,    "mp3", "wav", "flac", "m4a", "ogg", "aac", "wma", "opus");
        addAll(m, Family.ARCHIVES, "zip", "tar", "gz", "tgz", "7z", "rar", "bz2", "xz", "iso", "dmg");
        addAll(m, Family.CODE,     "js", "ts", "jsx", "tsx", "py", "java", "go", "rb", "rs",
                                   "c", "cpp", "cc", "h", "hpp", "sh", "json", "yaml", "yml", "xml",
                                   "kt", "swift", "scala", "php", "lua", "r");
        addAll(m, Family.SHEETS,   "xls", "xlsx", "csv", "numbers", "tsv", "ods");
        addAll(m, Family.SLIDES,   "ppt", "pptx", "key", "odp");
        return Map.copyOf(m);
    }

    private static void addAll(Map<String, Family> m, Family f, String... exts) {
        for (String e : exts) m.put(e, f);
    }

    /** Returns the family for an extension (case-insensitive). Unknown → OTHER. */
    public static Family of(String extension) {
        if (extension == null || extension.isEmpty()) return Family.OTHER;
        String e = extension.toLowerCase();
        if (e.startsWith(".")) e = e.substring(1);
        return EXT_TO_FAMILY.getOrDefault(e, Family.OTHER);
    }

    /** Extracts the extension from a filename (no dot, lowercase). Empty if none. */
    public static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        // dot <= 0 covers both "no dot at all" and "leading-dot hidden files
        // like .bashrc" — neither has a real extension. Trailing dot
        // ("foo.") is treated as no-extension too.
        if (dot <= 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }

    /**
     * Two files are compatible if they share a family. OTHER files are only
     * compatible with files of the exact same extension — we don't want
     * unknown extensions to all collapse into one "other" bucket.
     */
    public static boolean compatible(String fileNameA, String fileNameB) {
        String extA = extensionOf(fileNameA);
        String extB = extensionOf(fileNameB);
        Family fa = of(extA);
        Family fb = of(extB);
        if (fa == Family.OTHER || fb == Family.OTHER) {
            return extA.equals(extB);
        }
        return fa == fb;
    }

    /**
     * Returns true if {@code candidateFileName} is compatible with at least
     * one file in {@code folderFileNames} — i.e. could plausibly live in a
     * folder that already contains those files.
     */
    public static boolean compatibleWithAny(String candidateFileName, Set<String> folderFileNames) {
        for (String existing : folderFileNames) {
            if (compatible(candidateFileName, existing)) return true;
        }
        return false;
    }

    private ExtensionFamily() {}   // static-only
}
