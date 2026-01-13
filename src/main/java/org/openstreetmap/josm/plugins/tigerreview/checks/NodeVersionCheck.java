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
 * - The average version of nodes in the way exceeds a threshold (default 1.5)
 */
public class NodeVersionCheck {

    private static final String TIGER_REVIEWED = "tiger:reviewed";

    /** Values that indicate alignment has been explicitly reviewed */
    private static final Set<String> ALIGNMENT_REVIEWED_VALUES = new HashSet<>(
            Arrays.asList("position", "alignment", "yes"));

    private final double minAvgVersion;

    /**
     * Create a new NodeVersionCheck.
     *
     * @param minAvgVersion Minimum average node version to consider alignment verified
     */
    public NodeVersionCheck(double minAvgVersion) {
        this.minAvgVersion = minAvgVersion;
    }

    /**
     * Check if the way's alignment has been verified.
     *
     * @param way The way to check
     * @return true if alignment evidence suggests the road has been reviewed
     */
    public boolean isAlignmentVerified(Way way) {
        // Check for explicit alignment review tags
        String tigerReviewed = way.get(TIGER_REVIEWED);
        if (tigerReviewed != null && ALIGNMENT_REVIEWED_VALUES.contains(tigerReviewed)) {
            return true;
        }

        // Check average node version
        return calculateAverageNodeVersion(way) > minAvgVersion;
    }

    /**
     * Calculate the average version of all nodes in a way.
     *
     * @param way The way to analyze
     * @return The average version, or 0 if the way has no nodes
     */
    public double calculateAverageNodeVersion(Way way) {
        if (way.getNodesCount() == 0) {
            return 0;
        }

        double sumVersions = 0;
        int nodeCount = 0;

        for (Node node : way.getNodes()) {
            // Only count nodes that have been uploaded (version > 0)
            if (node.getVersion() > 0) {
                sumVersions += node.getVersion();
                nodeCount++;
            }
        }

        if (nodeCount == 0) {
            return 0;
        }

        return sumVersions / nodeCount;
    }
}
