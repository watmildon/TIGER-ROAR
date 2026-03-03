// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.User;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Checks if a road's alignment has been verified based on node edit history
 * and explicit tiger:reviewed tags.
 *
 * Alignment is considered verified if:
 * - The way has tiger:reviewed=position or tiger:reviewed=alignment, OR
 * - A high percentage of nodes have been edited by humans (not bots), OR
 * - Every node in the way has been edited by a human
 *
 * <p>The check distinguishes between bot/importer edits and human edits by
 * examining the last editor of each node. Nodes last touched by known TIGER
 * importers or cleanup bots are not considered "edited" for alignment purposes,
 * even if they have version > 1.</p>
 *
 * @see <a href="https://wiki.openstreetmap.org/wiki/TIGER_fixup/Overpass_queries">TIGER fixup queries</a>
 * @see <a href="https://wiki.openstreetmap.org/wiki/TIGER_fixup/node_tags">TIGER node tags cleanup</a>
 */
public class NodeVersionCheck {

    private static final String TIGER_REVIEWED = "tiger:reviewed";

    /**
     * Known bot and importer accounts whose edits don't indicate alignment review.
     * Nodes last touched by these accounts should not count as "edited."
     *
     * <p>History of TIGER automated edits:</p>
     * <ul>
     *   <li>DaveHansenTiger (Aug 2007 - May 2008): Original TIGER 2005 import</li>
     *   <li>Milenko (Oct - Dec 2007): Pennsylvania counties import</li>
     *   <li>woodpeck_fixbot (Sept 2009 - Jan 2010): Removed superfluous tags from ~170M nodes</li>
     *   <li>balrog-kun (March - April 2010): Abbreviation expansion</li>
     *   <li>bot-mode (Dec 2012 - April 2013): Abbreviation expansion</li>
     * </ul>
     */
    private static final Set<String> BUILTIN_AUTOMATED_USERNAMES = Set.of(
            // Original TIGER importers (2007-2008)
            "DaveHansenTiger",
            "Milenko",
            // Cleanup bots
            "woodpeck_fixbot",
            "balrog-kun",
            "bot-mode"
    );

    /**
     * Tag key for overriding node version in tests.
     * When present on a node, this value is used instead of the actual OSM version.
     */
    public static final String TEST_VERSION_TAG = "__TEST_VERSION";

    /**
     * Tag key for overriding node user in tests.
     * When present on a node, this value is used instead of the actual OSM user.
     */
    public static final String TEST_USER_TAG = "__TEST_USER";

    /** Values that indicate alignment has been explicitly reviewed */
    private static final Set<String> ALIGNMENT_REVIEWED_VALUES = new HashSet<>(
            Arrays.asList("position", "alignment", "yes"));

    private final double minAvgVersion;
    private final double minPercentageEdited;
    private final Set<String> automatedUsernames;

    /** Default minimum percentage of nodes that must be edited by humans (not bots) */
    public static final double DEFAULT_MIN_PERCENTAGE_EDITED = 0.8;

    /**
     * Evidence types for alignment verification.
     */
    public enum AlignmentEvidence {
        /** No evidence of alignment verification */
        NONE,
        /** Average node version exceeds threshold */
        AVG_VERSION_HIGH,
        /** Every node in the way has been edited by a human (not a bot) */
        ALL_NODES_EDITED,
        /** High percentage of nodes have been edited by a human (not a bot) */
        HIGH_PERCENTAGE_EDITED
    }

    /**
     * Result of alignment check with evidence details.
     */
    public static class AlignmentResult {
        private final AlignmentEvidence evidence;
        private final double avgVersion;
        private final double percentageEdited;

        public AlignmentResult(AlignmentEvidence evidence, double avgVersion, double percentageEdited) {
            this.evidence = evidence;
            this.avgVersion = avgVersion;
            this.percentageEdited = percentageEdited;
        }

        public AlignmentEvidence getEvidence() {
            return evidence;
        }

        public double getAvgVersion() {
            return avgVersion;
        }

        public double getPercentageEdited() {
            return percentageEdited;
        }

        public boolean isVerified() {
            return evidence != AlignmentEvidence.NONE;
        }
    }

    /**
     * Create a new NodeVersionCheck with default percentage threshold and no additional bot usernames.
     *
     * @param minAvgVersion Minimum average node version to consider alignment verified
     */
    public NodeVersionCheck(double minAvgVersion) {
        this(minAvgVersion, DEFAULT_MIN_PERCENTAGE_EDITED, "");
    }

    /**
     * Create a new NodeVersionCheck with no additional bot usernames.
     *
     * @param minAvgVersion Minimum average node version to consider alignment verified
     * @param minPercentageEdited Minimum percentage of nodes edited by humans to consider alignment verified
     */
    public NodeVersionCheck(double minAvgVersion, double minPercentageEdited) {
        this(minAvgVersion, minPercentageEdited, "");
    }

    /**
     * Create a new NodeVersionCheck.
     *
     * @param minAvgVersion Minimum average node version to consider alignment verified
     * @param minPercentageEdited Minimum percentage of nodes edited by humans to consider alignment verified
     * @param additionalBotUsernames Semicolon-delimited list of additional usernames to treat as bots
     */
    public NodeVersionCheck(double minAvgVersion, double minPercentageEdited, String additionalBotUsernames) {
        this.minAvgVersion = minAvgVersion;
        this.minPercentageEdited = minPercentageEdited;
        this.automatedUsernames = buildAutomatedUsernamesSet(additionalBotUsernames);
    }

    /**
     * Build the complete set of automated usernames by combining built-in and user-configured names.
     *
     * @param additionalUsernames Semicolon-delimited list of additional usernames (may be null or empty)
     * @return Combined set of all automated usernames
     */
    private static Set<String> buildAutomatedUsernamesSet(String additionalUsernames) {
        Set<String> combined = new HashSet<>(BUILTIN_AUTOMATED_USERNAMES);
        if (additionalUsernames != null && !additionalUsernames.trim().isEmpty()) {
            for (String username : additionalUsernames.split(";")) {
                String trimmed = username.trim();
                if (!trimmed.isEmpty()) {
                    combined.add(trimmed);
                }
            }
        }
        return combined;
    }

    /**
     * Get the built-in set of automated usernames.
     * Useful for displaying to users which usernames are already included.
     *
     * @return Unmodifiable set of built-in automated usernames
     */
    public static Set<String> getBuiltinAutomatedUsernames() {
        return BUILTIN_AUTOMATED_USERNAMES;
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
            // Explicit tag means verified, but we still calculate stats for display
            NodeStats stats = calculateNodeStats(way);
            return new AlignmentResult(AlignmentEvidence.AVG_VERSION_HIGH, stats.avgVersion, stats.percentageEdited);
        }

        // Calculate node statistics
        NodeStats stats = calculateNodeStats(way);

        // Check if all nodes have been edited (strongest evidence)
        if (stats.allNodesEdited && stats.nodeCount > 0) {
            return new AlignmentResult(AlignmentEvidence.ALL_NODES_EDITED, stats.avgVersion, stats.percentageEdited);
        }

        // Check if high percentage of nodes have been edited
        if (stats.percentageEdited >= minPercentageEdited && stats.nodeCount > 0) {
            return new AlignmentResult(AlignmentEvidence.HIGH_PERCENTAGE_EDITED, stats.avgVersion, stats.percentageEdited);
        }

        // Average version fallback: only use when no user info is available.
        // When we have usernames, the percentage-edited check above is more
        // accurate — a high avg version caused by bot edits (e.g. woodpeck_fixbot
        // bumping nodes to v2) should not count as alignment verification.
        if (!stats.hasUserInfo && stats.avgVersion > minAvgVersion) {
            return new AlignmentResult(AlignmentEvidence.AVG_VERSION_HIGH, stats.avgVersion, stats.percentageEdited);
        }

        return new AlignmentResult(AlignmentEvidence.NONE, stats.avgVersion, stats.percentageEdited);
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
     *
     * <p>A node is considered "edited" based on who last modified it,
     * not just its version number. See {@link #isNodeEdited(Node)}.</p>
     *
     * <p>The {@code hasUserInfo} flag tracks whether any node had user
     * information available. When user info is present, the average version
     * fallback heuristic should not be used because the username-based
     * edited/not-edited classification is more accurate.</p>
     */
    private NodeStats calculateNodeStats(Way way) {
        if (way.getNodesCount() == 0) {
            return new NodeStats(0, 0, false, 0, false);
        }

        double sumVersions = 0;
        int nodeCount = 0;
        int editedCount = 0;
        boolean hasUserInfo = false;

        for (Node node : way.getNodes()) {
            // Use __TEST_VERSION tag if present, otherwise use actual version
            int version = getEffectiveVersion(node);

            // Only count nodes that have been uploaded (version > 0)
            if (version > 0) {
                sumVersions += version;
                nodeCount++;
                // Track if we have user info for any node
                if (getEffectiveUsername(node) != null) {
                    hasUserInfo = true;
                }
                // Check if node was edited by a human (not a bot/importer)
                if (isNodeEdited(node)) {
                    editedCount++;
                }
            }
        }

        if (nodeCount == 0) {
            return new NodeStats(0, 0, false, 0, false);
        }

        double percentageEdited = (double) editedCount / nodeCount;
        boolean allNodesEdited = (editedCount == nodeCount);

        return new NodeStats(sumVersions / nodeCount, nodeCount, allNodesEdited, percentageEdited, hasUserInfo);
    }

    /**
     * Get the effective version of a node, using __TEST_VERSION tag if present.
     *
     * @param node The node to check
     * @return The test version if __TEST_VERSION tag exists, otherwise the actual OSM version
     */
    private int getEffectiveVersion(Node node) {
        String testVersion = node.get(TEST_VERSION_TAG);
        if (testVersion != null) {
            try {
                return Integer.parseInt(testVersion);
            } catch (NumberFormatException e) {
                // Fall back to actual version if tag value is invalid
                return node.getVersion();
            }
        }
        return node.getVersion();
    }

    /**
     * Get the effective username of a node's last editor, using __TEST_USER tag if present.
     *
     * @param node The node to check
     * @return The username, or null if not available
     */
    private String getEffectiveUsername(Node node) {
        // Check for test override first
        String testUser = node.get(TEST_USER_TAG);
        if (testUser != null) {
            return testUser;
        }

        // Get actual user from OSM data
        User user = node.getUser();
        if (user != null) {
            String name = user.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    /**
     * Determines if a node has been edited by a human (not a bot/importer).
     *
     * <p>A node is considered "edited" if:</p>
     * <ul>
     *   <li>It was last edited by a user NOT in the known bot/importer list, OR</li>
     *   <li>If no user info is available, it has version > 2 (fallback heuristic)</li>
     * </ul>
     *
     * <p>The fallback uses version > 2 because versions 1-2 typically represent
     * the original TIGER import plus bot cleanup (woodpeck_fixbot, etc.).</p>
     *
     * @param node The node to check
     * @return true if the node appears to have been human-edited
     */
    private boolean isNodeEdited(Node node) {
        // A node modified in the current editing session counts as human-edited,
        // even if the last *uploaded* editor was a bot.
        if (node.isModified()) {
            return true;
        }

        int version = getEffectiveVersion(node);

        // Version 0 means not uploaded yet
        if (version <= 0) {
            return false;
        }

        // Check who last edited this node
        String username = getEffectiveUsername(node);
        if (username != null) {
            // If last editor was a bot/importer, node is NOT considered edited
            if (automatedUsernames.contains(username)) {
                return false;
            }
            // If last editor was a real user, node IS edited (even v1)
            return true;
        }

        // Fallback: if no user info available, use version > 2 heuristic
        // (accounts for import + bot cleanup)
        return version > 2;
    }

    private static class NodeStats {
        final double avgVersion;
        final int nodeCount;
        final boolean allNodesEdited;
        final double percentageEdited;
        /** Whether any node had user info available */
        final boolean hasUserInfo;

        NodeStats(double avgVersion, int nodeCount, boolean allNodesEdited,
                double percentageEdited, boolean hasUserInfo) {
            this.avgVersion = avgVersion;
            this.nodeCount = nodeCount;
            this.allNodesEdited = allNodesEdited;
            this.percentageEdited = percentageEdited;
            this.hasUserInfo = hasUserInfo;
        }
    }
}
