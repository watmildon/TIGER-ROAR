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
import org.openstreetmap.josm.plugins.tigerreview.checks.ConnectedRoadCheck.ConnectionType;
import org.openstreetmap.josm.plugins.tigerreview.checks.NodeVersionCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.NodeVersionCheck.AlignmentEvidence;
import org.openstreetmap.josm.plugins.tigerreview.checks.NodeVersionCheck.AlignmentResult;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.SurfaceCheck.SurfaceResult;
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
    private SurfaceCheck surfaceCheck;

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
        surfaceCheck = new SurfaceCheck();

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
        ConnectionType connectionType = ConnectionType.NONE;
        boolean addressMatch = false;
        if (hasName) {
            connectionType = connectedRoadCheck.checkConnection(way, name);
            if (connectionType == ConnectionType.NONE) {
                addressMatch = addressCheck.isNameCorroborated(way, name);
            }
        }

        AlignmentResult alignmentResult = nodeVersionCheck.checkAlignment(way);
        boolean nameCorroborated = connectionType != ConnectionType.NONE || addressMatch;

        // Check for surface suggestions from connected roads
        SurfaceResult surfaceResult = surfaceCheck.checkSurface(way);

        // Apply decision matrix - only warn if we have actionable evidence
        if (hasName) {
            if (nameCorroborated && alignmentResult.isVerified()) {
                // Full verification - can remove tiger:reviewed tag
                String message = buildFullyVerifiedMessage(connectionType, addressMatch, alignmentResult);
                int code = getNameVerificationCode(connectionType, addressMatch);
                errors.add(TestError.builder(this, Severity.WARNING, code)
                        .message(tr("TIGERReview - Fully verified, can remove tiger:reviewed ({0})", message))
                        .primitives(way)
                        .fix(() -> createRemoveTagCommand(way))
                        .build());
            } else if (nameCorroborated) {
                // Name only - change to tiger:reviewed=name
                String nameEvidence = buildNameEvidenceMessage(connectionType, addressMatch);
                int code = getNameVerificationCode(connectionType, addressMatch);
                errors.add(TestError.builder(this, Severity.WARNING, code)
                        .message(tr("TIGERReview - Name verified ({0}), alignment still needs review", nameEvidence))
                        .primitives(way)
                        .fix(() -> createSetNameReviewedCommand(way))
                        .build());
            } else if (alignmentResult.isVerified()) {
                // Alignment only - name needs verification
                String alignmentEvidence = buildAlignmentEvidenceMessage(alignmentResult);
                errors.add(TestError.builder(this, Severity.WARNING, TIGER_NAME_NOT_CORROBORATED)
                        .message(tr("TIGERReview - Alignment verified ({0}), name not corroborated", alignmentEvidence))
                        .primitives(way)
                        .build());
            }
            // No evidence at all - don't warn, user can find these on their own
        } else {
            // Unnamed road - only warn if we have alignment evidence (actionable)
            if (alignmentResult.isVerified()) {
                String alignmentEvidence = buildAlignmentEvidenceMessage(alignmentResult);
                errors.add(TestError.builder(this, Severity.WARNING, TIGER_UNNAMED_VERIFIED)
                        .message(tr("TIGERReview - Unnamed road verified ({0}), can remove tiger:reviewed", alignmentEvidence))
                        .primitives(way)
                        .fix(() -> createRemoveTagCommand(way))
                        .build());
            }
            // No evidence - don't warn, user can find these on their own
        }

        // Add surface suggestion if available (independent of other checks)
        if (surfaceResult.hasSuggestion()) {
            addSurfaceSuggestion(way, surfaceResult);
        }
    }

    /**
     * Add a surface suggestion warning with auto-fix.
     */
    private void addSurfaceSuggestion(Way way, SurfaceResult surfaceResult) {
        String surface = surfaceResult.getSuggestedSurface();
        int code = surfaceResult.isBothEnds()
                ? TIGER_SURFACE_SUGGESTED_BOTH_ENDS
                : TIGER_SURFACE_SUGGESTED_ONE_END;
        String evidence = surfaceResult.isBothEnds()
                ? tr("connected roads at both ends")
                : tr("connected road");

        errors.add(TestError.builder(this, Severity.OTHER, code)
                .message(tr("TIGERReview - Surface suggestion: {0} ({1})", surface, evidence))
                .primitives(way)
                .fix(() -> createAddSurfaceCommand(way, surface))
                .build());
    }

    private Command createAddSurfaceCommand(Way way, String surface) {
        return new ChangePropertyCommand(way, "surface", surface);
    }

    /**
     * Build a message describing how the name was verified.
     */
    private String buildNameEvidenceMessage(ConnectionType connectionType, boolean addressMatch) {
        if (connectionType == ConnectionType.BOTH_ENDS) {
            return tr("connected roads at both ends");
        } else if (connectionType == ConnectionType.ONE_END) {
            return tr("connected road");
        } else if (addressMatch) {
            return tr("nearby addr:street");
        }
        return "";
    }

    /**
     * Build a message describing how the alignment was verified.
     */
    private String buildAlignmentEvidenceMessage(AlignmentResult result) {
        if (result.getEvidence() == AlignmentEvidence.ALL_NODES_EDITED) {
            return tr("all nodes edited");
        } else {
            return tr("avg node version {0}", String.format("%.1f", result.getAvgVersion()));
        }
    }

    /**
     * Build the full verification message with both name and alignment evidence.
     */
    private String buildFullyVerifiedMessage(ConnectionType connectionType, boolean addressMatch, AlignmentResult alignmentResult) {
        String nameEvidence = buildNameEvidenceMessage(connectionType, addressMatch);
        String alignmentEvidence = buildAlignmentEvidenceMessage(alignmentResult);
        return tr("name: {0}, alignment: {1}", nameEvidence, alignmentEvidence);
    }

    /**
     * Get the appropriate warning code based on how the name was verified.
     */
    private int getNameVerificationCode(ConnectionType connectionType, boolean addressMatch) {
        if (connectionType == ConnectionType.BOTH_ENDS) {
            return TIGER_NAME_VERIFIED_BOTH_ENDS;
        } else if (connectionType == ConnectionType.ONE_END) {
            return TIGER_NAME_VERIFIED_ONE_END;
        } else if (addressMatch) {
            return TIGER_NAME_VERIFIED_ADDRESS;
        }
        return TIGER_NAME_VERIFIED;
    }

    private void checkNameReviewedRoad(Way way) {
        // Road already has name verified, check if alignment is now also verified
        AlignmentResult alignmentResult = nodeVersionCheck.checkAlignment(way);
        if (alignmentResult.isVerified()) {
            String alignmentEvidence = buildAlignmentEvidenceMessage(alignmentResult);
            errors.add(TestError.builder(this, Severity.WARNING, TIGER_NAME_UPGRADE)
                    .message(tr("TIGERReview - Can be fully verified, alignment now confirmed ({0})", alignmentEvidence))
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
        surfaceCheck = null;
        super.endTest();
    }
}
