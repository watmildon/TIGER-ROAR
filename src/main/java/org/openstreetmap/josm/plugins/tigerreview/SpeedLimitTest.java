// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.tigerreview.SpeedLimitAnalyzer.SpeedLimitSuggestion;
import org.openstreetmap.josm.plugins.tigerreview.checks.SpeedLimitCheck;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryDataCache;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Validator test for suggesting speed limits based on Mapillary sign detections.
 *
 * <p>Runs independently of TIGER review — analyzes all vehicular roads
 * and compares against Mapillary-detected speed limit signs.</p>
 *
 * <p>This test is opt-in; it only produces results when the Mapillary check
 * is enabled and data has been loaded.</p>
 *
 * <p>Analysis logic is in {@link SpeedLimitAnalyzer}; this class translates
 * results into JOSM TestError objects.</p>
 */
public class SpeedLimitTest extends Test {

    /** Error code prefix based on Wikidata speed limit ID Q1077350 */
    private static final int CODE_PREFIX = 10773500;

    /** No maxspeed tag, single Mapillary sign detected */
    public static final int SPEED_MISSING = CODE_PREFIX + 1;

    /** maxspeed disagrees with single Mapillary sign */
    public static final int SPEED_CONFLICT = CODE_PREFIX + 2;

    /** No maxspeed tag, multiple agreeing Mapillary signs */
    public static final int SPEED_MISSING_MULTI_SIGN = CODE_PREFIX + 3;

    /** maxspeed disagrees with multiple agreeing Mapillary signs */
    public static final int SPEED_CONFLICT_MULTI_SIGN = CODE_PREFIX + 4;

    /** Group message for all speed limit warnings in the validator tree */
    private static final String GROUP_MESSAGE = tr("Speed Limit Suggestion (Mapillary)");

    private SpeedLimitCheck speedLimitCheck;

    public SpeedLimitTest() {
        super(tr("Speed Limit Suggestion"),
              tr("Suggests speed limits for roads based on Mapillary sign detections"));
    }

    @Override
    public void startTest(org.openstreetmap.josm.gui.progress.ProgressMonitor monitor) {
        super.startTest(monitor);
        // Only create the check if Mapillary data is available
        if (Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, false)
                && Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_SPEED, true)
                && MapillaryDataCache.getInstance().isReady()) {
            speedLimitCheck = new SpeedLimitCheck();
        }
    }

    @Override
    public void visit(Way way) {
        if (speedLimitCheck == null) {
            return;
        }

        if (!SpeedLimitAnalyzer.isEligible(way)) {
            return;
        }

        SpeedLimitSuggestion suggestion = SpeedLimitAnalyzer.analyzeWay(way, speedLimitCheck);
        if (suggestion == null) {
            return;
        }

        boolean isConflict = suggestion.getCode() == SPEED_CONFLICT
                || suggestion.getCode() == SPEED_CONFLICT_MULTI_SIGN;
        Severity severity = isConflict ? Severity.OTHER : Severity.WARNING;

        TestError.Builder builder = TestError.builder(this, severity, suggestion.getCode())
                .message(GROUP_MESSAGE, marktr("{0}"), suggestion.getMessage())
                .primitives(way);

        if (suggestion.getMaxspeedValue() != null) {
            String maxspeed = suggestion.getMaxspeedValue();
            builder.fix(() -> new ChangePropertyCommand(way, "maxspeed", maxspeed));
        }

        errors.add(builder.build());
    }

    @Override
    public void endTest() {
        speedLimitCheck = null;
        super.endTest();
    }
}
