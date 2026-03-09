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
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck.ConfidenceTier;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck.SurfaceResult;

/**
 * Analysis engine for surface suggestions, used by both the validator test
 * ({@link SurfaceTest}) and the side panel's Surface tab.
 */
public final class SurfaceAnalyzer {

    private SurfaceAnalyzer() {
        // utility class
    }

    /**
     * A single surface suggestion result.
     */
    public static class SurfaceSuggestion implements TreeDisplayable {
        private final Way way;
        private final int code;
        private final String message;
        private final String groupMessage;
        private final String surfaceValue;

        SurfaceSuggestion(Way way, int code, String message,
                          String groupMessage, String surfaceValue) {
            this.way = way;
            this.code = code;
            this.message = message;
            this.groupMessage = groupMessage;
            this.surfaceValue = surfaceValue;
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
            if (surfaceValue == null) {
                return null;
            }
            return () -> new ChangePropertyCommand(way, "surface", surfaceValue);
        }

        public String getSurfaceValue() {
            return surfaceValue;
        }
    }

    /**
     * Check if a way is eligible for surface analysis.
     */
    public static boolean isEligible(Way way) {
        if (!way.isUsable()) {
            return false;
        }
        String highway = way.get("highway");
        if (highway == null || !HighwayConstants.SURFACE_HIGHWAYS.contains(highway)) {
            return false;
        }
        String existingSurface = way.get("surface");
        return existingSurface == null
                || "paved".equals(existingSurface)
                || "unpaved".equals(existingSurface);
    }

    /**
     * Analyze a single way for surface suggestions.
     *
     * @param way          the way to analyze
     * @param surfaceCheck the check instance to use
     * @return a suggestion, or null if no suggestion
     */
    public static SurfaceSuggestion analyzeWay(Way way, SurfaceCheck surfaceCheck) {
        String existingSurface = way.get("surface");
        SurfaceResult result = surfaceCheck.checkSurface(way);

        if (result.isConflicting()) {
            return new SurfaceSuggestion(way, SurfaceTest.SURFACE_CONFLICT,
                    tr("Conflicting surfaces at endpoints"),
                    tr("Conflicting surfaces (review needed)"),
                    null);
        }

        if (!result.hasSuggestion()) {
            return null;
        }

        String surface = result.getSuggestedSurface();
        ConfidenceTier tier = result.getConfidence();
        String markingSuffix = result.hasMarkingEvidence() ? " + Mapillary markings" : "";

        if (result.isUpgrade()) {
            int code;
            String groupMessage;
            switch (tier) {
            case HIGH:
                code = SurfaceTest.SURFACE_UPGRADE_BOTH_ENDS;
                groupMessage = tr("Upgrade generic surface (same road, both ends)");
                break;
            case MEDIUM:
                code = SurfaceTest.SURFACE_UPGRADE_BOTH_ENDS_MIXED;
                groupMessage = tr("Upgrade generic surface (both ends)");
                break;
            default:
                code = SurfaceTest.SURFACE_UPGRADE_ONE_END;
                groupMessage = tr("Upgrade generic surface (one end)");
                break;
            }
            return new SurfaceSuggestion(way, code,
                    tr("Upgrade: {0} \u2192 {1} [{2}]",
                            existingSurface, surface, tier.getLabel())
                            + markingSuffix,
                    groupMessage,
                    surface);
        }

        if (result.hasMarkingEvidence() && tier == ConfidenceTier.LOW
                && "paved".equals(surface)
                && existingSurface == null) {
            // Marking-only suggestion
            return new SurfaceSuggestion(way,
                    SurfaceTest.SURFACE_SUGGESTED_MAPILLARY_MARKING,
                    tr("Suggest surface=paved (Mapillary road markings) [{0}]",
                            tier.getLabel()),
                    tr("Mapillary road markings"),
                    surface);
        }

        int code;
        String groupMessage;
        switch (tier) {
        case HIGH:
            code = SurfaceTest.SURFACE_SUGGESTED_BOTH_ENDS;
            groupMessage = tr("Same road at both ends");
            break;
        case MEDIUM:
            code = SurfaceTest.SURFACE_SUGGESTED_BOTH_ENDS_MIXED;
            groupMessage = tr("Connected roads at both ends");
            break;
        default:
            code = SurfaceTest.SURFACE_SUGGESTED_ONE_END;
            groupMessage = tr("Connected road at one end");
            break;
        }
        return new SurfaceSuggestion(way, code,
                tr("Suggest surface={0} [{1}]", surface, tier.getLabel())
                        + markingSuffix,
                groupMessage,
                surface);
    }

    /**
     * Analyze all eligible ways in a DataSet for surface suggestions.
     *
     * @param dataSet the dataset to analyze
     * @return list of surface suggestions
     */
    public static List<SurfaceSuggestion> analyzeAll(DataSet dataSet) {
        SurfaceCheck surfaceCheck = new SurfaceCheck();
        List<SurfaceSuggestion> results = new ArrayList<>();

        for (Way way : dataSet.getWays()) {
            if (!isEligible(way)) {
                continue;
            }

            SurfaceSuggestion suggestion = analyzeWay(way, surfaceCheck);
            if (suggestion != null) {
                results.add(suggestion);
            }
        }

        return results;
    }
}
