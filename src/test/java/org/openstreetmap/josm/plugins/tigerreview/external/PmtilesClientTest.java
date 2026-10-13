// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openstreetmap.josm.plugins.tigerreview.external.PmtilesFixture.lat;
import static org.openstreetmap.josm.plugins.tigerreview.external.PmtilesFixture.lon;
import static org.openstreetmap.josm.plugins.tigerreview.external.PmtilesFixture.tags;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.plugins.tigerreview.JosmTestSetup;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadAddress;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadQueryResult;
import org.openstreetmap.josm.plugins.tigerreview.external.PmtilesFixture.MvtLayer;
import org.openstreetmap.josm.plugins.tigerreview.external.PmtilesFixture.RangeServer;

/**
 * Tests for reading NAD address points from PMTiles archives.
 */
class PmtilesClientTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final int Z = 14;
    /** Two neighbouring zoom-14 tiles in Indiana. */
    private static final int X = 4205;
    private static final int Y = 6200;
    private static final int EXTENT = 4096;

    @BeforeEach
    void forgetArchives() {
        PmtilesClient.forgetArchives();
    }

    private static Map<String, String> address(String number, String street, String unit) {
        Map<String, String> t = tags("addr:housenumber", number, "addr:street", street, "addr:city", "Crawfordsville",
                "addr:state", "IN", "addr:postcode", "47933");
        if (unit != null) {
            t.put("addr:unit", unit);
        }
        return t;
    }

    /**
     * Tile X holds a building-level address and two units at one spot, a point with no street,
     * and (in its buffer) a point that belongs to tile X+1. Tile X+1 holds that point again plus
     * one near its east edge.
     */
    private static PmtilesFixture.Archive nadArchive() {
        MvtLayer a = new MvtLayer("addresses", EXTENT)
                .point(1000, 1000, address("114", "East Main Street", null))
                .point(1000, 1000, address("114", "East Main Street", "Apt A"))
                .point(1000, 1000, address("114", "East Main Street", "Apt B"))
                .point(2000, 3000, tags("addr:housenumber", "5"))
                .point(EXTENT + 4, 2000, address("89-02", "Avenue N", null));
        MvtLayer b = new MvtLayer("addresses", EXTENT)
                .point(4, 2000, address("89-02", "Avenue N", null))
                .point(4000, 500, address("1", "Far East Road", null));
        return new PmtilesFixture.Archive()
                .tile(Z, X, Y, PmtilesFixture.tile(a))
                .tile(Z, X + 1, Y, PmtilesFixture.tile(b));
    }

    /** Both tiles, except the last 600 units of the second one's width; just inside the tile edges. */
    private static Bounds view() {
        double inset = 0.5 / EXTENT;
        return new Bounds(lat(Z, Y + 1 - inset), lon(Z, X + inset), lat(Z, Y + inset), lon(Z, X + 1 + 3500.0 / EXTENT));
    }

    /** The 0.1 degree cell holding tile X's East Main Street addresses. */
    private static NadTileKey mainCell() {
        return NadTileKey.of(lon(Z, X + 1000.0 / EXTENT), lat(Z, Y + 1000.0 / EXTENT));
    }

    private static List<String> streets(List<NadAddress> addresses) {
        List<String> out = new ArrayList<>();
        for (NadAddress a : addresses) {
            out.add(a.street());
        }
        out.sort(null);
        return out;
    }

    // ---- format ---------------------------------------------------------------------------------

    @Test
    void tileIdsFollowTheHilbertCurveOfTheSpec() {
        assertEquals(0, PmtilesClient.tileId(0, 0, 0));
        assertEquals(1, PmtilesClient.tileId(1, 0, 0));
        assertEquals(2, PmtilesClient.tileId(1, 0, 1));
        assertEquals(3, PmtilesClient.tileId(1, 1, 1));
        assertEquals(4, PmtilesClient.tileId(1, 1, 0));
        assertEquals(5, PmtilesClient.tileId(2, 0, 0));
        assertEquals(19_078_479, PmtilesClient.tileId(12, 3423, 1763));
        assertThrows(IllegalArgumentException.class, () -> PmtilesClient.tileId(1, 2, 0));
    }

    @Test
    void directoryFindsTilesRunsAndLeaves() throws IOException {
        List<long[]> entries = new ArrayList<>();
        entries.add(new long[] {10, 0, 5, 1});
        entries.add(new long[] {20, 5, 7, 3}); // a run of 3 identical tiles: 20, 21, 22
        entries.add(new long[] {30, 100, 9, 0}); // a leaf directory for 30 onwards
        PmtilesClient.Directory d = PmtilesClient.Directory.parse(PmtilesFixture.directory(entries));
        assertEquals(0, d.find(10));
        assertEquals(1, d.find(22));
        assertEquals(-1, d.find(23));
        assertEquals(-1, d.find(5));
        assertEquals(2, d.find(1000));
        // The second entry's offset is written as "follows the previous one"
        assertEquals(5, d.offsets[1]);
        assertEquals(100, d.offsets[2]);
    }

    @Test
    void tilesCoveringACellAtTheCompleteZoom() {
        List<int[]> tiles = PmtilesClient.tilesCovering(view(), Z);
        assertEquals(2, tiles.size());
        assertEquals(X, tiles.get(0)[0]);
        assertEquals(X + 1, tiles.get(1)[0]);
        assertEquals(Y, tiles.get(1)[1]);
    }

    @Test
    void multipointGeometriesYieldEveryPoint() {
        MvtParser.Feature f = new MvtParser.Feature();
        // MoveTo x3: (10,20), (+5,+5) -> (15,25), (-15,-25) -> (0,0)
        for (long v : new long[] {1 | 3 << 3, PmtilesFixture.zigzag(10), PmtilesFixture.zigzag(20),
                PmtilesFixture.zigzag(5), PmtilesFixture.zigzag(5), PmtilesFixture.zigzag(-15), PmtilesFixture.zigzag(-25)}) {
            f.geometry.add((int) v);
        }
        List<int[]> points = f.decodePoints();
        assertEquals(3, points.size());
        assertEquals(15, points.get(1)[0]);
        assertEquals(25, points.get(1)[1]);
        assertEquals(0, points.get(2)[0]);
        assertEquals(0, points.get(2)[1]);
    }

    // ---- reading addresses ------------------------------------------------------------------------

    @Test
    void addressesInTheCellAreReadWithTheirTags() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            List<NadAddress> got = new PmtilesClient(server.url()).fetch(view());
            // Three addresses at one spot stay three; the point with no street is skipped; the
            // buffer copy of Avenue N is read once; Far East Road is outside the view.
            assertEquals(List.of("Avenue N", "East Main Street", "East Main Street", "East Main Street"), streets(got));
            NadAddress main = got.stream().filter(a -> a.street().equals("East Main Street")).findFirst().orElseThrow();
            assertEquals("114", main.houseNumber());
            assertEquals("Crawfordsville", main.city());
            assertEquals("IN", main.state());
            assertEquals("47933", main.postcode());
            assertEquals(lon(Z, X + 1000.0 / EXTENT), main.location().lon(), 1e-9);
            assertEquals(lat(Z, Y + 1000.0 / EXTENT), main.location().lat(), 1e-9);
        }
    }

    @Test
    void queryTileReturnsOnlyTheCell() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            NadTileKey cell = mainCell();
            NadQueryResult result = new PmtilesClient(server.url()).queryTile(cell);
            assertTrue(result.isSuccess(), result.errorMessage());
            assertTrue(streets(result.addresses()).contains("East Main Street"));
            for (NadAddress a : result.addresses()) {
                assertEquals(cell, NadTileKey.of(a.location().lon(), a.location().lat()));
            }
        }
    }

    @Test
    void seamPointsOfAnUnbufferedTilesetAreKept() throws IOException {
        PmtilesFixture.Archive archive = new PmtilesFixture.Archive()
                .tile(Z, X, Y, PmtilesFixture.tile(new MvtLayer("addresses", EXTENT)
                        .point(EXTENT, 2000, address("10", "Seam Street", null))))
                .tile(Z, X + 1, Y, PmtilesFixture.tile(new MvtLayer("addresses", EXTENT)
                        .point(100, 100, address("11", "Other Street", null))));
        try (RangeServer server = new RangeServer(archive.build())) {
            assertEquals(List.of("Other Street", "Seam Street"), streets(new PmtilesClient(server.url()).fetch(view())));
        }
    }

    @Test
    void seamPointsOfABufferedTilesetAreReadOnce() throws IOException {
        PmtilesFixture.Archive archive = new PmtilesFixture.Archive()
                .tile(Z, X, Y, PmtilesFixture.tile(new MvtLayer("addresses", EXTENT)
                        .point(EXTENT, 2000, address("10", "Seam Street", null))))
                .tile(Z, X + 1, Y, PmtilesFixture.tile(new MvtLayer("addresses", EXTENT)
                        .point(0, 2000, address("10", "Seam Street", null))));
        try (RangeServer server = new RangeServer(archive.build())) {
            assertEquals(List.of("Seam Street"), streets(new PmtilesClient(server.url()).fetch(view())));
        }
    }

    @Test
    void entriesInALeafDirectoryAreFound() throws IOException {
        PmtilesFixture.Archive archive = nadArchive();
        archive.leaf = true;
        try (RangeServer server = new RangeServer(archive.build())) {
            assertEquals(4, new PmtilesClient(server.url()).fetch(view()).size());
        }
    }

    @Test
    void uncompressedArchivesAreReadToo() throws IOException {
        PmtilesFixture.Archive archive = nadArchive();
        archive.tileCompression = PmtilesClient.COMPRESSION_NONE;
        archive.internalCompression = PmtilesClient.COMPRESSION_NONE;
        try (RangeServer server = new RangeServer(archive.build())) {
            assertEquals(4, new PmtilesClient(server.url()).fetch(view()).size());
        }
    }

    @Test
    void otherPropertySpellingsAreRecognised() throws IOException {
        PmtilesFixture.Archive archive = new PmtilesFixture.Archive()
                .tile(Z, X, Y, PmtilesFixture.tile(new MvtLayer("pts", EXTENT)
                        .point(1000, 1000, tags("ADDR_STREET", "Underscore Way", "Addr_HouseNumber", "7"))));
        try (RangeServer server = new RangeServer(archive.build())) {
            List<NadAddress> got = new PmtilesClient(server.url()).fetch(view());
            assertEquals(List.of("Underscore Way"), streets(got));
            assertEquals("7", got.get(0).houseNumber());
        }
    }

    @Test
    void aLayerCanBeChosenAfterTheHash() throws IOException {
        PmtilesFixture.Archive archive = new PmtilesFixture.Archive()
                .tile(Z, X, Y, PmtilesFixture.tile(
                        new MvtLayer("addresses", EXTENT).point(1000, 1000, address("1", "Wanted Street", null)),
                        new MvtLayer("other", EXTENT).point(1200, 1200, address("2", "Unwanted Street", null))));
        try (RangeServer server = new RangeServer(archive.build())) {
            assertEquals(List.of("Unwanted Street", "Wanted Street"), streets(new PmtilesClient(server.url()).fetch(view())));
            assertEquals(List.of("Wanted Street"), streets(new PmtilesClient(server.url() + "#addresses").fetch(view())));
        }
    }

    @Test
    void nonPointFeaturesAreSkipped() throws IOException {
        PmtilesFixture.Archive archive = new PmtilesFixture.Archive()
                .tile(Z, X, Y, PmtilesFixture.tile(new MvtLayer("addresses", EXTENT)
                        .square(1000, 1000, 50, address("1", "Parcel Street", null))
                        .point(2000, 2000, address("2", "Point Street", null))));
        try (RangeServer server = new RangeServer(archive.build())) {
            assertEquals(List.of("Point Street"), streets(new PmtilesClient(server.url()).fetch(view())));
        }
    }

    // ---- HTTP -------------------------------------------------------------------------------------

    @Test
    void theHeaderAndRootAreReadOncePerSession() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            new PmtilesClient(server.url()).fetch(view());
            // Opening read plus one per tile
            assertEquals(3, server.requests.get());
            new PmtilesClient(server.url()).fetch(view());
            assertEquals(5, server.requests.get());
        }
    }

    @Test
    void recentTilesAreNotReadAgainByTheSameClient() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            PmtilesClient client = new PmtilesClient(server.url());
            client.fetch(view());
            int after = server.requests.get();
            client.fetch(view());
            assertEquals(after, server.requests.get());
        }
    }

    @Test
    void compressionTheJdkCannotReadIsReported() throws IOException {
        PmtilesFixture.Archive archive = nadArchive();
        archive.tileCompression = 3; // brotli
        try (RangeServer server = new RangeServer(archive.build())) {
            IOException e = assertThrows(IOException.class, () -> new PmtilesClient(server.url()).fetch(view()));
            assertTrue(e.getMessage().contains("brotli"), e.getMessage());
        }
    }

    @Test
    void imageTilesetsAreRefused() throws IOException {
        PmtilesFixture.Archive archive = nadArchive();
        archive.tileType = 2; // png
        try (RangeServer server = new RangeServer(archive.build())) {
            assertThrows(IOException.class, () -> new PmtilesClient(server.url()).fetch(view()));
        }
    }

    @Test
    void aServerWithoutRangeRequestsIsAnError() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            server.honourRange = false;
            NadQueryResult result = new PmtilesClient(server.url()).queryTile(mainCell());
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("range"), result.errorMessage());
        }
    }

    @Test
    void aMissingFileIsAnError() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            NadQueryResult result = new PmtilesClient(server.url().replace("x.pmtiles", "y.pmtiles"))
                    .queryTile(mainCell());
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("404"), result.errorMessage());
        }
    }

    @Test
    void aFileReplacedUnderTheSameUrlIsReopened() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            PmtilesClient client = new PmtilesClient(server.url());
            String firstVersion = client.dataVersion();
            assertEquals("\"1\"", firstVersion);

            // A new release: different content and ETag
            server.file = new PmtilesFixture.Archive()
                    .tile(Z, X, Y, PmtilesFixture.tile(new MvtLayer("addresses", EXTENT)
                            .point(1000, 1000, address("1", "New Release Road", null))))
                    .build();
            server.etag = "\"2\"";

            NadQueryResult result = client.queryTile(mainCell());
            assertTrue(result.isSuccess(), result.errorMessage());
            assertEquals(List.of("New Release Road"), streets(result.addresses()));
            assertEquals("\"2\"", client.dataVersion());
        }
    }

    // ---- choosing a source ------------------------------------------------------------------------

    @Test
    void pmtilesUrlsAreRecognised() {
        assertTrue(AddressSource.isPmtilesUrl("https://nad.watmildon.org/nad-current.pmtiles"));
        assertTrue(AddressSource.isPmtilesUrl(" https://example.org/A.PMTILES#addresses "));
        assertTrue(AddressSource.isPmtilesUrl("https://bucket.example.org/nad.pmtiles?X-Amz-Signature=abc"));
        assertFalse(AddressSource.isPmtilesUrl(NadClient.DEFAULT_SERVICE_URL));
        assertFalse(AddressSource.isPmtilesUrl(null));
        assertTrue(AddressSource.forUrl("https://example.org/a.pmtiles") instanceof PmtilesClient);
        assertTrue(AddressSource.forUrl(NadClient.DEFAULT_SERVICE_URL) instanceof NadClient);
    }

    @Test
    void layerNamesComeAfterTheHash() {
        assertEquals("https://example.org/a.pmtiles", PmtilesClient.fileUrl("https://example.org/a.pmtiles#addresses"));
        assertEquals("addresses", PmtilesClient.layerName("https://example.org/a.pmtiles#addresses"));
        assertEquals("my layer", PmtilesClient.layerName("https://example.org/a.pmtiles#my%20layer"));
        assertNull(PmtilesClient.layerName("https://example.org/a.pmtiles"));
        assertNull(PmtilesClient.layerName("https://example.org/a.pmtiles#"));
    }

    @Test
    void differentArchivesAreDifferentReleases() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            String first = new PmtilesClient(server.url()).dataVersion();
            PmtilesClient.forgetArchives();
            server.etag = "\"abc\"";
            assertNotEquals(first, new PmtilesClient(server.url()).dataVersion());
        }
    }
}
