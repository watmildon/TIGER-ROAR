package org.openstreetmap.josm.plugins.tigerreview;

import org.openstreetmap.josm.data.validation.OsmValidator;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.tigerreview.external.NadDataLoader;

/**
 * TIGER Review plugin for JOSM.
 *
 * Helps mappers review TIGER-imported roadways by providing validator warnings
 * and one-click fixes based on corroborating evidence from connected roads
 * and nearby address data.
 */
public class TIGERReviewPlugin extends Plugin {

    public TIGERReviewPlugin(PluginInformation info) {
        super(info);
        OsmValidator.addTest(TIGERReviewTest.class);

        // Register NAD data loader to listen for layer changes
        NadDataLoader.getInstance().register();
    }

    @Override
    public PreferenceSetting getPreferenceSetting() {
        return new TIGERReviewPreferences();
    }
}
