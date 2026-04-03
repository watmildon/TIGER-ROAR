// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.util.ArrayList;
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
 * Background loader for NAD data with on-disk tile caching.
 *
 * Listens for OSM data layer changes and automatically fetches NAD data
 * when a US dataset is loaded (detected by bounding box in US coordinates).
 *
 * <p>Data is cached on disk as 0.1-degree tiles. When a layer is loaded,
 * only tiles not already cached (or expired) are fetched from the API.
 * The in-memory cache ({@link NadDataCache}) only holds addresses for
 * tiles overlapping the current layer's bounds.
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
    protected boolean isCacheReady() {
        return NadDataCache.getInstance().isReady();
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

        List<NadTileKey> tiles = NadTileKey.tilesForBounds(bounds);
        Logging.info("NAD tile coverage: " + tiles.size() + " tiles for requested bounds");

        NadTileStore store = NadTileStore.getInstance();
        NadClient client = new NadClient();
        List<NadAddress> allAddresses = new ArrayList<>();
        int diskHits = 0;
        int apiFetches = 0;
        int fetchErrors = 0;

        for (NadTileKey tile : tiles) {
            // Try disk cache first
            if (store.hasFreshTile(tile)) {
                List<NadAddress> cached = store.readTile(tile);
                if (cached != null) {
                    allAddresses.addAll(cached);
                    diskHits++;
                    continue;
                }
                // Read failed — fall through to API fetch
            }

            // Fetch from API
            NadQueryResult result = client.queryTile(tile);
            if (result.isSuccess()) {
                List<NadAddress> fetched = result.addresses();
                allAddresses.addAll(fetched);
                store.writeTile(tile, fetched);
                apiFetches++;
            } else {
                Logging.warn("NAD tile fetch failed for " + tile.toFilename() + ": " + result.errorMessage());
                fetchErrors++;
            }

            // Safety limit across all tiles
            if (allAddresses.size() >= 100_000) {
                Logging.warn("NAD tile loading hit safety limit of 100000 addresses");
                break;
            }
        }

        Logging.info(String.format("NAD tile loading complete: %d disk hits, %d API fetches, %d errors, %d total addresses",
                diskHits, apiFetches, fetchErrors, allAddresses.size()));

        if (!allAddresses.isEmpty() || fetchErrors == 0) {
            NadDataCache.getInstance().load(allAddresses, bounds);
            Logging.info("NAD data loaded: " + allAddresses.size() + " addresses");
        } else {
            NadDataCache.getInstance().setError("All NAD tile fetches failed");
            Logging.warn("NAD data load failed: all tile fetches failed");
        }

        // Run eviction check in background (non-critical)
        store.evictIfNeeded();
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
