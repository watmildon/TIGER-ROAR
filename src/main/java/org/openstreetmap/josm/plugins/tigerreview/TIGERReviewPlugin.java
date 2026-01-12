package org.openstreetmap.josm.plugins.tigerreview;

import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.data.validation.OsmValidator;

public class TIGERReviewPlugin extends Plugin {
    public TIGERReviewPlugin(PluginInformation info) {
        super(info);
        OsmValidator.addTest(HighwayTest.class);
    }
}
