// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.List;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewTest;

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

        public SurfaceResult(String suggestedSurface, boolean bothEnds) {
            this.suggestedSurface = suggestedSurface;
            this.bothEnds = bothEnds;
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
         * @return true if a surface suggestion was found
         */
        public boolean hasSuggestion() {
            return suggestedSurface != null;
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

        String surfaceAtFirst = getSurfaceFromConnections(firstNode, way);
        String surfaceAtLast = getSurfaceFromConnections(lastNode, way);

        // Best case: both ends have matching surfaces
        if (surfaceAtFirst != null && surfaceAtFirst.equals(surfaceAtLast)) {
            return new SurfaceResult(surfaceAtFirst, true);
        }

        // One end has a surface - still useful but less confident
        if (surfaceAtFirst != null) {
            return new SurfaceResult(surfaceAtFirst, false);
        }
        if (surfaceAtLast != null) {
            return new SurfaceResult(surfaceAtLast, false);
        }

        return new SurfaceResult(null, false);
    }

    /**
     * Get the surface from connected roads at a node.
     * Returns null if no surface found or if connected roads have conflicting surfaces.
     */
    private String getSurfaceFromConnections(Node node, Way excludeWay) {
        String foundSurface = null;

        for (OsmPrimitive referrer : node.getReferrers()) {
            if (referrer instanceof Way connectedWay && connectedWay != excludeWay) {
                String surface = getValidSurface(connectedWay);
                if (surface != null) {
                    if (foundSurface == null) {
                        foundSurface = surface;
                    } else if (!foundSurface.equals(surface)) {
                        // Conflicting surfaces at this node - can't suggest
                        return null;
                    }
                }
            }
        }

        return foundSurface;
    }

    /**
     * Get the surface tag from a way if it's a valid, reviewed highway.
     */
    private String getValidSurface(Way way) {
        // Must be a classified highway
        String highway = way.get("highway");
        if (highway == null || !TIGERReviewTest.CLASSIFIED_HIGHWAYS.contains(highway)) {
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
