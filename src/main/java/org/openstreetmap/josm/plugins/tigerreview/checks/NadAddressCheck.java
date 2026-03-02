// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.checks;

import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.external.NadDataCache;

/**
 * Checks if a road's name is corroborated by NAD (National Address Database) data.
 *
 * This check queries the NAD cache (populated by background download) to find
 * addresses with matching addr:street values near the road.
 */
public class NadAddressCheck {

    private final double maxDistanceMeters;

    /**
     * Create a new NadAddressCheck.
     *
     * @param maxDistanceMeters Maximum distance in meters to search for matching addresses
     */
    public NadAddressCheck(double maxDistanceMeters) {
        this.maxDistanceMeters = maxDistanceMeters;
    }

    /**
     * Check if the cache is ready for queries.
     *
     * @return true if NAD data has been loaded and is available
     */
    public boolean isReady() {
        return NadDataCache.getInstance().isReady();
    }

    /**
     * Check if the way's name is corroborated by NAD address data.
     *
     * @param way  The way to check
     * @param name The name to look for in NAD addr:street values
     * @return true if a nearby NAD address has a matching street name
     */
    public boolean isNameCorroborated(Way way, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        NadDataCache cache = NadDataCache.getInstance();
        if (!cache.isReady()) {
            return false;
        }

        return cache.isNameCorroborated(way, name, maxDistanceMeters);
    }

    /**
     * Find the NAD street name that matches the given road name (exact or fuzzy).
     *
     * <p>Returns the matching NAD street name if found. For exact matches,
     * returns the OSM name. For fuzzy matches (small Levenshtein distance),
     * returns the differing NAD name so the caller can surface the discrepancy.</p>
     *
     * @param way  The way to check
     * @param name The name to look for in NAD addr:street values
     * @return The matching NAD street name, or null if no match
     */
    public String findMatchingName(Way way, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        NadDataCache cache = NadDataCache.getInstance();
        if (!cache.isReady()) {
            return null;
        }

        return cache.findMatchingName(way, name, maxDistanceMeters);
    }
}
