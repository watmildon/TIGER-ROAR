// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.util.List;

import javax.swing.SwingWorker;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.LayerManager;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.tigerreview.HighwayConstants;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewPreferences;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.MapillaryQueryResult;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.SpeedLimitDetection;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * Background loader for Mapillary speed limit detection data.
 *
 * Listens for OSM data layer changes and automatically fetches Mapillary data
 * when a US dataset is loaded (detected by bounding box in US coordinates).
 * Follows the same pattern as {@link NadDataLoader}.
 */
public class MapillaryDataLoader implements LayerManager.LayerChangeListener {

    /** US bounding box (approximate continental US + Alaska + Hawaii) */
    private static final double US_MIN_LAT = 18.0;
    private static final double US_MAX_LAT = 72.0;
    private static final double US_MIN_LON = -180.0;
    private static final double US_MAX_LON = -66.0;

    /** Buffer in degrees to pad computed bounds (~100m at mid-latitudes) */
    private static final double BOUNDS_BUFFER_DEGREES = 0.001;

    /** Singleton instance */
    private static MapillaryDataLoader instance;

    /** Currently running loader task */
    private SwingWorker<Void, Void> currentTask;

    private MapillaryDataLoader() {
        // Singleton
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized MapillaryDataLoader getInstance() {
        if (instance == null) {
            instance = new MapillaryDataLoader();
        }
        return instance;
    }

    /**
     * Register this loader as a layer change listener.
     */
    public void register() {
        MainApplication.getLayerManager().addLayerChangeListener(this);
        Logging.info("Mapillary data loader registered");
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
            checkAndLoadData(osmLayer);
        }
    }

    @Override
    public void layerRemoving(LayerManager.LayerRemoveEvent e) {
        // Clear cache when all OSM layers are removed
        if (e.getRemovedLayer() instanceof OsmDataLayer) {
            boolean hasOsmLayers = MainApplication.getLayerManager().getLayers().stream()
                    .anyMatch(l -> l instanceof OsmDataLayer && l != e.getRemovedLayer());
            if (!hasOsmLayers) {
                MapillaryDataCache.getInstance().clear();
                Logging.info("Mapillary cache cleared - no OSM layers remaining");
            }
        }
    }

    @Override
    public void layerOrderChanged(LayerManager.LayerOrderChangeEvent e) {
        // Not relevant
    }

    /**
     * Check if the layer contains US data and load Mapillary data if enabled.
     */
    private void checkAndLoadData(OsmDataLayer layer) {
        if (!Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, false)) {
            Logging.debug("Mapillary check disabled, skipping data load");
            return;
        }

        DataSet dataSet = layer.getDataSet();
        if (dataSet == null) {
            return;
        }

        loadForDataSet(dataSet);
    }

    /**
     * Load Mapillary data for a dataset asynchronously.
     */
    public void loadForDataSet(DataSet dataSet) {
        Bounds bounds = computeBounds(dataSet);
        if (bounds != null) {
            loadDataAsync(bounds);
        }
    }

    /**
     * Load Mapillary data for a dataset synchronously. Call from a background thread only.
     */
    public void loadForDataSetSync(DataSet dataSet) {
        Bounds bounds = computeBounds(dataSet);
        if (bounds != null) {
            fetchData(bounds);
        }
    }

    /**
     * Compute the query bounds for a dataset.
     *
     * @return bounds to query, or null if none could be determined or not in US
     */
    private Bounds computeBounds(DataSet dataSet) {
        if (!Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, false)) {
            return null;
        }

        // Try data source bounds first (available when downloaded from API)
        Bounds bounds = null;
        for (Bounds b : dataSet.getDataSourceBounds()) {
            if (bounds == null) {
                bounds = new Bounds(b);
            } else {
                bounds.extend(b);
            }
        }

        // Fall back to bounds of highway ways (e.g. loading a .osm file)
        if (bounds == null || bounds.getArea() == 0) {
            bounds = computeHighwayBounds(dataSet);
            if (bounds != null) {
                bounds.extend(new LatLon(
                        bounds.getMinLat() - BOUNDS_BUFFER_DEGREES,
                        bounds.getMinLon() - BOUNDS_BUFFER_DEGREES));
                bounds.extend(new LatLon(
                        bounds.getMaxLat() + BOUNDS_BUFFER_DEGREES,
                        bounds.getMaxLon() + BOUNDS_BUFFER_DEGREES));
            }
        }

        if (bounds == null || bounds.getArea() == 0) {
            Logging.debug("Cannot determine bounds for Mapillary data load");
            return null;
        }

        if (!isInUS(bounds)) {
            Logging.debug("Data bounds not in US, skipping Mapillary data load");
            return null;
        }

        return bounds;
    }

    /**
     * Compute bounds from all highway ways in the dataset.
     */
    private Bounds computeHighwayBounds(DataSet dataSet) {
        Bounds bounds = null;
        for (Way way : dataSet.getWays()) {
            if (way.isDeleted() || way.isIncomplete()) {
                continue;
            }
            String highway = way.get("highway");
            if (highway == null || !HighwayConstants.SURFACE_HIGHWAYS.contains(highway)) {
                continue;
            }
            for (Node node : way.getNodes()) {
                if (node.isLatLonKnown()) {
                    if (bounds == null) {
                        bounds = new Bounds(node.getCoor());
                    } else {
                        bounds.extend(node.getCoor());
                    }
                }
            }
        }
        return bounds;
    }

    /**
     * Check if bounds are within the US.
     */
    private boolean isInUS(Bounds bounds) {
        double centerLat = bounds.getCenter().lat();
        double centerLon = bounds.getCenter().lon();
        boolean inLatRange = centerLat >= US_MIN_LAT && centerLat <= US_MAX_LAT;
        boolean inLonRange = centerLon >= US_MIN_LON && centerLon <= US_MAX_LON;
        return inLatRange && inLonRange;
    }

    /**
     * Fetch Mapillary data synchronously. Safe to call from any thread.
     */
    private void fetchData(Bounds bounds) {
        String apiToken = Config.getPref().get(TIGERReviewPreferences.PREF_MAPILLARY_API_KEY, "");
        if (apiToken.isEmpty()) {
            MapillaryDataCache.getInstance().setError("Mapillary API token not configured");
            Logging.warn("Mapillary API token not configured");
            return;
        }

        Logging.info("Starting Mapillary data fetch for bounds: " + bounds);

        MapillaryClient client = new MapillaryClient();
        MapillaryQueryResult result = client.queryDetections(bounds, apiToken);

        if (result.isSuccess()) {
            List<SpeedLimitDetection> detections = result.detections();
            MapillaryDataCache.getInstance().load(detections, bounds);
            Logging.info("Mapillary data loaded: " + detections.size() + " detections");
        } else {
            MapillaryDataCache.getInstance().setError(result.errorMessage());
            Logging.warn("Mapillary data load failed: " + result.errorMessage());
        }
    }

    /**
     * Load Mapillary data asynchronously.
     */
    private void loadDataAsync(Bounds bounds) {
        cancelCurrentTask();

        currentTask = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                fetchData(bounds);
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
}
