// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.List;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewTest;

/**
 * Checks if a road's name is corroborated by connected roads.
 *
 * A name is considered corroborated if a directly connected road (sharing a node)
 * has the same name AND does not have tiger:reviewed=no.
 */
public class ConnectedRoadCheck {

    private static final String TIGER_REVIEWED = "tiger:reviewed";

    /**
     * Types of connection evidence for name corroboration.
     */
    public enum ConnectionType {
        /** No matching connected roads */
        NONE,
        /** Matching road connected at one end only */
        ONE_END,
        /** Matching roads connected at both ends */
        BOTH_ENDS
    }

    /**
     * Check what type of connection corroborates the way's name.
     *
     * @param way  The way to check
     * @param name The name to look for on connected roads
     * @return ConnectionType indicating the level of corroboration
     */
    public ConnectionType checkConnection(Way way, String name) {
        if (name == null || name.isEmpty()) {
            return ConnectionType.NONE;
        }

        List<Node> nodes = way.getNodes();
        if (nodes.isEmpty()) {
            return ConnectionType.NONE;
        }

        Node firstNode = nodes.get(0);
        Node lastNode = nodes.get(nodes.size() - 1);

        boolean matchAtFirst = hasCorroboratingConnection(firstNode, way, name);
        boolean matchAtLast = hasCorroboratingConnection(lastNode, way, name);

        if (matchAtFirst && matchAtLast) {
            return ConnectionType.BOTH_ENDS;
        } else if (matchAtFirst || matchAtLast) {
            return ConnectionType.ONE_END;
        }

        // Only check endpoints - interior connections don't corroborate the name
        // (a road crossing over or under doesn't confirm the name is correct)
        return ConnectionType.NONE;
    }

    /**
     * Check if the way's name is corroborated by a connected road.
     *
     * @param way  The way to check
     * @param name The name to look for on connected roads
     * @return true if a connected reviewed road has the same name
     */
    public boolean isNameCorroborated(Way way, String name) {
        return checkConnection(way, name) != ConnectionType.NONE;
    }

    /**
     * Check if a node has any corroborating road connections.
     */
    private boolean hasCorroboratingConnection(Node node, Way excludeWay, String name) {
        for (OsmPrimitive referrer : node.getReferrers()) {
            if (referrer instanceof Way connectedWay && connectedWay != excludeWay) {
                if (isCorroboratingRoad(connectedWay, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** tiger:reviewed values that confirm the name has been verified */
    private static final java.util.Set<String> NAME_VERIFIED_VALUES = java.util.Set.of(
            "yes", "name");

    /**
     * Check if a connected way corroborates the given name.
     *
     * @param connectedWay The connected way to check
     * @param name         The name to match
     * @return true if this road corroborates the name
     */
    private boolean isCorroboratingRoad(Way connectedWay, String name) {
        // Must be a classified highway
        String highway = connectedWay.get("highway");
        if (highway == null || !TIGERReviewTest.CLASSIFIED_HIGHWAYS.contains(highway)) {
            return false;
        }

        // Only trust roads where the name is considered verified:
        // - No tiger:reviewed tag (reviewed or non-TIGER road)
        // - tiger:reviewed=yes (fully reviewed)
        // - tiger:reviewed=name (name specifically reviewed)
        String reviewed = connectedWay.get(TIGER_REVIEWED);
        if (reviewed != null && !NAME_VERIFIED_VALUES.contains(reviewed)) {
            return false;
        }

        // Must have the same name
        String connectedName = connectedWay.get("name");
        return name.equals(connectedName);
    }
}
