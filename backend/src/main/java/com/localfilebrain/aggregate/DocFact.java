package com.localfilebrain.aggregate;

/**
 * One fact pulled from ONE document for a given aggregation category — generic
 * enough to carry the common shapes: a money-with-status fact (fees: subject =
 * client, key = invoice id, value = amount, balance, status), a classification
 * fact (personal docs: subject = the doc, label = "personal"), or a dated fact
 * (deadlines: date + label). Fields the doc doesn't state are left null/blank,
 * so the merge step can fill each field from whichever doc actually states it.
 */
public record DocFact(
        String subject,      // who/what this is about (client, doc name, party)
        String key,          // merge key across docs (e.g. invoice id); falls back to subject
        String label,        // what-it-is / classification ("personal", "1099", "")
        Long   value,        // gross money amount, null if none
        Long   balance,      // still-owed amount when partly paid, null if none
        String status,       // status word (PAID/PENDING/PARTIAL/RECEIVED/…) or ""
        String date,         // ISO yyyy-MM-dd, or ""
        String sourceName,
        String sourcePath,
        String note) {

    /** The amount this fact contributes to a total: 0 when settled (paid/received)
     *  or a mere prospect; otherwise the stated balance (a partial due) when
     *  present, else the gross value. */
    public long owed() {
        String s = status == null ? "" : status.toLowerCase();
        boolean settled  = s.contains("paid") || s.contains("received")
                        || s.contains("settled") || s.contains("cleared");
        boolean prospect = s.contains("prospect") || s.contains("quote") || s.contains("unsigned");
        if (settled || prospect) return 0;
        if (balance != null && balance > 0) return balance;
        if (value != null && value > 0) return value;
        return 0;
    }

    /** The key used to group facts about the same thing across documents. */
    public String mergeKey() {
        if (key != null && !key.isBlank()) return "k:" + key.trim().toLowerCase();
        if (subject != null && !subject.isBlank()) return "s:" + subject.trim().toLowerCase();
        return "src:" + sourcePath;
    }
}
