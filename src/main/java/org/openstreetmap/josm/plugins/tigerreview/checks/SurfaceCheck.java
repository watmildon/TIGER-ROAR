// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.HighwayConstants;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewPreferences;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.MarkingDetection;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryDataCache;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Checks if a road's surface can be inferred from connected roads.
 *
 * A surface is suggested if connected roads at both ends have the same surface tag.
 * Confidence is graded based on whether connections are at the connected road's
 * endpoint and share the same name + highway type.
 */
public class SurfaceCheck {

    private static final String SURFACE = "surface";

    /** Smoothness values that imply a well-maintained paved surface */
    private static final Set<String> SMOOTH_PAVED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("excellent", "good")));

    /** Smoothness values that imply an extremely rough surface, incompatible with smooth pavement */
    private static final Set<String> ROUGH_SMOOTHNESS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("horrible", "very_horrible", "impassable")));

    /** Smooth paved surface types (asphalt, concrete) that conflict with very rough smoothness */
    private static final Set<String> SMOOTH_PAVED_SURFACES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("asphalt", "chipseal", "concrete",
                    "concrete:lanes", "concrete:plates")));

    /** Soft/natural unpaved surfaces incompatible with good smoothness */
    private static final Set<String> SOFT_UNPAVED_SURFACES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("dirt", "earth", "grass", "mud", "sand",
                    "ground", "woodchips")));

    /**
     * Confidence tier for surface suggestions.
     */
    public enum ConfidenceTier {
        /** Both ends connect to same-name+highway roads at their endpoints */
        HIGH,
        /** Both ends have a surface but connections are mixed quality */
        MEDIUM,
        /** Only one end has a surface from connected roads */
        LOW,
        /** No suggestion available */
        NONE;

        /**
         * @return a user-facing label for this confidence tier
         */
        public String getLabel() {
            switch (this) {
            case HIGH:
                return tr("high confidence");
            case MEDIUM:
                return tr("medium confidence");
            default:
                return tr("low confidence");
            }
        }

        /**
         * @return the next lower confidence tier (HIGH→MEDIUM, MEDIUM→LOW, LOW→NONE)
         */
        public ConfidenceTier demote() {
            switch (this) {
            case HIGH:
                return MEDIUM;
            case MEDIUM:
                return LOW;
            case LOW:
                return NONE;
            default:
                return NONE;
            }
        }
    }

    /**
     * Result of surface check with suggested value.
     */
    public static class SurfaceResult {
        private final String suggestedSurface;
        private final ConfidenceTier confidence;
        private final boolean conflicting;
        private final boolean upgrade;
        private final boolean hasMarkingEvidence;

        public SurfaceResult(String suggestedSurface, ConfidenceTier confidence) {
            this(suggestedSurface, confidence, false, false, false);
        }

        private SurfaceResult(String suggestedSurface, ConfidenceTier confidence,
                              boolean conflicting, boolean upgrade, boolean hasMarkingEvidence) {
            this.suggestedSurface = suggestedSurface;
            this.confidence = confidence;
            this.conflicting = conflicting;
            this.upgrade = upgrade;
            this.hasMarkingEvidence = hasMarkingEvidence;
        }

        static SurfaceResult conflict() {
            return new SurfaceResult(null, ConfidenceTier.NONE, true, false, false);
        }

        static SurfaceResult upgrade(String surface, ConfidenceTier confidence) {
            return new SurfaceResult(surface, confidence, false, true, false);
        }

        static SurfaceResult upgrade(String surface, ConfidenceTier confidence, boolean hasMarkingEvidence) {
            return new SurfaceResult(surface, confidence, false, true, hasMarkingEvidence);
        }

        SurfaceResult withMarkingEvidence() {
            return new SurfaceResult(suggestedSurface, confidence, conflicting, upgrade, true);
        }

        SurfaceResult withConfidence(ConfidenceTier newConfidence) {
            return new SurfaceResult(suggestedSurface, newConfidence, conflicting, upgrade, hasMarkingEvidence);
        }

        /**
         * @return The suggested surface value, or null if no suggestion
         */
        public String getSuggestedSurface() {
            return suggestedSurface;
        }

        /**
         * @return the confidence tier for this suggestion
         */
        public ConfidenceTier getConfidence() {
            return confidence;
        }

        /**
         * @return true if the suggestion comes from roads at both ends
         */
        public boolean isBothEnds() {
            return confidence == ConfidenceTier.HIGH || confidence == ConfidenceTier.MEDIUM;
        }

        /**
         * @return true if connected roads have conflicting surfaces (no auto-fix)
         */
        public boolean isConflicting() {
            return conflicting;
        }

        /**
         * @return true if this is an upgrade from a generic surface (paved/unpaved)
         */
        public boolean isUpgrade() {
            return upgrade;
        }

        /**
         * @return true if a surface suggestion was found
         */
        public boolean hasSuggestion() {
            return suggestedSurface != null;
        }

        /**
         * @return true if Mapillary road marking detections corroborate this suggestion
         */
        public boolean hasMarkingEvidence() {
            return hasMarkingEvidence;
        }
    }

    /**
     * Surface info gathered at a single endpoint node.
     */
    private static class NodeSurfaceInfo {
        /** Consistent surface value, or null if none or conflicting */
        final String surface;
        /** True if connected roads at this node have conflicting surfaces */
        final boolean hasConflict;
        /** True if the surface came from a same-name+highway road connected at its endpoint */
        final boolean endpointOfSameRoad;

        NodeSurfaceInfo(String surface, boolean hasConflict, boolean endpointOfSameRoad) {
            this.surface = surface;
            this.hasConflict = hasConflict;
            this.endpointOfSameRoad = endpointOfSameRoad;
        }
    }

    /**
     * Check if a surface can be inferred from connected roads and/or Mapillary markings.
     *
     * @param way The way to check (should not already have a surface tag)
     * @return SurfaceResult with suggested surface if found
     */
    public SurfaceResult checkSurface(Way way) {
        String existingSurface = way.get(SURFACE);

        // Already has a specific surface — nothing to suggest
        if (existingSurface != null
                && !"paved".equals(existingSurface)
                && !"unpaved".equals(existingSurface)) {
            return new SurfaceResult(null, ConfidenceTier.NONE);
        }

        List<Node> nodes = way.getNodes();
        if (nodes.isEmpty()) {
            return new SurfaceResult(null, ConfidenceTier.NONE);
        }

        Node firstNode = nodes.get(0);
        Node lastNode = nodes.get(nodes.size() - 1);

        String name = way.get("name");
        String highway = way.get("highway");

        NodeSurfaceInfo infoAtFirst = getSurfaceFromConnections(firstNode, way, name, highway);
        NodeSurfaceInfo infoAtLast = getSurfaceFromConnections(lastNode, way, name, highway);

        SurfaceResult result = evaluateEndpoints(infoAtFirst, infoAtLast);

        // Check for Mapillary road marking evidence
        boolean hasMarkings = hasNearbyMarkings(way);

        // If connected-road analysis found nothing but markings exist, suggest "paved"
        if (!result.hasSuggestion() && !result.isConflicting() && hasMarkings) {
            result = new SurfaceResult("paved", ConfidenceTier.LOW);
            result = result.withMarkingEvidence();
        }

        // If connected-road analysis suggests paved and markings agree, boost confidence
        if (result.hasSuggestion() && hasMarkings && !result.hasMarkingEvidence()) {
            String suggested = result.getSuggestedSurface();
            if (HighwayConstants.PAVED_SURFACES.contains(suggested) || "paved".equals(suggested)) {
                ConfidenceTier boosted = result.getConfidence() == ConfidenceTier.LOW
                        ? ConfidenceTier.MEDIUM : result.getConfidence();
                result = result.withConfidence(boosted).withMarkingEvidence();
            }
        }

        // Check if the way's own tags contradict or weaken the suggestion
        if (result.hasSuggestion()) {
            ConfidenceTier adjusted = checkTagCompatibility(way, result.getSuggestedSurface(),
                    result.getConfidence());
            if (adjusted == ConfidenceTier.NONE) {
                return new SurfaceResult(null, ConfidenceTier.NONE);
            }
            result = result.withConfidence(adjusted);
        }

        // For generic surfaces, validate the suggestion is in the same category
        if (existingSurface != null && result.hasSuggestion()) {
            if (!isSameCategory(existingSurface, result.getSuggestedSurface())) {
                return new SurfaceResult(null, ConfidenceTier.NONE);
            }
            return SurfaceResult.upgrade(result.getSuggestedSurface(),
                    result.getConfidence(), result.hasMarkingEvidence());
        }

        return result;
    }

    /**
     * Check if there are Mapillary road marking detections near a way.
     * Returns false if the Mapillary check is disabled or no data is loaded.
     */
    private boolean hasNearbyMarkings(Way way) {
        if (!Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, false)
                || !Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_MARKING, true)) {
            return false;
        }

        MapillaryDataCache cache = MapillaryDataCache.getInstance();
        if (!cache.isReady()) {
            return false;
        }

        double maxDistance = Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_MAPILLARY_MAX_DISTANCE,
                TIGERReviewPreferences.DEFAULT_MAPILLARY_MAX_DISTANCE);

        List<MarkingDetection> nearby = cache.findNearbyMarkings(way, maxDistance);
        return !nearby.isEmpty();
    }

    /**
     * Evaluate endpoint surface info and produce a result.
     */
    private SurfaceResult evaluateEndpoints(NodeSurfaceInfo infoAtFirst, NodeSurfaceInfo infoAtLast) {
        // Best case: both ends have matching surfaces
        if (infoAtFirst.surface != null && infoAtFirst.surface.equals(infoAtLast.surface)) {
            ConfidenceTier tier = (infoAtFirst.endpointOfSameRoad && infoAtLast.endpointOfSameRoad)
                    ? ConfidenceTier.HIGH
                    : ConfidenceTier.MEDIUM;
            return new SurfaceResult(infoAtFirst.surface, tier);
        }

        // Conflict: both ends have a surface but they disagree
        if (infoAtFirst.surface != null && infoAtLast.surface != null) {
            return SurfaceResult.conflict();
        }

        // Conflict: one end has a surface, the other has a node-level conflict
        if (infoAtFirst.surface != null && infoAtLast.hasConflict) {
            return SurfaceResult.conflict();
        }
        if (infoAtLast.surface != null && infoAtFirst.hasConflict) {
            return SurfaceResult.conflict();
        }

        // One end has a surface, the other has no info — suggest with lower confidence
        if (infoAtFirst.surface != null) {
            return new SurfaceResult(infoAtFirst.surface, ConfidenceTier.LOW);
        }
        if (infoAtLast.surface != null) {
            return new SurfaceResult(infoAtLast.surface, ConfidenceTier.LOW);
        }

        // Both ends have no info
        return new SurfaceResult(null, ConfidenceTier.NONE);
    }

    /**
     * Check if the way's own tags are compatible with the suggested surface.
     * Returns the adjusted confidence tier, or NONE to block the suggestion entirely.
     *
     * <p>Hard rejects (returns NONE):
     * <ul>
     *   <li>tracktype grade2-5 + paved suggestion</li>
     *   <li>tracktype grade1 + soft unpaved suggestion (dirt/grass/mud/earth/sand/ground)</li>
     *   <li>4wd_only=yes + paved suggestion</li>
     *   <li>smoothness excellent/good + soft unpaved suggestion</li>
     *   <li>smoothness horrible/very_horrible/impassable + smooth paved suggestion (asphalt/concrete)</li>
     * </ul>
     *
     * <p>Soft demotions (reduces confidence one tier):
     * <ul>
     *   <li>highway=track (without tracktype) + paved suggestion</li>
     *   <li>smoothness bad/very_bad + paved suggestion</li>
     * </ul>
     */
    private static ConfidenceTier checkTagCompatibility(Way way, String suggestedSurface,
                                                         ConfidenceTier baseTier) {
        boolean isPavedSuggestion = HighwayConstants.PAVED_SURFACES.contains(suggestedSurface)
                || "paved".equals(suggestedSurface);
        boolean isSoftUnpaved = SOFT_UNPAVED_SURFACES.contains(suggestedSurface);
        boolean isSmoothPaved = SMOOTH_PAVED_SURFACES.contains(suggestedSurface);

        ConfidenceTier tier = baseTier;

        // --- tracktype checks ---
        String tracktype = way.get("tracktype");
        if (tracktype != null) {
            if ("grade1".equals(tracktype) && isSoftUnpaved) {
                // grade1 = solid surface, incompatible with dirt/grass/mud
                return ConfidenceTier.NONE;
            }
            if (!"grade1".equals(tracktype) && isPavedSuggestion) {
                // grade2-5 = increasingly soft/natural, incompatible with pavement
                return ConfidenceTier.NONE;
            }
        }

        // --- 4wd_only check ---
        if ("yes".equals(way.get("4wd_only")) && isPavedSuggestion) {
            return ConfidenceTier.NONE;
        }

        // --- smoothness checks ---
        String smoothness = way.get("smoothness");
        if (smoothness != null) {
            // excellent/good smoothness + soft unpaved = hard reject
            if (SMOOTH_PAVED.contains(smoothness) && isSoftUnpaved) {
                return ConfidenceTier.NONE;
            }
            // horrible+ smoothness + smooth pavement = hard reject
            if (ROUGH_SMOOTHNESS.contains(smoothness) && isSmoothPaved) {
                return ConfidenceTier.NONE;
            }
            // bad/very_bad + paved = soft demote
            if (("bad".equals(smoothness) || "very_bad".equals(smoothness)) && isPavedSuggestion) {
                tier = tier.demote();
            }
        }

        // --- highway=track without tracktype + paved = soft demote ---
        if ("track".equals(way.get("highway")) && tracktype == null && isPavedSuggestion) {
            tier = tier.demote();
        }

        return tier;
    }

    /**
     * Check if a suggested surface is a more specific value in the same category
     * as the existing generic surface.
     */
    private static boolean isSameCategory(String genericSurface, String specificSurface) {
        if ("paved".equals(genericSurface)) {
            return HighwayConstants.PAVED_SURFACES.contains(specificSurface);
        }
        if ("unpaved".equals(genericSurface)) {
            return HighwayConstants.UNPAVED_SURFACES.contains(specificSurface);
        }
        return false;
    }

    /**
     * Get the surface from connected roads at a node.
     * Prefers same-name+highway roads when available, falling back to all roads.
     * Returns a {@link NodeSurfaceInfo} distinguishing "no surface found" from
     * "conflicting surfaces found".
     */
    private NodeSurfaceInfo getSurfaceFromConnections(Node node, Way excludeWay,
                                                      String targetName, String targetHighway) {
        // First pass: only same-name + same-highway connections
        NodeSurfaceInfo sameRoad = collectSurfaces(node, excludeWay, targetName, targetHighway);
        if (sameRoad.surface != null || sameRoad.hasConflict) {
            return sameRoad;
        }

        // Fallback: all connected roads — endpointOfSameRoad is always false
        // because these are not same-name+highway connections
        NodeSurfaceInfo fallback = collectSurfaces(node, excludeWay, null, null);
        return new NodeSurfaceInfo(fallback.surface, fallback.hasConflict, false);
    }

    /**
     * Collect surface info from connected roads at a node.
     * If filterName and filterHighway are non-null, only considers roads matching both,
     * and tracks whether any matching road has the shared node at its endpoint.
     */
    private NodeSurfaceInfo collectSurfaces(Node node, Way excludeWay,
                                            String filterName, String filterHighway) {
        String foundSurface = null;
        boolean anyEndpointConnection = false;

        for (OsmPrimitive referrer : node.getReferrers()) {
            if (referrer instanceof Way connectedWay && connectedWay != excludeWay) {
                if (filterName != null
                        && (!filterName.equals(connectedWay.get("name"))
                            || !filterHighway.equals(connectedWay.get("highway")))) {
                    continue;
                }
                String surface = getValidSurface(connectedWay);
                if (surface != null) {
                    if (foundSurface == null) {
                        foundSurface = surface;
                        anyEndpointConnection = isEndpointNode(node, connectedWay);
                    } else if (!foundSurface.equals(surface)) {
                        return new NodeSurfaceInfo(null, true, false);
                    } else {
                        anyEndpointConnection = anyEndpointConnection
                                || isEndpointNode(node, connectedWay);
                    }
                }
            }
        }

        return new NodeSurfaceInfo(foundSurface, false, anyEndpointConnection);
    }

    /**
     * Check if a node is the first or last node of a way.
     */
    private static boolean isEndpointNode(Node node, Way way) {
        List<Node> nodes = way.getNodes();
        if (nodes.isEmpty()) {
            return false;
        }
        return node.equals(nodes.get(0)) || node.equals(nodes.get(nodes.size() - 1));
    }

    /**
     * Get the surface tag from a way if it's a valid, reviewed highway.
     */
    private String getValidSurface(Way way) {
        // Must be a classified highway
        String highway = way.get("highway");
        if (highway == null || !HighwayConstants.SURFACE_HIGHWAYS.contains(highway)) {
            return null;
        }

        // Prefer roads that are reviewed (not tiger:reviewed=no)
        // but accept any road with a surface tag since someone added it
        String surface = way.get(SURFACE);
        if (surface == null || surface.isEmpty()) {
            return null;
        }

        return surface;
    }
}
