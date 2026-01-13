// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

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
     * Check if the way's name is corroborated by a connected road.
     *
     * @param way  The way to check
     * @param name The name to look for on connected roads
     * @return true if a connected reviewed road has the same name
     */
    public boolean isNameCorroborated(Way way, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        // Check each node in the way for connected roads
        for (Node node : way.getNodes()) {
            for (OsmPrimitive referrer : node.getReferrers()) {
                if (referrer instanceof Way connectedWay && connectedWay != way) {
                    if (isCorroboratingRoad(connectedWay, name)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

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

        // Must NOT have tiger:reviewed=no (unverified roads don't count as corroboration)
        if ("no".equals(connectedWay.get(TIGER_REVIEWED))) {
            return false;
        }

        // Must have the same name
        String connectedName = connectedWay.get("name");
        return name.equals(connectedName);
    }
}
