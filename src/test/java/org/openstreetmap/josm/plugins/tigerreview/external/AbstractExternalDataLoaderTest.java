// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.tigerreview.JosmTestSetup;

/**
 * Tests for the shared bounds guards in {@link AbstractExternalDataLoader}.
 *
 * Regression: loading a USA-wide layer used to fire an unbounded number of
 * NAD tile requests because the per-request area limit was bypassed by the
 * tiled fetch path. The loader must reject oversized bounds before fetching.
 */
class AbstractExternalDataLoaderTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    /** Test double that records fetches and cache errors instead of hitting the network. */
    private static class TestLoader extends AbstractExternalDataLoader {
        final List<Bounds> fetchedBounds = new ArrayList<>();
        final List<String> cacheErrors = new ArrayList<>();

        @Override
        protected String getLoaderName() {
            return "test loader";
        }

        @Override
        protected boolean isEnabled() {
            return true;
        }

        @Override
        protected boolean isCacheReady() {
            return false;
        }

        @Override
        protected void clearCache() {
            // no-op
        }

        @Override
        protected void setCacheError(String message) {
            cacheErrors.add(message);
        }

        @Override
        protected boolean isRelevantWay(Way way) {
            return true;
        }

        @Override
        protected void fetchData(Bounds bounds) {
            fetchedBounds.add(bounds);
        }
    }

    private static DataSet dataSetWithBounds(Bounds bounds) {
        DataSet ds = new DataSet();
        ds.addDataSource(new DataSource(bounds, "test"));
        return ds;
    }

    @Test
    void smallUsBoundsAreFetched() {
        TestLoader loader = new TestLoader();
        // ~0.01 sq degrees around Seattle
        loader.loadForDataSetSync(dataSetWithBounds(new Bounds(47.60, -122.35, 47.70, -122.25)));

        assertEquals(1, loader.fetchedBounds.size());
        assertTrue(loader.cacheErrors.isEmpty());
    }

    @Test
    void usaWideBoundsAreRejected() {
        TestLoader loader = new TestLoader();
        // Continental US, ~1475 sq degrees
        loader.loadForDataSetSync(dataSetWithBounds(new Bounds(24.0, -125.0, 49.0, -66.0)));

        assertTrue(loader.fetchedBounds.isEmpty(), "oversized bounds must not be fetched");
        assertEquals(1, loader.cacheErrors.size());
        assertTrue(loader.cacheErrors.get(0).contains("too large"));
    }

    @Test
    void boundsJustOverLimitAreRejected() {
        TestLoader loader = new TestLoader();
        // 0.6 x 0.6 = 0.36 sq degrees > 0.25 limit
        loader.loadForDataSetSync(dataSetWithBounds(new Bounds(40.0, -100.0, 40.6, -99.4)));

        assertTrue(loader.fetchedBounds.isEmpty());
        assertEquals(1, loader.cacheErrors.size());
    }

    @Test
    void unionOfDistantDataSourcesIsRejected() {
        TestLoader loader = new TestLoader();
        // Two small downloads on opposite coasts: each is tiny, but the covering
        // bbox spans the continent and must be rejected.
        DataSet ds = new DataSet();
        ds.addDataSource(new DataSource(new Bounds(47.60, -122.35, 47.61, -122.34), "seattle"));
        ds.addDataSource(new DataSource(new Bounds(25.76, -80.20, 25.77, -80.19), "miami"));
        loader.loadForDataSetSync(ds);

        assertTrue(loader.fetchedBounds.isEmpty());
        assertEquals(1, loader.cacheErrors.size());
    }

    @Test
    void nonUsBoundsAreSkippedWithoutError() {
        TestLoader loader = new TestLoader();
        // Paris
        loader.loadForDataSetSync(dataSetWithBounds(new Bounds(48.85, 2.30, 48.87, 2.36)));

        assertTrue(loader.fetchedBounds.isEmpty());
        assertTrue(loader.cacheErrors.isEmpty(), "non-US data is a silent skip, not an error");
    }

    @Test
    void nadTileCountForMaxAreaIsBounded() {
        // Sanity check the scale of the fix: bounds at the 0.25 sq degree limit
        // produce a few dozen tiles; the USA-wide case used to produce ~150,000.
        List<NadTileKey> atLimit = NadTileKey.tilesForBounds(new Bounds(40.0, -100.0, 40.5, -99.5));
        assertTrue(atLimit.size() <= 36, "expected at most 36 tiles at the area limit, got " + atLimit.size());

        List<NadTileKey> usaWide = NadTileKey.tilesForBounds(new Bounds(24.0, -125.0, 49.0, -66.0));
        assertTrue(usaWide.size() > 100_000, "USA-wide bounds tile count, got " + usaWide.size());
    }

    @Test
    void computeBoundsReturnsNullForEmptyDataSet() {
        TestLoader loader = new TestLoader();
        loader.loadForDataSetSync(new DataSet());

        assertTrue(loader.fetchedBounds.isEmpty());
        assertTrue(loader.cacheErrors.isEmpty());
    }

}
