// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.AbstractPrimitive;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.checks.AddressCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.ConnectedRoadCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.ConnectedRoadCheck.ConnectionType;
import org.openstreetmap.josm.plugins.tigerreview.checks.NadAddressCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.NodeVersionCheck;
import org.openstreetmap.josm.plugins.tigerreview.checks.NodeVersionCheck.AlignmentEvidence;
import org.openstreetmap.josm.plugins.tigerreview.checks.NodeVersionCheck.AlignmentResult;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Shared analysis engine for TIGER review.
 *
 * Contains the decision matrix and evidence-gathering logic used by both
 * the validator test ({@link TIGERReviewTest}) and the side panel
 * ({@link TIGERReviewDialog}).
 */
public final class TIGERReviewAnalyzer {

    private static final String TIGER_REVIEWED = "tiger:reviewed";

    /** What kind of fix action a result proposes */
    public enum FixAction {
        REMOVE_TAG,
        SET_NAME_REVIEWED,
        SET_ALIGNMENT_REVIEWED,
        SUGGEST_NAME
    }

    /**
     * A single actionable result from analyzing a way.
     */
    public static class ReviewResult implements TreeDisplayable {
        private final Way way;
        private final int code;
        private final String message;
        private final String groupMessage;
        private final FixAction fixAction;
        private final boolean stripTigerTags;
        private final String suggestedName;

        ReviewResult(Way way, int code, String message, String groupMessage,
                FixAction fixAction, boolean stripTigerTags) {
            this(way, code, message, groupMessage, fixAction, stripTigerTags, null);
        }

        ReviewResult(Way way, int code, String message, String groupMessage,
                FixAction fixAction, boolean stripTigerTags, String suggestedName) {
            this.way = way;
            this.code = code;
            this.message = message;
            this.groupMessage = groupMessage;
            this.fixAction = fixAction;
            this.stripTigerTags = stripTigerTags;
            this.suggestedName = suggestedName;
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

        public FixAction getFixAction() {
            return fixAction;
        }

        public String getSuggestedName() {
            return suggestedName;
        }

        @Override
        public Supplier<Command> getFixSupplier() {
            if (fixAction == null) {
                return null;
            }
            switch (fixAction) {
            case REMOVE_TAG:
                return () -> createRemoveTagCommand(way, stripTigerTags);
            case SET_NAME_REVIEWED:
                return () -> new ChangePropertyCommand(way, TIGER_REVIEWED, "name");
            case SET_ALIGNMENT_REVIEWED:
                return () -> new ChangePropertyCommand(way, TIGER_REVIEWED, "aerial");
            case SUGGEST_NAME:
                return () -> {
                    List<Command> cmds = new ArrayList<>();
                    cmds.add(new ChangePropertyCommand(way, "name", suggestedName));
                    String currentReviewed = way.get(TIGER_REVIEWED);
                    if (isAlignmentReviewedValue(currentReviewed)) {
                        // Name accepted + alignment already verified = fully verified
                        cmds.add(createRemoveTagCommand(way, stripTigerTags));
                    } else if ("no".equals(currentReviewed)) {
                        cmds.add(new ChangePropertyCommand(way, TIGER_REVIEWED, "name"));
                    }
                    return SequenceCommand.wrapIfNeeded(tr("Accept name suggestion"), cmds);
                };
            default:
                return () -> new ChangePropertyCommand(way, TIGER_REVIEWED, null);
            }
        }
    }

    private TIGERReviewAnalyzer() {
        // utility class
    }

    /**
     * Result of a timed analysis run, containing both the review results
     * and the timing breakdown for each phase.
     */
    public static class AnalysisResult {
        private final List<ReviewResult> results;
        private final AnalysisTimer timer;

        AnalysisResult(List<ReviewResult> results, AnalysisTimer timer) {
            this.results = results;
            this.timer = timer;
        }

        public List<ReviewResult> getResults() {
            return results;
        }

        public AnalysisTimer getTimer() {
            return timer;
        }
    }

    /**
     * Analyze all eligible ways in a DataSet, reading preferences for
     * check configuration.
     *
     * @param dataSet the dataset to analyze
     * @return list of actionable results
     */
    public static List<ReviewResult> analyzeAll(DataSet dataSet) {
        return analyzeAllTimed(dataSet).getResults();
    }

    /**
     * Analyze all eligible ways in a DataSet with timing instrumentation.
     *
     * @param dataSet the dataset to analyze
     * @return analysis results with per-phase timing breakdown
     */
    public static AnalysisResult analyzeAllTimed(DataSet dataSet) {
        AnalysisTimer timer = new AnalysisTimer();

        timer.start("readPrefs");
        // Read preferences
        boolean connectedRoadCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_CONNECTED_ROAD_CHECK, true);
        boolean addressCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_ADDRESS_CHECK, true);
        boolean nodeVersionCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_NODE_VERSION_CHECK, true);
        boolean nadCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false);
        boolean stripTigerTags = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);

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
        long postTigerNodeIdThreshold = (long) (Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_NODE_MIN_POST_TIGER_ID,
                TIGERReviewPreferences.DEFAULT_NODE_MIN_POST_TIGER_ID) * 1_000_000_000L);

        // Create check instances
        ConnectedRoadCheck connectedRoadCheck = new ConnectedRoadCheck();
        NodeVersionCheck nodeVersionCheck = new NodeVersionCheck(minAvgVersion, minPercentageEdited,
                additionalBotUsernames, postTigerNodeIdThreshold);
        AddressCheck addressCheck = new AddressCheck(maxAddressDistance);
        NadAddressCheck nadAddressCheck = new NadAddressCheck(maxNadDistance);

        // Build address spatial index
        timer.start("buildIndex");
        addressCheck.buildIndex(dataSet);

        // Prepare lazy address-to-road assignment (actual work deferred until findSuggestedName)
        timer.start("collectCandidates");
        List<Way> candidateWays = dataSet.getWays().stream()
                .filter(Way::isUsable)
                .filter(w -> w.get("highway") != null && HighwayConstants.TIGER_HIGHWAYS.contains(w.get("highway")))
                .collect(Collectors.toList());

        // Build road segment grid once, shared by both address checks
        Map<SpatialUtils.GridCell, List<SpatialUtils.RoadSegmentEntry>> roadGrid = null;
        if (addressCheckEnabled || nadCheckEnabled) {
            roadGrid = SpatialUtils.buildRoadSegmentGrid(candidateWays);
        }

        if (addressCheckEnabled) {
            addressCheck.assignAddressesToRoads(candidateWays, roadGrid);
        }
        if (nadCheckEnabled) {
            nadAddressCheck.assignAddressesToRoads(candidateWays, roadGrid);
        }

        timer.start("analyzeWays");
        List<ReviewResult> results = new ArrayList<>();

        for (Way way : dataSet.getWays()) {
            if (!way.isUsable()) {
                continue;
            }

            String highway = way.get("highway");
            if (highway == null || !HighwayConstants.TIGER_HIGHWAYS.contains(highway)) {
                continue;
            }

            String tigerReviewed = way.get(TIGER_REVIEWED);
            if ("no".equals(tigerReviewed)) {
                analyzeUnreviewedRoad(way, results,
                        connectedRoadCheck, nodeVersionCheck, addressCheck,
                        nadAddressCheck,
                        connectedRoadCheckEnabled, addressCheckEnabled,
                        nodeVersionCheckEnabled,
                        nadCheckEnabled, stripTigerTags);
            } else if ("name".equals(tigerReviewed)) {
                analyzeNameReviewedRoad(way, results, nodeVersionCheck, stripTigerTags);
            } else if (isAlignmentReviewedValue(tigerReviewed)) {
                analyzeAlignmentReviewedRoad(way, results,
                        connectedRoadCheck, addressCheck, nadAddressCheck,
                        connectedRoadCheckEnabled, addressCheckEnabled,
                        nadCheckEnabled, stripTigerTags);
            } else if (tigerReviewed != null
                    && !TIGERReviewTest.VALID_REVIEWED_VALUES.contains(tigerReviewed)) {
                analyzeInvalidReviewedValue(way, tigerReviewed, results);
            } else if (hasTigerTags(way)) {
                analyzeResidualTigerTags(way, results);
            }
        }

        timer.stop();
        return new AnalysisResult(results, timer);
    }

    /**
     * Analyze a single way that may produce results. Used by TIGERReviewTest
     * in its visit() method.
     */
    public static List<ReviewResult> analyzeWay(Way way,
            ConnectedRoadCheck connectedRoadCheck,
            NodeVersionCheck nodeVersionCheck,
            AddressCheck addressCheck,
            NadAddressCheck nadAddressCheck,
            boolean connectedRoadCheckEnabled,
            boolean addressCheckEnabled,
            boolean nodeVersionCheckEnabled,
            boolean nadCheckEnabled,
            boolean stripTigerTags) {
        List<ReviewResult> results = new ArrayList<>();

        String tigerReviewed = way.get(TIGER_REVIEWED);
        if ("no".equals(tigerReviewed)) {
            analyzeUnreviewedRoad(way, results,
                    connectedRoadCheck, nodeVersionCheck, addressCheck,
                    nadAddressCheck,
                    connectedRoadCheckEnabled, addressCheckEnabled,
                    nodeVersionCheckEnabled,
                    nadCheckEnabled, stripTigerTags);
        } else if ("name".equals(tigerReviewed)) {
            analyzeNameReviewedRoad(way, results, nodeVersionCheck, stripTigerTags);
        } else if (isAlignmentReviewedValue(tigerReviewed)) {
            analyzeAlignmentReviewedRoad(way, results,
                    connectedRoadCheck, addressCheck, nadAddressCheck,
                    connectedRoadCheckEnabled, addressCheckEnabled,
                    nadCheckEnabled, stripTigerTags);
        } else if (tigerReviewed != null
                && !TIGERReviewTest.VALID_REVIEWED_VALUES.contains(tigerReviewed)) {
            analyzeInvalidReviewedValue(way, tigerReviewed, results);
        } else if (hasTigerTags(way)) {
            analyzeResidualTigerTags(way, results);
        }

        return results;
    }

    /**
     * Analyze a single way using current user preferences.
     * Convenience wrapper that reads preferences and creates check instances.
     * Used by cascade fix logic in the dialog.
     */
    public static List<ReviewResult> analyzeWayWithPreferences(Way way) {
        boolean connectedRoadCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_CONNECTED_ROAD_CHECK, true);
        boolean addressCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_ADDRESS_CHECK, true);
        boolean nodeVersionCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_NODE_VERSION_CHECK, true);
        boolean nadCheckEnabled = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false);
        boolean stripTigerTags = Config.getPref().getBoolean(
                TIGERReviewPreferences.PREF_STRIP_TIGER_TAGS, true);

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
        long postTigerNodeIdThreshold = (long) (Config.getPref().getDouble(
                TIGERReviewPreferences.PREF_NODE_MIN_POST_TIGER_ID,
                TIGERReviewPreferences.DEFAULT_NODE_MIN_POST_TIGER_ID) * 1_000_000_000L);

        ConnectedRoadCheck connectedRoadCheck = new ConnectedRoadCheck();
        NodeVersionCheck nodeVersionCheck = new NodeVersionCheck(minAvgVersion, minPercentageEdited,
                additionalBotUsernames, postTigerNodeIdThreshold);
        AddressCheck addressCheck = new AddressCheck(maxAddressDistance);
        NadAddressCheck nadAddressCheck = new NadAddressCheck(maxNadDistance);

        return analyzeWay(way, connectedRoadCheck, nodeVersionCheck, addressCheck, nadAddressCheck,
                connectedRoadCheckEnabled, addressCheckEnabled, nodeVersionCheckEnabled,
                nadCheckEnabled, stripTigerTags);
    }

    private static void analyzeUnreviewedRoad(Way way, List<ReviewResult> results,
            ConnectedRoadCheck connectedRoadCheck,
            NodeVersionCheck nodeVersionCheck,
            AddressCheck addressCheck,
            NadAddressCheck nadAddressCheck,
            boolean connectedRoadCheckEnabled,
            boolean addressCheckEnabled,
            boolean nodeVersionCheckEnabled,
            boolean nadCheckEnabled,
            boolean stripTigerTags) {

        String name = way.get("name");
        boolean hasName = name != null && !name.isEmpty();

        // Gather name evidence (priority: user edit > etymology > address > NAD > connectivity)
        ConnectionType connectionType = ConnectionType.NONE;
        boolean addressMatch = false;
        boolean nadMatch = false;
        boolean etymologyMatch = false;
        boolean userEditMatch = false;
        String nadMatchedName = null; // NAD street name (differs from OSM name for fuzzy matches)
        if (hasName) {
            userEditMatch = NameSnapshotTracker.getInstance().isNameModified(way);
            if (!userEditMatch) {
                etymologyMatch = hasNameEvidenceTags(way);
            }
            if (!userEditMatch && !etymologyMatch && addressCheckEnabled) {
                addressMatch = addressCheck.isNameCorroborated(way, name);
            }
            if (!userEditMatch && !etymologyMatch && !addressMatch && nadCheckEnabled) {
                nadMatchedName = nadAddressCheck.findMatchingName(way, name);
                nadMatch = nadMatchedName != null;
            }
            if (!userEditMatch && !etymologyMatch && !addressMatch && !nadMatch && connectedRoadCheckEnabled) {
                connectionType = connectedRoadCheck.checkConnection(way, name);
            }
        }

        // If road has a name but no evidence found from any source,
        // check if nearby addresses suggest a different name
        String addressSuggestedName = null;
        String nadSuggestedName = null;
        if (hasName && !userEditMatch && !etymologyMatch && connectionType == ConnectionType.NONE
                && !addressMatch && !nadMatch) {
            // Check OSM addr:street data first
            if (addressCheckEnabled) {
                addressSuggestedName = addressCheck.findSuggestedName(way, name);
            }
            // Check NAD data
            if (nadCheckEnabled) {
                nadSuggestedName = nadAddressCheck.findSuggestedName(way, name);
            }
        }

        // Gather alignment evidence
        AlignmentResult alignmentResult = nodeVersionCheckEnabled
                ? nodeVersionCheck.checkAlignment(way)
                : new AlignmentResult(AlignmentEvidence.NONE, 0, 0);
        boolean nameCorroborated = connectionType != ConnectionType.NONE || addressMatch
                || nadMatch || etymologyMatch || userEditMatch;

        // Apply decision matrix
        if (hasName) {
            if (nameCorroborated && alignmentResult.isVerified()) {
                String message = buildFullyVerifiedMessage(connectionType, addressMatch, nadMatch, etymologyMatch,
                        userEditMatch, nadMatchedName, name, alignmentResult);
                int code = getNameVerificationCode(connectionType, addressMatch, nadMatch, etymologyMatch,
                        userEditMatch);
                results.add(new ReviewResult(way, code, message,
                        tr("Fully verified"),
                        FixAction.REMOVE_TAG, stripTigerTags));
            } else if (nameCorroborated) {
                String nameEvidence = buildNameEvidenceMessage(connectionType, addressMatch, nadMatch, etymologyMatch,
                        userEditMatch, nadMatchedName, name);
                int code = getNameVerificationCode(connectionType, addressMatch, nadMatch, etymologyMatch,
                        userEditMatch);
                results.add(new ReviewResult(way, code, nameEvidence,
                        tr("Name verified, alignment needs review"),
                        FixAction.SET_NAME_REVIEWED, stripTigerTags));
            } else if (alignmentResult.isVerified()) {
                String alignmentEvidence = buildAlignmentEvidenceMessage(alignmentResult);
                results.add(new ReviewResult(way, TIGERReviewTest.TIGER_NAME_NOT_CORROBORATED,
                        alignmentEvidence,
                        tr("Alignment verified, name not corroborated"),
                        FixAction.SET_ALIGNMENT_REVIEWED, stripTigerTags));
            }
        } else {
            if (alignmentResult.isVerified()) {
                String alignmentEvidence = buildAlignmentEvidenceMessage(alignmentResult);
                results.add(new ReviewResult(way, TIGERReviewTest.TIGER_UNNAMED_VERIFIED,
                        alignmentEvidence,
                        tr("Unnamed road verified"),
                        FixAction.REMOVE_TAG, stripTigerTags));
            }
        }

        // Address name suggestions — fix renames the way
        addNameSuggestions(way, results, addressSuggestedName, nadSuggestedName);

    }

    private static void analyzeNameReviewedRoad(Way way, List<ReviewResult> results,
            NodeVersionCheck nodeVersionCheck, boolean stripTigerTags) {
        AlignmentResult alignmentResult = nodeVersionCheck.checkAlignment(way);
        if (alignmentResult.isVerified()) {
            String alignmentEvidence = buildAlignmentEvidenceMessage(alignmentResult);
            results.add(new ReviewResult(way, TIGERReviewTest.TIGER_NAME_UPGRADE,
                    alignmentEvidence,
                    tr("Fully verified"),
                    FixAction.REMOVE_TAG, stripTigerTags));
        }
    }

    /**
     * Check if a tiger:reviewed value indicates alignment/position has been reviewed
     * but name still needs verification (aerial, position, alignment).
     */
    private static boolean isAlignmentReviewedValue(String tigerReviewed) {
        return "aerial".equals(tigerReviewed)
                || "position".equals(tigerReviewed)
                || "alignment".equals(tigerReviewed);
    }

    /**
     * Analyze a way where alignment has been reviewed (tiger:reviewed=aerial/position/alignment)
     * but name may still need corroboration. If name evidence is found, the way can be
     * fully verified and the tag removed.
     */
    private static void analyzeAlignmentReviewedRoad(Way way, List<ReviewResult> results,
            ConnectedRoadCheck connectedRoadCheck,
            AddressCheck addressCheck,
            NadAddressCheck nadAddressCheck,
            boolean connectedRoadCheckEnabled,
            boolean addressCheckEnabled,
            boolean nadCheckEnabled,
            boolean stripTigerTags) {

        String name = way.get("name");
        if (name == null || name.isEmpty()) {
            // Unnamed road with alignment verified — can remove tag
            results.add(new ReviewResult(way, TIGERReviewTest.TIGER_UNNAMED_VERIFIED,
                    tr("alignment previously reviewed"),
                    tr("Unnamed road verified"),
                    FixAction.REMOVE_TAG, stripTigerTags));
            return;
        }

        // Look for name corroboration (priority: user edit > etymology > address > NAD > connectivity)
        ConnectionType connectionType = ConnectionType.NONE;
        boolean addressMatch = false;
        boolean nadMatch = false;
        boolean etymologyMatch = false;
        boolean userEditMatch = false;
        String nadMatchedName = null;

        userEditMatch = NameSnapshotTracker.getInstance().isNameModified(way);
        if (!userEditMatch) {
            etymologyMatch = hasNameEvidenceTags(way);
        }
        if (!userEditMatch && !etymologyMatch && addressCheckEnabled) {
            addressMatch = addressCheck.isNameCorroborated(way, name);
        }
        if (!userEditMatch && !etymologyMatch && !addressMatch && nadCheckEnabled) {
            nadMatchedName = nadAddressCheck.findMatchingName(way, name);
            nadMatch = nadMatchedName != null;
        }
        if (!userEditMatch && !etymologyMatch && !addressMatch && !nadMatch && connectedRoadCheckEnabled) {
            connectionType = connectedRoadCheck.checkConnection(way, name);
        }

        boolean nameCorroborated = etymologyMatch || addressMatch || nadMatch
                || connectionType != ConnectionType.NONE || userEditMatch;

        if (nameCorroborated) {
            // Name now corroborated + alignment already reviewed = fully verified
            String nameEvidence = buildNameEvidenceMessage(connectionType, addressMatch, nadMatch, etymologyMatch,
                    userEditMatch, nadMatchedName, name);
            int code = getNameVerificationCode(connectionType, addressMatch, nadMatch, etymologyMatch, userEditMatch);
            results.add(new ReviewResult(way, code, nameEvidence,
                    tr("Fully verified"),
                    FixAction.REMOVE_TAG, stripTigerTags));
        } else if (!etymologyMatch && connectionType == ConnectionType.NONE
                && !addressMatch && !nadMatch && !userEditMatch) {
            // No name evidence at all — check if nearby addresses suggest a different name
            String addrSuggestedName = addressCheckEnabled ? addressCheck.findSuggestedName(way, name) : null;
            String nadSuggestedName2 = nadCheckEnabled ? nadAddressCheck.findSuggestedName(way, name) : null;
            addNameSuggestions(way, results, addrSuggestedName, nadSuggestedName2);
        }
        // If name is not corroborated and no suggestion, no action — alignment is already recorded
    }

    /**
     * Flag a way with an invalid/unrecognized tiger:reviewed value.
     * No automatic fix — user must decide the correct value.
     */
    private static void analyzeInvalidReviewedValue(Way way, String value, List<ReviewResult> results) {
        results.add(new ReviewResult(way, TIGERReviewTest.TIGER_REVIEWED_INVALID_VALUE,
                value,
                tr("Invalid tiger:reviewed value"),
                null, false));
    }

    /**
     * Add name suggestion results. If both OSM addresses and NAD agree on the same
     * suggested name, emit a single combined result; otherwise emit them separately.
     * Suggestions that only add a directional prefix/suffix are categorized separately
     * as "directional upgrades" for easier batch review.
     */
    private static void addNameSuggestions(Way way, List<ReviewResult> results,
            String addressSuggestedName, String nadSuggestedName) {
        String osmName = way.get("name");
        String expandedOsm = StreetNameUtils.expand(osmName);

        if (addressSuggestedName != null && nadSuggestedName != null
                && addressSuggestedName.equalsIgnoreCase(nadSuggestedName)) {
            boolean directional = StreetNameUtils.isDirectionalUpgrade(
                    expandedOsm, StreetNameUtils.expand(addressSuggestedName));
            if (directional) {
                results.add(new ReviewResult(way, TIGERReviewTest.TIGER_COMBINED_DIRECTIONAL_SUGGESTION,
                        addressSuggestedName,
                        tr("NAD and nearby addresses agree on directional upgrade"),
                        FixAction.SUGGEST_NAME, false, addressSuggestedName));
            } else {
                results.add(new ReviewResult(way, TIGERReviewTest.TIGER_COMBINED_NAME_SUGGESTION,
                        addressSuggestedName,
                        tr("NAD and nearby addresses agree on different name"),
                        FixAction.SUGGEST_NAME, false, addressSuggestedName));
            }
        } else {
            if (addressSuggestedName != null) {
                boolean directional = StreetNameUtils.isDirectionalUpgrade(
                        expandedOsm, StreetNameUtils.expand(addressSuggestedName));
                if (directional) {
                    results.add(new ReviewResult(way, TIGERReviewTest.TIGER_ADDRESS_DIRECTIONAL_SUGGESTION,
                            addressSuggestedName,
                            tr("Nearby addresses suggest directional upgrade"),
                            FixAction.SUGGEST_NAME, false, addressSuggestedName));
                } else {
                    results.add(new ReviewResult(way, TIGERReviewTest.TIGER_ADDRESS_NAME_SUGGESTION,
                            addressSuggestedName,
                            tr("Nearby addresses suggest different name"),
                            FixAction.SUGGEST_NAME, false, addressSuggestedName));
                }
            }
            if (nadSuggestedName != null) {
                boolean directional = StreetNameUtils.isDirectionalUpgrade(
                        expandedOsm, StreetNameUtils.expand(nadSuggestedName));
                if (directional) {
                    results.add(new ReviewResult(way, TIGERReviewTest.TIGER_NAD_DIRECTIONAL_SUGGESTION,
                            nadSuggestedName,
                            tr("NAD suggests directional upgrade"),
                            FixAction.SUGGEST_NAME, false, nadSuggestedName));
                } else {
                    results.add(new ReviewResult(way, TIGERReviewTest.TIGER_NAD_NAME_SUGGESTION,
                            nadSuggestedName,
                            tr("NAD suggests different name"),
                            FixAction.SUGGEST_NAME, false, nadSuggestedName));
                }
            }
        }
    }

    /**
     * Analyze a way that has residual tiger:* tags but is already reviewed
     * (tiger:reviewed=yes, or no tiger:reviewed but other tiger:* tags remain).
     */
    private static void analyzeResidualTigerTags(Way way, List<ReviewResult> results) {
        Collection<String> discardable = AbstractPrimitive.getDiscardableKeys();
        String tagList = way.getKeys().keySet().stream()
                .filter(k -> k.startsWith("tiger:") && !discardable.contains(k))
                .sorted()
                .collect(Collectors.joining(", "));
        if (tagList.isEmpty()) {
            return; // Only discardable tiger tags remain — JOSM will drop them on upload
        }
        results.add(new ReviewResult(way, TIGERReviewTest.TIGER_RESIDUAL_TAGS,
                tagList,
                tr("Review completed, residual TIGER tags can be removed"),
                FixAction.REMOVE_TAG, true));
    }

    /**
     * Check if a way has any non-discardable tiger:* tags (other than tiger:reviewed=no
     * or tiger:reviewed=name, which are handled by their own analysis branches).
     * Tags in JOSM's discardable set (e.g. tiger:source, tiger:tlid) are ignored
     * because JOSM silently drops them on upload.
     */
    static boolean hasTigerTags(Way way) {
        Collection<String> discardable = AbstractPrimitive.getDiscardableKeys();
        return way.getKeys().keySet().stream()
                .anyMatch(k -> k.startsWith("tiger:") && !discardable.contains(k));
    }

    /**
     * Tags on the way itself that prove a mapper has independently verified the road's name.
     * These require active human engagement with the road's identity — a mapper would not
     * add etymology or wikidata links without knowing the road's name is correct.
     */
    static boolean hasNameEvidenceTags(Way way) {
        return way.hasKey("name:etymology")
                || way.hasKey("name:etymology:wikidata")
                || way.hasKey("name:etymology:wikipedia")
                || way.hasKey("wikipedia")
                || way.hasKey("wikidata");
    }

    // --- Message building utilities ---

    static String buildNameEvidenceMessage(ConnectionType connectionType, boolean addressMatch, boolean nadMatch,
            boolean etymologyMatch, boolean userEditMatch, String nadMatchedName, String osmName) {
        if (userEditMatch) {
            return tr("name modified by user");
        } else if (etymologyMatch) {
            return tr("etymology/wikidata tags");
        } else if (addressMatch) {
            return tr("nearby addr:street");
        } else if (nadMatch) {
            return tr("NAD address data");
        } else if (connectionType == ConnectionType.BOTH_ENDS) {
            return tr("connected roads at both ends");
        } else if (connectionType == ConnectionType.ONE_END) {
            return tr("connected road");
        }
        return "";
    }

    static String buildAlignmentEvidenceMessage(AlignmentResult result) {
        if (result.getEvidence() == AlignmentEvidence.ALL_NODES_EDITED) {
            return tr("all nodes edited");
        } else if (result.getEvidence() == AlignmentEvidence.HIGH_PERCENTAGE_EDITED) {
            return tr("{0}% of nodes edited", bucketPercentage(result.getPercentageEdited()));
        } else if (result.getEvidence() == AlignmentEvidence.SHARED_NODES_VERIFIED) {
            return tr("both nodes shared with verified roads");
        } else {
            return tr("avg node version {0}", bucketVersion(result.getAvgVersion()));
        }
    }

    static String buildFullyVerifiedMessage(ConnectionType connectionType,
            boolean addressMatch, boolean nadMatch, boolean etymologyMatch,
            boolean userEditMatch, String nadMatchedName, String osmName, AlignmentResult alignmentResult) {
        String nameEvidence = buildNameEvidenceMessage(connectionType, addressMatch, nadMatch, etymologyMatch, userEditMatch, nadMatchedName, osmName);
        String alignmentEvidence = buildAlignmentEvidenceMessage(alignmentResult);
        return tr("name: {0}, alignment: {1}", nameEvidence, alignmentEvidence);
    }

    static String bucketPercentage(double pct) {
        double percent = pct * 100;
        if (percent >= 95) {
            return "95+";
        } else if (percent >= 90) {
            return "90+";
        } else {
            return "80+";
        }
    }

    static String bucketVersion(double version) {
        if (version >= 2.5) {
            return "2.5+";
        }
        int quarters = (int) Math.floor(version * 4);
        int whole = quarters / 4;
        int remainder = quarters % 4;
        switch (remainder) {
        case 0: return whole + ".0";
        case 1: return whole + ".25";
        case 2: return whole + ".5";
        default: return whole + ".75";
        }
    }

    static int getNameVerificationCode(ConnectionType connectionType, boolean addressMatch, boolean nadMatch,
            boolean etymologyMatch, boolean userEditMatch) {
        if (userEditMatch) {
            return TIGERReviewTest.TIGER_NAME_VERIFIED_USER_EDIT;
        } else if (etymologyMatch) {
            return TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY;
        } else if (addressMatch) {
            return TIGERReviewTest.TIGER_NAME_VERIFIED_ADDRESS;
        } else if (nadMatch) {
            return TIGERReviewTest.TIGER_NAME_VERIFIED_NAD;
        } else if (connectionType == ConnectionType.BOTH_ENDS) {
            return TIGERReviewTest.TIGER_NAME_VERIFIED_BOTH_ENDS;
        } else if (connectionType == ConnectionType.ONE_END) {
            return TIGERReviewTest.TIGER_NAME_VERIFIED_ONE_END;
        }
        return TIGERReviewTest.TIGER_NAME_VERIFIED;
    }

    /**
     * Get a human-readable group label for a warning code.
     */
    public static String getGroupLabel(int code) {
        if (code == TIGERReviewTest.TIGER_FULLY_VERIFIED
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_BOTH_ENDS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ONE_END
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ADDRESS
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_NAD
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_ETYMOLOGY
                || code == TIGERReviewTest.TIGER_NAME_VERIFIED_USER_EDIT) {
            // These codes are used for both fully verified and name-only results,
            // so we rely on the groupMessage field instead
            return null;
        } else if (code == TIGERReviewTest.TIGER_NAME_NOT_CORROBORATED) {
            return tr("Alignment verified, name not corroborated");
        } else if (code == TIGERReviewTest.TIGER_UNNAMED_VERIFIED) {
            return tr("Unnamed road verified");
        } else if (code == TIGERReviewTest.TIGER_NAME_UPGRADE) {
            return tr("Fully verified");
        } else if (code == TIGERReviewTest.TIGER_RESIDUAL_TAGS) {
            return tr("Review completed, residual TIGER tags can be removed");
        } else if (code == TIGERReviewTest.TIGER_REVIEWED_INVALID_VALUE) {
            return tr("Invalid tiger:reviewed value");
        } else if (code == TIGERReviewTest.TIGER_COMBINED_NAME_SUGGESTION) {
            return tr("NAD and nearby addresses agree on different name");
        } else if (code == TIGERReviewTest.TIGER_NAD_NAME_SUGGESTION) {
            return tr("NAD suggests different name");
        } else if (code == TIGERReviewTest.TIGER_ADDRESS_NAME_SUGGESTION) {
            return tr("Nearby addresses suggest different name");
        } else if (code == TIGERReviewTest.TIGER_COMBINED_DIRECTIONAL_SUGGESTION) {
            return tr("NAD and nearby addresses agree on directional upgrade");
        } else if (code == TIGERReviewTest.TIGER_NAD_DIRECTIONAL_SUGGESTION) {
            return tr("NAD suggests directional upgrade");
        } else if (code == TIGERReviewTest.TIGER_ADDRESS_DIRECTIONAL_SUGGESTION) {
            return tr("Nearby addresses suggest directional upgrade");
        }
        return null;
    }

    // --- Command creation utilities ---

    static Command createRemoveTagCommand(Way way, boolean stripAllTigerTags) {
        if (stripAllTigerTags) {
            Collection<String> discardable = AbstractPrimitive.getDiscardableKeys();
            List<Command> commands = new ArrayList<>();
            for (String key : way.getKeys().keySet()) {
                if (key.startsWith("tiger:") && !discardable.contains(key)) {
                    commands.add(new ChangePropertyCommand(way, key, null));
                }
            }
            if (commands.isEmpty()) {
                return new ChangePropertyCommand(way, TIGER_REVIEWED, null);
            }
            return SequenceCommand.wrapIfNeeded(tr("Remove TIGER tags"), commands);
        }
        return new ChangePropertyCommand(way, TIGER_REVIEWED, null);
    }
}
