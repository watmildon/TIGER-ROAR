// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Analysis engine for the Alignment tab. Identifies roads that need only
 * visual alignment review:
 * <ul>
 *   <li>{@code tiger:reviewed=name} — name already verified, alignment pending</li>
 *   <li>{@code tiger:reviewed=no} with no {@code name} tag — no name to verify</li>
 * </ul>
 *
 * Fix action: remove all tiger:* tags (same as full verification).
 */
public final class AlignmentAnalyzer {

    /** Code for named roads with tiger:reviewed=name. */
    public static final int ALIGNMENT_NAME_REVIEWED = 19939101;

    /** Code for unnamed roads with tiger:reviewed=no. */
    public static final int ALIGNMENT_UNNAMED_UNREVIEWED = 19939102;

    private AlignmentAnalyzer() {
        // utility class
    }

    /**
     * A single alignment review result.
     */
    public static class AlignmentResult implements TreeDisplayable {
        private final Way way;
        private final int code;
        private final String message;
        private final String groupMessage;
        private final boolean stripTigerTags;

        AlignmentResult(Way way, int code, String message, String groupMessage, boolean stripTigerTags) {
            this.way = way;
            this.code = code;
            this.message = message;
            this.groupMessage = groupMessage;
            this.stripTigerTags = stripTigerTags;
        }

        @Override
        public Way getWay() {
            return way;
        }

        @Override
        public int getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public String getGroupMessage() {
            return groupMessage;
        }

        @Override
        public Supplier<Command> getFixSupplier() {
            return () -> TIGERReviewAnalyzer.createRemoveTagCommand(way, stripTigerTags);
        }
    }

    /**
     * Result of a timed alignment analysis run.
     */
    public static class AlignmentAnalysisResult {
        private final List<AlignmentResult> results;
        private final AnalysisTimer timer;

        AlignmentAnalysisResult(List<AlignmentResult> results, AnalysisTimer timer) {
            this.results = results;
            this.timer = timer;
        }

        public List<AlignmentResult> getResults() {
            return results;
        }

        public AnalysisTimer getTimer() {
            return timer;
        }
    }

    /**
     * Check if a way is eligible for the alignment worklist.
     */
    public static boolean isEligible(Way way) {
        if (!way.isUsable()) {
            return false;
        }
        String highway = way.get("highway");
        if (highway == null || !HighwayConstants.TIGER_HIGHWAYS.contains(highway)) {
            return false;
        }
        String reviewed = way.get("tiger:reviewed");
        if ("name".equals(reviewed)) {
            return true;
        }
        if ("no".equals(reviewed) && !way.hasKey("name")) {
            return true;
        }
        return false;
    }

    /**
     * Analyze all eligible ways in a DataSet.
     */
    public static List<AlignmentResult> analyzeAll(DataSet dataSet) {
        return analyzeAllTimed(dataSet).getResults();
    }

    /**
     * Analyze all eligible ways with timing instrumentation.
     */
    public static AlignmentAnalysisResult analyzeAllTimed(DataSet dataSet) {
        AnalysisTimer timer = new AnalysisTimer();
        timer.start("analyzeWays");

        boolean stripTigerTags = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);

        List<AlignmentResult> namedResults = new ArrayList<>();
        List<AlignmentResult> unnamedResults = new ArrayList<>();

        for (Way way : dataSet.getWays()) {
            if (!isEligible(way)) {
                continue;
            }

            String reviewed = way.get("tiger:reviewed");
            String detail = formatWayDetail(way);
            if ("name".equals(reviewed)) {
                namedResults.add(new AlignmentResult(way, ALIGNMENT_NAME_REVIEWED,
                        detail,
                        tr("Name verified, check alignment"),
                        stripTigerTags));
            } else {
                // tiger:reviewed=no, no name
                unnamedResults.add(new AlignmentResult(way, ALIGNMENT_UNNAMED_UNREVIEWED,
                        detail,
                        tr("Unnamed roads"),
                        stripTigerTags));
            }
        }

        timer.stop();

        List<AlignmentResult> results = new ArrayList<>(namedResults.size() + unnamedResults.size());
        results.addAll(namedResults);
        results.addAll(unnamedResults);

        return new AlignmentAnalysisResult(results, timer);
    }

    /**
     * Format way detail string: highway type, length, and node count.
     */
    private static String formatWayDetail(Way way) {
        String highway = way.get("highway");
        int nodes = way.getNodesCount();
        double lengthM = way.getLength();
        String length;
        if (lengthM >= 1000) {
            length = String.format("%.1f km", lengthM / 1000);
        } else {
            length = String.format("%.0f m", lengthM);
        }
        return tr("{0}, {1}, {2} nodes", highway, length, nodes);
    }
}
