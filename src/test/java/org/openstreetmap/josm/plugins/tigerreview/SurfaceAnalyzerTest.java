// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.tigerreview.SurfaceAnalyzer.SurfaceSuggestion;

/**
 * Unit tests for {@link SurfaceAnalyzer} using surface test data.
 */
class SurfaceAnalyzerTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static DataSet surfaceTestData;

    @BeforeAll
    static void loadTestData() {
        surfaceTestData = JosmTestSetup.loadDataSet("test-data-surface-validation.osm");
    }

    @Test
    void testAnalyzeAllProducesResults() {
        List<SurfaceSuggestion> results = SurfaceAnalyzer.analyzeAll(surfaceTestData);
        assertFalse(results.isEmpty(), "Expected at least one surface suggestion");
    }

    @Test
    void testAllResultsHaveValidCodes() {
        List<SurfaceSuggestion> results = SurfaceAnalyzer.analyzeAll(surfaceTestData);
        for (SurfaceSuggestion s : results) {
            assertTrue(s.getCode() > 0, "Warning code should be positive");
            assertNotNull(s.getWay(), "Result should have a way");
            assertNotNull(s.getMessage(), "Result should have a message");
        }
    }

    @Test
    void testSurfaceCodesInRange() {
        List<SurfaceSuggestion> results = SurfaceAnalyzer.analyzeAll(surfaceTestData);
        for (SurfaceSuggestion s : results) {
            assertTrue(s.getCode() >= 10496671 && s.getCode() <= 10496679,
                    "Surface code should be in range 10496671-10496679, got: " + s.getCode());
        }
    }

    // --- Rule 1: Connected same-name/highway propagation ---

    @Test
    void testBasicPropagation() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("1"), "Should suggest surface for gap in same-name road");
        assertEquals(SurfaceTest.SURFACE_CONNECTED_ROAD, results.get("1").getCode());
        assertEquals("asphalt", results.get("1").getSurfaceValue());
    }

    @Test
    void testTransitivePropagation() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("2a"), "First gap should get surface");
        assertTrue(results.containsKey("2b"), "Second gap should get surface");
        assertTrue(results.containsKey("2c"), "Third gap should get surface");
        assertEquals("asphalt", results.get("2a").getSurfaceValue());
        assertEquals("asphalt", results.get("2b").getSurfaceValue());
        assertEquals("asphalt", results.get("2c").getSurfaceValue());
    }

    @Test
    void testConflictInComponent() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("3"), "Should flag conflict for asphalt vs gravel");
        assertEquals(SurfaceTest.SURFACE_CONFLICT, results.get("3").getCode());
        assertNull(results.get("3").getFixSupplier(), "Conflict should have no fix");
    }

    @Test
    void testGenericUpgradeFromConnectedRoad() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("4"), "Should upgrade paved to asphalt");
        assertEquals(SurfaceTest.SURFACE_CONNECTED_ROAD_UPGRADE, results.get("4").getCode());
        assertEquals("asphalt", results.get("4").getSurfaceValue());
    }

    @Test
    void testNoPropagationUnnamed() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("5"), "Should not propagate across unnamed roads");
    }

    @Test
    void testNoPropagationDifferentHighway() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("6"),
                "Should not propagate across different highway types");
    }

    @Test
    void testExistingIncompatibleSurfaceConflict() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("7"),
                "Should flag conflict for existing gravel vs connected asphalt");
        assertEquals(SurfaceTest.SURFACE_CONFLICT, results.get("7").getCode());
    }

    // --- Rule 2: Lanes tag ---

    @Test
    void testLanesSuggestsPaved() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("8"), "lanes tag should suggest paved");
        assertEquals(SurfaceTest.SURFACE_LANES_PAVED, results.get("8").getCode());
        assertEquals("paved", results.get("8").getSurfaceValue());
    }

    @Test
    void testLanesUnpavedConflict() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("9"), "lanes + unpaved should conflict");
        assertEquals(SurfaceTest.SURFACE_LANES_CONFLICT, results.get("9").getCode());
        assertNull(results.get("9").getFixSupplier(), "Conflict should have no fix");
    }

    @Test
    void testLanesAlreadyPavedNoSuggestion() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("10"),
                "lanes + existing asphalt should not produce a suggestion");
    }

    @Test
    void testLanes4wdOnlyVeto() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("11"),
                "4wd_only=yes should block lanes paved suggestion");
    }

    // --- Rule 3: Parking area ---

    @Test
    void testParkingAreaInheritSurface() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("12"),
                "Service inside parking should inherit surface");
        assertEquals(SurfaceTest.SURFACE_PARKING_AREA, results.get("12").getCode());
        assertEquals("asphalt", results.get("12").getSurfaceValue());
    }

    @Test
    void testParkingAreaPartiallyOutside() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("13"),
                "Service partially outside parking should not get suggestion");
    }

    @Test
    void testParkingAreaNonService() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("14"),
                "Non-service road inside parking should not get suggestion");
    }

    @Test
    void testParkingAreaExistingConflict() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("15"),
                "Service inside parking with conflicting surface should flag conflict");
        assertEquals(SurfaceTest.SURFACE_CONFLICT, results.get("15").getCode());
    }

    @Test
    void testParkingAreaGenericUpgrade() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("16"),
                "Service inside parking with paved should upgrade to asphalt");
        assertEquals(SurfaceTest.SURFACE_PARKING_AREA_UPGRADE, results.get("16").getCode());
        assertEquals("asphalt", results.get("16").getSurfaceValue());
    }

    // --- Tag compatibility ---

    @Test
    void testTracktypeGrade4BlocksAsphalt() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("17"),
                "tracktype=grade4 should block asphalt suggestion");
    }

    @Test
    void testSmoothnessHorribleBlocksAsphalt() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("18"),
                "smoothness=horrible should block asphalt suggestion");
    }

    // --- Rule 4: Crossing way surface inference ---

    @Test
    void testCrossingBasic() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("20"),
                "Should suggest surface from crossing way");
        assertEquals(SurfaceTest.SURFACE_CROSSING, results.get("20").getCode());
        assertEquals("asphalt", results.get("20").getSurfaceValue());
    }

    @Test
    void testCrossingUpgrade() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("21"),
                "Should upgrade paved to asphalt from crossing way");
        assertEquals(SurfaceTest.SURFACE_CROSSING_UPGRADE, results.get("21").getCode());
        assertEquals("asphalt", results.get("21").getSurfaceValue());
    }

    @Test
    void testCrossingConflictExisting() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("22"),
                "Should flag conflict for gravel road with asphalt crossing");
        assertEquals(SurfaceTest.SURFACE_CONFLICT, results.get("22").getCode());
        assertNull(results.get("22").getFixSupplier(), "Conflict should have no fix");
    }

    @Test
    void testCrossingMultipleAgree() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("23"),
                "Should suggest surface from multiple agreeing crossings");
        assertEquals(SurfaceTest.SURFACE_CROSSING, results.get("23").getCode());
        assertEquals("asphalt", results.get("23").getSurfaceValue());
    }

    @Test
    void testCrossingMultipleConflict() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("24"),
                "Should flag conflict for crossings with different surfaces");
        assertEquals(SurfaceTest.SURFACE_CONFLICT, results.get("24").getCode());
    }

    @Test
    void testCrossingSameNoSuggestion() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("25"),
                "Road with same surface as crossing should not get suggestion");
    }

    @Test
    void testCrossingMarkingsSurfaceExcluded() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("27"),
                "Crossing with crossing:markings=surface should be excluded");
    }

    @Test
    void testCrossingRaisedTableExcluded() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("28"),
                "Crossing with traffic_calming=table on node should be excluded");
    }

    @Test
    void testCrossingRaisedNodeExcluded() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("29"),
                "Crossing with crossing:raised=yes on node should be excluded");
    }

    @Test
    void testLanesBelowThresholdNoSuggestion() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("30"),
                "lanes=2 (below threshold of 3) should not suggest paved");
    }

    // --- Bridge exclusion ---

    @Test
    void testBridgeBlocksPropagation() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertFalse(results.containsKey("31"),
                "man_made=bridge should block surface propagation from connected road");
    }

    // --- Priority ---

    @Test
    void testRule1PriorityOverRule2() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("19"),
                "Way qualifying for both Rule 1 and Rule 2 should have a result");
        assertEquals(SurfaceTest.SURFACE_CONNECTED_ROAD, results.get("19").getCode(),
                "Should prefer Rule 1 (connected road) over Rule 2 (lanes)");
    }

    @Test
    void testRule1PriorityOverRule4() {
        Map<String, SurfaceSuggestion> results = buildResultMap(surfaceTestData);
        assertTrue(results.containsKey("26"),
                "Way qualifying for both Rule 1 and Rule 4 should have a result");
        assertEquals(SurfaceTest.SURFACE_CONNECTED_ROAD, results.get("26").getCode(),
                "Should prefer Rule 1 (connected road) over Rule 4 (crossing)");
    }

    // --- General ---

    @Test
    void testFixableResultsProduceCommands() {
        List<SurfaceSuggestion> results = SurfaceAnalyzer.analyzeAll(surfaceTestData);
        for (SurfaceSuggestion s : results) {
            if (s.getFixSupplier() != null) {
                Command cmd = s.getFixSupplier().get();
                assertNotNull(cmd, "Fix supplier should produce a command");
            }
        }
    }

    @Test
    void testConflictResultsHaveNoFix() {
        List<SurfaceSuggestion> results = SurfaceAnalyzer.analyzeAll(surfaceTestData);
        for (SurfaceSuggestion s : results) {
            if (s.getCode() == SurfaceTest.SURFACE_CONFLICT
                    || s.getCode() == SurfaceTest.SURFACE_LANES_CONFLICT) {
                assertNull(s.getFixSupplier(),
                        "Conflict results should not have a fix supplier");
            }
        }
    }

    @Test
    void testFixConvergence() {
        DataSet ds = JosmTestSetup.loadDataSet("test-data-surface-validation.osm");
        List<SurfaceSuggestion> before = SurfaceAnalyzer.analyzeAll(ds);
        int fixableCount = 0;

        for (SurfaceSuggestion s : before) {
            if (s.getFixSupplier() != null) {
                Command cmd = s.getFixSupplier().get();
                if (cmd != null) {
                    cmd.executeCommand();
                    fixableCount++;
                }
            }
        }

        if (fixableCount > 0) {
            List<SurfaceSuggestion> after = SurfaceAnalyzer.analyzeAll(ds);
            assertTrue(after.size() < before.size(),
                    "After applying surface fixes, result count should decrease. Before: "
                            + before.size() + ", After: " + after.size());
        }
    }

    @Test
    void testResultSnapshotCapture() {
        List<SurfaceSuggestion> results = SurfaceAnalyzer.analyzeAll(surfaceTestData);
        ResultSnapshot snapshot = ResultSnapshot.fromSurfaceResults(results);
        assertEquals(results.size(), snapshot.warnings.size());
    }

    // --- Helpers ---

    private Map<String, SurfaceSuggestion> buildResultMap(DataSet ds) {
        List<SurfaceSuggestion> results = SurfaceAnalyzer.analyzeAll(ds);
        Map<String, SurfaceSuggestion> map = new HashMap<>();
        for (SurfaceSuggestion s : results) {
            String testId = s.getWay().get("_test_id");
            if (testId != null) {
                map.put(testId, s);
            }
        }
        return map;
    }
}
