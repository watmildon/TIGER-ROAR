// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.tigerreview.HighwayConstants;

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
     * Addresses that have been assigned to a matching road.
     * Uses identity-based set since AddressData is a record (value equality).
     * These addresses are excluded from {@link #findSuggestedName} to avoid
     * false suggestions when an address legitimately belongs to a nearby road.
     */
    private final Set<AddressData> assignedAddresses = Collections.newSetFromMap(new IdentityHashMap<>());

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
        assignedAddresses.clear();

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
     * Pre-assign address points to their matching roads. An address is "assigned"
     * if a nearby road has the same name as the address's addr:street value.
     * Assigned addresses are excluded from {@link #findSuggestedName} to prevent
     * false name suggestions (e.g., Bar Street addresses suggesting that nearby
     * Foo Street should be renamed).
     *
     * <p>Must be called after {@link #buildIndex} and before any calls to
     * {@link #findSuggestedName}.</p>
     *
     * @param ways The roads to consider for assignment
     */
    public void assignAddressesToRoads(Collection<Way> ways) {
        assignedAddresses.clear();

        if (addressGrid.isEmpty()) {
            return;
        }

        for (Way way : ways) {
            String name = way.get("name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            String highway = way.get("highway");
            if (highway == null || !HighwayConstants.TIGER_HIGHWAYS.contains(highway)) {
                continue;
            }

            double scaledMaxDistance = computeScaledDistance(way);
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

                markMatchingAddressesNearSegment(en1, en2, name, scaledMaxDistance);
            }
        }
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

        // Scale the distance threshold to account for Mercator projection distortion.
        // EastNorth coordinates (EPSG:3857) inflate distances by 1/cos(lat), so we
        // scale our threshold by the same factor to keep the ground-distance meaning.
        double scaledMaxDistance = computeScaledDistance(way);

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
            if (hasMatchingAddressNearSegment(en1, en2, name, scaledMaxDistance)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Find the most common addr:street name along a way that does NOT match the
     * given OSM name. Used to suggest that a road might have a different name
     * than what OSM currently records. Addresses that have been assigned to a
     * matching road via {@link #assignAddressesToRoads} are excluded.
     *
     * @param way  The way to check
     * @param name The current OSM name (matches are excluded)
     * @return The most common non-matching addr:street name, or null if none found
     */
    public String findSuggestedName(Way way, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        // Build index on first use if not already built
        if (!indexBuilt && way.getDataSet() != null) {
            buildIndex(way.getDataSet());
        }

        if (addressGrid.isEmpty()) {
            return null;
        }

        double scaledMaxDistance = computeScaledDistance(way);

        Map<String, Integer> nameCounts = new HashMap<>();
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

            collectNonMatchingNamesNearSegment(en1, en2, name, scaledMaxDistance, nameCounts);
        }

        if (nameCounts.isEmpty()) {
            return null;
        }

        return Collections.max(nameCounts.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    /**
     * Collect addr:street names near a segment that do NOT match the given name
     * and are NOT assigned to a matching road.
     */
    private void collectNonMatchingNamesNearSegment(EastNorth en1, EastNorth en2, String name,
            double scaledMaxDistance, Map<String, Integer> nameCounts) {
        double minX = Math.min(en1.getX(), en2.getX()) - scaledMaxDistance;
        double maxX = Math.max(en1.getX(), en2.getX()) + scaledMaxDistance;
        double minY = Math.min(en1.getY(), en2.getY()) - scaledMaxDistance;
        double maxY = Math.max(en1.getY(), en2.getY()) + scaledMaxDistance;

        int minCellX = (int) Math.floor(minX / GRID_CELL_SIZE);
        int maxCellX = (int) Math.floor(maxX / GRID_CELL_SIZE);
        int minCellY = (int) Math.floor(minY / GRID_CELL_SIZE);
        int maxCellY = (int) Math.floor(maxY / GRID_CELL_SIZE);

        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                GridCell cell = new GridCell(cx, cy);
                List<AddressData> addresses = addressGrid.get(cell);

                if (addresses == null) {
                    continue;
                }

                for (AddressData addr : addresses) {
                    if (name.equalsIgnoreCase(addr.streetName)) {
                        continue; // skip matches
                    }

                    if (assignedAddresses.contains(addr)) {
                        continue; // already assigned to a matching road
                    }

                    double dist = distanceToSegment(addr.location, en1, en2);
                    if (dist <= scaledMaxDistance) {
                        nameCounts.merge(addr.streetName, 1, Integer::sum);
                    }
                }
            }
        }
    }

    /**
     * Mark addresses near a segment whose addr:street matches the given name as assigned.
     */
    private void markMatchingAddressesNearSegment(EastNorth en1, EastNorth en2, String name,
            double scaledMaxDistance) {
        double minX = Math.min(en1.getX(), en2.getX()) - scaledMaxDistance;
        double maxX = Math.max(en1.getX(), en2.getX()) + scaledMaxDistance;
        double minY = Math.min(en1.getY(), en2.getY()) - scaledMaxDistance;
        double maxY = Math.max(en1.getY(), en2.getY()) + scaledMaxDistance;

        int minCellX = (int) Math.floor(minX / GRID_CELL_SIZE);
        int maxCellX = (int) Math.floor(maxX / GRID_CELL_SIZE);
        int minCellY = (int) Math.floor(minY / GRID_CELL_SIZE);
        int maxCellY = (int) Math.floor(maxY / GRID_CELL_SIZE);

        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                GridCell cell = new GridCell(cx, cy);
                List<AddressData> addresses = addressGrid.get(cell);

                if (addresses == null) {
                    continue;
                }

                for (AddressData addr : addresses) {
                    if (name.equalsIgnoreCase(addr.streetName)) {
                        double dist = distanceToSegment(addr.location, en1, en2);
                        if (dist <= scaledMaxDistance) {
                            assignedAddresses.add(addr);
                        }
                    }
                }
            }
        }
    }

    /**
     * Compute the max distance threshold scaled for Mercator distortion at the way's latitude.
     */
    private double computeScaledDistance(Way way) {
        double lat = way.getBBox().getCenter().lat();
        double scaleFactor = 1.0 / Math.cos(Math.toRadians(lat));
        return maxDistanceMeters * scaleFactor;
    }

    /**
     * Check if there's a matching address near a line segment.
     */
    private boolean hasMatchingAddressNearSegment(EastNorth en1, EastNorth en2, String name,
            double scaledMaxDistance) {
        // Calculate bounding box around segment with buffer (using scaled distance)
        double minX = Math.min(en1.getX(), en2.getX()) - scaledMaxDistance;
        double maxX = Math.max(en1.getX(), en2.getX()) + scaledMaxDistance;
        double minY = Math.min(en1.getY(), en2.getY()) - scaledMaxDistance;
        double maxY = Math.max(en1.getY(), en2.getY()) + scaledMaxDistance;

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
                    // Case-insensitive comparison to handle minor casing differences
                    if (name.equalsIgnoreCase(addr.streetName)) {
                        // Check actual distance to segment
                        double dist = distanceToSegment(addr.location, en1, en2);
                        if (dist <= scaledMaxDistance) {
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
