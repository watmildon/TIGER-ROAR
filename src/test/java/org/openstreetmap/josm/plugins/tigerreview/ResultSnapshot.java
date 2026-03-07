// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewAnalyzer.ReviewResult;
import org.openstreetmap.josm.plugins.tigerreview.SurfaceAnalyzer.SurfaceSuggestion;
import org.openstreetmap.josm.plugins.tigerreview.SpeedLimitAnalyzer.SpeedLimitSuggestion;

/**
 * Captures a canonical snapshot of analysis results for regression comparison.
 *
 * <p>Analogous to multipoly-gone's DataSetSnapshot, but captures warning results
 * (codes, messages, fix actions) instead of geometry. Results are sorted
 * canonically by way ID then warning code for stable comparison.</p>
 */
class ResultSnapshot {

    /** Snapshot of a single warning/suggestion. */
    static class WarningSnapshot implements Comparable<WarningSnapshot> {
        final long wayId;
        final int code;
        final String message;
        final String fixAction; // "REMOVE_TAG", "SET_NAME_REVIEWED", etc., or "none"

        WarningSnapshot(long wayId, int code, String message, String fixAction) {
            this.wayId = wayId;
            this.code = code;
            this.message = message;
            this.fixAction = fixAction;
        }

        @Override
        public int compareTo(WarningSnapshot other) {
            int cmp = Long.compare(this.wayId, other.wayId);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(this.code, other.code);
            if (cmp != 0) return cmp;
            return this.message.compareTo(other.message);
        }

        @Override
        public String toString() {
            return "way=" + wayId + " code=" + code + " fix=" + fixAction + " msg=\"" + message + "\"";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof WarningSnapshot other)) return false;
            return wayId == other.wayId && code == other.code
                    && message.equals(other.message) && fixAction.equals(other.fixAction);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(wayId) * 31 + code;
        }
    }

    final List<WarningSnapshot> warnings;

    private ResultSnapshot(List<WarningSnapshot> warnings) {
        this.warnings = warnings;
    }

    /**
     * Capture a snapshot from TIGER review results.
     */
    static ResultSnapshot fromReviewResults(List<ReviewResult> results) {
        List<WarningSnapshot> warnings = new ArrayList<>();
        for (ReviewResult r : results) {
            String fix = r.getFixAction() != null ? r.getFixAction().name() : "none";
            warnings.add(new WarningSnapshot(
                    r.getWay().getUniqueId(), r.getCode(), r.getMessage(), fix));
        }
        warnings.sort(null);
        return new ResultSnapshot(warnings);
    }

    /**
     * Capture a snapshot from surface suggestion results.
     */
    static ResultSnapshot fromSurfaceResults(List<SurfaceSuggestion> results) {
        List<WarningSnapshot> warnings = new ArrayList<>();
        for (SurfaceSuggestion s : results) {
            String fix = s.getFixSupplier() != null ? "FIX" : "none";
            warnings.add(new WarningSnapshot(
                    s.getWay().getUniqueId(), s.getCode(), s.getMessage(), fix));
        }
        warnings.sort(null);
        return new ResultSnapshot(warnings);
    }

    /**
     * Capture a snapshot from speed limit suggestion results.
     */
    static ResultSnapshot fromSpeedLimitResults(List<SpeedLimitSuggestion> results) {
        List<WarningSnapshot> warnings = new ArrayList<>();
        for (SpeedLimitSuggestion s : results) {
            String fix = s.getFixSupplier() != null ? "FIX" : "none";
            warnings.add(new WarningSnapshot(
                    s.getWay().getUniqueId(), s.getCode(), s.getMessage(), fix));
        }
        warnings.sort(null);
        return new ResultSnapshot(warnings);
    }

    /**
     * Merge multiple snapshots into one (for regression tests that run all analyzers).
     */
    static ResultSnapshot merge(ResultSnapshot... snapshots) {
        List<WarningSnapshot> all = new ArrayList<>();
        for (ResultSnapshot s : snapshots) {
            all.addAll(s.warnings);
        }
        all.sort(null);
        return new ResultSnapshot(all);
    }

    /**
     * Convert to a canonical string for golden-file storage.
     */
    String toCanonicalString() {
        StringBuilder sb = new StringBuilder();
        sb.append("# TIGER-ROAR Result Snapshot\n");
        sb.append("# Total warnings: ").append(warnings.size()).append("\n\n");
        for (WarningSnapshot w : warnings) {
            sb.append(w).append("\n");
        }
        return sb.toString();
    }

    /**
     * Compare two snapshots and return a human-readable diff report.
     * Returns an empty string if the snapshots are identical.
     */
    static String diff(ResultSnapshot expected, ResultSnapshot actual) {
        StringBuilder sb = new StringBuilder();

        // Build lists for comparison
        List<WarningSnapshot> expectedList = new ArrayList<>(expected.warnings);
        List<WarningSnapshot> actualList = new ArrayList<>(actual.warnings);

        // Find removed warnings
        List<WarningSnapshot> removed = new ArrayList<>(expectedList);
        removed.removeAll(actualList);

        // Find added warnings
        List<WarningSnapshot> added = new ArrayList<>(actualList);
        added.removeAll(expectedList);

        if (!removed.isEmpty()) {
            sb.append("== REMOVED WARNINGS ==\n");
            for (WarningSnapshot w : removed) {
                sb.append("  - ").append(w).append("\n");
            }
            sb.append("\n");
        }

        if (!added.isEmpty()) {
            sb.append("== ADDED WARNINGS ==\n");
            for (WarningSnapshot w : added) {
                sb.append("  + ").append(w).append("\n");
            }
            sb.append("\n");
        }

        if (expected.warnings.size() != actual.warnings.size()) {
            sb.append("== SUMMARY ==\n");
            sb.append("  warnings: ").append(expected.warnings.size())
                    .append(" -> ").append(actual.warnings.size()).append("\n");
        }

        return sb.toString();
    }
}
