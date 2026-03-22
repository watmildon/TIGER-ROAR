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
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils.CellRange;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils.GridCell;
import org.openstreetmap.josm.plugins.tigerreview.StreetNameUtils;

/**
 * Checks if a road's name is corroborated by nearby address data.
 *
 * Uses a grid-based spatial index to efficiently find addresses within
 * a configurable distance of road segments.
 */
public class AddressCheck {

    private static final String ADDR_STREET = "addr:street";

    private final double maxDistanceMeters;
    private final Map<GridCell, List<AddressData>> addressGrid;
    private boolean indexBuilt;

    /**
     * Addresses whose addr:street matches one of the 2 nearest roads.
     * These are excluded from name suggestions entirely.
     * Uses identity-based set since AddressData is a record (value equality).
     */
    private final Set<AddressData> assignedAddresses = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Maps each address to its nearest road (by identity).
     * An unassigned address can only suggest a name for its nearest road.
     */
    private final Map<AddressData, Way> nearestRoad = new IdentityHashMap<>();

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
        nearestRoad.clear();

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

            GridCell cell = new GridCell(en);
            addressGrid.computeIfAbsent(cell, k -> new ArrayList<>())
                    .add(new AddressData(addrStreet, en));
        }

        indexBuilt = true;
    }

    /**
     * Pre-assign address points to roads based on proximity. For each address
     * within range of any road, finds the 2 nearest roads. If the address's
     * addr:street matches either of those roads' names, it is "assigned" and
     * excluded from name suggestions entirely. Otherwise, it can only suggest
     * a name for its single nearest road.
     *
     * <p>This prevents false cross-suggestions between nearby parallel roads
     * (e.g., "Country Creek Court" addresses suggesting a rename for adjacent
     * "Country Creek Terrace"), and handles corner addresses where the address
     * may be near two roads but clearly belongs to one of them.</p>
     *
     * <p>Must be called after {@link #buildIndex} and before any calls to
     * {@link #findSuggestedName}.</p>
     *
     * @param ways The roads to consider for assignment
     */
    public void assignAddressesToRoads(Collection<Way> ways) {
        assignedAddresses.clear();
        nearestRoad.clear();

        if (addressGrid.isEmpty()) {
            return;
        }

        // Build a list of named highway ways with their precomputed segments
        List<RoadSegments> roads = new ArrayList<>();
        for (Way way : ways) {
            String name = way.get("name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            String highway = way.get("highway");
            if (highway == null || !HighwayConstants.TIGER_HIGHWAYS.contains(highway)) {
                continue;
            }
            roads.add(new RoadSegments(way, name, computeScaledDistance(way)));
        }

        if (roads.isEmpty()) {
            return;
        }

        // For each address, find the 2 nearest roads
        for (List<AddressData> cell : addressGrid.values()) {
            for (AddressData addr : cell) {
                Way nearest = null;
                Way secondNearest = null;
                double nearestDist = Double.MAX_VALUE;
                double secondNearestDist = Double.MAX_VALUE;

                for (RoadSegments road : roads) {
                    double dist = minDistanceToWay(addr.location, road.way, road.scaledMaxDistance);
                    if (dist > road.scaledMaxDistance) {
                        continue; // too far from this road
                    }

                    if (dist < nearestDist) {
                        secondNearest = nearest;
                        secondNearestDist = nearestDist;
                        nearest = road.way;
                        nearestDist = dist;
                    } else if (dist < secondNearestDist) {
                        secondNearest = road.way;
                        secondNearestDist = dist;
                    }
                }

                if (nearest == null) {
                    continue; // not near any road
                }

                nearestRoad.put(addr, nearest);

                // Check if addr:street matches either of the 2 nearest roads
                String nearestName = nearest.get("name");
                if (nearestName != null && StreetNameUtils.namesMatch(addr.streetName, nearestName)) {
                    assignedAddresses.add(addr);
                } else if (secondNearest != null) {
                    String secondName = secondNearest.get("name");
                    if (secondName != null && StreetNameUtils.namesMatch(addr.streetName, secondName)) {
                        assignedAddresses.add(addr);
                    }
                }
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
     * than what OSM currently records.
     *
     * <p>Only considers addresses that are:
     * <ul>
     *   <li>Not assigned (addr:street didn't match either of the 2 nearest roads)</li>
     *   <li>Nearest to this specific way (won't suggest for a farther road)</li>
     * </ul>
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

            collectNonMatchingNamesNearSegment(en1, en2, name, scaledMaxDistance, way, nameCounts);
        }

        if (nameCounts.isEmpty()) {
            return null;
        }

        return Collections.max(nameCounts.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    /**
     * Collect addr:street names near a segment that do NOT match the given name,
     * are NOT assigned to a matching road, and whose nearest road is the given way.
     */
    private void collectNonMatchingNamesNearSegment(EastNorth en1, EastNorth en2, String name,
            double scaledMaxDistance, Way way, Map<String, Integer> nameCounts) {
        CellRange range = CellRange.of(en1, en2, scaledMaxDistance);

        for (AddressData addr : SpatialUtils.collectFromGrid(addressGrid, range)) {
            if (StreetNameUtils.namesMatch(name, addr.streetName)) {
                continue; // skip matches (exact or abbreviation-expanded)
            }

            if (assignedAddresses.contains(addr)) {
                continue; // addr:street matched one of the 2 nearest roads
            }

            // Only suggest for the address's nearest road
            Way addrNearest = nearestRoad.get(addr);
            if (addrNearest != null && addrNearest != way) {
                continue;
            }

            double dist = SpatialUtils.distanceToSegment(addr.location, en1, en2);
            if (dist <= scaledMaxDistance) {
                nameCounts.merge(addr.streetName, 1, Integer::sum);
            }
        }
    }

    /**
     * Compute the minimum distance from a point to any segment of a way.
     *
     * @return The minimum distance, or Double.MAX_VALUE if no valid segments
     */
    private static double minDistanceToWay(EastNorth point, Way way, double maxDistance) {
        double minDist = Double.MAX_VALUE;
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

            double dist = SpatialUtils.distanceToSegment(point, en1, en2);
            if (dist < minDist) {
                minDist = dist;
                if (minDist == 0) {
                    return 0; // can't get closer
                }
            }
        }

        return minDist;
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
        CellRange range = CellRange.of(en1, en2, scaledMaxDistance);

        for (AddressData addr : SpatialUtils.collectFromGrid(addressGrid, range)) {
            if (StreetNameUtils.namesMatch(name, addr.streetName)) {
                double dist = SpatialUtils.distanceToSegment(addr.location, en1, en2);
                if (dist <= scaledMaxDistance) {
                    return true;
                }
            }
        }

        return false;
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
     * Address data stored in the grid.
     */
    private record AddressData(String streetName, EastNorth location) {
    }

    /**
     * Precomputed road data for assignment.
     */
    private record RoadSegments(Way way, String name, double scaledMaxDistance) {
    }
}
