// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewAnalyzer.ReviewResult;
import org.openstreetmap.josm.plugins.tigerreview.checks.AddressCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.ConnectedRoadCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.NadAddressCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.NodeVersionCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Validator test for TIGER-imported roadways.
 *
 * Checks roads with tiger:reviewed=no for corroborating evidence that allows
 * automatic or semi-automatic verification of name and alignment data.
 *
 * Delegates analysis logic to {@link TIGERReviewAnalyzer} and translates
 * results into JOSM validator {@link TestError} objects.
 */
public class TIGERReviewTest extends Test {

    /** Error code prefix based on Wikidata TIGER ID Q19939 */
    private static final int CODE_PREFIX = 19939000;

    /** Name + alignment evidence found - can remove tiger:reviewed entirely */
    public static final int TIGER_FULLY_VERIFIED = CODE_PREFIX + 1;

    /** Name corroborated but no alignment evidence - set tiger:reviewed=name */
    public static final int TIGER_NAME_VERIFIED = CODE_PREFIX + 2;

    /** Has name, alignment OK, but name not verified */
    public static final int TIGER_NAME_NOT_CORROBORATED = CODE_PREFIX + 3;

    // CODE_PREFIX + 4 was TIGER_NEEDS_REVIEW - removed (no longer warning on roads without evidence)

    /** Unnamed road with alignment verified - can remove tiger:reviewed */
    public static final int TIGER_UNNAMED_VERIFIED = CODE_PREFIX + 5;

    /** Has tiger:reviewed=name, alignment now verified - can remove tag */
    public static final int TIGER_NAME_UPGRADE = CODE_PREFIX + 6;

    // CODE_PREFIX + 7 was TIGER_UNNAMED_NEEDS_REVIEW - removed (no longer warning on roads without evidence)

    /** Name verified via connected roads at both ends */
    public static final int TIGER_NAME_VERIFIED_BOTH_ENDS = CODE_PREFIX + 8;

    /** Name verified via connected road at one end */
    public static final int TIGER_NAME_VERIFIED_ONE_END = CODE_PREFIX + 9;

    /** Name verified via nearby addr:street */
    public static final int TIGER_NAME_VERIFIED_ADDRESS = CODE_PREFIX + 10;

    /** Surface can be inferred from connected roads at both ends */
    public static final int TIGER_SURFACE_SUGGESTED_BOTH_ENDS = CODE_PREFIX + 11;

    /** Surface can be inferred from connected road at one end */
    public static final int TIGER_SURFACE_SUGGESTED_ONE_END = CODE_PREFIX + 12;

    /** Name verified via NAD (National Address Database) */
    public static final int TIGER_NAME_VERIFIED_NAD = CODE_PREFIX + 13;

    /** Review completed but residual tiger:* tags remain */
    public static final int TIGER_RESIDUAL_TAGS = CODE_PREFIX + 14;

    /** tiger:reviewed has an invalid/unrecognized value */
    public static final int TIGER_REVIEWED_INVALID_VALUE = CODE_PREFIX + 15;

    /** NAD addresses along road suggest a different name */
    public static final int TIGER_NAD_NAME_SUGGESTION = CODE_PREFIX + 16;

    /** Accepted values for tiger:reviewed (no error triggered).
     *  "yes", "position", and "alignment" are legacy but not flagged as errors. */
    public static final Set<String> VALID_REVIEWED_VALUES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("no", "aerial", "name", "yes", "position", "alignment")));

    /** Highway types we care about for TIGER review (based on TagInfo combinations) */
    public static final Set<String> CLASSIFIED_HIGHWAYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "motorway", "motorway_link",
                    "trunk", "trunk_link",
                    "primary", "primary_link",
                    "secondary", "secondary_link",
                    "tertiary", "tertiary_link",
                    "unclassified", "residential",
                    "living_street", "service", "road",
                    "track",
                    "path", "footway", "cycleway", "pedestrian")));

    /** Group message for all TIGERReview warnings in the validator tree */
    private static final String GROUP_MESSAGE = tr("TIGERReview");

    private ConnectedRoadCheck connectedRoadCheck;
    private NodeVersionCheck nodeVersionCheck;
    private AddressCheck addressCheck;
    private SurfaceCheck surfaceCheck;
    private NadAddressCheck nadAddressCheck;

    // Check enable flags
    private boolean connectedRoadCheckEnabled;
    private boolean addressCheckEnabled;
    private boolean nodeVersionCheckEnabled;
    private boolean surfaceCheckEnabled;
    private boolean nadCheckEnabled;
    private boolean stripTigerTags;

    public TIGERReviewTest() {
        super(tr("TIGER Review"), tr("Validates TIGER-imported roadways for review status"));
    }

    @Override
    public void startTest(org.openstreetmap.josm.gui.progress.ProgressMonitor monitor) {
        super.startTest(monitor);

        // Read check enable flags from preferences
        connectedRoadCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_CONNECTED_ROAD_CHECK, true);
        addressCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_ADDRESS_CHECK, true);
        nodeVersionCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_NODE_VERSION_CHECK, true);
        surfaceCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_SURFACE_CHECK, true);
        nadCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false);
        stripTigerTags = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);

        // Initialize checks with user-configured values
        double maxAddressDistance = Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_ADDRESS_MAX_DISTANCE,
                TIGERReviewPreferences.DEFAULT_ADDRESS_MAX_DISTANCE);
        double minAvgVersion = Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_NODE_MIN_AVG_VERSION,
                TIGERReviewPreferences.DEFAULT_NODE_MIN_AVG_VERSION);
        double minPercentageEdited = Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_NODE_MIN_PERCENTAGE_EDITED,
                TIGERReviewPreferences.DEFAULT_NODE_MIN_PERCENTAGE_EDITED);
        double maxNadDistance = Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_NAD_MAX_DISTANCE,
                TIGERReviewPreferences.DEFAULT_NAD_MAX_DISTANCE);
        String additionalBotUsernames = Config.getPref().get(
                TIGERReviewPreferences.PREF_ADDITIONAL_BOT_USERNAMES, "");

        connectedRoadCheck = new ConnectedRoadCheck();
        nodeVersionCheck = new NodeVersionCheck(minAvgVersion, minPercentageEdited, additionalBotUsernames);
        addressCheck = new AddressCheck(maxAddressDistance);
        surfaceCheck = new SurfaceCheck();
        nadAddressCheck = new NadAddressCheck(maxNadDistance);

        // Build spatial index for addresses
        if (partialSelection) {
            addressCheck.buildIndex(null); // Will use dataset from first way
        }
    }

    @Override
    public void visit(Way way) {
        if (!way.isUsable()) {
            return;
        }

        // Only check classified highways
        String highway = way.get("highway");
        if (highway == null || !CLASSIFIED_HIGHWAYS.contains(highway)) {
            return;
        }

        List<ReviewResult> results = TIGERReviewAnalyzer.analyzeWay(way,
                connectedRoadCheck, nodeVersionCheck, addressCheck,
                surfaceCheck, nadAddressCheck,
                connectedRoadCheckEnabled, addressCheckEnabled,
                nodeVersionCheckEnabled, surfaceCheckEnabled,
                nadCheckEnabled, stripTigerTags);

        for (ReviewResult result : results) {
            errors.add(toTestError(result));
        }
    }

    /**
     * Convert an analyzer ReviewResult into a JOSM TestError for the validator.
     */
    private TestError toTestError(ReviewResult result) {
        Way way = result.getWay();
        int code = result.getCode();
        String message = result.getMessage();

        // Handle no-fix results (null fixAction) before the switch
        if (result.getFixAction() == null) {
            if (code == TIGER_NAD_NAME_SUGGESTION) {
                return TestError.builder(this, Severity.OTHER, code)
                        .message(GROUP_MESSAGE, marktr("NAD suggests different name [NAD: {0}]"), message)
                        .primitives(way)
                        .build();
            }
            if (code == TIGER_REVIEWED_INVALID_VALUE) {
                return TestError.builder(this, Severity.ERROR, code)
                        .message(GROUP_MESSAGE,
                                marktr("tiger:reviewed should be one of no, aerial, or name (found: {0})"), message)
                        .primitives(way)
                        .build();
            }
            return TestError.builder(this, Severity.WARNING, code)
                    .message(GROUP_MESSAGE, message)
                    .primitives(way)
                    .build();
        }

        // Use marktr() message patterns for translation extraction
        switch (result.getFixAction()) {
        case REMOVE_TAG:
            if (code == TIGER_NAME_UPGRADE) {
                return TestError.builder(this, Severity.WARNING, code)
                        .message(GROUP_MESSAGE, marktr("Can be fully verified, alignment now confirmed ({0})"), message)
                        .primitives(way)
                        .fix(result.getFixSupplier())
                        .build();
            } else if (code == TIGER_UNNAMED_VERIFIED) {
                return TestError.builder(this, Severity.WARNING, code)
                        .message(GROUP_MESSAGE, marktr("Unnamed road verified ({0}), can remove tiger:reviewed"), message)
                        .primitives(way)
                        .fix(result.getFixSupplier())
                        .build();
            } else if (code == TIGER_RESIDUAL_TAGS) {
                return TestError.builder(this, Severity.WARNING, code)
                        .message(GROUP_MESSAGE, marktr("Review completed, residual TIGER tags can be removed ({0})"), message)
                        .primitives(way)
                        .fix(result.getFixSupplier())
                        .build();
            } else {
                return TestError.builder(this, Severity.WARNING, code)
                        .message(GROUP_MESSAGE, marktr("Fully verified, can remove tiger:reviewed ({0})"), message)
                        .primitives(way)
                        .fix(result.getFixSupplier())
                        .build();
            }
        case SET_NAME_REVIEWED:
            return TestError.builder(this, Severity.WARNING, code)
                    .message(GROUP_MESSAGE, marktr("Name verified ({0}), alignment still needs review"), message)
                    .primitives(way)
                    .fix(result.getFixSupplier())
                    .build();
        case SET_ALIGNMENT_REVIEWED:
            return TestError.builder(this, Severity.WARNING, code)
                    .message(GROUP_MESSAGE, marktr("Alignment verified ({0}), name not corroborated"), message)
                    .primitives(way)
                    .fix(result.getFixSupplier())
                    .build();
        case ADD_SURFACE:
            return TestError.builder(this, Severity.WARNING, code)
                    .message(GROUP_MESSAGE, marktr("Surface suggestion: {0}"), message)
                    .primitives(way)
                    .fix(result.getFixSupplier())
                    .build();
        default:
            return TestError.builder(this, Severity.WARNING, code)
                    .message(GROUP_MESSAGE, message)
                    .primitives(way)
                    .fix(result.getFixSupplier())
                    .build();
        }
    }

    @Override
    public void endTest() {
        // Clean up
        connectedRoadCheck = null;
        nodeVersionCheck = null;
        addressCheck = null;
        surfaceCheck = null;
        nadAddressCheck = null;
        super.endTest();
    }
}
