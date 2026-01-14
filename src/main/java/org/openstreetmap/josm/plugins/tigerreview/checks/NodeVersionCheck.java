// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Checks if a road's alignment has been verified based on node versions
 * and explicit tiger:reviewed tags.
 *
 * Alignment is considered verified if:
 * - The way has tiger:reviewed=position or tiger:reviewed=alignment, OR
 * - The average version of nodes in the way exceeds a threshold (default 1.5), OR
 * - Every node in the way has version > 1 (all nodes have been edited)
 */
public class NodeVersionCheck {

    private static final String TIGER_REVIEWED = "tiger:reviewed";

    /** Values that indicate alignment has been explicitly reviewed */
    private static final Set<String> ALIGNMENT_REVIEWED_VALUES = new HashSet<>(
            Arrays.asList("position", "alignment", "yes"));

    private final double minAvgVersion;

    /**
     * Evidence types for alignment verification.
     */
    public enum AlignmentEvidence {
        /** No evidence of alignment verification */
        NONE,
        /** Average node version exceeds threshold */
        AVG_VERSION_HIGH,
        /** Every node in the way has been edited (version > 1) */
        ALL_NODES_EDITED
    }

    /**
     * Result of alignment check with evidence details.
     */
    public static class AlignmentResult {
        private final AlignmentEvidence evidence;
        private final double avgVersion;

        public AlignmentResult(AlignmentEvidence evidence, double avgVersion) {
            this.evidence = evidence;
            this.avgVersion = avgVersion;
        }

        public AlignmentEvidence getEvidence() {
            return evidence;
        }

        public double getAvgVersion() {
            return avgVersion;
        }

        public boolean isVerified() {
            return evidence != AlignmentEvidence.NONE;
        }
    }

    /**
     * Create a new NodeVersionCheck.
     *
     * @param minAvgVersion Minimum average node version to consider alignment verified
     */
    public NodeVersionCheck(double minAvgVersion) {
        this.minAvgVersion = minAvgVersion;
    }

    /**
     * Check if the way's alignment has been verified and return detailed evidence.
     *
     * @param way The way to check
     * @return AlignmentResult with evidence type and average node version
     */
    public AlignmentResult checkAlignment(Way way) {
        // Check for explicit alignment review tags
        String tigerReviewed = way.get(TIGER_REVIEWED);
        if (tigerReviewed != null && ALIGNMENT_REVIEWED_VALUES.contains(tigerReviewed)) {
            // Explicit tag means verified, but we still calculate avg for display
            double avgVersion = calculateAverageNodeVersion(way);
            return new AlignmentResult(AlignmentEvidence.AVG_VERSION_HIGH, avgVersion);
        }

        // Calculate node statistics
        NodeStats stats = calculateNodeStats(way);

        // Check if all nodes have been edited
        if (stats.allNodesEdited && stats.nodeCount > 0) {
            return new AlignmentResult(AlignmentEvidence.ALL_NODES_EDITED, stats.avgVersion);
        }

        // Check average node version
        if (stats.avgVersion > minAvgVersion) {
            return new AlignmentResult(AlignmentEvidence.AVG_VERSION_HIGH, stats.avgVersion);
        }

        return new AlignmentResult(AlignmentEvidence.NONE, stats.avgVersion);
    }

    /**
     * Check if the way's alignment has been verified.
     *
     * @param way The way to check
     * @return true if alignment evidence suggests the road has been reviewed
     */
    public boolean isAlignmentVerified(Way way) {
        return checkAlignment(way).isVerified();
    }

    /**
     * Calculate the average version of all nodes in a way.
     *
     * @param way The way to analyze
     * @return The average version, or 0 if the way has no nodes
     */
    public double calculateAverageNodeVersion(Way way) {
        return calculateNodeStats(way).avgVersion;
    }

    /**
     * Calculate statistics about node versions in a way.
     */
    private NodeStats calculateNodeStats(Way way) {
        if (way.getNodesCount() == 0) {
            return new NodeStats(0, 0, false);
        }

        double sumVersions = 0;
        int nodeCount = 0;
        boolean allNodesEdited = true;

        for (Node node : way.getNodes()) {
            // Only count nodes that have been uploaded (version > 0)
            if (node.getVersion() > 0) {
                sumVersions += node.getVersion();
                nodeCount++;
                if (node.getVersion() <= 1) {
                    allNodesEdited = false;
                }
            }
        }

        if (nodeCount == 0) {
            return new NodeStats(0, 0, false);
        }

        return new NodeStats(sumVersions / nodeCount, nodeCount, allNodesEdited);
    }

    private static class NodeStats {
        final double avgVersion;
        final int nodeCount;
        final boolean allNodesEdited;

        NodeStats(double avgVersion, int nodeCount, boolean allNodesEdited) {
            this.avgVersion = avgVersion;
            this.nodeCount = nodeCount;
            this.allNodesEdited = allNodesEdited;
        }
    }
}
