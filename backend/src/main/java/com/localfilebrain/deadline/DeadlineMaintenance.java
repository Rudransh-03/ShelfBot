package com.localfilebrain.deadline;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.DeadlineRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.TemporalAmount;
import java.util.List;

/**
 * Startup housekeeping for extracted deadlines. Run once when the app opens:
 * past deadlines aren't actionable anymore, so
 *   - one-time (non-recurring) ones are deleted, and
 *   - recurring ones are rolled forward to their next occurrence,
 * so a yearly renewal stays in the upcoming list instead of vanishing after the
 * first year. Keeps both the "to set" list and the "reminders set" list showing
 * only things still ahead, with no past clutter to scroll past.
 */
public final class DeadlineMaintenance {

    private static final Logger log = LoggerFactory.getLogger(DeadlineMaintenance.class);

    private DeadlineMaintenance() {}

    /**
     * First occurrence of a recurring deadline on or after {@code today}, found
     * by stepping {@code due} forward by its recurrence interval. Returns
     * {@code due} unchanged for a non-recurring value or a null date.
     */
    public static LocalDate nextOccurrence(LocalDate due, String recurring, LocalDate today) {
        if (due == null) return null;
        TemporalAmount step = stepFor(recurring);
        if (step == null) return due; // not recurring
        LocalDate next = due;
        // Defensive bound so a corrupt far-past date can never spin forever.
        int guard = 0;
        while (next.isBefore(today) && guard++ < 100_000) {
            next = next.plus(step);
        }
        return next;
    }

    private static TemporalAmount stepFor(String recurring) {
        if (recurring == null) return null;
        return switch (recurring.toUpperCase()) {
            case "WEEKLY"    -> Period.ofWeeks(1);
            case "MONTHLY"   -> Period.ofMonths(1);
            case "QUARTERLY" -> Period.ofMonths(3);
            case "YEARLY"    -> Period.ofYears(1);
            default          -> null; // NONE / unknown → not recurring
        };
    }

    /**
     * Deletes past one-time deadlines and rolls past recurring ones forward.
     * Returns a short summary for logging. Never throws — housekeeping must not
     * block startup.
     */
    public static String purgeAndRoll(IndexMetadataStore store, LocalDate today) {
        String todayIso = today.toString();
        int deleted = 0, rolled = 0;
        try {
            deleted = store.deleteOneTimePastDeadlines(todayIso);
            List<DeadlineRow> recurring = store.listRecurringPastDeadlines(todayIso);
            for (DeadlineRow r : recurring) {
                try {
                    LocalDate due  = LocalDate.parse(r.dueDate());
                    LocalDate next = nextOccurrence(due, r.recurring(), today);
                    if (next != null && !next.equals(due)) {
                        store.updateDeadline(r.id(), null, null, null, null, next.toString(), null);
                        rolled++;
                    }
                } catch (Exception perRow) {
                    log.debug("Skipping deadline {} during roll-forward: {}", r.id(), perRow.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Deadline maintenance failed: {}", e.getMessage());
        }
        return "purged " + deleted + " past one-time, rolled " + rolled + " recurring forward";
    }
}
