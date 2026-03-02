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

    /** Connected roads at endpoints have conflicting surfaces (needs human review) */
    public static final int SURFACE_CONFLICT = CODE_PREFIX + 3;

    /** Generic surface (paved/unpaved) can be upgraded to a specific value, both ends agree */
    public static final int SURFACE_UPGRADE_BOTH_ENDS = CODE_PREFIX + 4;

    /** Generic surface (paved/unpaved) can be upgraded to a specific value, one end */
    public static final int SURFACE_UPGRADE_ONE_END = CODE_PREFIX + 5;

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

        // Already has a specific surface — nothing to suggest
        String existingSurface = way.get("surface");
        if (existingSurface != null
                && !"paved".equals(existingSurface)
                && !"unpaved".equals(existingSurface)) {
            return;
        }

        SurfaceResult result = surfaceCheck.checkSurface(way);
        if (result.isConflicting()) {
            errors.add(TestError.builder(this, Severity.OTHER, SURFACE_CONFLICT)
                    .message(GROUP_MESSAGE, marktr("conflicting surfaces at endpoints"))
                    .primitives(way)
                    .build());
        } else if (result.hasSuggestion()) {
            String surface = result.getSuggestedSurface();
            if (result.isUpgrade()) {
                int code = result.isBothEnds()
                        ? SURFACE_UPGRADE_BOTH_ENDS
                        : SURFACE_UPGRADE_ONE_END;
                String evidence = result.isBothEnds()
                        ? tr("connected roads at both ends")
                        : tr("connected road");
                errors.add(TestError.builder(this, Severity.WARNING, code)
                        .message(GROUP_MESSAGE,
                                marktr("upgrade {0} \u2192 {1} ({2})"),
                                existingSurface, surface, evidence)
                        .primitives(way)
                        .fix(() -> new ChangePropertyCommand(way, "surface", surface))
                        .build());
            } else {
                int code = result.isBothEnds()
                        ? SURFACE_SUGGESTED_BOTH_ENDS
                        : SURFACE_SUGGESTED_ONE_END;
                String evidence = result.isBothEnds()
                        ? tr("connected roads at both ends")
                        : tr("connected road");
                errors.add(TestError.builder(this, Severity.WARNING, code)
                        .message(GROUP_MESSAGE,
                                marktr("{0} ({1})"), surface, evidence)
                        .primitives(way)
                        .fix(() -> new ChangePropertyCommand(way, "surface", surface))
                        .build());
            }
        }
    }

    @Override
    public void endTest() {
        surfaceCheck = null;
        super.endTest();
    }
}
