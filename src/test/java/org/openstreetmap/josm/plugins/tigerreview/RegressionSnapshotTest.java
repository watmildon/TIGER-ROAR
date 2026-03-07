// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewAnalyzer.ReviewResult;
import org.openstreetmap.josm.plugins.tigerreview.SurfaceAnalyzer.SurfaceSuggestion;
import org.openstreetmap.josm.plugins.tigerreview.SpeedLimitAnalyzer.SpeedLimitSuggestion;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Regression snapshot test for TIGER-ROAR analyzers.
 *
 * <p>Place {@code .osm} files in {@code test-data/regression/}. On first run,
 * this generates baseline {@code .snapshot} files in
 * {@code test-data/regression/expected/} and fails asking you to review and
 * commit. On subsequent runs, it compares current output against the committed
 * baseline and reports any differences.</p>
 *
 * <p>Adapted from multipoly-gone's RegressionSnapshotTest, but captures
 * warning results (codes, messages) instead of geometry.</p>
 */
class RegressionSnapshotTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    /** Root of the regression test data directory. */
    private static final Path REGRESSION_DIR = findRegressionDir();
    private static final Path EXPECTED_DIR = REGRESSION_DIR.resolve("expected");

    @TestFactory
    List<DynamicTest> snapshotRegression() {
        // Configure analyzers for deterministic results
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_CONNECTED_ROAD_CHECK, true);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_ADDRESS_CHECK, true);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_NODE_VERSION_CHECK, true);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false);
        Config.getPref().putBoolean(TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);

        List<String> inputFiles = discoverInputFiles();
        if (inputFiles.isEmpty()) {
            return Collections.emptyList();
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (String fileName : inputFiles) {
            tests.add(DynamicTest.dynamicTest(fileName, () -> runSnapshotTest(fileName)));
        }
        return tests;
    }

    private void runSnapshotTest(String inputFileName) throws Exception {
        Path inputPath = REGRESSION_DIR.resolve(inputFileName);
        String snapshotName = inputFileName.replace(".osm", ".snapshot");
        Path expectedPath = EXPECTED_DIR.resolve(snapshotName);
        boolean isFirstRun = !Files.exists(expectedPath);

        // Load dataset and extract any fake external data into caches
        DataSet ds = loadDataSetFromDisk(inputPath);
        TestDataExtractor.extractAndLoadCaches(ds);

        try {
            // Run all analyzers (speed limit runs if _test__Mapillary data was extracted)
            List<ReviewResult> tigerResults = TIGERReviewAnalyzer.analyzeAll(ds);
            List<SurfaceSuggestion> surfaceResults = SurfaceAnalyzer.analyzeAll(ds);
            List<SpeedLimitSuggestion> speedResults = SpeedLimitAnalyzer.analyzeAll(ds);

            ResultSnapshot tigerSnapshot = ResultSnapshot.fromReviewResults(tigerResults);
            ResultSnapshot surfaceSnapshot = ResultSnapshot.fromSurfaceResults(surfaceResults);
            ResultSnapshot speedSnapshot = ResultSnapshot.fromSpeedLimitResults(speedResults);
            ResultSnapshot actualSnapshot = ResultSnapshot.merge(
                    tigerSnapshot, surfaceSnapshot, speedSnapshot);

            if (isFirstRun) {
                // Generate baseline
                Files.createDirectories(EXPECTED_DIR);
                Files.writeString(expectedPath, actualSnapshot.toCanonicalString(), StandardCharsets.UTF_8);
                fail("Baseline created for " + inputFileName + " at " + expectedPath + ".\n"
                        + "Review the baseline output, then commit it.");
            }

            // Load baseline and compare
            String expectedContent = Files.readString(expectedPath, StandardCharsets.UTF_8);
            String actualContent = actualSnapshot.toCanonicalString();

            if (!expectedContent.equals(actualContent)) {
                fail("Regression detected for " + inputFileName + ".\n\n"
                        + "Expected:\n" + expectedContent + "\n"
                        + "Actual:\n" + actualContent + "\n\n"
                        + "If this change is intentional, delete " + expectedPath
                        + " and re-run to generate a new baseline.");
            }
        } finally {
            TestDataExtractor.clearCaches();
        }
    }

    // ===================================================================
    // File discovery
    // ===================================================================

    private static List<String> discoverInputFiles() {
        if (!Files.isDirectory(REGRESSION_DIR)) {
            return Collections.emptyList();
        }
        List<String> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(REGRESSION_DIR, "*.osm")) {
            for (Path path : stream) {
                files.add(path.getFileName().toString());
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        Collections.sort(files);
        return files;
    }

    // ===================================================================
    // File I/O helpers
    // ===================================================================

    private static DataSet loadDataSetFromDisk(Path path) throws Exception {
        try (var is = Files.newInputStream(path)) {
            return OsmReader.parseDataSet(is, null);
        }
    }

    // ===================================================================
    // Directory resolution
    // ===================================================================

    private static Path findRegressionDir() {
        // Try relative to working directory (typical Gradle run)
        Path candidate = Paths.get("test-data", "regression");
        if (Files.isDirectory(candidate)) return candidate;

        // Try relative to project root via system property
        String projectDir = System.getProperty("user.dir");
        if (projectDir != null) {
            candidate = Paths.get(projectDir, "test-data", "regression");
            if (Files.isDirectory(candidate)) return candidate;
        }

        // Return the default path even if it doesn't exist yet
        return Paths.get("test-data", "regression");
    }
}
