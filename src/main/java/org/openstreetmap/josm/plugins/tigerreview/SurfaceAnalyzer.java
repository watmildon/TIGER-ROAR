// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck.SurfaceResult;

/**
 * Analysis engine for surface suggestions, used by both the validator test
 * ({@link SurfaceTest}) and the side panel's Surface tab.
 *
 * <p>Four rules:
 * <ol>
 *   <li>Connected same-name/highway propagation (graph-based BFS)</li>
 *   <li>{@code lanes} tag implies {@code surface=paved}</li>
 *   <li>Service roads inside parking areas inherit the area's surface</li>
 *   <li>Crossing way surface inference (footway/cycleway crossings)</li>
 * </ol>
 */
public final class SurfaceAnalyzer {

    private SurfaceAnalyzer() {
        // utility class
    }

    /**
     * A single surface suggestion result.
     */
    public static class SurfaceSuggestion implements TreeDisplayable {
        private final Way way;
        private final int code;
        private final String message;
        private final String groupMessage;
        private final String surfaceValue;

        SurfaceSuggestion(Way way, int code, String message,
                          String groupMessage, String surfaceValue) {
            this.way = way;
            this.code = code;
            this.message = message;
            this.groupMessage = groupMessage;
            this.surfaceValue = surfaceValue;
        }

        @Override
        public Way getWay() {
            return way;
        }

        @Override
        public int getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public String getGroupMessage() {
            return groupMessage;
        }

        @Override
        public Supplier<Command> getFixSupplier() {
            if (surfaceValue == null) {
                return null;
            }
            return () -> new ChangePropertyCommand(way, "surface", surfaceValue);
        }

        public String getSurfaceValue() {
            return surfaceValue;
        }
    }

    /**
     * Check if a way is eligible for surface analysis.
     */
    public static boolean isEligible(Way way) {
        if (!way.isUsable()) {
            return false;
        }
        String highway = way.get("highway");
        return highway != null && HighwayConstants.SURFACE_HIGHWAYS.contains(highway);
    }

    /**
     * Check if a way is a bridge structure ({@code man_made=bridge}).
     * Bridge structures commonly have a different surface material than
     * the roads they carry, so surface should not propagate across them.
     */
    private static boolean isBridgeStructure(Way way) {
        return "bridge".equals(way.get("man_made"));
    }

    /**
     * Check if a way needs a surface suggestion (no surface, or generic surface).
     */
    private static boolean needsSurface(Way way) {
        String existingSurface = way.get("surface");
        return existingSurface == null
                || "paved".equals(existingSurface)
                || "unpaved".equals(existingSurface);
    }

    /**
     * Analyze a single way for surface suggestions (for validator per-way mode).
     * Uses immediate-neighbor check for Rule 1 (not full graph propagation).
     *
     * @param way          the way to analyze
     * @param surfaceCheck the check instance to use
     * @return a suggestion, or null if no suggestion
     */
    public static SurfaceSuggestion analyzeWay(Way way, SurfaceCheck surfaceCheck) {
        if (!needsSurface(way)) {
            return null;
        }

        // Rule 1: immediate-neighbor connected road check
        SurfaceSuggestion connResult = checkImmediateNeighbors(way, surfaceCheck);
        if (connResult != null) {
            return connResult;
        }

        // Rule 4: crossing way surface inference
        SurfaceSuggestion crossingResult = checkCrossingsForWay(way);
        if (crossingResult != null) {
            return crossingResult;
        }

        // Rule 3: parking area containment
        SurfaceSuggestion parkingResult = checkParkingAreaForWay(way, surfaceCheck);
        if (parkingResult != null) {
            return parkingResult;
        }

        // Rule 2: lanes tag
        SurfaceSuggestion lanesResult = checkLanesForWay(way, surfaceCheck);
        if (lanesResult != null) {
            return lanesResult;
        }

        return null;
    }

    /**
     * Rule 1 in per-way mode: check immediate neighbors with same name/highway.
     */
    private static SurfaceSuggestion checkImmediateNeighbors(Way way, SurfaceCheck surfaceCheck) {
        String name = way.get("name");
        String highway = way.get("highway");
        if (name == null || highway == null || isBridgeStructure(way)) {
            return null;
        }

        String foundSurface = null;
        boolean hasConflict = false;

        for (Node node : way.getNodes()) {
            for (OsmPrimitive referrer : node.getReferrers()) {
                if (!(referrer instanceof Way neighbor) || neighbor == way
                        || !neighbor.isUsable() || isBridgeStructure(neighbor)) {
                    continue;
                }
                if (!name.equals(neighbor.get("name"))
                        || !highway.equals(neighbor.get("highway"))) {
                    continue;
                }
                String neighborSurface = neighbor.get("surface");
                if (neighborSurface == null || neighborSurface.isEmpty()) {
                    continue;
                }
                if (foundSurface == null) {
                    foundSurface = neighborSurface;
                } else if (!foundSurface.equals(neighborSurface)) {
                    // Check if one is a compatible upgrade of the other
                    if (isCompatibleUpgrade(foundSurface, neighborSurface)) {
                        foundSurface = moreSpecific(foundSurface, neighborSurface);
                    } else if (!isCompatibleUpgrade(neighborSurface, foundSurface)) {
                        hasConflict = true;
                    }
                }
            }
        }

        if (hasConflict) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CONFLICT,
                    tr("Connected same-name roads have conflicting surfaces"),
                    tr("Conflicting surfaces (review needed)"),
                    null);
        }

        if (foundSurface != null && SurfaceCheck.checkTagCompatibility(way, foundSurface)) {
            SurfaceResult result = SurfaceCheck.reconcileWithExisting(way, foundSurface);
            return toConnectedRoadSuggestion(way, result);
        }

        return null;
    }

    /**
     * Rule 2 in per-way mode.
     */
    private static SurfaceSuggestion checkLanesForWay(Way way, SurfaceCheck surfaceCheck) {
        SurfaceResult result = surfaceCheck.checkLanesTag(way);
        return toLanesSuggestion(way, result);
    }

    /**
     * Rule 3 in per-way mode: find containing parking areas.
     */
    private static SurfaceSuggestion checkParkingAreaForWay(Way way, SurfaceCheck surfaceCheck) {
        if (!"service".equals(way.get("highway"))) {
            return null;
        }

        // Find parking areas by checking referrers of the way's nodes
        for (Node node : way.getNodes()) {
            for (OsmPrimitive referrer : node.getReferrers()) {
                if (referrer instanceof Way area
                        && area != way
                        && area.isClosed()
                        && "parking".equals(area.get("amenity"))
                        && area.get("surface") != null) {
                    if (SurfaceCheck.isWayInsideArea(way, area)) {
                        SurfaceResult result = surfaceCheck.checkParkingArea(way, area);
                        return toParkingAreaSuggestion(way, result);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Result of a timed surface analysis run.
     */
    public static class SurfaceAnalysisResult {
        private final List<SurfaceSuggestion> results;
        private final AnalysisTimer timer;

        SurfaceAnalysisResult(List<SurfaceSuggestion> results, AnalysisTimer timer) {
            this.results = results;
            this.timer = timer;
        }

        public List<SurfaceSuggestion> getResults() {
            return results;
        }

        public AnalysisTimer getTimer() {
            return timer;
        }
    }

    /**
     * Analyze all eligible ways in a DataSet for surface suggestions.
     *
     * @param dataSet the dataset to analyze
     * @return list of surface suggestions
     */
    public static List<SurfaceSuggestion> analyzeAll(DataSet dataSet) {
        return analyzeAllTimed(dataSet).getResults();
    }

    /**
     * Analyze all eligible ways with timing instrumentation.
     * Uses full graph propagation for Rule 1.
     *
     * @param dataSet the dataset to analyze
     * @return surface analysis results with timing breakdown
     */
    public static SurfaceAnalysisResult analyzeAllTimed(DataSet dataSet) {
        AnalysisTimer timer = new AnalysisTimer();
        SurfaceCheck surfaceCheck = new SurfaceCheck();
        Map<Way, SurfaceSuggestion> results = new HashMap<>();

        // === Rule 1: Connected same-name/highway propagation ===
        timer.start("connectedRoads");
        analyzeConnectedRoads(dataSet, surfaceCheck, results);

        // === Rule 4: Crossing way surface inference ===
        timer.start("crossings");
        analyzeCrossings(dataSet, results);

        // === Rule 3: Parking area containment ===
        timer.start("parkingAreas");
        analyzeParkingAreas(dataSet, surfaceCheck, results);

        // === Rule 2: Lanes tag ===
        timer.start("lanes");
        analyzeLanes(dataSet, surfaceCheck, results);

        timer.stop();
        return new SurfaceAnalysisResult(new ArrayList<>(results.values()), timer);
    }

    /**
     * Rule 1: Build connected components of same-name/highway roads and propagate surfaces.
     */
    private static void analyzeConnectedRoads(DataSet dataSet, SurfaceCheck surfaceCheck,
                                               Map<Way, SurfaceSuggestion> results) {
        // Collect all eligible named highway ways (excluding bridge structures)
        Set<Way> allEligible = new HashSet<>();
        for (Way way : dataSet.getWays()) {
            if (isEligible(way) && !isBridgeStructure(way)
                    && way.get("name") != null && way.get("highway") != null) {
                allEligible.add(way);
            }
        }

        // BFS to find connected components of same-name/highway roads
        Set<Way> visited = new HashSet<>();
        for (Way seed : allEligible) {
            if (visited.contains(seed)) {
                continue;
            }

            String name = seed.get("name");
            String highway = seed.get("highway");

            // BFS: find all connected ways with same name+highway
            List<Way> component = new ArrayList<>();
            Deque<Way> queue = new ArrayDeque<>();
            queue.add(seed);
            visited.add(seed);

            while (!queue.isEmpty()) {
                Way current = queue.poll();
                component.add(current);

                for (Node node : current.getNodes()) {
                    for (OsmPrimitive referrer : node.getReferrers()) {
                        if (referrer instanceof Way neighbor
                                && neighbor != current
                                && neighbor.isUsable()
                                && allEligible.contains(neighbor)
                                && !visited.contains(neighbor)
                                && name.equals(neighbor.get("name"))
                                && highway.equals(neighbor.get("highway"))) {
                            visited.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
            }

            // Collect all surface values in this component
            String resolvedSurface = null;
            boolean conflict = false;

            for (Way w : component) {
                String surface = w.get("surface");
                if (surface == null || surface.isEmpty()) {
                    continue;
                }
                if (resolvedSurface == null) {
                    resolvedSurface = surface;
                } else if (!resolvedSurface.equals(surface)) {
                    if (isCompatibleUpgrade(resolvedSurface, surface)) {
                        resolvedSurface = moreSpecific(resolvedSurface, surface);
                    } else if (isCompatibleUpgrade(surface, resolvedSurface)) {
                        // resolvedSurface is already more specific, keep it
                    } else {
                        conflict = true;
                        break;
                    }
                }
            }

            // Apply to unsurfaced members of the component
            if (resolvedSurface == null && !conflict) {
                continue;  // No surface info in this component
            }

            for (Way w : component) {
                if (results.containsKey(w)) {
                    continue;  // Already has a result from an earlier component
                }

                if (conflict) {
                    // Flag all ways in a conflicting component
                    results.put(w, new SurfaceSuggestion(w, SurfaceTest.SURFACE_CONFLICT,
                            tr("Connected same-name roads have conflicting surfaces"),
                            tr("Conflicting surfaces (review needed)"),
                            null));
                    continue;
                }

                if (!needsSurface(w)) {
                    // Way already has a specific surface. Check if it conflicts with resolved.
                    String existing = w.get("surface");
                    if (existing != null && !existing.equals(resolvedSurface)
                            && !isCompatibleUpgrade(existing, resolvedSurface)
                            && !isCompatibleUpgrade(resolvedSurface, existing)) {
                        results.put(w, new SurfaceSuggestion(w, SurfaceTest.SURFACE_CONFLICT,
                                tr("Connected same-name roads have conflicting surfaces"),
                                tr("Conflicting surfaces (review needed)"),
                                null));
                    }
                    continue;
                }

                if (!SurfaceCheck.checkTagCompatibility(w, resolvedSurface)) {
                    continue;  // Tag veto
                }

                SurfaceResult result = SurfaceCheck.reconcileWithExisting(w, resolvedSurface);
                SurfaceSuggestion suggestion = toConnectedRoadSuggestion(w, result);
                if (suggestion != null) {
                    results.put(w, suggestion);
                }
            }
        }
    }

    /**
     * Rule 4: Infer road surface from connected crossing ways.
     * A crossing way (highway=footway + footway=crossing, or cycleway equivalent)
     * with a surface tag provides evidence of the road's surface.
     */
    private static void analyzeCrossings(DataSet dataSet,
                                          Map<Way, SurfaceSuggestion> results) {
        for (Way way : dataSet.getWays()) {
            if (results.containsKey(way)) {
                continue;  // Already has a result from Rule 1
            }
            if (!isEligible(way)) {
                continue;
            }

            SurfaceSuggestion suggestion = checkCrossingsForWay(way);
            if (suggestion != null) {
                results.put(way, suggestion);
            }
        }
    }

    /**
     * Rule 4 per-way: check crossing ways connected to this road.
     */
    private static SurfaceSuggestion checkCrossingsForWay(Way way) {
        String foundSurface = null;
        boolean hasConflict = false;

        for (Node node : way.getNodes()) {
            for (OsmPrimitive referrer : node.getReferrers()) {
                if (!(referrer instanceof Way crossingWay) || crossingWay == way
                        || !crossingWay.isUsable()) {
                    continue;
                }
                if (!isCrossingWay(crossingWay)) {
                    continue;
                }
                String crossingSurface = crossingWay.get("surface");
                if (crossingSurface == null || crossingSurface.isEmpty()) {
                    continue;
                }
                if (isCrossingExcluded(crossingWay, node)) {
                    continue;
                }

                if (foundSurface == null) {
                    foundSurface = crossingSurface;
                } else if (!foundSurface.equals(crossingSurface)) {
                    if (isCompatibleUpgrade(foundSurface, crossingSurface)) {
                        foundSurface = moreSpecific(foundSurface, crossingSurface);
                    } else if (!isCompatibleUpgrade(crossingSurface, foundSurface)) {
                        hasConflict = true;
                    }
                }
            }
        }

        if (hasConflict) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CONFLICT,
                    tr("Connected crossing ways have conflicting surfaces"),
                    tr("Conflicting surfaces (review needed)"),
                    null);
        }

        if (foundSurface != null && SurfaceCheck.checkTagCompatibility(way, foundSurface)) {
            SurfaceResult result = SurfaceCheck.reconcileWithExisting(way, foundSurface);
            return toCrossingSuggestion(way, result);
        }

        return null;
    }

    /**
     * Check if a way is a crossing way (footway or cycleway crossing).
     */
    private static boolean isCrossingWay(Way way) {
        String highway = way.get("highway");
        if ("footway".equals(highway) && "crossing".equals(way.get("footway"))) {
            return true;
        }
        if ("cycleway".equals(highway) && "crossing".equals(way.get("cycleway"))) {
            return true;
        }
        return false;
    }

    /**
     * Check if a crossing should be excluded from surface inference.
     * Raised crossings and surface-marked crossings commonly have a different
     * surface material than the road (e.g., brick pavers on a raised table).
     *
     * @param crossingWay the crossing way
     * @param sharedNode  the node shared between the crossing and the road
     * @return true if the crossing should be skipped
     */
    private static boolean isCrossingExcluded(Way crossingWay, Node sharedNode) {
        // Check crossing way tags
        if ("surface".equals(crossingWay.get("crossing:markings"))) {
            return true;
        }
        String wayTrafficCalming = crossingWay.get("traffic_calming");
        if ("table".equals(wayTrafficCalming) || "raised_crossing".equals(wayTrafficCalming)) {
            return true;
        }

        // Check shared node tags
        if ("surface".equals(sharedNode.get("crossing:markings"))) {
            return true;
        }
        String nodeTrafficCalming = sharedNode.get("traffic_calming");
        if ("table".equals(nodeTrafficCalming) || "raised_crossing".equals(nodeTrafficCalming)) {
            return true;
        }
        if ("yes".equals(sharedNode.get("crossing:raised"))) {
            return true;
        }

        return false;
    }

    /**
     * Rule 3: Find parking areas and check service roads inside them.
     */
    private static void analyzeParkingAreas(DataSet dataSet, SurfaceCheck surfaceCheck,
                                             Map<Way, SurfaceSuggestion> results) {
        // Find all closed parking area ways with a surface tag
        List<Way> parkingAreas = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (way.isUsable() && way.isClosed()
                    && "parking".equals(way.get("amenity"))
                    && way.get("surface") != null) {
                parkingAreas.add(way);
            }
        }

        if (parkingAreas.isEmpty()) {
            return;
        }

        // Find service roads that might be inside parking areas
        for (Way way : dataSet.getWays()) {
            if (results.containsKey(way)) {
                continue;  // Already has a result from Rule 1
            }
            if (!isEligible(way) || !"service".equals(way.get("highway"))) {
                continue;
            }

            for (Way parkingArea : parkingAreas) {
                if (SurfaceCheck.isWayInsideArea(way, parkingArea)) {
                    SurfaceResult result = surfaceCheck.checkParkingArea(way, parkingArea);
                    SurfaceSuggestion suggestion = toParkingAreaSuggestion(way, result);
                    if (suggestion != null) {
                        results.put(way, suggestion);
                        break;  // Found a containing parking area
                    }
                }
            }
        }
    }

    /**
     * Rule 2: Check for lanes tag implying paved surface.
     */
    private static void analyzeLanes(DataSet dataSet, SurfaceCheck surfaceCheck,
                                      Map<Way, SurfaceSuggestion> results) {
        for (Way way : dataSet.getWays()) {
            if (results.containsKey(way)) {
                continue;  // Already has a result from Rule 1 or 3
            }
            if (!isEligible(way)) {
                continue;
            }

            SurfaceResult result = surfaceCheck.checkLanesTag(way);
            SurfaceSuggestion suggestion = toLanesSuggestion(way, result);
            if (suggestion != null) {
                results.put(way, suggestion);
            }
        }
    }

    // --- Result conversion helpers ---

    private static SurfaceSuggestion toConnectedRoadSuggestion(Way way, SurfaceResult result) {
        if (result == null) {
            return null;
        }
        if (result.isConflicting()) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CONFLICT,
                    tr("Connected same-name roads have conflicting surfaces"),
                    tr("Conflicting surfaces (review needed)"),
                    null);
        }
        if (!result.hasSuggestion()) {
            return null;
        }
        if (result.isUpgrade()) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CONNECTED_ROAD_UPGRADE,
                    tr("Upgrade: {0} \u2192 {1} (connected road)",
                            way.get("surface"), result.getSuggestedSurface()),
                    tr("Upgrade generic surface (connected road)"),
                    result.getSuggestedSurface());
        }
        return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CONNECTED_ROAD,
                tr("Suggest surface={0} (connected road)", result.getSuggestedSurface()),
                tr("Connected same-name road"),
                result.getSuggestedSurface());
    }

    private static SurfaceSuggestion toLanesSuggestion(Way way, SurfaceResult result) {
        if (result == null) {
            return null;
        }
        if (result.isConflicting()) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_LANES_CONFLICT,
                    tr("Has lanes tag but surface={0} (review needed)", way.get("surface")),
                    tr("Lanes tag conflicts with surface (review needed)"),
                    null);
        }
        if (!result.hasSuggestion()) {
            return null;
        }
        return new SurfaceSuggestion(way, SurfaceTest.SURFACE_LANES_PAVED,
                tr("Suggest surface=paved (has lanes tag)"),
                tr("Has lanes tag"),
                result.getSuggestedSurface());
    }

    private static SurfaceSuggestion toCrossingSuggestion(Way way, SurfaceResult result) {
        if (result == null) {
            return null;
        }
        if (result.isConflicting()) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CONFLICT,
                    tr("Connected crossing way has incompatible surface"),
                    tr("Conflicting surfaces (review needed)"),
                    null);
        }
        if (!result.hasSuggestion()) {
            return null;
        }
        if (result.isUpgrade()) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CROSSING_UPGRADE,
                    tr("Upgrade: {0} \u2192 {1} (crossing way)",
                            way.get("surface"), result.getSuggestedSurface()),
                    tr("Upgrade generic surface (crossing way)"),
                    result.getSuggestedSurface());
        }
        return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CROSSING,
                tr("Suggest surface={0} (crossing way)", result.getSuggestedSurface()),
                tr("Crossing way surface"),
                result.getSuggestedSurface());
    }

    private static SurfaceSuggestion toParkingAreaSuggestion(Way way, SurfaceResult result) {
        if (result == null) {
            return null;
        }
        if (result.isConflicting()) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CONFLICT,
                    tr("Inside parking area but existing surface conflicts"),
                    tr("Conflicting surfaces (review needed)"),
                    null);
        }
        if (!result.hasSuggestion()) {
            return null;
        }
        if (result.isUpgrade()) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_PARKING_AREA_UPGRADE,
                    tr("Upgrade: {0} \u2192 {1} (parking area)",
                            way.get("surface"), result.getSuggestedSurface()),
                    tr("Upgrade generic surface (parking area)"),
                    result.getSuggestedSurface());
        }
        return new SurfaceSuggestion(way, SurfaceTest.SURFACE_PARKING_AREA,
                tr("Suggest surface={0} (inside parking area)", result.getSuggestedSurface()),
                tr("Inside parking area"),
                result.getSuggestedSurface());
    }

    // --- Surface compatibility helpers ---

    /**
     * Check if {@code existing} is a generic surface and {@code candidate} is
     * a compatible more-specific value (e.g., paved → asphalt).
     */
    private static boolean isCompatibleUpgrade(String existing, String candidate) {
        return SurfaceCheck.isSameCategory(existing, candidate);
    }

    /**
     * Given two surfaces where one may be a generic upgrade of the other,
     * return the more specific value.
     */
    private static String moreSpecific(String a, String b) {
        if ("paved".equals(a) || "unpaved".equals(a)) {
            return b;
        }
        return a;
    }
}
