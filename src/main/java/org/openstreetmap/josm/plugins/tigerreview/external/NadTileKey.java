// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.Bounds;

/**
 * Identifies a 0.1-degree geographic tile for NAD address caching.
 *
 * Tile indices are computed by flooring the coordinate divided by tile size.
 * For example, longitude -90.05 maps to lonIndex -901, latitude 35.12 maps to latIndex 351.
 *
 * @param lonIndex floor(longitude / 0.1)
 * @param latIndex floor(latitude / 0.1)
 */
public record NadTileKey(int lonIndex, int latIndex) {

    /** Tile size in degrees */
    static final double TILE_SIZE = 0.1;

    /**
     * Create a tile key for the given coordinate.
     */
    public static NadTileKey of(double lon, double lat) {
        return new NadTileKey(
                (int) Math.floor(lon / TILE_SIZE),
                (int) Math.floor(lat / TILE_SIZE)
        );
    }

    /**
     * Get the geographic bounds of this tile.
     */
    public Bounds toBounds() {
        double minLon = lonIndex * TILE_SIZE;
        double minLat = latIndex * TILE_SIZE;
        return new Bounds(minLat, minLon, minLat + TILE_SIZE, minLon + TILE_SIZE);
    }

    /**
     * Compute all tile keys that overlap the given bounds.
     */
    public static List<NadTileKey> tilesForBounds(Bounds bounds) {
        int minLonIdx = (int) Math.floor(bounds.getMinLon() / TILE_SIZE);
        int maxLonIdx = (int) Math.floor(bounds.getMaxLon() / TILE_SIZE);
        int minLatIdx = (int) Math.floor(bounds.getMinLat() / TILE_SIZE);
        int maxLatIdx = (int) Math.floor(bounds.getMaxLat() / TILE_SIZE);

        List<NadTileKey> tiles = new ArrayList<>();
        for (int lon = minLonIdx; lon <= maxLonIdx; lon++) {
            for (int lat = minLatIdx; lat <= maxLatIdx; lat++) {
                tiles.add(new NadTileKey(lon, lat));
            }
        }
        return tiles;
    }

    /**
     * Convert to a filename-safe string.
     */
    public String toFilename() {
        return "tile_" + lonIndex + "_" + latIndex + ".ndjson";
    }

    /**
     * Parse a tile key from a filename, or null if the filename doesn't match.
     */
    public static NadTileKey fromFilename(String filename) {
        if (filename == null || !filename.startsWith("tile_") || !filename.endsWith(".ndjson")) {
            return null;
        }
        String stripped = filename.substring(5, filename.length() - 7); // remove "tile_" and ".ndjson"
        int sep = stripped.indexOf('_');
        if (sep == -1) {
            return null;
        }
        try {
            int lonIdx = Integer.parseInt(stripped.substring(0, sep));
            int latIdx = Integer.parseInt(stripped.substring(sep + 1));
            return new NadTileKey(lonIdx, latIdx);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
