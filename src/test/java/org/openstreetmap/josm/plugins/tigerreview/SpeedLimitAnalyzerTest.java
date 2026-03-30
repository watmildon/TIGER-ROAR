// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.SpeedLimitAnalyzer.SpeedLimitSuggestion;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.SpeedLimitDetection;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryDataCache;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Unit tests for {@link SpeedLimitAnalyzer} with injected Mapillary detections.
 */
class SpeedLimitAnalyzerTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @BeforeAll
    static void configurePrefs() {
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, true);
        Config.getPref().putDouble(TIGERReviewPreferences.PREF_MAPILLARY_MAX_DISTANCE, 25.0);
    }

    @AfterEach
    void clearCache() {
        TestDataExtractor.clearCaches();
    }

    @Test
    void testNoCacheProducesNoResults() {
        DataSet ds = createSimpleRoadDataSet(40.0, -74.0, null);
        List<SpeedLimitSuggestion> results = SpeedLimitAnalyzer.analyzeAll(ds);
        assertTrue(results.isEmpty(), "No results expected when cache is not ready");
    }

    @Test
    void testMissingMaxspeedSingleSign() {
        DataSet ds = createSimpleRoadDataSet(40.0, -74.0, null);

        // Inject a detection near the road
        List<SpeedLimitDetection> detections = List.of(
                new SpeedLimitDetection("det1", 35, new LatLon(40.0001, -74.0001),
                        "regulatory--maximum-speed-limit-35--us", null, null)
        );
        Bounds bounds = new Bounds(39.9, -74.1, 40.1, -73.9);
        MapillaryDataCache.getInstance().load(detections, bounds);

        List<SpeedLimitSuggestion> results = SpeedLimitAnalyzer.analyzeAll(ds);
        assertFalse(results.isEmpty(), "Should produce a result for missing maxspeed");

        SpeedLimitSuggestion result = results.get(0);
        assertEquals(SpeedLimitTest.SPEED_MISSING, result.getCode(),
                "Single sign missing maxspeed should use SPEED_MISSING code");
        assertNotNull(result.getFixSupplier(), "Missing maxspeed should have a fix");
    }

    @Test
    void testMissingMaxspeedMultipleSigns() {
        DataSet ds = createSimpleRoadDataSet(40.0, -74.0, null);

        // Inject multiple agreeing detections
        List<SpeedLimitDetection> detections = List.of(
                new SpeedLimitDetection("det1", 25, new LatLon(40.0001, -74.0001),
                        "regulatory--maximum-speed-limit-25--us", null, null),
                new SpeedLimitDetection("det2", 25, new LatLon(40.0002, -74.0002),
                        "regulatory--maximum-speed-limit-25--us", null, null)
        );
        Bounds bounds = new Bounds(39.9, -74.1, 40.1, -73.9);
        MapillaryDataCache.getInstance().load(detections, bounds);

        List<SpeedLimitSuggestion> results = SpeedLimitAnalyzer.analyzeAll(ds);
        assertFalse(results.isEmpty(), "Should produce a result");

        SpeedLimitSuggestion result = results.get(0);
        assertEquals(SpeedLimitTest.SPEED_MISSING_MULTI_SIGN, result.getCode(),
                "Multiple agreeing signs should use SPEED_MISSING_MULTI_SIGN code");
    }

    @Test
    void testConflictSingleSign() {
        DataSet ds = createSimpleRoadDataSet(40.0, -74.0, "45 mph");

        // Inject a detection that disagrees with the existing maxspeed
        List<SpeedLimitDetection> detections = List.of(
                new SpeedLimitDetection("det1", 35, new LatLon(40.0001, -74.0001),
                        "regulatory--maximum-speed-limit-35--us", null, null)
        );
        Bounds bounds = new Bounds(39.9, -74.1, 40.1, -73.9);
        MapillaryDataCache.getInstance().load(detections, bounds);

        List<SpeedLimitSuggestion> results = SpeedLimitAnalyzer.analyzeAll(ds);
        assertFalse(results.isEmpty(), "Should produce a conflict result");

        SpeedLimitSuggestion result = results.get(0);
        assertEquals(SpeedLimitTest.SPEED_CONFLICT, result.getCode(),
                "Should be SPEED_CONFLICT code");
        assertNull(result.getFixSupplier(), "Conflict results should not have a fix");
    }

    @Test
    void testMatchingSpeedProducesNoResult() {
        DataSet ds = createSimpleRoadDataSet(40.0, -74.0, "35 mph");

        // Inject a detection that matches the existing maxspeed
        List<SpeedLimitDetection> detections = List.of(
                new SpeedLimitDetection("det1", 35, new LatLon(40.0001, -74.0001),
                        "regulatory--maximum-speed-limit-35--us", null, null)
        );
        Bounds bounds = new Bounds(39.9, -74.1, 40.1, -73.9);
        MapillaryDataCache.getInstance().load(detections, bounds);

        List<SpeedLimitSuggestion> results = SpeedLimitAnalyzer.analyzeAll(ds);
        assertTrue(results.isEmpty(), "Matching speed should produce no result");
    }

    @Test
    void testNonHighwayWayIgnored() {
        DataSet ds = new DataSet();
        Node n1 = new Node(new LatLon(40.0, -74.0));
        Node n2 = new Node(new LatLon(40.001, -74.001));
        ds.addPrimitive(n1);
        ds.addPrimitive(n2);
        Way way = new Way();
        way.addNode(n1);
        way.addNode(n2);
        way.put("building", "yes");
        ds.addPrimitive(way);

        List<SpeedLimitDetection> detections = List.of(
                new SpeedLimitDetection("det1", 35, new LatLon(40.0001, -74.0001),
                        "regulatory--maximum-speed-limit-35--us", null, null)
        );
        Bounds bounds = new Bounds(39.9, -74.1, 40.1, -73.9);
        MapillaryDataCache.getInstance().load(detections, bounds);

        List<SpeedLimitSuggestion> results = SpeedLimitAnalyzer.analyzeAll(ds);
        assertTrue(results.isEmpty(), "Non-highway ways should be ignored");
    }

    @Test
    void testResultSnapshotCapture() {
        DataSet ds = createSimpleRoadDataSet(40.0, -74.0, null);
        List<SpeedLimitDetection> detections = List.of(
                new SpeedLimitDetection("det1", 35, new LatLon(40.0001, -74.0001),
                        "regulatory--maximum-speed-limit-35--us", null, null)
        );
        Bounds bounds = new Bounds(39.9, -74.1, 40.1, -73.9);
        MapillaryDataCache.getInstance().load(detections, bounds);

        List<SpeedLimitSuggestion> results = SpeedLimitAnalyzer.analyzeAll(ds);
        ResultSnapshot snapshot = ResultSnapshot.fromSpeedLimitResults(results);
        assertEquals(results.size(), snapshot.warnings.size());
    }

    // --- Helper ---

    /**
     * Create a simple DataSet with a single residential road.
     */
    private DataSet createSimpleRoadDataSet(double lat, double lon, String maxspeed) {
        DataSet ds = new DataSet();
        Node n1 = new Node(new LatLon(lat, lon));
        Node n2 = new Node(new LatLon(lat + 0.001, lon + 0.001));
        ds.addPrimitive(n1);
        ds.addPrimitive(n2);
        Way way = new Way();
        way.addNode(n1);
        way.addNode(n2);
        way.put("highway", "residential");
        if (maxspeed != null) {
            way.put("maxspeed", maxspeed);
        }
        ds.addPrimitive(way);
        return ds;
    }
}
