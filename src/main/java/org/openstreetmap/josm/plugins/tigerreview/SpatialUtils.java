// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Shared spatial utilities for grid-based spatial indexing and geometry calculations.
 *
 * Used by AddressCheck, NadDataCache, and MapillaryDataCache to avoid duplicating
 * the same grid cell, distance-to-segment, and cell range logic.
 */
public final class SpatialUtils {

    /** Standard grid cell size in meters (approximately) used by all spatial indexes. */
    public static final double GRID_CELL_SIZE = 10.0;

    private SpatialUtils() {
        // utility class
    }

    /**
     * Grid cell for spatial indexing. Two points in the same cell are within
     * {@link #GRID_CELL_SIZE} meters of each other (approximately).
     */
    public record GridCell(int x, int y) {
        public GridCell(EastNorth en, double cellSize) {
            this((int) Math.floor(en.getX() / cellSize),
                 (int) Math.floor(en.getY() / cellSize));
        }

        public GridCell(EastNorth en) {
            this(en, GRID_CELL_SIZE);
        }
    }

    /**
     * Calculate the distance from a point to a line segment.
     *
     * @param point    the point
     * @param segStart start of the segment
     * @param segEnd   end of the segment
     * @return distance in EastNorth units (meters, approximately)
     */
    public static double distanceToSegment(EastNorth point, EastNorth segStart, EastNorth segEnd) {
        double dx = segEnd.getX() - segStart.getX();
        double dy = segEnd.getY() - segStart.getY();

        if (dx == 0 && dy == 0) {
            return point.distance(segStart);
        }

        double t = ((point.getX() - segStart.getX()) * dx + (point.getY() - segStart.getY()) * dy)
                / (dx * dx + dy * dy);

        if (t < 0) {
            return point.distance(segStart);
        } else if (t > 1) {
            return point.distance(segEnd);
        } else {
            EastNorth projection = new EastNorth(
                    segStart.getX() + t * dx,
                    segStart.getY() + t * dy);
            return point.distance(projection);
        }
    }

    /**
     * The bounding cell range for a segment buffered by maxDistance.
     * Use with {@link #forEachCellInRange} to iterate grid cells near a segment.
     */
    public record CellRange(int minCellX, int maxCellX, int minCellY, int maxCellY) {
        /**
         * Compute the grid cell range that covers a segment plus a buffer distance.
         */
        public static CellRange of(EastNorth en1, EastNorth en2, double maxDistance) {
            double minX = Math.min(en1.getX(), en2.getX()) - maxDistance;
            double maxX = Math.max(en1.getX(), en2.getX()) + maxDistance;
            double minY = Math.min(en1.getY(), en2.getY()) - maxDistance;
            double maxY = Math.max(en1.getY(), en2.getY()) + maxDistance;

            return new CellRange(
                    (int) Math.floor(minX / GRID_CELL_SIZE),
                    (int) Math.floor(maxX / GRID_CELL_SIZE),
                    (int) Math.floor(minY / GRID_CELL_SIZE),
                    (int) Math.floor(maxY / GRID_CELL_SIZE));
        }
    }

    /**
     * Look up all items in grid cells overlapping the given range, returning a flat view.
     * Callers iterate the returned list and apply their own distance/filter checks.
     *
     * @param grid     the spatial grid
     * @param range    the cell range to scan
     * @param <T>      item type
     * @return items from all matching cells (may contain duplicates across segments)
     */
    public static <T> List<T> collectFromGrid(Map<GridCell, List<T>> grid, CellRange range) {
        List<T> result = new java.util.ArrayList<>();
        for (int cx = range.minCellX(); cx <= range.maxCellX(); cx++) {
            for (int cy = range.minCellY(); cy <= range.maxCellY(); cy++) {
                List<T> items = grid.get(new GridCell(cx, cy));
                if (items != null) {
                    result.addAll(items);
                }
            }
        }
        return result;
    }
}
