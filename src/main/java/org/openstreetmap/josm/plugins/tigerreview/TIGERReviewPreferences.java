// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Preferences panel for TIGER Review plugin settings.
 */
public class TIGERReviewPreferences extends DefaultTabPreferenceSetting {

    /** Preference key for maximum address matching distance */
    public static final String PREF_ADDRESS_MAX_DISTANCE = "tigerreview.address.maxDistance";

    /** Preference key for minimum average node version threshold */
    public static final String PREF_NODE_MIN_AVG_VERSION = "tigerreview.node.minAvgVersion";

    /** Preference key for enabling connected road check */
    public static final String PREF_ENABLE_CONNECTED_ROAD_CHECK = "tigerreview.check.connectedRoad";

    /** Preference key for enabling address check */
    public static final String PREF_ENABLE_ADDRESS_CHECK = "tigerreview.check.address";

    /** Preference key for enabling node version check */
    public static final String PREF_ENABLE_NODE_VERSION_CHECK = "tigerreview.check.nodeVersion";

    /** Preference key for enabling NAD (National Address Database) check */
    public static final String PREF_ENABLE_NAD_CHECK = "tigerreview.check.nad";

    /** Preference key for minimum percentage of nodes edited threshold */
    public static final String PREF_NODE_MIN_PERCENTAGE_EDITED = "tigerreview.node.minPercentageEdited";

    /** Preference key for NAD maximum address matching distance */
    public static final String PREF_NAD_MAX_DISTANCE = "tigerreview.nad.maxDistance";

    /** Preference key for additional bot/importer usernames (semicolon-delimited) */
    public static final String PREF_ADDITIONAL_BOT_USERNAMES = "tigerreview.node.additionalBotUsernames";

    /** Preference key for minimum node ID to consider post-TIGER (in billions) */
    public static final String PREF_NODE_MIN_POST_TIGER_ID = "tigerreview.node.minPostTigerId";

    /** Preference key for stripping all tiger:* tags on fully verified roads */
    public static final String PREF_STRIP_TIGER_TAGS = "tigerreview.fix.stripTigerTags";

    /** Preference key for enabling Mapillary data download (master toggle) */
    public static final String PREF_ENABLE_MAPILLARY_CHECK = "tigerreview.check.mapillary";

    /** Preference key for enabling Mapillary speed limit analysis */
    public static final String PREF_ENABLE_MAPILLARY_SPEED = "tigerreview.check.mapillary.speed";

    /** Preference key for Mapillary API token */
    public static final String PREF_MAPILLARY_API_KEY = "tigerreview.mapillary.apiKey";

    /** Preference key for Mapillary sign-to-way matching distance */
    public static final String PREF_MAPILLARY_MAX_DISTANCE = "tigerreview.mapillary.maxDistance";

    /** Default maximum distance for Mapillary sign-to-way matching (meters) */
    public static final double DEFAULT_MAPILLARY_MAX_DISTANCE = 25.0;

    /** Default maximum distance for NAD address matching (meters) */
    public static final double DEFAULT_NAD_MAX_DISTANCE = 50.0;

    /** Default maximum distance for address matching (meters) */
    public static final double DEFAULT_ADDRESS_MAX_DISTANCE = 50.0;

    /** Default minimum average node version for alignment verification */
    public static final double DEFAULT_NODE_MIN_AVG_VERSION = 1.5;

    /** Default minimum percentage of nodes edited for alignment verification (0.0-1.0) */
    public static final double DEFAULT_NODE_MIN_PERCENTAGE_EDITED = 0.8;

    /** Default minimum node ID (in billions) to consider post-TIGER. 8B ≈ 2021. */
    public static final double DEFAULT_NODE_MIN_POST_TIGER_ID = 8.0;

    /** HTML snippet for a circled info icon rendered inline in Swing labels */
    private static final String INFO_ICON_HTML =
            "<span style='color: #336699; font-size: 12px;'>\u24D8</span>";

    private JSpinner addressDistanceSpinner;
    private JSpinner nodeVersionSpinner;
    private JSpinner nodePercentageSpinner;
    private JSpinner nadDistanceSpinner;
    private JCheckBox connectedRoadCheckBox;
    private JCheckBox addressCheckBox;
    private JCheckBox nodeVersionCheckBox;
    private JCheckBox nadCheckBox;
    private JCheckBox stripTigerTagsCheckBox;
    private JTextField additionalBotUsernamesField;
    private JSpinner postTigerIdSpinner;
    private JCheckBox mapillaryCheckBox;
    private JCheckBox mapillarySpeedCheckBox;
    private JTextField mapillaryApiKeyField;
    private JSpinner mapillaryDistanceSpinner;

    public TIGERReviewPreferences() {
        super("preferences/tiger_review", tr("TIGER ROAR"), tr("Settings for TIGER ROAR plugin"));
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        JPanel outerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints outerGbc = new GridBagConstraints();
        outerGbc.gridx = 0;
        outerGbc.fill = GridBagConstraints.HORIZONTAL;
        outerGbc.weightx = 1.0;
        outerGbc.insets = new Insets(2, 0, 2, 0);

        // === Name Verification Section ===
        JPanel namePanel = new JPanel(new GridBagLayout());
        namePanel.setBorder(BorderFactory.createTitledBorder(tr("Name Verification")));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        connectedRoadCheckBox = new JCheckBox(tr("Connected road check"));
        connectedRoadCheckBox.setToolTipText(
                tr("Corroborate road names using connected roads that share an endpoint and are not tiger:reviewed=no"));
        connectedRoadCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_CONNECTED_ROAD_CHECK, true));
        addCheckBox(namePanel, gbc, row++, connectedRoadCheckBox);

        addressCheckBox = new JCheckBox(tr("Address check"));
        addressCheckBox.setToolTipText(
                tr("Corroborate road names using nearby addr:street tags within the matching distance"));
        addressCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_ADDRESS_CHECK, true));
        addCheckBox(namePanel, gbc, row++, addressCheckBox);

        nadCheckBox = new JCheckBox(tr("NAD check (US only)"));
        nadCheckBox.setToolTipText(
                tr("Download addresses from the National Address Database for US areas. Must be enabled before downloading data."));
        nadCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_NAD_CHECK, false));
        addCheckBox(namePanel, gbc, row++, nadCheckBox);

        double currentDistance = Config.getPref().getDouble(PREF_ADDRESS_MAX_DISTANCE, DEFAULT_ADDRESS_MAX_DISTANCE);
        addressDistanceSpinner = new JSpinner(new SpinnerNumberModel(currentDistance, 10.0, 500.0, 10.0));
        addLabeledRow(namePanel, gbc, row++,
                tr("Address matching distance (m):"), addressDistanceSpinner,
                tr("(default: {0})", DEFAULT_ADDRESS_MAX_DISTANCE),
                tr("Maximum distance to search for corroborating addr:street tags"));

        double currentNadDistance = Config.getPref().getDouble(PREF_NAD_MAX_DISTANCE, DEFAULT_NAD_MAX_DISTANCE);
        nadDistanceSpinner = new JSpinner(new SpinnerNumberModel(currentNadDistance, 10.0, 500.0, 10.0));
        nadDistanceSpinner.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        addLabeledRow(namePanel, gbc, row++,
                tr("NAD matching distance (m):"), nadDistanceSpinner,
                tr("(default: {0})", DEFAULT_NAD_MAX_DISTANCE),
                tr("Maximum distance to search for corroborating NAD addresses"));

        outerGbc.gridy = 0;
        outerPanel.add(namePanel, outerGbc);

        // === Alignment Verification Section ===
        JPanel alignmentPanel = new JPanel(new GridBagLayout());
        alignmentPanel.setBorder(BorderFactory.createTitledBorder(tr("Alignment Verification")));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        row = 0;

        nodeVersionCheckBox = new JCheckBox(tr("Node version check"));
        nodeVersionCheckBox.setToolTipText(
                tr("Verify alignment by checking if nodes were edited by humans (not bots/importers)"));
        nodeVersionCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_NODE_VERSION_CHECK, true));
        addCheckBox(alignmentPanel, gbc, row++, nodeVersionCheckBox);

        double currentVersion = Config.getPref().getDouble(PREF_NODE_MIN_AVG_VERSION, DEFAULT_NODE_MIN_AVG_VERSION);
        nodeVersionSpinner = new JSpinner(new SpinnerNumberModel(currentVersion, 1.0, 5.0, 0.1));
        nodeVersionSpinner.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        addLabeledRow(alignmentPanel, gbc, row++,
                tr("Min average node version:"), nodeVersionSpinner,
                tr("(default: {0})", DEFAULT_NODE_MIN_AVG_VERSION),
                tr("Roads with average node version above this are considered alignment-verified"));

        double currentPercentage = Config.getPref().getDouble(PREF_NODE_MIN_PERCENTAGE_EDITED, DEFAULT_NODE_MIN_PERCENTAGE_EDITED);
        // Display as percentage (0-100) but store as decimal (0.0-1.0)
        nodePercentageSpinner = new JSpinner(new SpinnerNumberModel(currentPercentage * 100, 0.0, 100.0, 5.0));
        nodePercentageSpinner.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        addLabeledRow(alignmentPanel, gbc, row++,
                tr("Min percentage of nodes edited:"), nodePercentageSpinner,
                tr("(default: {0}%)", (int) (DEFAULT_NODE_MIN_PERCENTAGE_EDITED * 100)),
                tr("Minimum percentage of nodes that must have been edited by humans"));

        String currentBotUsernames = Config.getPref().get(PREF_ADDITIONAL_BOT_USERNAMES, "");
        additionalBotUsernamesField = new JTextField(currentBotUsernames, 20);
        additionalBotUsernamesField.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        addLabeledRow(alignmentPanel, gbc, row++,
                tr("Additional bot usernames:"), additionalBotUsernamesField,
                tr("(semicolon-separated)"),
                tr("Edits by these users don''t count as human review. "
                   + "Built-in: DaveHansenTiger, Milenko, woodpeck_fixbot, balrog-kun, bot-mode"));

        double currentPostTigerId = Config.getPref().getDouble(PREF_NODE_MIN_POST_TIGER_ID, DEFAULT_NODE_MIN_POST_TIGER_ID);
        postTigerIdSpinner = new JSpinner(new SpinnerNumberModel(currentPostTigerId, 1.0, 15.0, 1.0));
        postTigerIdSpinner.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        addLabeledRow(alignmentPanel, gbc, row++,
                tr("Post-TIGER node ID (billions):"), postTigerIdSpinner,
                tr("(default: {0}B \u2248 2021)", (int) DEFAULT_NODE_MIN_POST_TIGER_ID),
                tr("Nodes with IDs above this threshold (in billions) are assumed to be human-created, "
                   + "even at version 1. OSM assigns IDs sequentially; 8B was reached around 2021."));

        outerGbc.gridy = 1;
        outerPanel.add(alignmentPanel, outerGbc);

        // === Fix Behavior Section ===
        JPanel fixPanel = new JPanel(new GridBagLayout());
        fixPanel.setBorder(BorderFactory.createTitledBorder(tr("Fix Behavior")));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;

        stripTigerTagsCheckBox = new JCheckBox(tr("Strip all tiger:* tags when fully verified"));
        stripTigerTagsCheckBox.setToolTipText(
                tr("Remove all tiger:* tags (cfcc, county, name_base, etc.) when fixing, not just tiger:reviewed"));
        stripTigerTagsCheckBox.setSelected(Config.getPref().getBoolean(PREF_STRIP_TIGER_TAGS, true));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        fixPanel.add(stripTigerTagsCheckBox, gbc);
        gbc.weightx = 0;

        outerGbc.gridy = 2;
        outerPanel.add(fixPanel, outerGbc);

        // === Mapillary (US only) Section ===
        JPanel mapillaryPanel = new JPanel(new GridBagLayout());
        mapillaryPanel.setBorder(BorderFactory.createTitledBorder(tr("Mapillary (US only)")));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;
        row = 0;

        mapillaryCheckBox = new JCheckBox(tr("Enable Mapillary data download"));
        mapillaryCheckBox.setToolTipText(
                tr("Download detections from Mapillary for US areas. Must be enabled before downloading data."));
        mapillaryCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_MAPILLARY_CHECK, false));
        addCheckBox(mapillaryPanel, gbc, row++, mapillaryCheckBox);

        mapillarySpeedCheckBox = new JCheckBox(tr("Speed limit signs"));
        mapillarySpeedCheckBox.setToolTipText(
                tr("Use Mapillary speed limit sign detections to suggest or verify maxspeed tags"));
        mapillarySpeedCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_MAPILLARY_SPEED, true));
        mapillarySpeedCheckBox.setEnabled(mapillaryCheckBox.isSelected());
        addIndentedCheckBox(mapillaryPanel, gbc, row++, mapillarySpeedCheckBox);

        // Enable/disable sub-checkboxes when master toggle changes
        mapillaryCheckBox.addActionListener(e -> {
            boolean enabled = mapillaryCheckBox.isSelected();
            mapillarySpeedCheckBox.setEnabled(enabled);
        });

        String currentApiKey = Config.getPref().get(PREF_MAPILLARY_API_KEY, "");
        mapillaryApiKeyField = new JTextField(currentApiKey, 20);
        mapillaryApiKeyField.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        addLabeledRow(mapillaryPanel, gbc, row++,
                tr("API token:"), mapillaryApiKeyField,
                "",
                tr("Mapillary client token from mapillary.com/dashboard/developers. Stored in JOSM preferences file."));

        double currentMapillaryDistance = Config.getPref().getDouble(
                PREF_MAPILLARY_MAX_DISTANCE, DEFAULT_MAPILLARY_MAX_DISTANCE);
        mapillaryDistanceSpinner = new JSpinner(
                new SpinnerNumberModel(currentMapillaryDistance, 5.0, 100.0, 5.0));
        mapillaryDistanceSpinner.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        addLabeledRow(mapillaryPanel, gbc, row++,
                tr("Matching distance (m):"), mapillaryDistanceSpinner,
                tr("(default: {0})", DEFAULT_MAPILLARY_MAX_DISTANCE),
                tr("Maximum distance to match a Mapillary detection to a road"));

        outerGbc.gridy = 3;
        outerPanel.add(mapillaryPanel, outerGbc);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(outerPanel, BorderLayout.NORTH);
        GridBagConstraints tabConstraints = new GridBagConstraints();
        tabConstraints.fill = GridBagConstraints.BOTH;
        tabConstraints.weightx = 1.0;
        tabConstraints.weighty = 1.0;
        gui.createPreferenceTab(this).add(wrapper, tabConstraints);
    }

    private static void addCheckBox(JPanel panel, GridBagConstraints gbc, int row, JCheckBox checkBox) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 4;
        panel.add(checkBox, gbc);
        gbc.gridwidth = 1;
    }

    private static void addIndentedCheckBox(JPanel panel, GridBagConstraints gbc, int row, JCheckBox checkBox) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 4;
        Insets original = gbc.insets;
        gbc.insets = new Insets(original.top, original.left + 20, original.bottom, original.right);
        panel.add(checkBox, gbc);
        gbc.insets = original;
        gbc.gridwidth = 1;
    }

    private static void addLabeledRow(JPanel panel, GridBagConstraints gbc, int row,
            String labelText, javax.swing.JComponent field, String defaultText, String tooltip) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel(labelText), gbc);

        gbc.gridx = 1;
        panel.add(field, gbc);

        gbc.gridx = 2;
        panel.add(new JLabel(defaultText), gbc);

        gbc.gridx = 3;
        gbc.weightx = 1.0;
        JLabel info = new JLabel("<html>" + INFO_ICON_HTML + "</html>");
        info.setToolTipText(tooltip);
        info.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        panel.add(info, gbc);
        gbc.weightx = 0;
    }

    @Override
    public boolean ok() {
        // Save check enable/disable settings
        Config.getPref().putBoolean(PREF_ENABLE_CONNECTED_ROAD_CHECK, connectedRoadCheckBox.isSelected());
        Config.getPref().putBoolean(PREF_ENABLE_ADDRESS_CHECK, addressCheckBox.isSelected());
        Config.getPref().putBoolean(PREF_ENABLE_NODE_VERSION_CHECK, nodeVersionCheckBox.isSelected());
        Config.getPref().putBoolean(PREF_ENABLE_NAD_CHECK, nadCheckBox.isSelected());
        Config.getPref().putBoolean(PREF_STRIP_TIGER_TAGS, stripTigerTagsCheckBox.isSelected());

        // Save parameter settings
        Config.getPref().putDouble(PREF_ADDRESS_MAX_DISTANCE, (Double) addressDistanceSpinner.getValue());
        Config.getPref().putDouble(PREF_NODE_MIN_AVG_VERSION, (Double) nodeVersionSpinner.getValue());
        // Convert percentage (0-100) back to decimal (0.0-1.0) for storage
        Config.getPref().putDouble(PREF_NODE_MIN_PERCENTAGE_EDITED, (Double) nodePercentageSpinner.getValue() / 100.0);
        Config.getPref().putDouble(PREF_NAD_MAX_DISTANCE, (Double) nadDistanceSpinner.getValue());
        Config.getPref().put(PREF_ADDITIONAL_BOT_USERNAMES, additionalBotUsernamesField.getText().trim());
        Config.getPref().putDouble(PREF_NODE_MIN_POST_TIGER_ID, (Double) postTigerIdSpinner.getValue());

        // Save Mapillary settings
        Config.getPref().putBoolean(PREF_ENABLE_MAPILLARY_CHECK, mapillaryCheckBox.isSelected());
        Config.getPref().putBoolean(PREF_ENABLE_MAPILLARY_SPEED, mapillarySpeedCheckBox.isSelected());
        Config.getPref().put(PREF_MAPILLARY_API_KEY, mapillaryApiKeyField.getText().trim());
        Config.getPref().putDouble(PREF_MAPILLARY_MAX_DISTANCE, (Double) mapillaryDistanceSpinner.getValue());

        return false; // No restart required
    }
}
