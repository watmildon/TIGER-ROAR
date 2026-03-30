// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.LayerManager;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.Logging;

/**
 * Tracks the originally-downloaded {@code name} tag for TIGER roads.
 *
 * <p>When an OSM data layer is added, this class snapshots the {@code name} tag
 * of every highway way that has {@code tiger:reviewed=no}. At analysis time,
 * {@link #isNameModified(Way)} can be called to detect whether the current user
 * has changed the name in this editing session.</p>
 *
 * <p>Uses {@link Way#getUniqueId()} as the map key so that ways from different
 * datasets (which may share XML IDs) don't collide.</p>
 */
public final class NameSnapshotTracker implements LayerManager.LayerChangeListener {

    private static final NameSnapshotTracker INSTANCE = new NameSnapshotTracker();

    /** Sentinel for ways that had no name at download time. */
    private static final String NO_NAME = "";

    /** way unique ID → original name (empty string means originally unnamed) */
    private final Map<Long, String> originalNames = new ConcurrentHashMap<>();

    private NameSnapshotTracker() {
    }

    public static NameSnapshotTracker getInstance() {
        return INSTANCE;
    }

    public void register() {
        MainApplication.getLayerManager().addLayerChangeListener(this);
        Logging.info("TIGER ROAR name snapshot tracker registered");
    }

    public void unregister() {
        MainApplication.getLayerManager().removeLayerChangeListener(this);
        originalNames.clear();
    }

    @Override
    public void layerAdded(LayerManager.LayerAddEvent e) {
        Layer layer = e.getAddedLayer();
        if (layer instanceof OsmDataLayer osmLayer) {
            DataSet ds = osmLayer.getDataSet();
            if (ds != null) {
                snapshotNames(ds);
            }
        }
    }

    @Override
    public void layerRemoving(LayerManager.LayerRemoveEvent e) {
        if (e.getRemovedLayer() instanceof OsmDataLayer) {
            boolean hasOsmLayers = MainApplication.getLayerManager().getLayers().stream()
                    .anyMatch(l -> l instanceof OsmDataLayer && l != e.getRemovedLayer());
            if (!hasOsmLayers) {
                originalNames.clear();
                Logging.info("TIGER ROAR name snapshot cleared - no OSM layers remaining");
            }
        }
    }

    @Override
    public void layerOrderChanged(LayerManager.LayerOrderChangeEvent e) {
        // Not relevant
    }

    /**
     * Snapshot the name tags of all highway roads in a dataset.
     * Only records ways not already tracked (preserves the original snapshot
     * across multiple downloads into the same layer).
     */
    public void snapshotNames(DataSet dataSet) {
        int count = 0;
        for (Way way : dataSet.getWays()) {
            if (!way.isUsable()) {
                continue;
            }
            String highway = way.get("highway");
            if (highway == null || !HighwayConstants.TIGER_HIGHWAYS.contains(highway)) {
                continue;
            }
            // Only snapshot if not already tracked
            if (!originalNames.containsKey(way.getUniqueId())) {
                String name = way.get("name");
                originalNames.put(way.getUniqueId(), name != null ? name : NO_NAME);
                count++;
            }
        }
        if (count > 0) {
            Logging.info("TIGER ROAR: snapshotted names for {0} ways", count);
        }
    }

    /**
     * Check whether the current user has modified the {@code name} tag of a way
     * relative to the originally-downloaded value.
     *
     * @param way the way to check
     * @return true if the name has been changed from its original value
     */
    public boolean isNameModified(Way way) {
        String originalName = originalNames.get(way.getUniqueId());
        if (originalName == null) {
            // No snapshot for this way — wasn't tracked at download time
            return false;
        }
        String currentName = way.get("name");
        if (currentName == null) {
            // Way currently has no name; was it originally unnamed too?
            return !NO_NAME.equals(originalName);
        }
        return !originalName.equals(currentName);
    }

    /**
     * Whether the tracker has a snapshot for a given way.
     */
    public boolean hasSnapshot(Way way) {
        return originalNames.containsKey(way.getUniqueId());
    }

    /**
     * Clear all snapshots. Used in tests.
     */
    public void clear() {
        originalNames.clear();
    }

    /**
     * Manually record an original name for a way. Used in tests.
     */
    public void putOriginalName(Way way, String name) {
        originalNames.put(way.getUniqueId(), name != null ? name : NO_NAME);
    }
}
