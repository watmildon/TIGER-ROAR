// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewAnalyzer.FixAction;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewAnalyzer.ReviewResult;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Unit tests for {@link TIGERReviewAnalyzer} using the existing test data files.
 */
class TIGERReviewAnalyzerTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static DataSet tigerTestData;
    private static DataSet comprehensiveTestData;

    @BeforeAll
    static void loadTestData() {
        // Enable all checks for comprehensive testing
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_CONNECTED_ROAD_CHECK, true);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_ADDRESS_CHECK, true);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_NODE_VERSION_CHECK, true);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);

        tigerTestData = JosmTestSetup.loadDataSetWithCaches("tiger-review-test.osm");
        comprehensiveTestData = JosmTestSetup.loadDataSetWithCaches("test-data-comprehensive.osm");
    }

    @AfterAll
    static void cleanup() {
        TestDataExtractor.clearCaches();
    }

    @Test
    void testAnalyzeAllProducesResults() {
        List<ReviewResult> results = TIGERReviewAnalyzer.analyzeAll(tigerTestData);
        assertFalse(results.isEmpty(), "Expected at least one result from tiger-review-test.osm");
    }

    @Test
    void testComprehensiveDataProducesResults() {
        List<ReviewResult> results = TIGERReviewAnalyzer.analyzeAll(comprehensiveTestData);
        assertFalse(results.isEmpty(), "Expected at least one result from test-data-comprehensive.osm");
    }

    @Test
    void testAllResultsHaveValidCodes() {
        List<ReviewResult> results = TIGERReviewAnalyzer.analyzeAll(tigerTestData);
        for (ReviewResult r : results) {
            assertTrue(r.getCode() > 0, "Warning code should be positive: " + r.getCode());
            assertNotNull(r.getWay(), "Result should have a way");
            assertNotNull(r.getMessage(), "Result should have a message");
        }
    }

    @Test
    void testFixableResultsHaveFixSupplier() {
        List<ReviewResult> results = TIGERReviewAnalyzer.analyzeAll(tigerTestData);
        for (ReviewResult r : results) {
            if (r.getFixAction() != null) {
                assertNotNull(r.getFixSupplier(),
                        "Result with fix action " + r.getFixAction() + " should have a fix supplier");
            }
        }
    }

    @Test
    void testResultsByTestId() {
        Map<String, ReviewResult> resultsByTestId = buildResultMap(comprehensiveTestData);

        // Class A: Invalid tiger:reviewed values
        assertContainsCode(resultsByTestId, "A1",
                TIGERReviewTest.TIGER_REVIEWED_INVALID_VALUE);
        assertContainsCode(resultsByTestId, "A2",
                TIGERReviewTest.TIGER_REVIEWED_INVALID_VALUE);

        // Class B: Residual tiger:* tags
        assertContainsCode(resultsByTestId, "B1",
                TIGERReviewTest.TIGER_RESIDUAL_TAGS);

        // Class Q: Etymology / wikidata name evidence tags
        assertContainsCode(resultsByTestId, "Q1",
                TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY);
        assertEquals(FixAction.SET_NAME_REVIEWED, resultsByTestId.get("Q1").getFixAction(),
                "Q1: name:etymology, no alignment → SET_NAME_REVIEWED");
        assertContainsCode(resultsByTestId, "Q2",
                TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY);
        assertEquals(FixAction.REMOVE_TAG, resultsByTestId.get("Q2").getFixAction(),
                "Q2: name:etymology:wikidata + alignment → REMOVE_TAG");
        assertContainsCode(resultsByTestId, "Q3",
                TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY);
        assertEquals(FixAction.REMOVE_TAG, resultsByTestId.get("Q3").getFixAction(),
                "Q3: wikipedia + alignment → REMOVE_TAG");
        assertContainsCode(resultsByTestId, "Q4",
                TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY);
        assertEquals(FixAction.SET_NAME_REVIEWED, resultsByTestId.get("Q4").getFixAction(),
                "Q4: wikidata, no alignment → SET_NAME_REVIEWED");
        assertContainsCode(resultsByTestId, "Q5",
                TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY);
        assertEquals(FixAction.REMOVE_TAG, resultsByTestId.get("Q5").getFixAction(),
                "Q5: wikidata + aerial reviewed → REMOVE_TAG");
        // Q6: Etymology takes priority over connected road
        assertContainsCode(resultsByTestId, "Q6",
                TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY);

        // Class R: Post-TIGER node ID detection
        assertContainsCode(resultsByTestId, "R1",
                TIGERReviewTest.TIGER_UNNAMED_VERIFIED);
        assertContainsCode(resultsByTestId, "R2",
                TIGERReviewTest.TIGER_UNNAMED_VERIFIED);
        // R3: All old v1 nodes, no user info — should NOT produce a result
        assertNull(resultsByTestId.get("R3"),
                "R3: All old v1 nodes should not produce alignment evidence");
    }

    @Test
    void testFixConvergence() {
        // Apply all fixes and re-analyze — should produce fewer results
        DataSet ds = JosmTestSetup.loadDataSet("tiger-review-test.osm");
        List<ReviewResult> before = TIGERReviewAnalyzer.analyzeAll(ds);
        int fixableCount = 0;

        for (ReviewResult r : before) {
            if (r.getFixSupplier() != null) {
                Command cmd = r.getFixSupplier().get();
                if (cmd != null) {
                    cmd.executeCommand();
                    fixableCount++;
                }
            }
        }

        if (fixableCount > 0) {
            List<ReviewResult> after = TIGERReviewAnalyzer.analyzeAll(ds);
            // After fixing, there should be fewer results (fixes may generate residual tag warnings)
            assertTrue(after.size() <= before.size(),
                    "After applying fixes, result count should not increase. Before: "
                            + before.size() + ", After: " + after.size());
        }
    }

    @Test
    void testFixUndoRedo() {
        DataSet ds = JosmTestSetup.loadDataSet("tiger-review-test.osm");
        List<ReviewResult> original = TIGERReviewAnalyzer.analyzeAll(ds);

        // Find a fixable result
        ReviewResult fixable = original.stream()
                .filter(r -> r.getFixSupplier() != null)
                .findFirst()
                .orElse(null);

        if (fixable == null) {
            return; // No fixable results to test
        }

        Way way = fixable.getWay();
        Map<String, String> tagsBefore = new HashMap<>(way.getKeys());

        // Apply fix
        Command cmd = fixable.getFixSupplier().get();
        assertNotNull(cmd);
        cmd.executeCommand();

        // Tags should have changed
        assertNotEquals(tagsBefore, way.getKeys(), "Fix should modify tags");

        // Undo
        cmd.undoCommand();
        assertEquals(tagsBefore, way.getKeys(), "Undo should restore original tags");
    }

    @Test
    void testResultSnapshotRoundTrip() {
        List<ReviewResult> results = TIGERReviewAnalyzer.analyzeAll(tigerTestData);
        ResultSnapshot snapshot = ResultSnapshot.fromReviewResults(results);

        // Snapshot should have same count as results
        assertEquals(results.size(), snapshot.warnings.size());

        // toCanonicalString should produce non-empty output
        String canonical = snapshot.toCanonicalString();
        assertFalse(canonical.isEmpty());
        assertTrue(canonical.contains("TIGER-ROAR Result Snapshot"));
    }

    @Test
    void testResultSnapshotDiffIdentical() {
        List<ReviewResult> results = TIGERReviewAnalyzer.analyzeAll(tigerTestData);
        ResultSnapshot s1 = ResultSnapshot.fromReviewResults(results);
        ResultSnapshot s2 = ResultSnapshot.fromReviewResults(results);

        String diff = ResultSnapshot.diff(s1, s2);
        assertTrue(diff.isEmpty(), "Identical snapshots should produce empty diff, got: " + diff);
    }

    // --- Helpers ---

    /**
     * Build a map from _test_id tag values to ReviewResults.
     */
    private Map<String, ReviewResult> buildResultMap(DataSet ds) {
        List<ReviewResult> results = TIGERReviewAnalyzer.analyzeAll(ds);
        Map<String, ReviewResult> map = new HashMap<>();
        for (ReviewResult r : results) {
            String testId = r.getWay().get("_test_id");
            if (testId != null) {
                map.put(testId, r);
            }
        }
        return map;
    }

    private void assertContainsCode(Map<String, ReviewResult> map, String testId, int expectedCode) {
        ReviewResult r = map.get(testId);
        assertNotNull(r, "No result found for test ID: " + testId);
        assertEquals(expectedCode, r.getCode(),
                "Expected code " + expectedCode + " for test ID " + testId + " but got " + r.getCode());
    }
}
