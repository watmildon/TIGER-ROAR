// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.MarkingDetection;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.SpeedLimitDetection;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryDataCache;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadAddress;
import org.openstreetmap.josm.plugins.tigerreview.external.NadDataCache;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Extracts fake external data from a test DataSet and loads it into
 * the singleton caches, then removes the fake nodes from the DataSet.
 *
 * <h3>Conventions</h3>
 * <ul>
 *   <li><b>Fake NAD address:</b> a node with {@code _test__NAD=yes} and
 *       {@code addr:street} set. Optional: addr:housenumber, addr:city,
 *       addr:state, addr:postcode.</li>
 *   <li><b>Fake Mapillary detection:</b> a node with {@code _test__Mapillary=yes}
 *       and {@code _test__speed} set (OSM maxspeed convention, e.g. "35 mph").
 *       Optional: {@code _test__id} for detection ID.</li>
 * </ul>
 *
 * <p>Fake nodes are removed from the DataSet so they don't interfere with
 * analysis (e.g. AddressCheck would double-index addr:street nodes).</p>
 */
final class TestDataExtractor {

    private static final String TAG_TEST_NAD = "_test__NAD";
    private static final String TAG_TEST_MAPILLARY = "_test__Mapillary";
    private static final String TAG_TEST_MAPILLARY_MARKING = "_test__Mapillary_marking";
    private static final String TAG_TEST_SPEED = "_test__speed";
    private static final String TAG_TEST_MARKING_VALUE = "_test__marking_value";
    private static final String TAG_TEST_ID = "_test__id";

    private static int autoIdCounter;

    private TestDataExtractor() {
        // utility class
    }

    /**
     * Scan the DataSet for fake NAD and Mapillary nodes, load them into
     * their respective caches, enable the corresponding preferences,
     * and remove the fake nodes from the DataSet.
     */
    static void extractAndLoadCaches(DataSet ds) {
        Bounds bounds = computeBounds(ds);

        extractNad(ds, bounds);
        extractMapillary(ds, bounds);
        extractMapillaryMarkings(ds, bounds);
    }

    /**
     * Clear both external data caches.
     */
    static void clearCaches() {
        NadDataCache.getInstance().clear();
        MapillaryDataCache.getInstance().clear();
    }

    private static void extractNad(DataSet ds, Bounds bounds) {
        List<NadAddress> addresses = new ArrayList<>();
        List<Node> toRemove = new ArrayList<>();

        for (Node node : ds.getNodes()) {
            if (!"yes".equals(node.get(TAG_TEST_NAD))) {
                continue;
            }
            if (!node.isLatLonKnown()) {
                continue;
            }

            String street = node.get("addr:street");
            if (street == null || street.isEmpty()) {
                continue;
            }

            addresses.add(new NadAddress(
                    street,
                    node.get("addr:housenumber"),
                    node.get("addr:city"),
                    node.get("addr:state"),
                    node.get("addr:postcode"),
                    node.getCoor()));
            toRemove.add(node);
        }

        if (!addresses.isEmpty()) {
            NadDataCache.getInstance().load(addresses, bounds);
            Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, true);
        }

        // Remove fake nodes from DataSet (after iteration)
        for (Node node : toRemove) {
            ds.removePrimitive(node);
        }
    }

    private static void extractMapillary(DataSet ds, Bounds bounds) {
        List<SpeedLimitDetection> detections = new ArrayList<>();
        List<Node> toRemove = new ArrayList<>();

        for (Node node : ds.getNodes()) {
            if (!"yes".equals(node.get(TAG_TEST_MAPILLARY))) {
                continue;
            }
            if (!node.isLatLonKnown()) {
                continue;
            }

            String speedTag = node.get(TAG_TEST_SPEED);
            if (speedTag == null || speedTag.isEmpty()) {
                continue;
            }

            int speedValue = parseMaxspeed(speedTag);
            if (speedValue <= 0) {
                continue;
            }

            String id = node.get(TAG_TEST_ID);
            if (id == null || id.isEmpty()) {
                id = "test-det-" + (++autoIdCounter);
            }

            String objectValue = "regulatory--maximum-speed-limit-" + speedValue + "--us";

            detections.add(new SpeedLimitDetection(
                    id, speedValue, node.getCoor(), objectValue, null, null));
            toRemove.add(node);
        }

        if (!detections.isEmpty()) {
            MapillaryDataCache.getInstance().load(detections, bounds);
            Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, true);
        }

        // Remove fake nodes from DataSet (after iteration)
        for (Node node : toRemove) {
            ds.removePrimitive(node);
        }
    }

    private static void extractMapillaryMarkings(DataSet ds, Bounds bounds) {
        List<MarkingDetection> markings = new ArrayList<>();
        List<Node> toRemove = new ArrayList<>();

        for (Node node : ds.getNodes()) {
            if (!"yes".equals(node.get(TAG_TEST_MAPILLARY_MARKING))) {
                continue;
            }
            if (!node.isLatLonKnown()) {
                continue;
            }

            String markingValue = node.get(TAG_TEST_MARKING_VALUE);
            if (markingValue == null || markingValue.isEmpty()) {
                markingValue = "marking--discrete--stop-line";
            }

            String id = node.get(TAG_TEST_ID);
            if (id == null || id.isEmpty()) {
                id = "test-marking-" + (++autoIdCounter);
            }

            markings.add(new MarkingDetection(id, node.getCoor(), markingValue));
            toRemove.add(node);
        }

        if (!markings.isEmpty()) {
            MapillaryDataCache.getInstance().loadMarkings(markings, bounds);
            Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, true);
        }

        for (Node node : toRemove) {
            ds.removePrimitive(node);
        }
    }

    /**
     * Parse a maxspeed value following OSM convention (km/h unless stated).
     * Handles "XX mph" format and plain numbers.
     */
    private static int parseMaxspeed(String maxspeed) {
        if (maxspeed == null || maxspeed.isEmpty()) {
            return -1;
        }

        String trimmed = maxspeed.trim();
        if (trimmed.endsWith(" mph")) {
            try {
                return Integer.parseInt(trimmed.substring(0, trimmed.length() - 4).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // Plain number (assumed km/h in OSM convention, but for US test data
        // we treat it as mph since the SpeedLimitCheck operates in mph)
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Compute bounds from the DataSet's data, with a small buffer.
     */
    private static Bounds computeBounds(DataSet ds) {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        boolean hasData = false;

        for (Node node : ds.getNodes()) {
            if (!node.isLatLonKnown()) continue;
            LatLon ll = node.getCoor();
            minLat = Math.min(minLat, ll.lat());
            maxLat = Math.max(maxLat, ll.lat());
            minLon = Math.min(minLon, ll.lon());
            maxLon = Math.max(maxLon, ll.lon());
            hasData = true;
        }

        if (!hasData) {
            return new Bounds(0, 0, 0, 0);
        }

        // Add small buffer
        double buffer = 0.01;
        return new Bounds(minLat - buffer, minLon - buffer,
                maxLat + buffer, maxLon + buffer);
    }
}
