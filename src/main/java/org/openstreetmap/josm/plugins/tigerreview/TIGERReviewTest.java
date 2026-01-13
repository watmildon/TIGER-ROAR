// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.tigerreview.checks.AddressCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.ConnectedRoadCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.NodeVersionCheck;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Validator test for TIGER-imported roadways.
 *
 * Checks roads with tiger:reviewed=no for corroborating evidence that allows
 * automatic or semi-automatic verification of name and alignment data.
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

    /** Has name, no evidence at all */
    public static final int TIGER_NEEDS_REVIEW = CODE_PREFIX + 4;

    /** Unnamed road with alignment verified - can remove tiger:reviewed */
    public static final int TIGER_UNNAMED_VERIFIED = CODE_PREFIX + 5;

    /** Has tiger:reviewed=name, alignment now verified - can remove tag */
    public static final int TIGER_NAME_UPGRADE = CODE_PREFIX + 6;

    /** Unnamed road needs alignment review */
    public static final int TIGER_UNNAMED_NEEDS_REVIEW = CODE_PREFIX + 7;

    /** Highway types we care about (classified roads) */
    public static final Set<String> CLASSIFIED_HIGHWAYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "motorway", "motorway_link",
                    "trunk", "trunk_link",
                    "primary", "primary_link",
                    "secondary", "secondary_link",
                    "tertiary", "tertiary_link",
                    "unclassified", "residential",
                    "living_street", "service", "road")));

    private static final String TIGER_REVIEWED = "tiger:reviewed";

    private ConnectedRoadCheck connectedRoadCheck;
    private NodeVersionCheck nodeVersionCheck;
    private AddressCheck addressCheck;

    public TIGERReviewTest() {
        super(tr("TIGER Review"), tr("Validates TIGER-imported roadways for review status"));
    }

    @Override
    public void startTest(org.openstreetmap.josm.gui.progress.ProgressMonitor monitor) {
        super.startTest(monitor);

        // Initialize checks with user-configured values
        double maxAddressDistance = Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_ADDRESS_MAX_DISTANCE,
                TIGERReviewPreferences.DEFAULT_ADDRESS_MAX_DISTANCE);
        double minAvgVersion = Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_NODE_MIN_AVG_VERSION,
                TIGERReviewPreferences.DEFAULT_NODE_MIN_AVG_VERSION);

        connectedRoadCheck = new ConnectedRoadCheck();
        nodeVersionCheck = new NodeVersionCheck(minAvgVersion);
        addressCheck = new AddressCheck(maxAddressDistance);

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

        String tigerReviewed = way.get(TIGER_REVIEWED);

        // Check tiger:reviewed=no roads
        if ("no".equals(tigerReviewed)) {
            checkUnreviewedRoad(way);
        }
        // Check tiger:reviewed=name roads for potential upgrade
        else if ("name".equals(tigerReviewed)) {
            checkNameReviewedRoad(way);
        }
    }

    private void checkUnreviewedRoad(Way way) {
        String name = way.get("name");
        boolean hasName = name != null && !name.isEmpty();

        // Gather evidence
        boolean nameCorroborated = false;
        if (hasName) {
            nameCorroborated = connectedRoadCheck.isNameCorroborated(way, name)
                    || addressCheck.isNameCorroborated(way, name);
        }

        boolean alignmentVerified = nodeVersionCheck.isAlignmentVerified(way);

        // Apply decision matrix
        if (hasName) {
            if (nameCorroborated && alignmentVerified) {
                // Full verification - can remove tiger:reviewed tag
                errors.add(TestError.builder(this, Severity.WARNING, TIGER_FULLY_VERIFIED)
                        .message(tr("TIGERReview - Road fully verified, can remove tiger:reviewed tag"))
                        .primitives(way)
                        .fix(() -> createRemoveTagCommand(way))
                        .build());
            } else if (nameCorroborated) {
                // Name only - change to tiger:reviewed=name
                errors.add(TestError.builder(this, Severity.WARNING, TIGER_NAME_VERIFIED)
                        .message(tr("TIGERReview - Name verified, alignment still needs review"))
                        .primitives(way)
                        .fix(() -> createSetNameReviewedCommand(way))
                        .build());
            } else if (alignmentVerified) {
                // Alignment only - name needs verification
                errors.add(TestError.builder(this, Severity.WARNING, TIGER_NAME_NOT_CORROBORATED)
                        .message(tr("TIGERReview - Alignment OK but name not corroborated"))
                        .primitives(way)
                        .build());
            } else {
                // No evidence - needs full review
                errors.add(TestError.builder(this, Severity.WARNING, TIGER_NEEDS_REVIEW)
                        .message(tr("TIGERReview - Needs review, no corroborating evidence found"))
                        .primitives(way)
                        .build());
            }
        } else {
            // Unnamed road
            if (alignmentVerified) {
                // Can remove tag since alignment is verified and no name to check
                errors.add(TestError.builder(this, Severity.WARNING, TIGER_UNNAMED_VERIFIED)
                        .message(tr("TIGERReview - Unnamed road, alignment verified, can remove tiger:reviewed"))
                        .primitives(way)
                        .fix(() -> createRemoveTagCommand(way))
                        .build());
            } else {
                // Needs alignment review
                errors.add(TestError.builder(this, Severity.WARNING, TIGER_UNNAMED_NEEDS_REVIEW)
                        .message(tr("TIGERReview - Unnamed road, needs alignment review"))
                        .primitives(way)
                        .build());
            }
        }
    }

    private void checkNameReviewedRoad(Way way) {
        // Road already has name verified, check if alignment is now also verified
        if (nodeVersionCheck.isAlignmentVerified(way)) {
            errors.add(TestError.builder(this, Severity.WARNING, TIGER_NAME_UPGRADE)
                    .message(tr("TIGERReview - Can be fully verified, alignment now confirmed"))
                    .primitives(way)
                    .fix(() -> createRemoveTagCommand(way))
                    .build());
        }
        // If alignment not verified, no warning - it's already marked as name-only reviewed
    }

    private Command createRemoveTagCommand(Way way) {
        return new ChangePropertyCommand(way, TIGER_REVIEWED, null);
    }

    private Command createSetNameReviewedCommand(Way way) {
        return new ChangePropertyCommand(way, TIGER_REVIEWED, "name");
    }

    @Override
    public void endTest() {
        // Clean up
        connectedRoadCheck = null;
        nodeVersionCheck = null;
        addressCheck = null;
        super.endTest();
    }
}
