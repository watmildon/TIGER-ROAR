// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import java.awt.BorderLayout;

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

    /** Default maximum distance for address matching (meters) */
    public static final double DEFAULT_ADDRESS_MAX_DISTANCE = 50.0;

    /** Default minimum average node version for alignment verification */
    public static final double DEFAULT_NODE_MIN_AVG_VERSION = 1.5;

    private JSpinner addressDistanceSpinner;
    private JSpinner nodeVersionSpinner;

    public TIGERReviewPreferences() {
        super("preferences/tiger_review", tr("TIGER Review"), tr("Settings for TIGER Review validator"));
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Address matching distance
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel(tr("Maximum address matching distance (meters):")), gbc);

        gbc.gridx = 1;
        double currentDistance = Config.getPref().getDouble(PREF_ADDRESS_MAX_DISTANCE, DEFAULT_ADDRESS_MAX_DISTANCE);
        addressDistanceSpinner = new JSpinner(new SpinnerNumberModel(currentDistance, 10.0, 500.0, 10.0));
        panel.add(addressDistanceSpinner, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1.0;
        panel.add(new JLabel(tr("(default: {0})", DEFAULT_ADDRESS_MAX_DISTANCE)), gbc);
        gbc.weightx = 0;

        // Node version threshold
        gbc.gridx = 0;
        gbc.gridy = 1;
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

        // Explanatory text
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(20, 5, 5, 5);
        JLabel explanation = new JLabel("<html><body style='width: 400px'>" +
                tr("<b>Address matching distance:</b> When checking if a road name is corroborated " +
                   "by nearby addresses, only consider addresses within this distance of the road.") +
                "<br><br>" +
                tr("<b>Node version threshold:</b> Roads with average node version above this value " +
                   "are considered to have verified alignment (nodes have been moved/edited).") +
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
        Config.getPref().putDouble(PREF_ADDRESS_MAX_DISTANCE, (Double) addressDistanceSpinner.getValue());
        Config.getPref().putDouble(PREF_NODE_MIN_AVG_VERSION, (Double) nodeVersionSpinner.getValue());
        return false; // No restart required
    }
}
