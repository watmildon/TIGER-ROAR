// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.HighwayConstants;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils.CellRange;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils.GridCell;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils.RoadSegmentEntry;

/**
 * Checks if a road's name is corroborated by a nearby parallel carriageway.
 *
 * <p>Dual carriageways (divided highways) are mapped as two parallel oneway ways
 * in OSM. Both sides share the same name but may not be connected at endpoints.
 * This check finds nearby oneway roads with the same name running parallel,
 * providing name corroboration even without direct connectivity.</p>
 *
 * <p>Only trusts roads where the name is considered verified: no tiger:reviewed tag,
 * tiger:reviewed=yes, or tiger:reviewed=name. Roads with alignment-only review
 * or tiger:reviewed=no are not trusted for name corroboration.</p>
 */
public class DualCarriageCheck {

    private static final String TIGER_REVIEWED = "tiger:reviewed";

    /** tiger:reviewed values that confirm the name has been verified */
    private static final Set<String> NAME_VERIFIED_VALUES = Set.of("yes", "name");

    private final Map<GridCell, List<RoadSegmentEntry>> onewayGrid;
    private final double maxDistance;

    /**
     * Create a new dual carriageway check.
     *
     * @param onewayGrid spatial grid of oneway road segments
     * @param maxDistance maximum lateral distance (meters, in EastNorth units) for parallel matching
     */
    public DualCarriageCheck(Map<GridCell, List<RoadSegmentEntry>> onewayGrid, double maxDistance) {
        this.onewayGrid = onewayGrid;
        this.maxDistance = maxDistance;
    }

    /**
     * Check if the way's name is corroborated by a nearby parallel carriageway.
     *
     * @param way  the way to check
     * @param name the name to look for on parallel roads
     * @return true if a nearby parallel reviewed oneway road has the same name
     */
    public boolean isNameCorroborated(Way way, String name) {
        if (name == null || name.isEmpty() || onewayGrid.isEmpty()) {
            return false;
        }

        // The target way must itself be oneway
        if (!isOneway(way)) {
            return false;
        }

        List<Node> nodes = way.getNodes();
        // Check each segment of the target way for nearby parallel candidates
        for (int i = 0; i < nodes.size() - 1; i++) {
            Node n1 = nodes.get(i);
            Node n2 = nodes.get(i + 1);
            if (!n1.isLatLonKnown() || !n2.isLatLonKnown()) {
                continue;
            }
            EastNorth en1 = n1.getEastNorth();
            EastNorth en2 = n2.getEastNorth();
            if (en1 == null || en2 == null) {
                continue;
            }

            // Find candidate segments within maxDistance of this segment
            CellRange range = CellRange.of(en1, en2, maxDistance);
            List<RoadSegmentEntry> candidates = SpatialUtils.collectFromGrid(onewayGrid, range);

            // Deduplicate candidates by Way identity
            Set<Way> checked = new HashSet<>();
            for (RoadSegmentEntry candidate : candidates) {
                Way candidateWay = candidate.way();
                if (candidateWay == way || !checked.add(candidateWay)) {
                    continue;
                }
                if (isCorroboratingParallelRoad(candidateWay, name)
                        && isWithinDistance(en1, en2, candidateWay)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if a way is tagged as oneway (explicitly or implied by highway=motorway).
     */
    public static boolean isOneway(Way way) {
        if ("yes".equals(way.get("oneway")) || "-1".equals(way.get("oneway"))) {
            return true;
        }
        String highway = way.get("highway");
        return "motorway".equals(highway) || "motorway_link".equals(highway);
    }

    /**
     * Check if a candidate way qualifies as a corroborating parallel road.
     */
    private boolean isCorroboratingParallelRoad(Way candidateWay, String name) {
        // Must be a classified highway
        String highway = candidateWay.get("highway");
        if (highway == null || !HighwayConstants.TIGER_HIGHWAYS.contains(highway)) {
            return false;
        }

        // Must be oneway
        if (!isOneway(candidateWay)) {
            return false;
        }

        // Only trust roads where the name is considered verified:
        // - No tiger:reviewed tag (reviewed or non-TIGER road)
        // - tiger:reviewed=yes (fully reviewed)
        // - tiger:reviewed=name (name specifically reviewed)
        String reviewed = candidateWay.get(TIGER_REVIEWED);
        if (reviewed != null && !NAME_VERIFIED_VALUES.contains(reviewed)) {
            return false;
        }

        // Must have the same name
        String candidateName = candidateWay.get("name");
        return name.equals(candidateName);
    }

    /**
     * Check if any segment of a candidate way is within maxDistance of the target segment.
     */
    private boolean isWithinDistance(EastNorth targetStart, EastNorth targetEnd, Way candidateWay) {
        List<Node> candidateNodes = candidateWay.getNodes();
        for (int j = 0; j < candidateNodes.size() - 1; j++) {
            Node cn1 = candidateNodes.get(j);
            Node cn2 = candidateNodes.get(j + 1);
            if (!cn1.isLatLonKnown() || !cn2.isLatLonKnown()) {
                continue;
            }
            EastNorth cen1 = cn1.getEastNorth();
            EastNorth cen2 = cn2.getEastNorth();
            if (cen1 == null || cen2 == null) {
                continue;
            }

            // Check if midpoints of the candidate segment are near the target segment
            // (using midpoint avoids false matches at segment endpoints near intersections)
            EastNorth candidateMid = new EastNorth(
                    (cen1.getX() + cen2.getX()) / 2.0,
                    (cen1.getY() + cen2.getY()) / 2.0);
            double dist = SpatialUtils.distanceToSegment(candidateMid, targetStart, targetEnd);
            if (dist <= maxDistance && dist > 0) {
                return true;
            }

            // Also check the target segment midpoint against the candidate segment
            EastNorth targetMid = new EastNorth(
                    (targetStart.getX() + targetEnd.getX()) / 2.0,
                    (targetStart.getY() + targetEnd.getY()) / 2.0);
            dist = SpatialUtils.distanceToSegment(targetMid, cen1, cen2);
            if (dist <= maxDistance && dist > 0) {
                return true;
            }
        }
        return false;
    }
}
