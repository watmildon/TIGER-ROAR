// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.tigerreview.HighwayConstants;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils.CellRange;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils.GridCell;
import org.openstreetmap.josm.plugins.tigerreview.SpatialUtils.RoadSegmentEntry;
import org.openstreetmap.josm.plugins.tigerreview.StreetNameUtils;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadAddress;
import org.openstreetmap.josm.tools.Logging;

/**
 * Thread-safe cache and spatial index for NAD address data.
 *
 * Uses a grid-based spatial index for efficient nearby address lookups.
 */
public class NadDataCache {

    /** Singleton instance */
    private static NadDataCache instance;

    /** Lock for thread-safe access */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Grid-based spatial index */
    private final Map<GridCell, List<NadAddressData>> addressGrid = new HashMap<>();

    /** Bounds of the cached data */
    private Bounds cachedBounds;

    /** Whether the cache is ready for queries */
    private boolean ready;

    /** Error message if loading failed */
    private String errorMessage;

    /** Number of addresses in cache */
    private int addressCount;

    /**
     * Addresses whose street name matches one of the 2 nearest roads.
     * These are excluded from {@link #findMostCommonStreetName} entirely.
     * Uses identity-based set since NadAddressData is a record (value equality).
     */
    private final Set<NadAddressData> assignedAddresses = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Maps each address to its nearest road (by identity).
     * An unassigned address can only suggest a name for its nearest road.
     */
    private final Map<NadAddressData, Way> nearestRoad = new IdentityHashMap<>();

    /** Deferred assignment: stored candidate ways for lazy execution. */
    private Collection<Way> deferredWays;
    /** Deferred assignment: stored road grid for lazy execution. */
    private Map<GridCell, List<RoadSegmentEntry>> deferredRoadGrid;
    /** Deferred assignment: stored max distance for lazy execution. */
    private double deferredMaxDistance;
    /** Whether assignment has been performed (eagerly or lazily). */
    private boolean assignmentDone;

    private NadDataCache() {
        // Singleton
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized NadDataCache getInstance() {
        if (instance == null) {
            instance = new NadDataCache();
        }
        return instance;
    }

    /**
     * Load addresses into the cache.
     *
     * @param addresses List of addresses from NAD
     * @param bounds    The bounds these addresses cover
     */
    public void load(List<NadAddress> addresses, Bounds bounds) {
        lock.writeLock().lock();
        try {
            addressGrid.clear();
            assignedAddresses.clear();
            nearestRoad.clear();
            cachedBounds = bounds;
            addressCount = 0;
            errorMessage = null;

            for (NadAddress addr : addresses) {
                if (addr.street() == null || addr.street().isEmpty()) {
                    continue;
                }

                LatLon latLon = addr.location();
                if (latLon == null) {
                    continue;
                }

                EastNorth en = latLon.getEastNorth(ProjectionRegistry.getProjection());
                if (en == null) {
                    continue;
                }

                GridCell cell = new GridCell(en);
                addressGrid.computeIfAbsent(cell, k -> new ArrayList<>())
                        .add(new NadAddressData(addr.street(), StreetNameUtils.expand(addr.street()), en));
                addressCount++;
            }

            ready = true;
            Logging.info("NAD cache loaded: " + addressCount + " addresses");

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Set an error state for the cache.
     */
    public void setError(String message) {
        lock.writeLock().lock();
        try {
            ready = false;
            errorMessage = message;
            addressGrid.clear();
            cachedBounds = null;
            addressCount = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Clear the cache.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            ready = false;
            errorMessage = null;
            addressGrid.clear();
            assignedAddresses.clear();
            nearestRoad.clear();
            cachedBounds = null;
            addressCount = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Pre-assign NAD address points to roads based on proximity. For each address
     * within range of any road, finds the 2 nearest roads. If the address's
     * street name matches either of those roads' names, it is "assigned" and
     * excluded from name suggestions entirely. Otherwise, it can only suggest
     * a name for its single nearest road.
     *
     * @param ways              The roads to consider for assignment
     * @param maxDistanceMeters Maximum distance for matching
     * @param roadGrid          Pre-built road segment grid from
     *                          {@link SpatialUtils#buildRoadSegmentGrid}, or null
     *                          to fall back to linear scan (slower)
     */
    public void assignAddressesToRoads(Collection<Way> ways, double maxDistanceMeters,
            Map<GridCell, List<RoadSegmentEntry>> roadGrid) {
        lock.writeLock().lock();
        try {
            this.deferredWays = ways;
            this.deferredRoadGrid = roadGrid;
            this.deferredMaxDistance = maxDistanceMeters;
            this.assignmentDone = false;
            assignedAddresses.clear();
            nearestRoad.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Execute the deferred address-to-road assignment if not yet done.
     * Must be called under write lock.
     */
    private void ensureAssignmentDone() {
        if (assignmentDone || deferredWays == null) {
            return;
        }
        assignmentDone = true;
        doAssignAddressesToRoads(deferredWays, deferredMaxDistance, deferredRoadGrid);
        deferredWays = null;
        deferredRoadGrid = null;
    }

    /**
     * Actually perform the address-to-road assignment.
     * Must be called under write lock.
     */
    private void doAssignAddressesToRoads(Collection<Way> ways, double maxDistanceMeters,
            Map<GridCell, List<RoadSegmentEntry>> roadGrid) {

        if (!ready || addressGrid.isEmpty()) {
            return;
        }

        // Build a map from Way → RoadInfo for quick lookup of expanded names
        Map<Way, RoadInfo> roadsByWay = new IdentityHashMap<>();
        for (Way way : ways) {
            String name = way.get("name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            String highway = way.get("highway");
            if (highway == null || !HighwayConstants.TIGER_HIGHWAYS.contains(highway)) {
                continue;
            }
            roadsByWay.put(way, new RoadInfo(way, name, StreetNameUtils.expand(name)));
        }

        if (roadsByWay.isEmpty()) {
            return;
        }

        // For each address, find the 2 nearest roads using grid lookup
        for (List<NadAddressData> cell : addressGrid.values()) {
            for (NadAddressData addr : cell) {
                RoadInfo nearest = null;
                RoadInfo secondNearest = null;
                double nearestDist = Double.MAX_VALUE;
                double secondNearestDist = Double.MAX_VALUE;

                // Use road grid to find candidate roads near this address
                Set<Way> candidates = (roadGrid != null)
                        ? SpatialUtils.findNearbyRoads(addr.location(), roadGrid, maxDistanceMeters)
                        : roadsByWay.keySet();

                for (Way candidateWay : candidates) {
                    RoadInfo road = roadsByWay.get(candidateWay);
                    if (road == null) {
                        continue; // not a named highway
                    }

                    double dist = minDistanceToWay(addr.location(), road.way);
                    if (dist > maxDistanceMeters) {
                        continue;
                    }

                    if (dist < nearestDist) {
                        secondNearest = nearest;
                        secondNearestDist = nearestDist;
                        nearest = road;
                        nearestDist = dist;
                    } else if (dist < secondNearestDist) {
                        secondNearest = road;
                        secondNearestDist = dist;
                    }
                }

                if (nearest == null) {
                    continue;
                }

                nearestRoad.put(addr, nearest.way);

                // Check if street name matches either of the 2 nearest roads
                if (addr.expandedStreetName().equalsIgnoreCase(nearest.expandedName())) {
                    assignedAddresses.add(addr);
                } else if (secondNearest != null
                        && addr.expandedStreetName().equalsIgnoreCase(secondNearest.expandedName())) {
                    assignedAddresses.add(addr);
                }
            }
        }
    }

    /**
     * Check if the cache is ready for queries.
     */
    public boolean isReady() {
        lock.readLock().lock();
        try {
            return ready;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the error message if loading failed.
     */
    public String getErrorMessage() {
        lock.readLock().lock();
        try {
            return errorMessage;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the number of cached addresses.
     */
    public int getAddressCount() {
        lock.readLock().lock();
        try {
            return addressCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if a road's name is corroborated by NAD addresses.
     *
     * @param way             The way to check
     * @param name            The name to look for
     * @param maxDistanceMeters Maximum distance to search
     * @return true if a nearby NAD address has a matching street name
     */
    public boolean isNameCorroborated(Way way, String name, double maxDistanceMeters) {
        return findMatchingName(way, name, maxDistanceMeters) != null;
    }

    /**
     * Find the NAD street name that exactly matches the given road name (case-insensitive).
     *
     * <p>Fuzzy matches (small Levenshtein distance) are NOT treated as corroboration
     * because they require human review. They will instead appear as name suggestions
     * via {@link #findMostCommonStreetName}.</p>
     *
     * @param way             The way to check
     * @param name            The name to look for
     * @param maxDistanceMeters Maximum distance to search
     * @return The OSM name if an exact match is found, or null if no exact match
     */
    public String findMatchingName(Way way, String name, double maxDistanceMeters) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        // Expand road name once for all segment comparisons
        String expandedName = StreetNameUtils.expand(name);

        lock.readLock().lock();
        try {
            if (!ready || addressGrid.isEmpty()) {
                return null;
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

                if (hasExactMatchNearSegment(en1, en2, expandedName, maxDistanceMeters)) {
                    return name;
                }
            }

            return null;

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if there's an exact (case-insensitive) NAD street name match near a line segment.
     *
     * @param expandedName Pre-expanded road name (via {@link StreetNameUtils#expand})
     */
    private boolean hasExactMatchNearSegment(EastNorth en1, EastNorth en2, String expandedName, double maxDistance) {
        CellRange range = CellRange.of(en1, en2, maxDistance);

        for (NadAddressData addr : SpatialUtils.collectFromGrid(addressGrid, range)) {
            if (expandedName.equalsIgnoreCase(addr.expandedStreetName())) {
                double dist = SpatialUtils.distanceToSegment(addr.location(), en1, en2);
                if (dist <= maxDistance) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Find the most common NAD street name near a way, excluding names that match
     * the given OSM name (exact or fuzzy). Used to suggest that a road might have
     * a different name than what OSM currently records.
     *
     * <p>Only considers addresses that are:
     * <ul>
     *   <li>Not assigned (street name didn't match either of the 2 nearest roads)</li>
     *   <li>Nearest to this specific way (won't suggest for a farther road)</li>
     * </ul>
     *
     * @param way              The way to check
     * @param osmName          The current OSM name to exclude from results
     * @param maxDistanceMeters Maximum distance to search
     * @return The most common non-matching NAD street name near the way, or null if none found
     */
    public String findMostCommonStreetName(Way way, String osmName, double maxDistanceMeters) {
        if (osmName == null || osmName.isEmpty()) {
            return null;
        }

        // Expand road name once for all segment comparisons
        String expandedOsmName = StreetNameUtils.expand(osmName);

        // Trigger lazy assignment if needed (requires write lock)
        if (!assignmentDone && deferredWays != null) {
            lock.writeLock().lock();
            try {
                ensureAssignmentDone();
            } finally {
                lock.writeLock().unlock();
            }
        }

        lock.readLock().lock();
        try {
            if (!ready || addressGrid.isEmpty()) {
                return null;
            }

            // Collect all NAD street names near the way (excluding matches)
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

                collectStreetNamesNearSegment(en1, en2, expandedOsmName, maxDistanceMeters, way, nameCounts);
            }

            if (nameCounts.isEmpty()) {
                return null;
            }

            // Return the most common name
            return Collections.max(nameCounts.entrySet(), Map.Entry.comparingByValue()).getKey();

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Collect NAD street names near a segment, excluding names that match osmName,
     * addresses assigned to a matching road, and addresses whose nearest road
     * is not the given way.
     *
     * @param expandedOsmName Pre-expanded road name (via {@link StreetNameUtils#expand})
     */
    private void collectStreetNamesNearSegment(EastNorth en1, EastNorth en2, String expandedOsmName,
            double maxDistance, Way way, Map<String, Integer> nameCounts) {
        CellRange range = CellRange.of(en1, en2, maxDistance);

        for (NadAddressData addr : SpatialUtils.collectFromGrid(addressGrid, range)) {
            double dist = SpatialUtils.distanceToSegment(addr.location(), en1, en2);
            if (dist > maxDistance) {
                continue;
            }

            // Skip name matches (using pre-expanded names)
            if (expandedOsmName.equalsIgnoreCase(addr.expandedStreetName())) {
                continue;
            }

            // Skip addresses whose street name matched one of the 2 nearest roads
            if (assignedAddresses.contains(addr)) {
                continue;
            }

            // Only suggest for the address's nearest road
            Way addrNearest = nearestRoad.get(addr);
            if (addrNearest != null && addrNearest != way) {
                continue;
            }

            nameCounts.merge(addr.streetName(), 1, Integer::sum);
        }
    }

    /**
     * Compute the minimum distance from a point to any segment of a way.
     *
     * @return The minimum distance, or Double.MAX_VALUE if no valid segments
     */
    private static double minDistanceToWay(EastNorth point, Way way) {
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
                    return 0;
                }
            }
        }

        return minDist;
    }

    /**
     * NAD address data stored in the grid, with pre-expanded street name for fast matching.
     */
    private record NadAddressData(String streetName, String expandedStreetName, EastNorth location) {
    }

    /**
     * Precomputed road info for assignment, with pre-expanded name for fast matching.
     */
    private record RoadInfo(Way way, String name, String expandedName) {
    }
}
