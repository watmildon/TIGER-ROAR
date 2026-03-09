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
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.MarkingDetection;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.SpeedLimitDetection;
import org.openstreetmap.josm.tools.Logging;

/**
 * Thread-safe cache and spatial index for Mapillary speed limit detections.
 *
 * Uses a grid-based spatial index (same pattern as {@link NadDataCache})
 * for efficient nearby detection lookups.
 */
public class MapillaryDataCache {

    /** Grid cell size in meters (approximately) */
    private static final double GRID_CELL_SIZE = 10.0;

    /** Singleton instance */
    private static MapillaryDataCache instance;

    /** Lock for thread-safe access */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Grid-based spatial index for speed limit detections */
    private final Map<GridCell, List<CachedDetection>> detectionGrid = new HashMap<>();

    /** Grid-based spatial index for road marking detections */
    private final Map<GridCell, List<CachedMarking>> markingGrid = new HashMap<>();

    /** Bounds of the cached data */
    private Bounds cachedBounds;

    /** Whether the cache is ready for queries */
    private boolean ready;

    /** Error message if loading failed */
    private String errorMessage;

    /** Number of speed limit detections in cache */
    private int detectionCount;

    /** Number of marking detections in cache */
    private int markingCount;

    private MapillaryDataCache() {
        // Singleton
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized MapillaryDataCache getInstance() {
        if (instance == null) {
            instance = new MapillaryDataCache();
        }
        return instance;
    }

    /**
     * Load detections into the cache.
     *
     * @param detections List of speed limit detections from Mapillary
     * @param bounds     The bounds these detections cover
     */
    public void load(List<SpeedLimitDetection> detections, Bounds bounds) {
        lock.writeLock().lock();
        try {
            detectionGrid.clear();
            cachedBounds = bounds;
            detectionCount = 0;
            errorMessage = null;

            for (SpeedLimitDetection det : detections) {
                if (det.location() == null) {
                    continue;
                }

                EastNorth en = det.location().getEastNorth(ProjectionRegistry.getProjection());
                if (en == null) {
                    continue;
                }

                GridCell cell = new GridCell(en, GRID_CELL_SIZE);
                detectionGrid.computeIfAbsent(cell, k -> new ArrayList<>())
                        .add(new CachedDetection(det, en));
                detectionCount++;
            }

            ready = true;
            Logging.info("Mapillary cache loaded: " + detectionCount + " speed limit detections");

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Load road marking detections into the cache.
     *
     * @param markings List of marking detections from Mapillary
     * @param bounds   The bounds these markings cover
     */
    public void loadMarkings(List<MarkingDetection> markings, Bounds bounds) {
        lock.writeLock().lock();
        try {
            markingGrid.clear();
            markingCount = 0;

            if (cachedBounds == null) {
                cachedBounds = bounds;
            }

            for (MarkingDetection det : markings) {
                if (det.location() == null) {
                    continue;
                }

                EastNorth en = det.location().getEastNorth(ProjectionRegistry.getProjection());
                if (en == null) {
                    continue;
                }

                GridCell cell = new GridCell(en, GRID_CELL_SIZE);
                markingGrid.computeIfAbsent(cell, k -> new ArrayList<>())
                        .add(new CachedMarking(det, en));
                markingCount++;
            }

            Logging.info("Mapillary cache loaded: " + markingCount + " road marking detections");

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
            detectionGrid.clear();
            markingGrid.clear();
            cachedBounds = null;
            detectionCount = 0;
            markingCount = 0;
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
            detectionGrid.clear();
            markingGrid.clear();
            cachedBounds = null;
            detectionCount = 0;
            markingCount = 0;
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
     * Get the number of cached detections.
     */
    public int getDetectionCount() {
        lock.readLock().lock();
        try {
            return detectionCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the number of cached marking detections.
     */
    public int getMarkingCount() {
        lock.readLock().lock();
        try {
            return markingCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find all road marking detections near a way (within maxDistanceMeters of any segment).
     *
     * @param way               The way to search around
     * @param maxDistanceMeters Maximum distance in meters
     * @return List of nearby marking detections (may be empty, never null)
     */
    public List<MarkingDetection> findNearbyMarkings(Way way, double maxDistanceMeters) {
        lock.readLock().lock();
        try {
            if (!ready || markingGrid.isEmpty()) {
                return List.of();
            }

            List<MarkingDetection> nearby = new ArrayList<>();
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

                findMarkingsNearSegment(en1, en2, maxDistanceMeters, nearby);
            }

            return nearby;

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find markings near a line segment and add them to the results list.
     */
    private void findMarkingsNearSegment(EastNorth en1, EastNorth en2,
            double maxDistance, List<MarkingDetection> results) {
        double minX = Math.min(en1.getX(), en2.getX()) - maxDistance;
        double maxX = Math.max(en1.getX(), en2.getX()) + maxDistance;
        double minY = Math.min(en1.getY(), en2.getY()) - maxDistance;
        double maxY = Math.max(en1.getY(), en2.getY()) + maxDistance;

        int minCellX = (int) Math.floor(minX / GRID_CELL_SIZE);
        int maxCellX = (int) Math.floor(maxX / GRID_CELL_SIZE);
        int minCellY = (int) Math.floor(minY / GRID_CELL_SIZE);
        int maxCellY = (int) Math.floor(maxY / GRID_CELL_SIZE);

        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                GridCell cell = new GridCell(cx, cy);
                List<CachedMarking> cached = markingGrid.get(cell);

                if (cached == null) {
                    continue;
                }

                for (CachedMarking det : cached) {
                    double dist = distanceToSegment(det.location(), en1, en2);
                    if (dist <= maxDistance) {
                        if (!containsMarking(results, det.marking().id())) {
                            results.add(det.marking());
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if results already contain a marking with the given ID.
     */
    private boolean containsMarking(List<MarkingDetection> results, String id) {
        if (id == null) return false;
        for (MarkingDetection det : results) {
            if (id.equals(det.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find all speed limit detections near a way (within maxDistanceMeters of any segment).
     *
     * @param way               The way to search around
     * @param maxDistanceMeters Maximum distance in meters
     * @return List of nearby detections (may be empty, never null)
     */
    public List<SpeedLimitDetection> findNearbyDetections(Way way, double maxDistanceMeters) {
        lock.readLock().lock();
        try {
            if (!ready || detectionGrid.isEmpty()) {
                return List.of();
            }

            List<SpeedLimitDetection> nearby = new ArrayList<>();
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

                findDetectionsNearSegment(en1, en2, maxDistanceMeters, nearby);
            }

            return nearby;

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find detections near a line segment and add them to the results list.
     * Avoids adding duplicates (same detection ID).
     */
    private void findDetectionsNearSegment(EastNorth en1, EastNorth en2,
            double maxDistance, List<SpeedLimitDetection> results) {
        double minX = Math.min(en1.getX(), en2.getX()) - maxDistance;
        double maxX = Math.max(en1.getX(), en2.getX()) + maxDistance;
        double minY = Math.min(en1.getY(), en2.getY()) - maxDistance;
        double maxY = Math.max(en1.getY(), en2.getY()) + maxDistance;

        int minCellX = (int) Math.floor(minX / GRID_CELL_SIZE);
        int maxCellX = (int) Math.floor(maxX / GRID_CELL_SIZE);
        int minCellY = (int) Math.floor(minY / GRID_CELL_SIZE);
        int maxCellY = (int) Math.floor(maxY / GRID_CELL_SIZE);

        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                GridCell cell = new GridCell(cx, cy);
                List<CachedDetection> cached = detectionGrid.get(cell);

                if (cached == null) {
                    continue;
                }

                for (CachedDetection det : cached) {
                    double dist = distanceToSegment(det.location(), en1, en2);
                    if (dist <= maxDistance) {
                        // Avoid duplicates (a detection might be near multiple segments)
                        if (!containsDetection(results, det.detection().id())) {
                            results.add(det.detection());
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if results already contain a detection with the given ID.
     */
    private boolean containsDetection(List<SpeedLimitDetection> results, String id) {
        if (id == null) return false;
        for (SpeedLimitDetection det : results) {
            if (id.equals(det.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate the distance from a point to a line segment.
     */
    static double distanceToSegment(EastNorth point, EastNorth segStart, EastNorth segEnd) {
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
     * Detection data stored in the grid.
     */
    private record CachedDetection(SpeedLimitDetection detection, EastNorth location) {
    }

    /**
     * Marking detection data stored in the grid.
     */
    private record CachedMarking(MarkingDetection marking, EastNorth location) {
    }
}
