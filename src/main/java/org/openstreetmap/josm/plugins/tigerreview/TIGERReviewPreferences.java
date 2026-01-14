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

    /** Preference key for enabling surface check */
    public static final String PREF_ENABLE_SURFACE_CHECK = "tigerreview.check.surface";

    /** Preference key for minimum percentage of nodes edited threshold */
    public static final String PREF_NODE_MIN_PERCENTAGE_EDITED = "tigerreview.node.minPercentageEdited";

    /** Default maximum distance for address matching (meters) */
    public static final double DEFAULT_ADDRESS_MAX_DISTANCE = 50.0;

    /** Default minimum average node version for alignment verification */
    public static final double DEFAULT_NODE_MIN_AVG_VERSION = 1.5;

    /** Default minimum percentage of nodes edited for alignment verification (0.0-1.0) */
    public static final double DEFAULT_NODE_MIN_PERCENTAGE_EDITED = 0.8;

    private JSpinner addressDistanceSpinner;
    private JSpinner nodeVersionSpinner;
    private JSpinner nodePercentageSpinner;
    private JCheckBox connectedRoadCheckBox;
    private JCheckBox addressCheckBox;
    private JCheckBox nodeVersionCheckBox;
    private JCheckBox surfaceCheckBox;

    public TIGERReviewPreferences() {
        super("preferences/tiger_review", tr("TIGER Review"), tr("Settings for TIGER Review validator"));
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

        // Surface check
        gbc.gridy = row++;
        surfaceCheckBox = new JCheckBox(tr("Surface check (suggest surface from connected roads)"));
        surfaceCheckBox.setSelected(Config.getPref().getBoolean(PREF_ENABLE_SURFACE_CHECK, true));
        panel.add(surfaceCheckBox, gbc);

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
        Config.getPref().putBoolean(PREF_ENABLE_SURFACE_CHECK, surfaceCheckBox.isSelected());

        // Save parameter settings
        Config.getPref().putDouble(PREF_ADDRESS_MAX_DISTANCE, (Double) addressDistanceSpinner.getValue());
        Config.getPref().putDouble(PREF_NODE_MIN_AVG_VERSION, (Double) nodeVersionSpinner.getValue());
        // Convert percentage (0-100) back to decimal (0.0-1.0) for storage
        Config.getPref().putDouble(PREF_NODE_MIN_PERCENTAGE_EDITED, (Double) nodePercentageSpinner.getValue() / 100.0);
        return false; // No restart required
    }
}
