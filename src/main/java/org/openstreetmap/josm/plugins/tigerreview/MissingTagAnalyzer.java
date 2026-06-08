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
 * Analysis engine for the Single Review tab's non-TIGER worklists: vehicular
 * roads missing a {@code surface=} tag and roads missing a {@code lanes=} tag.
 *
 * <p>These are general roadway-quality checks, independent of TIGER review
 * state. Fix is intentionally disabled (no fix supplier) since the correct
 * value is judgment-dependent and the user must inspect the road first.
 */
public final class MissingTagAnalyzer {

    /** Vehicular road has no {@code surface} tag. */
    public static final int MISSING_SURFACE = 19939201;

    /** Vehicular road has no {@code lanes} tag. */
    public static final int MISSING_LANES = 19939202;

    private MissingTagAnalyzer() {
        // utility class
    }

    /**
     * A single missing-tag result. Display-only; fix supplier is null.
     */
    public static final class MissingTagResult implements TreeDisplayable {
        private final Way way;
        private final int code;
        private final String message;
        private final String groupMessage;

        MissingTagResult(Way way, int code, String message, String groupMessage) {
            this.way = way;
            this.code = code;
            this.message = message;
            this.groupMessage = groupMessage;
        }

        @Override public Way getWay() { return way; }
        @Override public int getCode() { return code; }
        @Override public String getMessage() { return message; }
        @Override public String getGroupMessage() { return groupMessage; }
        @Override public Supplier<Command> getFixSupplier() { return null; }
    }

    /** Result of a timed missing-tag analysis run. */
    public static final class MissingTagAnalysisResult {
        private final List<MissingTagResult> results;
        private final AnalysisTimer timer;

        MissingTagAnalysisResult(List<MissingTagResult> results, AnalysisTimer timer) {
            this.results = results;
            this.timer = timer;
        }

        public List<MissingTagResult> getResults() { return results; }
        public AnalysisTimer getTimer() { return timer; }
    }

    public static MissingTagAnalysisResult analyzeAllTimed(DataSet dataSet) {
        AnalysisTimer timer = new AnalysisTimer();
        timer.start("analyzeWays");

        boolean checkSurface = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_CHECK_MISSING_SURFACE, true);
        boolean checkLanes = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_CHECK_MISSING_LANES, true);

        List<MissingTagResult> results = new ArrayList<>();
        if (!checkSurface && !checkLanes) {
            timer.stop();
            return new MissingTagAnalysisResult(results, timer);
        }

        for (Way way : dataSet.getWays()) {
            if (!way.isUsable()) continue;
            String highway = way.get("highway");
            if (highway == null || !HighwayConstants.SURFACE_HIGHWAYS.contains(highway)) {
                continue;
            }
            String detail = formatWayDetail(way);
            if (checkSurface && !way.hasKey("surface")) {
                results.add(new MissingTagResult(way, MISSING_SURFACE, detail,
                        tr("Missing surface")));
            }
            if (checkLanes && !way.hasKey("lanes")) {
                results.add(new MissingTagResult(way, MISSING_LANES, detail,
                        tr("Missing lanes")));
            }
        }

        timer.stop();
        return new MissingTagAnalysisResult(results, timer);
    }

    private static String formatWayDetail(Way way) {
        String highway = way.get("highway");
        int nodes = way.getNodesCount();
        double lengthM = way.getLength();
        String length = lengthM >= 1000
                ? String.format("%.1f km", lengthM / 1000)
                : String.format("%.0f m", lengthM);
        return tr("{0}, {1}, {2} nodes", highway, length, nodes);
    }
}
