// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.checks.SpeedLimitCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.SpeedLimitCheck.SpeedLimitResult;

/**
 * Analysis engine for speed limit suggestions based on Mapillary sign detections,
 * used by both the validator test ({@link SpeedLimitTest}) and the side panel's
 * Speed Limit tab.
 */
public final class SpeedLimitAnalyzer {

    private SpeedLimitAnalyzer() {
        // utility class
    }

    /**
     * A single speed limit suggestion result.
     */
    public static class SpeedLimitSuggestion implements TreeDisplayable {
        private final Way way;
        private final int code;
        private final String message;
        private final String groupMessage;
        private final String maxspeedValue;

        SpeedLimitSuggestion(Way way, int code, String message,
                             String groupMessage, String maxspeedValue) {
            this.way = way;
            this.code = code;
            this.message = message;
            this.groupMessage = groupMessage;
            this.maxspeedValue = maxspeedValue;
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
            if (maxspeedValue == null) {
                return null;
            }
            return () -> new ChangePropertyCommand(way, "maxspeed", maxspeedValue);
        }

        public String getMaxspeedValue() {
            return maxspeedValue;
        }
    }

    /**
     * Check if a way is eligible for speed limit analysis.
     */
    public static boolean isEligible(Way way) {
        if (!way.isUsable()) {
            return false;
        }
        String highway = way.get("highway");
        return highway != null && HighwayConstants.SURFACE_HIGHWAYS.contains(highway);
    }

    /**
     * Analyze a single way for speed limit suggestions.
     *
     * @param way   the way to analyze
     * @param check the check instance to use
     * @return a suggestion, or null if no suggestion
     */
    public static SpeedLimitSuggestion analyzeWay(Way way, SpeedLimitCheck check) {
        SpeedLimitResult result = check.check(way);

        if (result.getType() == SpeedLimitResult.ResultType.MISSING) {
            String speedTag = result.getDetectedSpeed() + " mph";
            int code;
            String groupMessage;
            if (result.getDetectionCount() > 1) {
                code = SpeedLimitTest.SPEED_MISSING_MULTI_SIGN;
                groupMessage = tr("Missing speed limit (multiple signs)");
            } else {
                code = SpeedLimitTest.SPEED_MISSING;
                groupMessage = tr("Missing speed limit");
            }
            return new SpeedLimitSuggestion(way, code,
                    tr("Mapillary: {0} ({1} sign(s))",
                            speedTag, result.getDetectionCount()),
                    groupMessage,
                    speedTag);

        } else if (result.getType() == SpeedLimitResult.ResultType.CONFLICT) {
            String detected = result.getDetectedSpeed() + " mph";
            int code;
            String groupMessage;
            if (result.getDetectionCount() > 1) {
                code = SpeedLimitTest.SPEED_CONFLICT_MULTI_SIGN;
                groupMessage = tr("Speed limit conflict (multiple signs)");
            } else {
                code = SpeedLimitTest.SPEED_CONFLICT;
                groupMessage = tr("Speed limit conflict");
            }
            return new SpeedLimitSuggestion(way, code,
                    tr("OSM: {0}, Mapillary: {1} ({2} sign(s))",
                            result.getExistingMaxspeed(), detected,
                            result.getDetectionCount()),
                    groupMessage,
                    null); // No auto-fix for conflicts
        }

        return null;
    }

    /**
     * Analyze all eligible ways in a DataSet for speed limit suggestions.
     *
     * @param dataSet the dataset to analyze
     * @return list of speed limit suggestions
     */
    public static List<SpeedLimitSuggestion> analyzeAll(DataSet dataSet) {
        SpeedLimitCheck check = new SpeedLimitCheck();
        List<SpeedLimitSuggestion> results = new ArrayList<>();

        for (Way way : dataSet.getWays()) {
            if (!isEligible(way)) {
                continue;
            }

            SpeedLimitSuggestion suggestion = analyzeWay(way, check);
            if (suggestion != null) {
                results.add(suggestion);
            }
        }

        return results;
    }
}
