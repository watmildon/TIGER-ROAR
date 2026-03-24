// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.HighwayConstants;
import org.openstreetmap.josm.tools.Geometry;

/**
 * Utility methods for surface inference checks.
 *
 * Three rules:
 * <ol>
 *   <li>Connected same-name/highway propagation (graph-based, driven by SurfaceAnalyzer)</li>
 *   <li>{@code lanes} tag implies {@code surface=paved}</li>
 *   <li>Service roads inside parking areas inherit the area's surface</li>
 * </ol>
 *
 * Tag compatibility vetoes apply to all rules.
 */
public class SurfaceCheck {

    private static final String SURFACE = "surface";

    /** Smoothness values that imply a well-maintained paved surface */
    private static final Set<String> SMOOTH_PAVED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("excellent", "good")));

    /** Smoothness values that imply an extremely rough surface, incompatible with smooth pavement */
    private static final Set<String> ROUGH_SMOOTHNESS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("horrible", "very_horrible", "impassable")));

    /** Smooth paved surface types that conflict with very rough smoothness */
    private static final Set<String> SMOOTH_PAVED_SURFACES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("asphalt", "chipseal", "concrete",
                    "concrete:lanes", "concrete:plates")));

    /** Soft/natural unpaved surfaces incompatible with good smoothness */
    private static final Set<String> SOFT_UNPAVED_SURFACES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("dirt", "earth", "grass", "mud", "sand",
                    "ground", "woodchips")));

    /**
     * Result of a surface check.
     */
    public static class SurfaceResult {
        private final String suggestedSurface;
        private final boolean conflicting;
        private final boolean upgrade;

        public SurfaceResult(String suggestedSurface) {
            this(suggestedSurface, false, false);
        }

        private SurfaceResult(String suggestedSurface, boolean conflicting, boolean upgrade) {
            this.suggestedSurface = suggestedSurface;
            this.conflicting = conflicting;
            this.upgrade = upgrade;
        }

        public static SurfaceResult conflict() {
            return new SurfaceResult(null, true, false);
        }

        public static SurfaceResult upgrade(String surface) {
            return new SurfaceResult(surface, false, true);
        }

        public static SurfaceResult none() {
            return new SurfaceResult(null, false, false);
        }

        /**
         * @return The suggested surface value, or null if no suggestion
         */
        public String getSuggestedSurface() {
            return suggestedSurface;
        }

        /**
         * @return true if incompatible surfaces were found (no auto-fix)
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
    }

    /**
     * Check Rule 2: lanes tag implies surface=paved.
     *
     * @param way the way to check
     * @return a result suggesting paved, a conflict, or none
     */
    public SurfaceResult checkLanesTag(Way way) {
        String lanes = way.get("lanes");
        if (lanes == null) {
            return SurfaceResult.none();
        }

        String existingSurface = way.get(SURFACE);

        // No surface -> suggest paved
        if (existingSurface == null) {
            if (checkTagCompatibility(way, "paved")) {
                return new SurfaceResult("paved");
            }
            return SurfaceResult.none();
        }

        // Already paved or a specific paved surface -> no action needed
        if ("paved".equals(existingSurface)
                || HighwayConstants.PAVED_SURFACES.contains(existingSurface)) {
            return SurfaceResult.none();
        }

        // Has an unpaved surface -> conflict
        return SurfaceResult.conflict();
    }

    /**
     * Check Rule 3: service road inside a parking area inherits the area's surface.
     *
     * @param way         the service road to check
     * @param parkingArea the containing parking area (closed way with surface tag)
     * @return a result with the suggested surface, a conflict, upgrade, or none
     */
    public SurfaceResult checkParkingArea(Way way, Way parkingArea) {
        String areaSurface = parkingArea.get(SURFACE);
        if (areaSurface == null || areaSurface.isEmpty()) {
            return SurfaceResult.none();
        }

        if (!checkTagCompatibility(way, areaSurface)) {
            return SurfaceResult.none();
        }

        return reconcileWithExisting(way, areaSurface);
    }

    /**
     * Reconcile a suggested surface with an existing surface on a way.
     * Returns a suggestion, upgrade, conflict, or none as appropriate.
     *
     * @param way              the way
     * @param suggestedSurface the surface to suggest
     * @return the result
     */
    public static SurfaceResult reconcileWithExisting(Way way, String suggestedSurface) {
        String existingSurface = way.get("surface");

        // No existing surface -> new suggestion
        if (existingSurface == null) {
            return new SurfaceResult(suggestedSurface);
        }

        // Same value -> nothing to do
        if (existingSurface.equals(suggestedSurface)) {
            return SurfaceResult.none();
        }

        // Generic -> specific upgrade
        if (isSameCategory(existingSurface, suggestedSurface)) {
            return SurfaceResult.upgrade(suggestedSurface);
        }

        // Incompatible existing surface -> conflict
        return SurfaceResult.conflict();
    }

    /**
     * Check if a way is fully inside a closed parking area.
     * All nodes of the way must be inside the polygon formed by the parking area.
     *
     * @param way         the service road
     * @param parkingArea the closed parking area way
     * @return true if all nodes of the way are inside the parking area
     */
    public static boolean isWayInsideArea(Way way, Way parkingArea) {
        List<Node> polygonNodes = parkingArea.getNodes();
        for (Node node : way.getNodes()) {
            if (!Geometry.nodeInsidePolygon(node, polygonNodes)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if the way's own tags are compatible with the suggested surface.
     * Returns false to block the suggestion entirely.
     *
     * <p>Hard rejects:
     * <ul>
     *   <li>tracktype grade2-5 + paved suggestion</li>
     *   <li>tracktype grade1 + soft unpaved suggestion</li>
     *   <li>4wd_only=yes + paved suggestion</li>
     *   <li>smoothness excellent/good + soft unpaved suggestion</li>
     *   <li>smoothness horrible/very_horrible/impassable + smooth paved suggestion</li>
     * </ul>
     *
     * @param way              the way to check
     * @param suggestedSurface the proposed surface value
     * @return true if compatible, false if the suggestion should be blocked
     */
    public static boolean checkTagCompatibility(Way way, String suggestedSurface) {
        boolean isPavedSuggestion = HighwayConstants.PAVED_SURFACES.contains(suggestedSurface)
                || "paved".equals(suggestedSurface);
        boolean isSoftUnpaved = SOFT_UNPAVED_SURFACES.contains(suggestedSurface);
        boolean isSmoothPaved = SMOOTH_PAVED_SURFACES.contains(suggestedSurface);

        // --- tracktype checks ---
        String tracktype = way.get("tracktype");
        if (tracktype != null) {
            if ("grade1".equals(tracktype) && isSoftUnpaved) {
                return false;
            }
            if (!"grade1".equals(tracktype) && isPavedSuggestion) {
                return false;
            }
        }

        // --- 4wd_only check ---
        if ("yes".equals(way.get("4wd_only")) && isPavedSuggestion) {
            return false;
        }

        // --- smoothness checks ---
        String smoothness = way.get("smoothness");
        if (smoothness != null) {
            if (SMOOTH_PAVED.contains(smoothness) && isSoftUnpaved) {
                return false;
            }
            if (ROUGH_SMOOTHNESS.contains(smoothness) && isSmoothPaved) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if a suggested surface is a more specific value in the same category
     * as the existing generic surface.
     *
     * @param genericSurface  the existing generic surface (paved/unpaved)
     * @param specificSurface the suggested specific surface
     * @return true if the specific surface is in the same category
     */
    public static boolean isSameCategory(String genericSurface, String specificSurface) {
        if ("paved".equals(genericSurface)) {
            return HighwayConstants.PAVED_SURFACES.contains(specificSurface);
        }
        if ("unpaved".equals(genericSurface)) {
            return HighwayConstants.UNPAVED_SURFACES.contains(specificSurface);
        }
        return false;
    }
}
