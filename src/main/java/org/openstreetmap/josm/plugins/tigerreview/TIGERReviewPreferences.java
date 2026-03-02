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

    /** Preference key for stripping all tiger:* tags on fully verified roads */
    public static final String PREF_STRIP_TIGER_TAGS = "tigerreview.fix.stripTigerTags";

    /** Default maximum distance for NAD address matching (meters) */
    public static final double DEFAULT_NAD_MAX_DISTANCE = 50.0;

    /** Default maximum distance for address matching (meters) */
    public static final double DEFAULT_ADDRESS_MAX_DISTANCE = 50.0;

    /** Default minimum average node version for alignment verification */
    public static final double DEFAULT_NODE_MIN_AVG_VERSION = 1.5;

    /** Default minimum percentage of nodes edited for alignment verification (0.0-1.0) */
    public static final double DEFAULT_NODE_MIN_PERCENTAGE_EDITED = 0.8;

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

    public TIGERReviewPreferences() {
        super("preferences/tiger_review", tr("TIGER ROAR"), tr("Settings for TIGER ROAR plugin"));
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // === Enable/Disable Checks Section ===
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 3;
        JLabel checksLabel = new JLabel(tr("Enable/Disable Checks:"));
        checksLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        panel.add(checksLabel, gbc);

        // Connected road check
        gbc.gridy = row++;
        gbc.gridwidth = 3;
        connectedRoadCheckBox = new JCheckBox(tr("Connected road check (name corroboration via connected roads)"));
        connectedRoadCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_CONNECTED_ROAD_CHECK, true));
        panel.add(connectedRoadCheckBox, gbc);

        // Address check
        gbc.gridy = row++;
        addressCheckBox = new JCheckBox(tr("Address check (name corroboration via nearby addr:street)"));
        addressCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_ADDRESS_CHECK, true));
        panel.add(addressCheckBox, gbc);

        // Node version check
        gbc.gridy = row++;
        nodeVersionCheckBox = new JCheckBox(tr("Node version check (alignment verification via node versions)"));
        nodeVersionCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_NODE_VERSION_CHECK, true));
        panel.add(nodeVersionCheckBox, gbc);

        // NAD check
        gbc.gridy = row++;
        nadCheckBox = new JCheckBox(tr("NAD check (name corroboration via National Address Database - US only)"));
        nadCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_NAD_CHECK, false));
        panel.add(nadCheckBox, gbc);

        // === Fix Behavior Section ===
        gbc.gridy = row++;
        gbc.gridwidth = 3;
        gbc.insets = new Insets(15, 5, 5, 5);
        JLabel fixLabel = new JLabel(tr("Fix Behavior:"));
        fixLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        panel.add(fixLabel, gbc);

        gbc.insets = new Insets(5, 5, 5, 5);

        // Strip all tiger: tags
        gbc.gridy = row++;
        stripTigerTagsCheckBox = new JCheckBox(tr("Strip all tiger:* tags when fully verified (not just tiger:reviewed)"));
        stripTigerTagsCheckBox.setSelected(Config.getPref().getBoolean(PREF_STRIP_TIGER_TAGS, true));
        panel.add(stripTigerTagsCheckBox, gbc);

        // === Parameters Section ===
        gbc.gridy = row++;
        gbc.gridwidth = 3;
        gbc.insets = new Insets(15, 5, 5, 5);
        JLabel paramsLabel = new JLabel(tr("Check Parameters:"));
        paramsLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        panel.add(paramsLabel, gbc);

        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.gridwidth = 1;

        // Address matching distance
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(tr("Maximum address matching distance (meters):")), gbc);

        gbc.gridx = 1;
        double currentDistance = Config.getPref().getDouble(PREF_ADDRESS_MAX_DISTANCE, DEFAULT_ADDRESS_MAX_DISTANCE);
        addressDistanceSpinner = new JSpinner(new SpinnerNumberModel(currentDistance, 10.0, 500.0, 10.0));
        panel.add(addressDistanceSpinner, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1.0;
        panel.add(new JLabel(tr("(default: {0})", DEFAULT_ADDRESS_MAX_DISTANCE)), gbc);
        gbc.weightx = 0;

        row++;

        // Node version threshold
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(tr("Minimum average node version for alignment verification:")), gbc);

        gbc.gridx = 1;
        double currentVersion = Config.getPref().getDouble(PREF_NODE_MIN_AVG_VERSION, DEFAULT_NODE_MIN_AVG_VERSION);
        nodeVersionSpinner = new JSpinner(new SpinnerNumberModel(currentVersion, 1.0, 5.0, 0.1));
        nodeVersionSpinner.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        panel.add(nodeVersionSpinner, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1.0;
        panel.add(new JLabel(tr("(default: {0})", DEFAULT_NODE_MIN_AVG_VERSION)), gbc);
        gbc.weightx = 0;

        row++;

        // Node percentage edited threshold
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(tr("Minimum percentage of nodes edited (0-100%):")), gbc);

        gbc.gridx = 1;
        double currentPercentage = Config.getPref().getDouble(PREF_NODE_MIN_PERCENTAGE_EDITED, DEFAULT_NODE_MIN_PERCENTAGE_EDITED);
        // Display as percentage (0-100) but store as decimal (0.0-1.0)
        nodePercentageSpinner = new JSpinner(new SpinnerNumberModel(currentPercentage * 100, 0.0, 100.0, 5.0));
        nodePercentageSpinner.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        panel.add(nodePercentageSpinner, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1.0;
        panel.add(new JLabel(tr("(default: {0}%)", (int)(DEFAULT_NODE_MIN_PERCENTAGE_EDITED * 100))), gbc);
        gbc.weightx = 0;

        row++;

        // NAD address matching distance
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(tr("NAD address matching distance (meters):")), gbc);

        gbc.gridx = 1;
        double currentNadDistance = Config.getPref().getDouble(PREF_NAD_MAX_DISTANCE, DEFAULT_NAD_MAX_DISTANCE);
        nadDistanceSpinner = new JSpinner(new SpinnerNumberModel(currentNadDistance, 10.0, 500.0, 10.0));
        nadDistanceSpinner.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        panel.add(nadDistanceSpinner, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1.0;
        panel.add(new JLabel(tr("(default: {0})", DEFAULT_NAD_MAX_DISTANCE)), gbc);
        gbc.weightx = 0;

        row++;

        // Additional bot usernames
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(tr("Additional bot/importer usernames:")), gbc);

        gbc.gridx = 1;
        String currentBotUsernames = Config.getPref().get(PREF_ADDITIONAL_BOT_USERNAMES, "");
        additionalBotUsernamesField = new JTextField(currentBotUsernames, 20);
        additionalBotUsernamesField.setPreferredSize(addressDistanceSpinner.getPreferredSize());
        panel.add(additionalBotUsernamesField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1.0;
        panel.add(new JLabel(tr("(semicolon-separated)")), gbc);
        gbc.weightx = 0;

        row++;

        // Explanatory text
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(20, 5, 5, 5);
        JLabel explanation = new JLabel("<html><body style='width: 400px'>" +
                tr("<b>Address matching distance:</b> When checking if a road name is corroborated " +
                   "by nearby addresses, only consider addresses within this distance of the road.") +
                "<br><br>" +
                tr("<b>Node version threshold:</b> Roads with average node version above this value " +
                   "are considered to have verified alignment (nodes have been moved/edited).") +
                "<br><br>" +
                tr("<b>Percentage of nodes edited:</b> Roads where at least this percentage of nodes " +
                   "have been edited (version > 1) are considered to have verified alignment.") +
                "<br><br>" +
                tr("<b>NAD check:</b> When enabled, downloads address data from the National Address Database " +
                   "for US areas and uses it to corroborate road names. Data is fetched in the background " +
                   "when a US dataset is loaded.") +
                "<br><br>" +
                tr("<b>Additional bot usernames:</b> Semicolon-separated list of additional usernames to treat " +
                   "as bots/importers. Nodes last edited by these users won''t count toward alignment verification. " +
                   "Built-in: DaveHansenTiger, Milenko, woodpeck_fixbot, balrog-kun, bot-mode.") +
                "<br><br>" +
                tr("<b>Strip all tiger:* tags:</b> When enabled, fixing a fully verified road removes all tags " +
                   "starting with ''tiger:'' (e.g. tiger:cfcc, tiger:county, tiger:name_base), not just tiger:reviewed. " +
                   "These tags are remnants of the original TIGER import and are generally no longer needed.") +
                "</body></html>");
        panel.add(explanation, gbc);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        GridBagConstraints tabConstraints = new GridBagConstraints();
        tabConstraints.fill = GridBagConstraints.BOTH;
        tabConstraints.weightx = 1.0;
        tabConstraints.weighty = 1.0;
        gui.createPreferenceTab(this).add(wrapper, tabConstraints);
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
        return false; // No restart required
    }
}
