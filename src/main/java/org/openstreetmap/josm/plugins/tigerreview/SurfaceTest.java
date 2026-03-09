// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.tigerreview.SurfaceAnalyzer.SurfaceSuggestion;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck;

/**
 * Validator test for suggesting road surface tags based on connected roads.
 *
 * Runs independently of TIGER review — analyzes all vehicular roads
 * that lack a surface tag, regardless of tiger:reviewed status.
 *
 * Analysis logic is in {@link SurfaceAnalyzer}; this class translates
 * results into JOSM TestError objects.
 */
public class SurfaceTest extends Test {

    /** Error code prefix based on Wikidata road surface ID Q1049667 */
    private static final int CODE_PREFIX = 10496670;

    /** Surface inferred from same-road endpoint connections at both ends (high confidence) */
    public static final int SURFACE_SUGGESTED_BOTH_ENDS = CODE_PREFIX + 1;

    /** Surface inferred from connected road at one end (low confidence) */
    public static final int SURFACE_SUGGESTED_ONE_END = CODE_PREFIX + 2;

    /** Connected roads at endpoints have conflicting surfaces (needs human review) */
    public static final int SURFACE_CONFLICT = CODE_PREFIX + 3;

    /** Generic surface upgrade, same-road endpoints at both ends (high confidence) */
    public static final int SURFACE_UPGRADE_BOTH_ENDS = CODE_PREFIX + 4;

    /** Generic surface (paved/unpaved) can be upgraded to a specific value, one end */
    public static final int SURFACE_UPGRADE_ONE_END = CODE_PREFIX + 5;

    /** Surface inferred from connected roads at both ends, mixed quality (medium confidence) */
    public static final int SURFACE_SUGGESTED_BOTH_ENDS_MIXED = CODE_PREFIX + 6;

    /** Generic surface upgrade, both ends, mixed quality (medium confidence) */
    public static final int SURFACE_UPGRADE_BOTH_ENDS_MIXED = CODE_PREFIX + 7;

    /** Surface=paved suggested solely from Mapillary road marking detections (low confidence) */
    public static final int SURFACE_SUGGESTED_MAPILLARY_MARKING = CODE_PREFIX + 8;

    /** Group message for all surface warnings in the validator tree */
    private static final String GROUP_MESSAGE = tr("Surface Suggestion");

    private SurfaceCheck surfaceCheck;

    public SurfaceTest() {
        super(tr("Surface Suggestion"),
              tr("Suggests surface tags for roads based on connected road surfaces"));
    }

    @Override
    public void startTest(org.openstreetmap.josm.gui.progress.ProgressMonitor monitor) {
        super.startTest(monitor);
        surfaceCheck = new SurfaceCheck();
    }

    @Override
    public void visit(Way way) {
        if (!SurfaceAnalyzer.isEligible(way)) {
            return;
        }

        SurfaceSuggestion suggestion = SurfaceAnalyzer.analyzeWay(way, surfaceCheck);
        if (suggestion == null) {
            return;
        }

        Severity severity = (suggestion.getCode() == SURFACE_CONFLICT)
                ? Severity.OTHER : Severity.WARNING;

        TestError.Builder builder = TestError.builder(this, severity, suggestion.getCode())
                .message(GROUP_MESSAGE, marktr("{0}"), suggestion.getMessage())
                .primitives(way);

        if (suggestion.getSurfaceValue() != null) {
            String surface = suggestion.getSurfaceValue();
            builder.fix(() -> new ChangePropertyCommand(way, "surface", surface));
        }

        errors.add(builder.build());
    }

    @Override
    public void endTest() {
        surfaceCheck = null;
        super.endTest();
    }
}
