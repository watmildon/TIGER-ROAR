// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.util.List;

import javax.swing.SwingWorker;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.LayerManager;
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
public class NadDataLoader implements LayerManager.LayerChangeListener {

    /** US bounding box (approximate continental US + Alaska + Hawaii) */
    private static final double US_MIN_LAT = 18.0;  // Hawaii
    private static final double US_MAX_LAT = 72.0;  // Alaska
    private static final double US_MIN_LON = -180.0; // Alaska (crosses dateline)
    private static final double US_MAX_LON = -66.0;  // Maine

    /** Singleton instance */
    private static NadDataLoader instance;

    /** Currently running loader task */
    private SwingWorker<Void, Void> currentTask;

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

    /**
     * Register this loader as a layer change listener.
     */
    public void register() {
        MainApplication.getLayerManager().addLayerChangeListener(this);
        Logging.info("NAD data loader registered");
    }

    /**
     * Unregister this loader.
     */
    public void unregister() {
        MainApplication.getLayerManager().removeLayerChangeListener(this);
        cancelCurrentTask();
    }

    @Override
    public void layerAdded(LayerManager.LayerAddEvent e) {
        Layer layer = e.getAddedLayer();
        if (layer instanceof OsmDataLayer osmLayer) {
            checkAndLoadNadData(osmLayer);
        }
    }

    @Override
    public void layerRemoving(LayerManager.LayerRemoveEvent e) {
        // Clear cache when all OSM layers are removed
        if (e.getRemovedLayer() instanceof OsmDataLayer) {
            boolean hasOsmLayers = MainApplication.getLayerManager().getLayers().stream()
                    .anyMatch(l -> l instanceof OsmDataLayer && l != e.getRemovedLayer());
            if (!hasOsmLayers) {
                NadDataCache.getInstance().clear();
                Logging.info("NAD cache cleared - no OSM layers remaining");
            }
        }
    }

    @Override
    public void layerOrderChanged(LayerManager.LayerOrderChangeEvent e) {
        // Not relevant for NAD loading
    }

    /**
     * Check if the layer contains US data and load NAD data if enabled.
     */
    private void checkAndLoadNadData(OsmDataLayer layer) {
        // Check if NAD check is enabled
        if (!Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_NAD_CHECK, false)) {
            Logging.debug("NAD check disabled, skipping NAD data load");
            return;
        }

        DataSet dataSet = layer.getDataSet();
        if (dataSet == null) {
            return;
        }

        // Get the bounds of the data
        Bounds bounds = null;
        for (Bounds b : dataSet.getDataSourceBounds()) {
            if (bounds == null) {
                bounds = new Bounds(b);
            } else {
                bounds.extend(b);
            }
        }

        if (bounds == null || bounds.getArea() == 0) {
            Logging.debug("Cannot determine bounds for NAD data load");
            return;
        }

        // Check if bounds are in the US
        if (!isInUS(bounds)) {
            Logging.debug("Data bounds not in US, skipping NAD data load");
            return;
        }

        // Start background load
        loadNadDataAsync(bounds);
    }

    /**
     * Check if bounds are within the US.
     */
    private boolean isInUS(Bounds bounds) {
        double centerLat = bounds.getCenter().lat();
        double centerLon = bounds.getCenter().lon();

        // Check if center is in continental US, Alaska, or Hawaii
        boolean inLatRange = centerLat >= US_MIN_LAT && centerLat <= US_MAX_LAT;
        boolean inLonRange = centerLon >= US_MIN_LON && centerLon <= US_MAX_LON;

        return inLatRange && inLonRange;
    }

    /**
     * Load NAD data asynchronously.
     */
    private void loadNadDataAsync(Bounds bounds) {
        // Cancel any existing task
        cancelCurrentTask();

        currentTask = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
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

                return null;
            }

            @Override
            protected void done() {
                currentTask = null;
            }
        };

        currentTask.execute();
    }

    /**
     * Cancel any currently running load task.
     */
    private void cancelCurrentTask() {
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
            currentTask = null;
        }
    }

    /**
     * Manually trigger NAD data load for current active layer.
     * Can be called from UI if user wants to refresh.
     */
    public void refreshNadData() {
        OsmDataLayer activeLayer = MainApplication.getLayerManager().getActiveDataLayer();
        if (activeLayer != null) {
            checkAndLoadNadData(activeLayer);
        }
    }
}
