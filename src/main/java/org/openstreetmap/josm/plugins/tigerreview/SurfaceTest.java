// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck.SurfaceResult;

/**
 * Validator test for suggesting road surface tags based on connected roads.
 *
 * Runs independently of TIGER review — analyzes all vehicular roads
 * that lack a surface tag, regardless of tiger:reviewed status.
 */
public class SurfaceTest extends Test {

    /** Error code prefix based on Wikidata road surface ID Q1049667 */
    private static final int CODE_PREFIX = 10496670;

    /** Surface inferred from connected roads at both ends (high confidence) */
    public static final int SURFACE_SUGGESTED_BOTH_ENDS = CODE_PREFIX + 1;

    /** Surface inferred from connected road at one end (lower confidence) */
    public static final int SURFACE_SUGGESTED_ONE_END = CODE_PREFIX + 2;

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
        if (!way.isUsable()) {
            return;
        }

        String highway = way.get("highway");
        if (highway == null || !HighwayConstants.SURFACE_HIGHWAYS.contains(highway)) {
            return;
        }

        // Already has surface — nothing to suggest
        if (way.get("surface") != null) {
            return;
        }

        SurfaceResult result = surfaceCheck.checkSurface(way);
        if (result.hasSuggestion()) {
            errors.add(toTestError(way, result));
        }
    }

    private TestError toTestError(Way way, SurfaceResult result) {
        String surface = result.getSuggestedSurface();
        int code = result.isBothEnds()
                ? SURFACE_SUGGESTED_BOTH_ENDS
                : SURFACE_SUGGESTED_ONE_END;
        String evidence = result.isBothEnds()
                ? tr("connected roads at both ends")
                : tr("connected road");

        return TestError.builder(this, Severity.WARNING, code)
                .message(GROUP_MESSAGE,
                        marktr("{0} ({1})"), surface, evidence)
                .primitives(way)
                .fix(() -> new ChangePropertyCommand(way, "surface", surface))
                .build();
    }

    @Override
    public void endTest() {
        surfaceCheck = null;
        super.endTest();
    }
}
