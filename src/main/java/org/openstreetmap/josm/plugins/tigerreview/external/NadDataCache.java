// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.tigerreview.StringDistance;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadAddress;
import org.openstreetmap.josm.tools.Logging;

/**
 * Thread-safe cache and spatial index for NAD address data.
 *
 * Uses a grid-based spatial index similar to the existing AddressCheck
 * for efficient nearby address lookups.
 */
public class NadDataCache {

    /** Grid cell size in meters (approximately) */
    private static final double GRID_CELL_SIZE = 10.0;

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

                GridCell cell = new GridCell(en, GRID_CELL_SIZE);
                addressGrid.computeIfAbsent(cell, k -> new ArrayList<>())
                        .add(new NadAddressData(addr.street(), en));
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
            cachedBounds = null;
            addressCount = 0;
        } finally {
            lock.writeLock().unlock();
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
     * Find the NAD street name that matches the given road name (exact or fuzzy).
     *
     * <p>Returns the NAD street name if a nearby NAD address has a matching street name.
     * If the match is exact (case-insensitive), returns the OSM name unchanged.
     * If the match is fuzzy (small Levenshtein distance), returns the NAD name so the
     * caller can surface the discrepancy to the user.</p>
     *
     * @param way             The way to check
     * @param name            The name to look for
     * @param maxDistanceMeters Maximum distance to search
     * @return The matching NAD street name, or null if no match found.
     *         Returns {@code name} itself for exact matches, or the differing NAD name for fuzzy matches.
     */
    public String findMatchingName(Way way, String name, double maxDistanceMeters) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        lock.readLock().lock();
        try {
            if (!ready || addressGrid.isEmpty()) {
                return null;
            }

            // Check each segment of the way
            List<Node> nodes = way.getNodes();
            String fuzzyMatch = null;

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

                String match = findMatchingNameNearSegment(en1, en2, name, maxDistanceMeters);
                if (match != null) {
                    if (name.equalsIgnoreCase(match)) {
                        // Exact match — return immediately
                        return name;
                    }
                    // Fuzzy match — keep looking for an exact match
                    if (fuzzyMatch == null) {
                        fuzzyMatch = match;
                    }
                }
            }

            return fuzzyMatch;

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find a matching NAD street name near a line segment (exact or fuzzy).
     *
     * @return The NAD street name if found, or null if no match
     */
    private String findMatchingNameNearSegment(EastNorth en1, EastNorth en2, String name, double maxDistance) {
        // Calculate bounding box around segment with buffer
        double minX = Math.min(en1.getX(), en2.getX()) - maxDistance;
        double maxX = Math.max(en1.getX(), en2.getX()) + maxDistance;
        double minY = Math.min(en1.getY(), en2.getY()) - maxDistance;
        double maxY = Math.max(en1.getY(), en2.getY()) + maxDistance;

        // Calculate grid cell range to check
        int minCellX = (int) Math.floor(minX / GRID_CELL_SIZE);
        int maxCellX = (int) Math.floor(maxX / GRID_CELL_SIZE);
        int minCellY = (int) Math.floor(minY / GRID_CELL_SIZE);
        int maxCellY = (int) Math.floor(maxY / GRID_CELL_SIZE);

        String fuzzyMatch = null;

        // Check all cells in range
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                GridCell cell = new GridCell(cx, cy);
                List<NadAddressData> addresses = addressGrid.get(cell);

                if (addresses == null) {
                    continue;
                }

                for (NadAddressData addr : addresses) {
                    double dist = distanceToSegment(addr.location(), en1, en2);
                    if (dist > maxDistance) {
                        continue;
                    }

                    if (name.equalsIgnoreCase(addr.streetName())) {
                        return addr.streetName(); // exact match — return immediately
                    }

                    if (fuzzyMatch == null
                            && StringDistance.isFuzzyMatch(name, addr.streetName())) {
                        fuzzyMatch = addr.streetName();
                    }
                }
            }
        }

        return fuzzyMatch;
    }

    /**
     * Calculate the distance from a point to a line segment.
     */
    private double distanceToSegment(EastNorth point, EastNorth segStart, EastNorth segEnd) {
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
     * Grid cell for spatial indexing.
     */
    private record GridCell(int x, int y) {
        GridCell(EastNorth en, double cellSize) {
            this((int) Math.floor(en.getX() / cellSize),
                 (int) Math.floor(en.getY() / cellSize));
        }
    }

    /**
     * NAD address data stored in the grid.
     */
    private record NadAddressData(String streetName, EastNorth location) {
    }
}
