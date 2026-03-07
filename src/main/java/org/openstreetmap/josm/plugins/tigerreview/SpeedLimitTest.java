// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.tigerreview.checks.SpeedLimitCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.SpeedLimitCheck.SpeedLimitResult;
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
                && MapillaryDataCache.getInstance().isReady()) {
            speedLimitCheck = new SpeedLimitCheck();
        }
    }

    @Override
    public void visit(Way way) {
        if (speedLimitCheck == null) {
            return;
        }

        if (!way.isUsable()) {
            return;
        }

        String highway = way.get("highway");
        if (highway == null || !HighwayConstants.SURFACE_HIGHWAYS.contains(highway)) {
            return;
        }

        SpeedLimitResult result = speedLimitCheck.check(way);

        if (result.getType() == SpeedLimitResult.ResultType.MISSING) {
            String speedTag = result.getDetectedSpeed() + " mph";
            int code = result.getDetectionCount() > 1 ? SPEED_MISSING_MULTI_SIGN : SPEED_MISSING;
            errors.add(TestError.builder(this, Severity.WARNING, code)
                    .message(GROUP_MESSAGE,
                            marktr("{0} ({1} sign(s))"), speedTag,
                            String.valueOf(result.getDetectionCount()))
                    .primitives(way)
                    .fix(() -> new ChangePropertyCommand(way, "maxspeed", speedTag))
                    .build());

        } else if (result.getType() == SpeedLimitResult.ResultType.CONFLICT) {
            String detected = result.getDetectedSpeed() + " mph";
            int code = result.getDetectionCount() > 1 ? SPEED_CONFLICT_MULTI_SIGN : SPEED_CONFLICT;
            errors.add(TestError.builder(this, Severity.OTHER, code)
                    .message(GROUP_MESSAGE,
                            marktr("OSM: {0}, Mapillary: {1} ({2} sign(s))"),
                            result.getExistingMaxspeed(), detected,
                            String.valueOf(result.getDetectionCount()))
                    .primitives(way)
                    .build());
        }
    }

    @Override
    public void endTest() {
        speedLimitCheck = null;
        super.endTest();
    }
}
