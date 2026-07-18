package com.localfilebrain.aggregate;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.AggCategory;
import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.model.MoneyFormat;
import com.localfilebrain.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs an aggregation end to end: resolve the category (register a new one) →
 * gather facts from every matching doc (cache-first, cheap keyword gate before
 * any LLM call) → merge across docs → compute the operation → render, with
 * conflicts surfaced and sources cited. Returns null when there's nothing to
 * aggregate, so the caller falls back to normal search.
 */
public final class AggregationService {

    private static final Logger log = LoggerFactory.getLogger(AggregationService.class);

    private final IndexMetadataStore meta;
    private final VectorStore vectorStore;
    private final FactExtractor extractor;

    public AggregationService(GPT4oMiniClient llm, IndexMetadataStore meta, VectorStore vectorStore) {
        this.meta = meta; this.vectorStore = vectorStore;
        this.extractor = new FactExtractor(llm, meta, vectorStore);
    }

    public record AggResult(String answer, List<String> sourcePaths) {}

    public AggResult run(QueryPlan plan, Set<String> allowedPaths) {
        if (meta == null) return null;
        String category = plan.categoryId();
        if (category.isBlank()) return null;

        String fieldSpec, filterTerms;
        if (plan.isNewCategory()) {
            fieldSpec = plan.newFieldSpec() == null ? "" : plan.newFieldSpec();
            filterTerms = plan.newFilterTerms() == null ? "" : plan.newFilterTerms();
            meta.putCategory(category, plan.newLabel(), fieldSpec, filterTerms);   // learn it
        } else {
            AggCategory c = meta.listCategories().stream()
                    .filter(x -> x.name().equalsIgnoreCase(category)).findFirst().orElse(null);
            if (c == null) return null;                    // unknown category → fall back
            fieldSpec = c.fieldSpec(); filterTerms = c.filterTerms() == null ? "" : c.filterTerms();
        }

        List<String> terms = new ArrayList<>();
        for (String t : filterTerms.toLowerCase().split("[,\\s]+")) if (!t.isBlank()) terms.add(t);

        // Gather facts across the whole corpus, cache-first; the keyword gate only
        // decides whether to spend an LLM call, never whether to read the cache.
        List<DocFact> all = new ArrayList<>();
        for (FileRecord r : meta.listIndexedFilesBySizeDesc()) {
            if (!inScope(r.getAbsolutePath(), allowedPaths)) continue;
            if (isImageOrBinary(r)) continue;
            String hash = r.getContentHash() == null ? null : r.getContentHash() + "#" + FactExtractor.VERSION;
            if (hash != null && meta.getDocFacts(r.getAbsolutePath(), category, hash).isPresent()) {
                all.addAll(extractor.extract(r, category, fieldSpec, null));   // cache hit, no read/LLM
                continue;
            }
            String content = readContent(r);
            if (content == null || content.isBlank()) continue;
            if (!terms.isEmpty() && !containsAny(content.toLowerCase(), terms)) {
                if (hash != null) meta.putDocFacts(r.getAbsolutePath(), category, hash, "[]");  // gate out, remember
                continue;
            }
            all.addAll(extractor.extract(r, category, fieldSpec, content));
        }

        List<DocFact> merged = Aggregator.merge(all);
        List<String> conflicts = Aggregator.conflicts(all);
        return render(plan, merged, conflicts);
    }

    private AggResult render(QueryPlan plan, List<DocFact> merged, List<String> conflicts) {
        boolean paid = "paid".equalsIgnoreCase(plan.statusFilter());
        boolean unpaid = "unpaid".equalsIgnoreCase(plan.statusFilter());
        List<DocFact> hits = new ArrayList<>();
        for (DocFact f : merged) {
            if (unpaid && f.owed() <= 0) continue;
            if (paid && f.owed() > 0) continue;
            hits.add(f);
        }
        if (plan.operation() == QueryPlan.Op.WHO_MOST) {
            hits.sort((a, b) -> Long.compare(b.owed(), a.owed()));
            if (hits.size() > 1) hits = hits.subList(0, 1);
        } else {
            hits.sort((a, b) -> Long.compare(b.owed(), a.owed()));
        }
        if (hits.isEmpty() && conflicts.isEmpty()) return null;   // nothing → let RAG try

        StringBuilder sb = new StringBuilder();
        List<String> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        switch (plan.operation()) {
            case COUNT -> sb.append(hits.size()).append(hits.size() == 1 ? " item" : " items").append(" match.");
            case TOTAL, WHO_MOST, LIST, NONE -> {
                long total = 0;
                sb.append(plan.operation() == QueryPlan.Op.WHO_MOST
                        ? "" : hits.size() + (hits.size() == 1 ? " item:" : " items:"));
                for (DocFact f : hits) {
                    total += f.owed();
                    sb.append("\n- **").append(f.subject().isBlank() ? "(unnamed)" : f.subject()).append("**");
                    if (f.owed() > 0) sb.append(" — ").append(MoneyFormat.format(f.owed()));
                    else if (!f.label().isBlank()) sb.append(" — ").append(f.label());
                    if (f.sourcePath() != null && seen.add(f.sourcePath())) sources.add(f.sourcePath());
                }
                if (plan.operation() == QueryPlan.Op.TOTAL && total > 0)
                    sb.append("\n\nTotal: ").append(MoneyFormat.format(total));
            }
        }
        for (String c : conflicts) sb.append("\n\nNote: ").append(c).append(" — worth reconciling.");
        return new AggResult(sb.toString().trim(), sources);
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private static boolean containsAny(String text, List<String> terms) {
        for (String t : terms) if (text.contains(t)) return true;
        return false;
    }
    private static boolean isImageOrBinary(FileRecord r) {
        String low = r.getFileName() == null ? "" : r.getFileName().toLowerCase();
        return low.matches(".*\\.(jpg|jpeg|png|gif|bmp|tiff|heic|svg|ico|mp3|mp4|mov|wav|zip|exe|dll)$");
    }
    private static boolean inScope(String path, Set<String> allowed) {
        return allowed == null || allowed.contains(path);
    }
    private String readContent(FileRecord r) {
        String low = r.getFileName() == null ? "" : r.getFileName().toLowerCase();
        if (low.endsWith(".csv") || low.endsWith(".tsv") || low.endsWith(".txt") || low.endsWith(".md")) {
            try {
                java.nio.file.Path p = java.nio.file.Path.of(r.getAbsolutePath());
                if (java.nio.file.Files.size(p) <= 512 * 1024) return java.nio.file.Files.readString(p);
            } catch (Exception ignored) { }
        }
        if (vectorStore == null) return null;
        var chunks = vectorStore.getChunksForFile(r.getAbsolutePath());
        if (chunks.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (var c : chunks) sb.append(c.text()).append('\n');
        return sb.toString();
    }
}
