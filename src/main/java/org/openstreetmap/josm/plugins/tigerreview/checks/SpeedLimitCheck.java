// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.HighwayConstants;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewPreferences;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryDataCache;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.SpeedLimitDetection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Matches Mapillary speed limit sign detections to OSM ways and compares
 * detected speed values against existing {@code maxspeed} tags.
 *
 * <p>Ambiguous detections near junction nodes shared by multiple highway
 * ways are skipped to avoid false positives.</p>
 */
public class SpeedLimitCheck {

    /** Distance threshold for considering a detection "at" a junction node (meters) */
    private static final double JUNCTION_TOLERANCE_M = 5.0;

    /** Special maxspeed values that should not be compared against sign detections */
    private static final Set<String> SPECIAL_MAXSPEED_VALUES = Set.of(
            "none", "signals", "walk", "variable", "national", "urban", "rural",
            "motorway", "trunk", "nsl_single", "nsl_dual", "zone:living_street");

    /**
     * Result of checking a way against Mapillary speed limit detections.
     */
    public static class SpeedLimitResult {
        private final int detectedSpeed;
        private final int detectionCount;
        private final String existingMaxspeed;
        private final ResultType type;

        SpeedLimitResult(int detectedSpeed, int detectionCount,
                         String existingMaxspeed, ResultType type) {
            this.detectedSpeed = detectedSpeed;
            this.detectionCount = detectionCount;
            this.existingMaxspeed = existingMaxspeed;
            this.type = type;
        }

        public int getDetectedSpeed() {
            return detectedSpeed;
        }

        public int getDetectionCount() {
            return detectionCount;
        }

        public String getExistingMaxspeed() {
            return existingMaxspeed;
        }

        public ResultType getType() {
            return type;
        }

        public enum ResultType {
            /** Road has no maxspeed tag; Mapillary detected a speed limit sign */
            MISSING,
            /** Road's maxspeed disagrees with Mapillary detection */
            CONFLICT,
            /** Road's maxspeed matches Mapillary detection */
            MATCH,
            /** No usable detections for this way */
            NO_DATA
        }
    }

    /**
     * Check a way against Mapillary speed limit detections.
     *
     * @param way The way to check
     * @return The check result
     */
    public SpeedLimitResult check(Way way) {
        MapillaryDataCache cache = MapillaryDataCache.getInstance();
        if (!cache.isReady()) {
            return new SpeedLimitResult(0, 0, null, SpeedLimitResult.ResultType.NO_DATA);
        }

        double maxDistance = Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_MAPILLARY_MAX_DISTANCE,
                TIGERReviewPreferences.DEFAULT_MAPILLARY_MAX_DISTANCE);

        List<SpeedLimitDetection> nearby = cache.findNearbyDetections(way, maxDistance);
        if (nearby.isEmpty()) {
            return new SpeedLimitResult(0, 0, null, SpeedLimitResult.ResultType.NO_DATA);
        }

        // Filter out ambiguous detections near junction nodes
        List<SpeedLimitDetection> unambiguous = filterAmbiguousDetections(way, nearby);
        if (unambiguous.isEmpty()) {
            return new SpeedLimitResult(0, 0, null, SpeedLimitResult.ResultType.NO_DATA);
        }

        // Find consensus speed (mode)
        int consensusSpeed = findConsensusSpeed(unambiguous);
        if (consensusSpeed <= 0) {
            return new SpeedLimitResult(0, 0, null, SpeedLimitResult.ResultType.NO_DATA);
        }

        int count = countSpeed(unambiguous, consensusSpeed);
        String existingMaxspeed = way.get("maxspeed");

        if (existingMaxspeed == null || existingMaxspeed.isEmpty()) {
            return new SpeedLimitResult(consensusSpeed, count, null,
                    SpeedLimitResult.ResultType.MISSING);
        }

        // Parse existing maxspeed and compare
        int existingSpeed = parseMaxspeed(existingMaxspeed);
        if (existingSpeed <= 0) {
            // Special value or unparseable — treat as no data for comparison
            return new SpeedLimitResult(0, 0, existingMaxspeed,
                    SpeedLimitResult.ResultType.NO_DATA);
        }

        if (existingSpeed == consensusSpeed) {
            return new SpeedLimitResult(consensusSpeed, count, existingMaxspeed,
                    SpeedLimitResult.ResultType.MATCH);
        } else {
            return new SpeedLimitResult(consensusSpeed, count, existingMaxspeed,
                    SpeedLimitResult.ResultType.CONFLICT);
        }
    }

    /**
     * Filter out detections that are near a junction node shared by multiple highway ways.
     * These are ambiguous because the sign could apply to any of the connected roads.
     */
    private List<SpeedLimitDetection> filterAmbiguousDetections(Way way, List<SpeedLimitDetection> detections) {
        List<Node> nodes = way.getNodes();
        return detections.stream().filter(det -> {
            EastNorth detEn = det.location().getEastNorth(ProjectionRegistry.getProjection());
            if (detEn == null) return false;

            // Check if detection is near any junction node
            for (Node node : nodes) {
                if (!node.isLatLonKnown()) continue;
                if (!isJunctionNode(node, way)) continue;

                EastNorth nodeEn = node.getEastNorth();
                if (nodeEn == null) continue;

                if (detEn.distance(nodeEn) < JUNCTION_TOLERANCE_M) {
                    return false; // Ambiguous — skip
                }
            }
            return true;
        }).toList();
    }

    /**
     * Check if a node is a junction (shared by 2+ highway ways).
     */
    private boolean isJunctionNode(Node node, Way currentWay) {
        List<OsmPrimitive> referrers = node.getReferrers();
        int highwayCount = 0;
        for (OsmPrimitive ref : referrers) {
            if (ref instanceof Way w && w != currentWay && !w.isDeleted()) {
                String highway = w.get("highway");
                if (highway != null && HighwayConstants.SURFACE_HIGHWAYS.contains(highway)) {
                    highwayCount++;
                    if (highwayCount >= 1) {
                        return true; // Current way + at least one other highway way
                    }
                }
            }
        }
        return false;
    }

    /**
     * Find the most common speed value (mode). Returns -1 if tied.
     */
    private int findConsensusSpeed(List<SpeedLimitDetection> detections) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (SpeedLimitDetection det : detections) {
            counts.merge(det.speedValue(), 1, Integer::sum);
        }

        int bestSpeed = -1;
        int bestCount = 0;
        boolean tied = false;

        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestSpeed = entry.getKey();
                bestCount = entry.getValue();
                tied = false;
            } else if (entry.getValue() == bestCount) {
                tied = true;
            }
        }

        return tied ? -1 : bestSpeed;
    }

    /**
     * Count how many detections have the given speed value.
     */
    private int countSpeed(List<SpeedLimitDetection> detections, int speed) {
        int count = 0;
        for (SpeedLimitDetection det : detections) {
            if (det.speedValue() == speed) {
                count++;
            }
        }
        return count;
    }

    /**
     * Parse a maxspeed tag value to extract the numeric speed in mph.
     *
     * <p>Handles formats:
     * <ul>
     *   <li>{@code "35 mph"} → 35</li>
     *   <li>{@code "35"} → 35 (assumed mph in US context)</li>
     *   <li>{@code "none"}, {@code "signals"}, etc. → -1 (special values)</li>
     * </ul>
     *
     * @return the speed in mph, or -1 if unparseable or a special value
     */
    static int parseMaxspeed(String maxspeed) {
        if (maxspeed == null || maxspeed.isEmpty()) {
            return -1;
        }

        // Check for special values
        if (SPECIAL_MAXSPEED_VALUES.contains(maxspeed.toLowerCase())) {
            return -1;
        }

        // Try "XX mph" format
        String trimmed = maxspeed.trim();
        if (trimmed.endsWith(" mph")) {
            try {
                return Integer.parseInt(trimmed.substring(0, trimmed.length() - 4).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // Try plain number (assumed mph in US context)
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
