package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Automated scan and human review of a release (design §8.2). */
public class Review {
    public UUID id;
    public UUID releaseId;
    public UUID listingId;
    /** PENDING | IN_REVIEW | APPROVED | REJECTED | CHANGES_REQUESTED */
    public String status;
    public UUID reviewerId;
    public String notes;
    public Instant submittedAt;
    public Instant decidedAt;
    public List<Finding> findings = new ArrayList<>();

    public record Finding(String severity, String rule, String message) {
        public static Finding error(String rule, String message) {
            return new Finding("ERROR", rule, message);
        }

        public static Finding warn(String rule, String message) {
            return new Finding("WARN", rule, message);
        }

        public static Finding info(String rule, String message) {
            return new Finding("INFO", rule, message);
        }
    }

    public boolean hasBlockingFindings() {
        return findings.stream()
                .anyMatch(f -> "ERROR".equals(f.severity()) || "CRITICAL".equals(f.severity()));
    }
}
