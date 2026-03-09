// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewPreferences;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadAddress;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadQueryResult;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * Background loader for NAD data.
 *
 * Listens for OSM data layer changes and automatically fetches NAD data
 * when a US dataset is loaded (detected by bounding box in US coordinates).
 */
public class NadDataLoader extends AbstractExternalDataLoader {

    /** Singleton instance */
    private static NadDataLoader instance;

    private NadDataLoader() {
        // Singleton
    }

    /**
     * Get the singleton instance and register as layer listener.
     */
    public static synchronized NadDataLoader getInstance() {
        if (instance == null) {
            instance = new NadDataLoader();
        }
        return instance;
    }

    @Override
    protected String getLoaderName() {
        return "NAD data loader";
    }

    @Override
    protected boolean isEnabled() {
        return Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false);
    }

    @Override
    protected void clearCache() {
        NadDataCache.getInstance().clear();
    }

    @Override
    protected boolean isRelevantWay(Way way) {
        // NAD only needs bounds from ways needing review
        return "no".equals(way.get("tiger:reviewed"));
    }

    @Override
    protected void fetchData(Bounds bounds) {
        Logging.info("Starting NAD data fetch for bounds: " + bounds);

        NadClient client = new NadClient();
        NadQueryResult result = client.queryAddresses(bounds);

        if (result.isSuccess()) {
            List<NadAddress> addresses = result.addresses();
            NadDataCache.getInstance().load(addresses, bounds);
            Logging.info("NAD data loaded: " + addresses.size() + " addresses");
        } else {
            NadDataCache.getInstance().setError(result.errorMessage());
            Logging.warn("NAD data load failed: " + result.errorMessage());
        }
    }

    /**
     * Manually trigger NAD data load for current active layer.
     * Can be called from UI if user wants to refresh.
     */
    public void refreshNadData() {
        OsmDataLayer activeLayer = MainApplication.getLayerManager().getActiveDataLayer();
        if (activeLayer != null && activeLayer.getDataSet() != null) {
            loadForDataSet(activeLayer.getDataSet());
        }
    }
}
