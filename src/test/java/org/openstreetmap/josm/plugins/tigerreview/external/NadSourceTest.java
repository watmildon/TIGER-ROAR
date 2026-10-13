// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.tigerreview.JosmTestSetup;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadAddress;

/**
 * Tests for choosing and caching NAD address sources.
 */
class NadSourceTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @Test
    void featureServerUrlsGetOneQueryEndpoint() {
        String expected = NadClient.DEFAULT_SERVICE_URL + "/query";
        assertEquals(expected, NadClient.queryEndpoint(NadClient.DEFAULT_SERVICE_URL));
        assertEquals(expected, NadClient.queryEndpoint(NadClient.DEFAULT_SERVICE_URL + "/"));
        assertEquals(expected, NadClient.queryEndpoint(NadClient.DEFAULT_SERVICE_URL + "/query"));
        assertEquals(expected, NadClient.queryEndpoint(" " + NadClient.DEFAULT_SERVICE_URL + "/query/ "));
    }

    @Test
    void queryEnvelopeIgnoresTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String url = new NadClient("https://example.org/FeatureServer/0")
                    .buildQueryUrl(new Bounds(40.1, -87.6, 40.2, -87.5), 0);
            assertTrue(url.startsWith("https://example.org/FeatureServer/0/query?"), url);
            assertTrue(url.contains("geometry=-87.600000,40.100000,-87.500000,40.200000&"), url);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void theDefaultSourceKeepsTheOriginalCacheDirectory(@TempDir Path root) {
        assertEquals(root, NadTileStore.sourceDir(root, NadClient.DEFAULT_SERVICE_URL));
        assertEquals(root, NadTileStore.sourceDir(root, NadClient.DEFAULT_SERVICE_URL + "/query"));
        Path pmtiles = NadTileStore.sourceDir(root, "https://nad.watmildon.org/nad-current.pmtiles");
        Path other = NadTileStore.sourceDir(root, "https://example.org/other.pmtiles");
        assertEquals(root, pmtiles.getParent());
        assertTrue(pmtiles.getFileName().toString().startsWith("src-"));
        assertNotEquals(pmtiles, other);
    }

    @Test
    void aNewDataVersionDiscardsCachedTiles(@TempDir Path root) throws IOException {
        Path dir = root.resolve("src-test");
        NadTileStore store = new NadTileStore(root, dir);
        NadTileKey key = NadTileKey.of(-87.55, 40.05);
        List<NadAddress> addresses = List.of(new NadAddress("Main Street", null, null, null, null, new LatLon(40.05, -87.55)));

        store.syncVersion("\"1\"");
        store.writeTile(key, addresses);
        assertTrue(store.hasFreshTile(key));

        // Same version: kept
        store.syncVersion("\"1\"");
        assertNotNull(store.readTile(key));

        // Unknown version (source unreachable or no ETag): kept
        store.syncVersion(null);
        assertNotNull(store.readTile(key));

        // New release: discarded
        store.syncVersion("\"2\"");
        assertNull(store.readTile(key));
        assertEquals("\"2\"", Files.readString(dir.resolve("version.txt")).trim());
    }

    @Test
    void cachedTilesOfEachSourceAreSeparate(@TempDir Path root) {
        NadTileStore esri = new NadTileStore(root, root);
        NadTileStore pmtiles = new NadTileStore(root, root.resolve("src-pm"));
        NadTileKey key = NadTileKey.of(-87.55, 40.05);
        esri.writeTile(key, List.of(new NadAddress("Esri Street", null, null, null, null, new LatLon(40.05, -87.55))));
        assertNull(pmtiles.readTile(key));
        assertEquals("Esri Street", esri.readTile(key).get(0).street());
    }
}
