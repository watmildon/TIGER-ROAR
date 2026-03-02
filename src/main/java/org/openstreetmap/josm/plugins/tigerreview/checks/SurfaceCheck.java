// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.List;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.HighwayConstants;

/**
 * Checks if a road's surface can be inferred from connected roads.
 *
 * A surface is suggested if connected roads at both ends have the same surface tag.
 * This provides strong evidence that the road likely has the same surface.
 */
public class SurfaceCheck {

    private static final String SURFACE = "surface";

    /**
     * Result of surface check with suggested value.
     */
    public static class SurfaceResult {
        private final String suggestedSurface;
        private final boolean bothEnds;
        private final boolean conflicting;

        public SurfaceResult(String suggestedSurface, boolean bothEnds) {
            this(suggestedSurface, bothEnds, false);
        }

        private SurfaceResult(String suggestedSurface, boolean bothEnds, boolean conflicting) {
            this.suggestedSurface = suggestedSurface;
            this.bothEnds = bothEnds;
            this.conflicting = conflicting;
        }

        static SurfaceResult conflict() {
            return new SurfaceResult(null, false, true);
        }

        /**
         * @return The suggested surface value, or null if no suggestion
         */
        public String getSuggestedSurface() {
            return suggestedSurface;
        }

        /**
         * @return true if the suggestion comes from roads at both ends
         */
        public boolean isBothEnds() {
            return bothEnds;
        }

        /**
         * @return true if connected roads have conflicting surfaces (no auto-fix)
         */
        public boolean isConflicting() {
            return conflicting;
        }

        /**
         * @return true if a surface suggestion was found
         */
        public boolean hasSuggestion() {
            return suggestedSurface != null;
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

        NodeSurfaceInfo(String surface, boolean hasConflict) {
            this.surface = surface;
            this.hasConflict = hasConflict;
        }
    }

    /**
     * Check if a surface can be inferred from connected roads.
     *
     * @param way The way to check (should not already have a surface tag)
     * @return SurfaceResult with suggested surface if found
     */
    public SurfaceResult checkSurface(Way way) {
        // Don't suggest if way already has a surface
        if (way.get(SURFACE) != null) {
            return new SurfaceResult(null, false);
        }

        List<Node> nodes = way.getNodes();
        if (nodes.isEmpty()) {
            return new SurfaceResult(null, false);
        }

        Node firstNode = nodes.get(0);
        Node lastNode = nodes.get(nodes.size() - 1);

        NodeSurfaceInfo infoAtFirst = getSurfaceFromConnections(firstNode, way);
        NodeSurfaceInfo infoAtLast = getSurfaceFromConnections(lastNode, way);

        // Best case: both ends have matching surfaces
        if (infoAtFirst.surface != null && infoAtFirst.surface.equals(infoAtLast.surface)) {
            return new SurfaceResult(infoAtFirst.surface, true);
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
            return new SurfaceResult(infoAtFirst.surface, false);
        }
        if (infoAtLast.surface != null) {
            return new SurfaceResult(infoAtLast.surface, false);
        }

        // Both ends have no info (conflicts at both ends with no clear surface, or just nothing)
        return new SurfaceResult(null, false);
    }

    /**
     * Get the surface from connected roads at a node.
     * Returns a {@link NodeSurfaceInfo} distinguishing "no surface found" from
     * "conflicting surfaces found".
     */
    private NodeSurfaceInfo getSurfaceFromConnections(Node node, Way excludeWay) {
        String foundSurface = null;

        for (OsmPrimitive referrer : node.getReferrers()) {
            if (referrer instanceof Way connectedWay && connectedWay != excludeWay) {
                String surface = getValidSurface(connectedWay);
                if (surface != null) {
                    if (foundSurface == null) {
                        foundSurface = surface;
                    } else if (!foundSurface.equals(surface)) {
                        // Conflicting surfaces at this node
                        return new NodeSurfaceInfo(null, true);
                    }
                }
            }
        }

        return new NodeSurfaceInfo(foundSurface, false);
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
