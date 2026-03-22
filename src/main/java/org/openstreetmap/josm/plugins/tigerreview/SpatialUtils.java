// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

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

    /**
     * An entry in the road segment grid, linking a grid cell back to the road
     * that has a segment passing through (or near) that cell.
     *
     * <p>Multiple entries may reference the same Way (one per segment per cell),
     * so callers should deduplicate by Way identity when iterating results.</p>
     */
    public record RoadSegmentEntry(Way way, EastNorth segStart, EastNorth segEnd) {
    }

    /**
     * Build a spatial grid index of road segments. Each segment of each way is
     * rasterized into the grid cells it passes through, so that nearby-road queries
     * can be answered in O(1) per cell lookup instead of scanning all roads.
     *
     * @param ways the roads to index
     * @return a grid mapping cells to the road segment entries that pass through them
     */
    public static Map<GridCell, List<RoadSegmentEntry>> buildRoadSegmentGrid(Collection<Way> ways) {
        Map<GridCell, List<RoadSegmentEntry>> grid = new HashMap<>();

        for (Way way : ways) {
            List<Node> nodes = way.getNodes();
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

                RoadSegmentEntry entry = new RoadSegmentEntry(way, en1, en2);
                rasterizeSegmentIntoCells(en1, en2, entry, grid);
            }
        }

        return grid;
    }

    /**
     * Rasterize a line segment into grid cells using a simple walk along the
     * segment, stepping by half a cell size to ensure no cells are missed.
     * Also includes both endpoint cells.
     */
    private static void rasterizeSegmentIntoCells(EastNorth en1, EastNorth en2,
            RoadSegmentEntry entry, Map<GridCell, List<RoadSegmentEntry>> grid) {

        double cs = GRID_CELL_SIZE;
        GridCell cell1 = new GridCell(en1, cs);
        GridCell cell2 = new GridCell(en2, cs);

        // Always add both endpoint cells
        grid.computeIfAbsent(cell1, k -> new ArrayList<>()).add(entry);
        if (!cell1.equals(cell2)) {
            grid.computeIfAbsent(cell2, k -> new ArrayList<>()).add(entry);
        }

        // Walk intermediate cells for segments spanning more than 1 cell
        double dx = en2.getX() - en1.getX();
        double dy = en2.getY() - en1.getY();
        double segLen = Math.sqrt(dx * dx + dy * dy);

        if (segLen <= cs) {
            return; // short segment — endpoints cover it
        }

        // Step along the segment at half-cell intervals to catch all traversed cells
        double stepSize = cs * 0.5;
        int steps = (int) Math.ceil(segLen / stepSize);
        for (int s = 1; s < steps; s++) {
            double t = (double) s / steps;
            double x = en1.getX() + t * dx;
            double y = en1.getY() + t * dy;
            GridCell cell = new GridCell((int) Math.floor(x / cs),
                                        (int) Math.floor(y / cs));
            if (!cell.equals(cell1) && !cell.equals(cell2)) {
                grid.computeIfAbsent(cell, k -> new ArrayList<>()).add(entry);
            }
        }
    }

    /**
     * Find candidate roads near a point by looking up the road segment grid.
     * Returns a deduplicated set of Ways that have at least one segment in a
     * cell within {@code maxDistance} of the point.
     *
     * @param point       the query point
     * @param roadGrid    the road segment grid
     * @param maxDistance  the search radius (in EastNorth units / meters approx)
     * @return set of candidate Ways (deduplicated)
     */
    public static Set<Way> findNearbyRoads(EastNorth point,
            Map<GridCell, List<RoadSegmentEntry>> roadGrid, double maxDistance) {
        // Use CellRange to compute the search area, matching how address grids work.
        // This correctly handles any projection (degrees or meters).
        CellRange range = CellRange.of(point, point, maxDistance);
        Set<Way> candidates = new HashSet<>();

        for (int cx = range.minCellX(); cx <= range.maxCellX(); cx++) {
            for (int cy = range.minCellY(); cy <= range.maxCellY(); cy++) {
                List<RoadSegmentEntry> entries = roadGrid.get(new GridCell(cx, cy));
                if (entries != null) {
                    for (RoadSegmentEntry e : entries) {
                        candidates.add(e.way());
                    }
                }
            }
        }

        return candidates;
    }
}
