// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

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
import org.openstreetmap.josm.tools.Logging;

/**
 * Base class for background external data loaders (NAD, Mapillary).
 *
 * Handles the common lifecycle: singleton layer listener, US bounds checking,
 * async SwingWorker execution, and bounds computation from data sources.
 * Subclasses provide the enable-check, cache-clear, way-filter, and fetch logic.
 */
public abstract class AbstractExternalDataLoader implements LayerManager.LayerChangeListener {

    /** US bounding box (approximate continental US + Alaska + Hawaii) */
    private static final double US_MIN_LAT = 18.0;
    private static final double US_MAX_LAT = 72.0;
    private static final double US_MIN_LON = -180.0;
    private static final double US_MAX_LON = -66.0;

    /** Buffer in degrees to pad computed bounds (~100m at mid-latitudes) */
    private static final double BOUNDS_BUFFER_DEGREES = 0.001;

    /** Currently running loader task */
    private SwingWorker<Void, Void> currentTask;

    /**
     * Register this loader as a layer change listener.
     */
    public void register() {
        MainApplication.getLayerManager().addLayerChangeListener(this);
        Logging.info(getLoaderName() + " registered");
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
        if (e.getRemovedLayer() instanceof OsmDataLayer) {
            boolean hasOsmLayers = MainApplication.getLayerManager().getLayers().stream()
                    .anyMatch(l -> l instanceof OsmDataLayer && l != e.getRemovedLayer());
            if (!hasOsmLayers) {
                clearCache();
                Logging.info(getLoaderName() + " cache cleared - no OSM layers remaining");
            }
        }
    }

    @Override
    public void layerOrderChanged(LayerManager.LayerOrderChangeEvent e) {
        // Not relevant
    }

    /**
     * Check if the layer contains US data and load if enabled.
     */
    private void checkAndLoadData(OsmDataLayer layer) {
        if (!isEnabled()) {
            Logging.debug(getLoaderName() + " disabled, skipping data load");
            return;
        }

        DataSet dataSet = layer.getDataSet();
        if (dataSet == null) {
            return;
        }

        loadForDataSet(dataSet);
    }

    /**
     * Load data for a dataset asynchronously.
     */
    public void loadForDataSet(DataSet dataSet) {
        Bounds bounds = computeBounds(dataSet);
        if (bounds != null) {
            loadDataAsync(bounds);
        }
    }

    /**
     * Load data for a dataset synchronously. Call from a background thread only.
     */
    public void loadForDataSetSync(DataSet dataSet) {
        Bounds bounds = computeBounds(dataSet);
        if (bounds != null) {
            fetchData(bounds);
        }
    }

    /**
     * Compute the query bounds for a dataset, trying data source bounds first
     * then falling back to bounds of relevant ways.
     *
     * @return bounds to query, or null if none could be determined or not in US
     */
    private Bounds computeBounds(DataSet dataSet) {
        if (!isEnabled()) {
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

        // Fall back to bounds of relevant ways (e.g. loading a .osm file)
        if (bounds == null || bounds.getArea() == 0) {
            bounds = computeFallbackBounds(dataSet);
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
            Logging.debug("Cannot determine bounds for " + getLoaderName() + " data load");
            return null;
        }

        if (!isInUS(bounds)) {
            Logging.debug("Data bounds not in US, skipping " + getLoaderName() + " data load");
            return null;
        }

        return bounds;
    }

    /**
     * Compute fallback bounds from relevant ways in the dataset.
     * Subclasses define which ways are relevant.
     */
    protected Bounds computeFallbackBounds(DataSet dataSet) {
        Bounds bounds = null;
        for (Way way : dataSet.getWays()) {
            if (way.isDeleted() || way.isIncomplete()) {
                continue;
            }
            if (!isRelevantWay(way)) {
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
     * Load data asynchronously.
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

    // --- Subclass hooks ---

    /** Human-readable name for logging (e.g. "NAD data loader"). */
    protected abstract String getLoaderName();

    /** Whether this loader is enabled in preferences. */
    protected abstract boolean isEnabled();

    /** Clear the associated cache. */
    protected abstract void clearCache();

    /** Whether the given way is relevant for fallback bounds computation. */
    protected abstract boolean isRelevantWay(Way way);

    /** Fetch data synchronously for the given bounds. Safe to call from any thread. */
    protected abstract void fetchData(Bounds bounds);
}
