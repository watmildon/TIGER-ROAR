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
 * Analysis engine for surface suggestions, used by the side panel's Surface tab.
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
            if (!way.isUsable()) {
                continue;
            }

            String highway = way.get("highway");
            if (highway == null || !HighwayConstants.SURFACE_HIGHWAYS.contains(highway)) {
                continue;
            }

            String existingSurface = way.get("surface");
            if (existingSurface != null
                    && !"paved".equals(existingSurface)
                    && !"unpaved".equals(existingSurface)) {
                continue;
            }

            SurfaceResult surfaceResult = surfaceCheck.checkSurface(way);
            if (surfaceResult.isConflicting()) {
                results.add(new SurfaceSuggestion(way, SurfaceTest.SURFACE_CONFLICT,
                        tr("Conflicting surfaces at endpoints"),
                        tr("Conflicting surfaces (review needed)"),
                        null));
            } else if (surfaceResult.hasSuggestion()) {
                String surface = surfaceResult.getSuggestedSurface();
                ConfidenceTier tier = surfaceResult.getConfidence();
                if (surfaceResult.isUpgrade()) {
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
                    results.add(new SurfaceSuggestion(way, code,
                            tr("Upgrade: {0} \u2192 {1} [{2}]",
                                    existingSurface, surface, tier.getLabel()),
                            groupMessage,
                            surface));
                } else {
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
                    results.add(new SurfaceSuggestion(way, code,
                            tr("Suggest surface={0} [{1}]", surface, tier.getLabel()),
                            groupMessage,
                            surface));
                }
            }
        }

        return results;
    }
}
