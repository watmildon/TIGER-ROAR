// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;

/**
 * Checks if a road's name is corroborated by nearby address data.
 *
 * Uses a grid-based spatial index to efficiently find addresses within
 * a configurable distance of road segments.
 */
public class AddressCheck {

    private static final String ADDR_STREET = "addr:street";

    /** Grid cell size in meters (approximately) */
    private static final double GRID_CELL_SIZE = 10.0;

    private final double maxDistanceMeters;
    private final Map<GridCell, List<AddressData>> addressGrid;
    private boolean indexBuilt;

    /**
     * Create a new AddressCheck.
     *
     * @param maxDistanceMeters Maximum distance in meters to search for matching addresses
     */
    public AddressCheck(double maxDistanceMeters) {
        this.maxDistanceMeters = maxDistanceMeters;
        this.addressGrid = new HashMap<>();
        this.indexBuilt = false;
    }

    /**
     * Build the spatial index for all addresses in the dataset.
     *
     * @param dataSet The dataset to index, or null to defer until first check
     */
    public void buildIndex(DataSet dataSet) {
        if (dataSet == null) {
            return;
        }

        addressGrid.clear();

        for (OsmPrimitive primitive : dataSet.allPrimitives()) {
            if (!primitive.isUsable()) {
                continue;
            }

            String addrStreet = primitive.get(ADDR_STREET);
            if (addrStreet == null || addrStreet.isEmpty()) {
                continue;
            }

            EastNorth en = getCenter(primitive);
            if (en == null) {
                continue;
            }

            GridCell cell = new GridCell(en, GRID_CELL_SIZE);
            addressGrid.computeIfAbsent(cell, k -> new ArrayList<>())
                    .add(new AddressData(addrStreet, en));
        }

        indexBuilt = true;
    }

    /**
     * Check if the way's name is corroborated by a nearby address.
     *
     * @param way  The way to check
     * @param name The name to look for in nearby addr:street tags
     * @return true if a nearby address has a matching addr:street
     */
    public boolean isNameCorroborated(Way way, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        // Build index on first use if not already built
        if (!indexBuilt && way.getDataSet() != null) {
            buildIndex(way.getDataSet());
        }

        if (addressGrid.isEmpty()) {
            return false;
        }

        // Check each segment of the way
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

            // Check addresses near this segment
            if (hasMatchingAddressNearSegment(en1, en2, name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if there's a matching address near a line segment.
     */
    private boolean hasMatchingAddressNearSegment(EastNorth en1, EastNorth en2, String name) {
        // Calculate bounding box around segment with buffer
        double minX = Math.min(en1.getX(), en2.getX()) - maxDistanceMeters;
        double maxX = Math.max(en1.getX(), en2.getX()) + maxDistanceMeters;
        double minY = Math.min(en1.getY(), en2.getY()) - maxDistanceMeters;
        double maxY = Math.max(en1.getY(), en2.getY()) + maxDistanceMeters;

        // Calculate grid cell range to check
        int minCellX = (int) Math.floor(minX / GRID_CELL_SIZE);
        int maxCellX = (int) Math.floor(maxX / GRID_CELL_SIZE);
        int minCellY = (int) Math.floor(minY / GRID_CELL_SIZE);
        int maxCellY = (int) Math.floor(maxY / GRID_CELL_SIZE);

        // Check all cells in range
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                GridCell cell = new GridCell(cx, cy);
                List<AddressData> addresses = addressGrid.get(cell);

                if (addresses == null) {
                    continue;
                }

                for (AddressData addr : addresses) {
                    if (name.equals(addr.streetName)) {
                        // Check actual distance to segment
                        double dist = distanceToSegment(addr.location, en1, en2);
                        if (dist <= maxDistanceMeters) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Calculate the distance from a point to a line segment.
     */
    private double distanceToSegment(EastNorth point, EastNorth segStart, EastNorth segEnd) {
        double dx = segEnd.getX() - segStart.getX();
        double dy = segEnd.getY() - segStart.getY();

        if (dx == 0 && dy == 0) {
            // Segment is a point
            return point.distance(segStart);
        }

        // Calculate projection parameter
        double t = ((point.getX() - segStart.getX()) * dx + (point.getY() - segStart.getY()) * dy)
                / (dx * dx + dy * dy);

        if (t < 0) {
            // Closest to start
            return point.distance(segStart);
        } else if (t > 1) {
            // Closest to end
            return point.distance(segEnd);
        } else {
            // Closest to interior point
            EastNorth projection = new EastNorth(
                    segStart.getX() + t * dx,
                    segStart.getY() + t * dy);
            return point.distance(projection);
        }
    }

    /**
     * Get the center point of a primitive in EastNorth coordinates.
     */
    private EastNorth getCenter(OsmPrimitive primitive) {
        if (primitive instanceof Node node) {
            return node.isLatLonKnown() ? node.getEastNorth() : null;
        } else if (primitive instanceof Way way) {
            if (way.getNodesCount() == 0) {
                return null;
            }
            // Use center of bounding box
            return way.getBBox().getCenter().getEastNorth(ProjectionRegistry.getProjection());
        } else {
            // Relations - use bounding box center
            return primitive.getBBox().getCenter().getEastNorth(ProjectionRegistry.getProjection());
        }
    }

    /**
     * Grid cell for spatial indexing.
     */
    private record GridCell(int x, int y) {
        GridCell(EastNorth en, double cellSize) {
            this((int) Math.floor(en.getX() / cellSize),
                 (int) Math.floor(en.getY() / cellSize));
        }
    }

    /**
     * Address data stored in the grid.
     */
    private record AddressData(String streetName, EastNorth location) {
    }
}
