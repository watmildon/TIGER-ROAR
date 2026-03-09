// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.HighwayConstants;
import org.openstreetmap.josm.plugins.tigerreview.TIGERReviewPreferences;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.MapillaryQueryResult;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.MarkingDetection;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.MarkingQueryResult;
import org.openstreetmap.josm.plugins.tigerreview.external.MapillaryClient.SpeedLimitDetection;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * Background loader for Mapillary speed limit detection data.
 *
 * Listens for OSM data layer changes and automatically fetches Mapillary data
 * when a US dataset is loaded (detected by bounding box in US coordinates).
 */
public class MapillaryDataLoader extends AbstractExternalDataLoader {

    /** Singleton instance */
    private static MapillaryDataLoader instance;

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

    @Override
    protected String getLoaderName() {
        return "Mapillary data loader";
    }

    @Override
    protected boolean isEnabled() {
        return Config.getPref().getBoolean(TIGERReviewPreferences.PREF_ENABLE_MAPILLARY_CHECK, false);
    }

    @Override
    protected void clearCache() {
        MapillaryDataCache.getInstance().clear();
    }

    @Override
    protected boolean isRelevantWay(Way way) {
        // Mapillary needs bounds from all vehicular highway ways
        String highway = way.get("highway");
        return highway != null && HighwayConstants.SURFACE_HIGHWAYS.contains(highway);
    }

    @Override
    protected void fetchData(Bounds bounds) {
        String apiToken = Config.getPref().get(TIGERReviewPreferences.PREF_MAPILLARY_API_KEY, "");
        if (apiToken.isEmpty()) {
            MapillaryDataCache.getInstance().setError("Mapillary API token not configured");
            Logging.warn("Mapillary API token not configured");
            return;
        }

        Logging.info("Starting Mapillary data fetch for bounds: " + bounds);

        MapillaryClient client = new MapillaryClient();

        // Fetch speed limit sign detections
        MapillaryQueryResult result = client.queryDetections(bounds, apiToken);
        if (result.isSuccess()) {
            List<SpeedLimitDetection> detections = result.detections();
            MapillaryDataCache.getInstance().load(detections, bounds);
            Logging.info("Mapillary data loaded: " + detections.size() + " speed limit detections");
        } else {
            MapillaryDataCache.getInstance().setError(result.errorMessage());
            Logging.warn("Mapillary data load failed: " + result.errorMessage());
            return;
        }

        // Fetch road marking detections (for surface corroboration)
        MarkingQueryResult markingResult = client.queryMarkings(bounds, apiToken);
        if (markingResult.isSuccess()) {
            List<MarkingDetection> markings = markingResult.markings();
            MapillaryDataCache.getInstance().loadMarkings(markings, bounds);
            Logging.info("Mapillary data loaded: " + markings.size() + " road marking detections");
        } else {
            // Marking fetch failure is non-fatal — speed limit data is still usable
            Logging.warn("Mapillary marking data load failed: " + markingResult.errorMessage());
        }
    }
}
