// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewAnalyzer.AnalysisResult;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewAnalyzer.ReviewResult;
import org.openstreetmap.josm.plugins.tigerreview.SurfaceAnalyzer.SurfaceAnalysisResult;
import org.openstreetmap.josm.plugins.tigerreview.SpeedLimitAnalyzer.SpeedLimitAnalysisResult;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Performance benchmarks using a realistic dataset.
 *
 * <p>Excluded from normal test runs via {@code @Tag("benchmark")}.
 * Run with: {@code ./gradlew test --no-daemon -PincludeBenchmarks=true}
 */
@Tag("benchmark")
class PerformanceBenchmarkTest {

    private static final String TEST_DATA = "typical-layer-VA.osm";
    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 3;

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @BeforeAll
    static void configure() {
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_CONNECTED_ROAD_CHECK, true);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_ADDRESS_CHECK, true);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_NODE_VERSION_CHECK, true);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);
    }

    @Test
    void benchmarkFullAnalysis() {
        DataSet ds = JosmTestSetup.loadDataSet(TEST_DATA);

        System.out.println("\n=== Full TIGER Analysis Benchmark ===");
        System.out.println("Dataset: " + TEST_DATA);
        System.out.println("Ways: " + ds.getWays().size());

        // Warmup
        for (int i = 0; i < WARMUP_RUNS; i++) {
            TIGERReviewAnalyzer.analyzeAllTimed(ds);
        }

        // Measured runs
        long[] totals = new long[MEASURED_RUNS];
        AnalysisResult lastResult = null;
        for (int i = 0; i < MEASURED_RUNS; i++) {
            lastResult = TIGERReviewAnalyzer.analyzeAllTimed(ds);
            totals[i] = lastResult.getTimer().totalMs();
        }

        Arrays.sort(totals);
        System.out.println("Results: " + lastResult.getResults().size());
        System.out.println("Phase breakdown (last run): " + lastResult.getTimer().summary());
        System.out.println("Total ms (min/median/max): "
                + totals[0] + " / " + totals[MEASURED_RUNS / 2] + " / " + totals[MEASURED_RUNS - 1]);
        System.out.println();

        assertFalse(lastResult.getResults().isEmpty(), "Expected results from benchmark data");
    }

    @Test
    void benchmarkSurfaceAnalysis() {
        DataSet ds = JosmTestSetup.loadDataSet(TEST_DATA);

        System.out.println("\n=== Surface Analysis Benchmark ===");

        // Warmup
        for (int i = 0; i < WARMUP_RUNS; i++) {
            SurfaceAnalyzer.analyzeAllTimed(ds);
        }

        // Measured runs
        long[] totals = new long[MEASURED_RUNS];
        SurfaceAnalysisResult lastResult = null;
        for (int i = 0; i < MEASURED_RUNS; i++) {
            lastResult = SurfaceAnalyzer.analyzeAllTimed(ds);
            totals[i] = lastResult.getTimer().totalMs();
        }

        Arrays.sort(totals);
        System.out.println("Results: " + lastResult.getResults().size());
        System.out.println("Phase breakdown (last run): " + lastResult.getTimer().summary());
        System.out.println("Total ms (min/median/max): "
                + totals[0] + " / " + totals[MEASURED_RUNS / 2] + " / " + totals[MEASURED_RUNS - 1]);
        System.out.println();
    }

    @Test
    void benchmarkSpeedLimitAnalysis() {
        DataSet ds = JosmTestSetup.loadDataSet(TEST_DATA);

        System.out.println("\n=== Speed Limit Analysis Benchmark ===");

        // Warmup
        for (int i = 0; i < WARMUP_RUNS; i++) {
            SpeedLimitAnalyzer.analyzeAllTimed(ds);
        }

        // Measured runs
        long[] totals = new long[MEASURED_RUNS];
        SpeedLimitAnalysisResult lastResult = null;
        for (int i = 0; i < MEASURED_RUNS; i++) {
            lastResult = SpeedLimitAnalyzer.analyzeAllTimed(ds);
            totals[i] = lastResult.getTimer().totalMs();
        }

        Arrays.sort(totals);
        System.out.println("Results: " + lastResult.getResults().size());
        System.out.println("Phase breakdown (last run): " + lastResult.getTimer().summary());
        System.out.println("Total ms (min/median/max): "
                + totals[0] + " / " + totals[MEASURED_RUNS / 2] + " / " + totals[MEASURED_RUNS - 1]);
        System.out.println();
    }

    @Test
    void benchmarkFixThenReanalyze() {
        DataSet ds = JosmTestSetup.loadDataSet(TEST_DATA);

        System.out.println("\n=== Fix-Then-Reanalyze Benchmark ===");

        // Initial full analysis
        AnalysisResult initial = TIGERReviewAnalyzer.analyzeAllTimed(ds);
        System.out.println("Initial analysis: " + initial.getResults().size()
                + " results, " + initial.getTimer().totalMs() + "ms");

        // Count fixable results
        List<ReviewResult> fixable = initial.getResults().stream()
                .filter(r -> r.getFixSupplier() != null)
                .toList();
        System.out.println("Fixable results: " + fixable.size());

        if (fixable.isEmpty()) {
            System.out.println("No fixable results — skipping fix benchmark");
            return;
        }

        // Apply ONE fix, then re-analyze (simulates user clicking Fix)
        ReviewResult firstFixable = fixable.get(0);
        Command cmd = firstFixable.getFixSupplier().get();
        assertNotNull(cmd);
        cmd.executeCommand();

        long[] reanalyzeTotals = new long[MEASURED_RUNS];
        AnalysisResult reanalysis = null;
        for (int i = 0; i < MEASURED_RUNS; i++) {
            reanalysis = TIGERReviewAnalyzer.analyzeAllTimed(ds);
            reanalyzeTotals[i] = reanalysis.getTimer().totalMs();
        }
        Arrays.sort(reanalyzeTotals);

        System.out.println("After 1 fix, full re-analysis: " + reanalysis.getResults().size()
                + " results");
        System.out.println("Re-analysis breakdown (last run): " + reanalysis.getTimer().summary());
        System.out.println("Re-analysis ms (min/median/max): "
                + reanalyzeTotals[0] + " / " + reanalyzeTotals[MEASURED_RUNS / 2]
                + " / " + reanalyzeTotals[MEASURED_RUNS - 1]);

        // Undo for clean state
        cmd.undoCommand();
        System.out.println();
    }

    @Test
    void benchmarkFixAllThenReanalyze() {
        DataSet ds = JosmTestSetup.loadDataSet(TEST_DATA);

        System.out.println("\n=== Fix-All-Then-Reanalyze Benchmark ===");

        // Initial full analysis
        AnalysisResult initial = TIGERReviewAnalyzer.analyzeAllTimed(ds);
        System.out.println("Initial analysis: " + initial.getResults().size()
                + " results, " + initial.getTimer().totalMs() + "ms");

        // Apply ALL fixes
        int fixCount = 0;
        for (ReviewResult r : initial.getResults()) {
            if (r.getFixSupplier() != null) {
                Command cmd = r.getFixSupplier().get();
                if (cmd != null) {
                    cmd.executeCommand();
                    fixCount++;
                }
            }
        }
        System.out.println("Applied " + fixCount + " fixes");

        // Re-analyze
        AnalysisResult reanalysis = TIGERReviewAnalyzer.analyzeAllTimed(ds);
        System.out.println("After all fixes, full re-analysis: " + reanalysis.getResults().size()
                + " results, " + reanalysis.getTimer().totalMs() + "ms");
        System.out.println("Re-analysis breakdown: " + reanalysis.getTimer().summary());
        System.out.println();
    }
}
