package com.localfilebrain.ingestion;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wraps Apache Tika to extract clean plain text from any supported file format.
 *
 * For plain text formats (txt, md, csv, html, rtf), we read directly with UTF-8
 * as a fast path that avoids Tika's charset detection issues on small files.
 * For binary formats (PDF, DOCX, XLSX, PPTX, etc.) we use Tika's full parser pipeline.
 *
 * Thread safety: AutoDetectParser is thread-safe. This class is safe to share.
 */
public final class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    private static final int MAX_CHARS = 10_000_000;

    // A genuine text page carries ~1,500–3,500 chars; a scanned (image-only)
    // page yields ~0. So if a PDF's text-only extraction averages fewer than
    // this many chars per page, we treat it as a scan and run full OCR.
    private static final int MIN_CHARS_PER_PAGE = 100;

    // Extensions we handle via direct UTF-8 read (fast path, no charset detection needed)
    private static final java.util.Set<String> PLAIN_TEXT_EXTENSIONS = java.util.Set.of(
        "txt", "md", "markdown", "csv", "tsv", "log", "properties", "yaml", "yml", "json", "xml", "htm", "html"
    );

    // Image-and-image-like extensions that only yield text if we OCR them.
    // (Scanned/image-only PDFs are detected inside extractWithTika via a fast
    //  text-only pass, then OCR'd only if their text-per-page is too low.)
    private static final java.util.Set<String> IMAGE_EXTENSIONS = java.util.Set.of(
        "jpg", "jpeg", "png", "tif", "tiff", "bmp", "webp", "gif"
    );

    private final AutoDetectParser parser;
    private final boolean ocrAvailable;

    public TextExtractor() {
        this.parser       = new AutoDetectParser();
        this.ocrAvailable = detectTesseract();
        if (ocrAvailable) {
            log.info("OCR enabled — system Tesseract detected, image files will be indexed");
        } else {
            log.info("OCR disabled — install Tesseract (e.g. `brew install tesseract`) to index images");
        }
    }

    /**
     * Returns true when a system Tesseract binary is on PATH. Tika's
     * built-in image OCR shells out to it, so this is the single signal
     * that tells the rest of the app "image search is or isn't possible
     * on this machine right now." Surfaced through /api/status.
     */
    public boolean isOcrAvailable() { return ocrAvailable; }

    private static boolean detectTesseract() {
        try {
            Process p = new ProcessBuilder("tesseract", "--version")
                    .redirectErrorStream(true).start();
            boolean finished = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public ExtractionResult extract(Path file) throws ExtractionException {
        log.debug("Extracting: {}", file.getFileName());

        String fileName  = file.getFileName().toString();
        String extension = getExtension(fileName);

        // Fast path for plain text — avoids Tika charset detection entirely
        if (PLAIN_TEXT_EXTENSIONS.contains(extension)) {
            return extractPlainText(file, extension);
        }

        // Images (JPG/PNG/etc.) — Tika auto-detects + invokes Tesseract.
        // If Tesseract isn't installed we'll get back an empty string and
        // mark the file accordingly so the user knows why their photos
        // aren't searchable.
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return extractImage(file, extension);
        }

        // Full Tika path for binary formats (PDF, DOCX, XLSX, PPTX, etc.)
        return extractWithTika(file);
    }

    /**
     * Run Tesseract on a standalone image file via Tika's OCR parser.
     * Returns empty text if Tesseract isn't installed on the system —
     * the user gets a UI hint via /api/status, the indexer doesn't crash.
     */
    private ExtractionResult extractImage(Path file, String extension) throws ExtractionException {
        if (!ocrAvailable) {
            log.debug("Skipping OCR for '{}' — Tesseract not on PATH", file.getFileName());
            return new ExtractionResult("", "image/" + extension);
        }

        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());

        BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
        ParseContext context = newOcrContext();

        try (TikaInputStream tis = TikaInputStream.get(file.toFile(), metadata)) {
            parser.parse(tis, handler, metadata, context);
        } catch (Exception e) {
            throw new ExtractionException("OCR failed for: " + file, e);
        }

        String text = cleanText(handler.toString());
        if (text.isBlank()) {
            log.warn("OCR produced no text from '{}' (image may be too noisy / wrong language)", file.getFileName());
        } else {
            log.info("OCR extracted {} chars from '{}'", text.length(), file.getFileName());
        }
        return new ExtractionResult(text, "image/" + extension);
    }

    /**
     * ParseContext wired up for OCR — used for both standalone images and
     * scanned PDFs. {@code OCR_AND_TEXT_EXTRACTION} on PDFParserConfig
     * means Tika will run OCR over embedded images in PDFs so scanned
     * documents (e.g. an Aadhaar photo inside a PDF) become searchable.
     */
    private ParseContext newOcrContext() {
        ParseContext context = new ParseContext();

        TesseractOCRConfig ocr = new TesseractOCRConfig();
        ocr.setLanguage("eng");
        // Keep the timeout reasonable so a corrupt image can't stall indexing.
        ocr.setTimeoutSeconds(60);
        context.set(TesseractOCRConfig.class, ocr);

        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setExtractInlineImages(true);
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.OCR_AND_TEXT_EXTRACTION);
        context.set(PDFParserConfig.class, pdf);

        return context;
    }

    /**
     * Context that extracts a PDF's embedded text layer only — NO OCR. Used for
     * the fast first pass that decides whether a PDF is digital (skip OCR) or a
     * scan (fall back to {@link #newOcrContext()}).
     */
    private ParseContext newNoOcrContext() {
        ParseContext context = new ParseContext();
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdf);
        return context;
    }

    // -------------------------------------------------------------------------
    // Plain text fast path
    // -------------------------------------------------------------------------

    private ExtractionResult extractPlainText(Path file, String extension) throws ExtractionException {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String cleaned = cleanText(raw);
            String mime = extensionToMime(extension);
            if (cleaned.isBlank()) {
                log.warn("Empty content in plain text file: {}", file.getFileName());
            } else {
                log.debug("Read {} chars (plain text) from '{}'", cleaned.length(), file.getFileName());
            }
            return new ExtractionResult(cleaned, mime);
        } catch (IOException e) {
            throw new ExtractionException("Cannot read plain text file: " + file, e);
        }
    }

    private String extensionToMime(String ext) {
        return switch (ext) {
            case "md", "markdown" -> "text/markdown";
            case "csv"            -> "text/csv";
            case "html", "htm", "xhtml"    -> "text/html";
            case "json"           -> "application/json";
            case "xml"            -> "application/xml";
            default               -> "text/plain";
        };
    }

    // -------------------------------------------------------------------------
    // Tika full-parse path (binary formats)
    // -------------------------------------------------------------------------

    private ExtractionResult extractWithTika(Path file) throws ExtractionException {
        String ext = getExtension(file.getFileName().toString());

        // PDFs are the expensive case: with OCR_AND_TEXT_EXTRACTION Tika renders
        // and OCRs *every* page, so a 300-page digital PDF pays for 300 needless
        // OCR passes (~minutes). Decide first with a fast text-only pass: only
        // fall back to full OCR when the text-per-page is too low to be a real
        // text layer (i.e. it's a scan). The check is at the whole-document
        // level, so a scan carrying a thin text layer (e.g. a cover page) still
        // drops below the threshold and gets OCR'd.
        if ("pdf".equals(ext) && ocrAvailable) {
            TikaParse fast = parse(file, newNoOcrContext());
            int pages   = pageCount(fast.metadata());
            int perPage = pages > 0 ? fast.text().length() / pages : fast.text().length();

            if (perPage >= MIN_CHARS_PER_PAGE) {
                log.debug("PDF '{}' looks digital ({} chars / {} pages = {}/page) — skipping OCR",
                        file.getFileName(), fast.text().length(), pages, perPage);
                return finish(file, fast);
            }
            log.info("PDF '{}' looks scanned ({} chars / {} pages = {}/page, < {}) — running full OCR",
                    file.getFileName(), fast.text().length(), pages, perPage, MIN_CHARS_PER_PAGE);
            return finish(file, parse(file, newOcrContext()));
        }

        // Everything else (DOCX, XLSX, PPTX, …): OCR-aware context when Tesseract
        // is available so embedded images still get read; plain otherwise.
        ParseContext ctx = ocrAvailable ? newOcrContext() : new ParseContext();
        return finish(file, parse(file, ctx));
    }

    /** Cleans the parsed text, builds the result, and logs the blank case. */
    private ExtractionResult finish(Path file, TikaParse p) {
        String cleaned = cleanText(p.text());
        if (cleaned.isBlank()) {
            log.warn("No text extracted from '{}' (MIME: {}). File may be scanned/image-only.",
                    file.getFileName(), p.mime());
        } else {
            log.debug("Extracted {} chars from '{}' (MIME: {})", cleaned.length(), file.getFileName(), p.mime());
        }
        return new ExtractionResult(cleaned, p.mime());
    }

    /** One Tika parse with the given context; tolerates the char-limit SAX abort. */
    private TikaParse parse(Path file, ParseContext context) throws ExtractionException {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
        BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);

        try (TikaInputStream tis = TikaInputStream.get(file.toFile(), metadata)) {
            parser.parse(tis, handler, metadata, context);
        } catch (SAXException e) {
            if (isCharLimitException(e)) {
                log.warn("Char limit hit extracting '{}', using partial text", file.getFileName());
            } else {
                throw new ExtractionException("SAX parse error extracting: " + file, e);
            }
        } catch (org.apache.tika.exception.TikaException e) {
            throw new ExtractionException("Tika parse error extracting: " + file, e);
        } catch (IOException e) {
            throw new ExtractionException("IO error reading file: " + file, e);
        }

        String mime = metadata.get(HttpHeaders.CONTENT_TYPE);
        if (mime == null) mime = "application/octet-stream";
        int semi = mime.indexOf(';');
        if (semi > 0) mime = mime.substring(0, semi).trim();
        return new TikaParse(handler.toString(), mime, metadata);
    }

    private record TikaParse(String text, String mime, Metadata metadata) {}

    /** PDF page count from Tika metadata; 0 if it couldn't be determined. */
    private static int pageCount(Metadata md) {
        String[] keys = { "xmpTPg:NPages", "meta:page-count", "Page-Count" };
        for (String k : keys) {
            String v = md.get(k);
            if (v != null) {
                try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) { /* try next */ }
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Text cleaning (package-private for testing)
    // -------------------------------------------------------------------------

    static String cleanText(String raw) {
        if (raw == null || raw.isBlank()) return "";

        String text = raw
            .replace("\r\n", "\n")
            .replace("\r", "\n");

        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '\t' || c == '\n' || (c >= 0x20 && c != 0x7F)) {
                sb.append(c);
            }
        }
        text = sb.toString();

        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.replaceAll("[ \t]{3,}", " ");

        return text.strip();
    }

    private boolean isCharLimitException(SAXException e) {
        return e.getMessage() != null && e.getMessage().contains("limit");
    }

    private static String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) return "";
        return fileName.substring(lastDot + 1).toLowerCase();
    }

    // -------------------------------------------------------------------------
    // Result + exception types
    // -------------------------------------------------------------------------

    public record ExtractionResult(String text, String mimeType) {
        public boolean isEmpty() { return text == null || text.isBlank(); }
    }

    public static class ExtractionException extends Exception {
        public ExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
