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
 * Validator test for suggesting road surface tags.
 *
 * <p>Three rules:
 * <ol>
 *   <li>Connected same-name/highway roads (immediate neighbors in per-way mode)</li>
 *   <li>{@code lanes} tag implies paved</li>
 *   <li>Service roads inside parking areas</li>
 * </ol>
 *
 * <p>Analysis logic is in {@link SurfaceAnalyzer}; this class translates
 * results into JOSM TestError objects.
 */
public class SurfaceTest extends Test {

    /** Error code prefix based on Wikidata road surface ID Q1049667 */
    private static final int CODE_PREFIX = 10496670;

    /** Surface from connected same-name/highway roads */
    public static final int SURFACE_CONNECTED_ROAD = CODE_PREFIX + 1;

    /** Generic surface upgrade from connected same-name/highway roads */
    public static final int SURFACE_CONNECTED_ROAD_UPGRADE = CODE_PREFIX + 2;

    /** Incompatible surfaces found (needs human review, no fix) */
    public static final int SURFACE_CONFLICT = CODE_PREFIX + 3;

    /** Road has lanes tag -> surface=paved */
    public static final int SURFACE_LANES_PAVED = CODE_PREFIX + 4;

    /** Road has lanes tag but existing surface contradicts paved (no fix) */
    public static final int SURFACE_LANES_CONFLICT = CODE_PREFIX + 5;

    /** Service road inside parking area inherits surface */
    public static final int SURFACE_PARKING_AREA = CODE_PREFIX + 6;

    /** Generic surface upgrade from parking area */
    public static final int SURFACE_PARKING_AREA_UPGRADE = CODE_PREFIX + 7;

    /** Group message for all surface warnings in the validator tree */
    private static final String GROUP_MESSAGE = tr("Surface Suggestion");

    private SurfaceCheck surfaceCheck;

    public SurfaceTest() {
        super(tr("Surface Suggestion"),
              tr("Suggests surface tags for roads based on connected roads, lanes, and parking areas"));
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

        boolean isConflict = suggestion.getCode() == SURFACE_CONFLICT
                || suggestion.getCode() == SURFACE_LANES_CONFLICT;
        Severity severity = isConflict ? Severity.OTHER : Severity.WARNING;

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
