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
import org.openstreetmap.josm.data.osm.Way;
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
            assertTrue(s.getCode() >= 10496671 && s.getCode() <= 10496677,
                    "Surface code should be in range 10496671-10496677, got: " + s.getCode());
        }
    }

    @Test
    void testResultsByTestId() {
        Map<String, SurfaceSuggestion> resultsByTestId = buildResultMap(surfaceTestData);

        // Verify we got some results with _test_id tags
        assertFalse(resultsByTestId.isEmpty(), "Should have results with _test_id tags");
    }

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
            if (s.getCode() == SurfaceTest.SURFACE_CONFLICT) {
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
