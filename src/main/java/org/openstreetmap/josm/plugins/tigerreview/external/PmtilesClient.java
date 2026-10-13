// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadAddress;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadQueryResult;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

/**
 * Reads address points from a PMTiles (version 3) archive of Mapbox vector tiles by HTTP range
 * requests: the header and root directory once per URL per session, then the tiles at the
 * archive's maximum zoom that cover the requested cell. The archive can be gigabytes, so it is
 * never read whole.
 *
 * <p>Points only. A URL may name one layer of the archive after a {@code #}, as in
 * {@code https://example.org/nad.pmtiles#addresses}; without one, every layer with a street
 * property is read. Property names are matched leniently, so {@code addr:street},
 * {@code addr_street} and {@code street} all work.
 *
 * <p>Ported from the Better Address Conflation plugin's reader.
 *
 * @see <a href="https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md">PMTiles v3</a>
 */
public final class PmtilesClient implements AddressSource {
    static final int HEADER_BYTES = 127;
    /** The header and root directory, which the format guarantees fit in the first 16 KiB. */
    private static final int FIRST_READ = 16_384;
    /** Root plus up to three levels of leaf directories, as the format allows. */
    private static final int MAX_DEPTH = 4;
    /** Leaf directories kept per archive; one is a few hundred KB at most. */
    private static final int MAX_LEAVES = 64;
    /**
     * Decoded tiles kept per client. Cells don't line up with tiles, so neighbouring cells share
     * a row of tiles; keeping recent ones avoids reading those twice.
     */
    private static final int MAX_RECENT_TILES = 64;
    /** Tiles fetched at once. */
    private static final int THREADS = 6;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    static final int COMPRESSION_NONE = 1;
    static final int COMPRESSION_GZIP = 2;
    static final int TILE_TYPE_MVT = 1;
    private static final int GEOM_POINT = 1;

    /** Property names (lowercased, punctuation removed) accepted for each address field. */
    private static final Set<String> STREET_KEYS = Set.of("addrstreet", "street", "streetname");
    private static final Set<String> HOUSENUMBER_KEYS = Set.of("addrhousenumber", "housenumber", "number");
    private static final Set<String> CITY_KEYS = Set.of("addrcity", "city");
    private static final Set<String> STATE_KEYS = Set.of("addrstate", "state");
    private static final Set<String> POSTCODE_KEYS = Set.of("addrpostcode", "postcode", "zip", "zipcode");

    /** Opened archives by file URL, for the session. */
    private static final Map<String, Archive> ARCHIVES = new ConcurrentHashMap<>();

    /** The archive's URL, without the {@code #layer} */
    private final String fileUrl;
    /** The layer to read, or null for every layer */
    private final String layer;
    /** Recently decoded tiles by tile id; {@link #NO_TILE} where the archive has none */
    private final Map<Long, TilePoints> recentTiles = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, TilePoints> eldest) {
            return size() > MAX_RECENT_TILES;
        }
    };
    private static final TilePoints NO_TILE = new TilePoints();

    /**
     * Create a client for a PMTiles archive.
     *
     * @param url the archive URL, optionally followed by {@code #layer}
     */
    public PmtilesClient(String url) {
        this.fileUrl = fileUrl(url);
        this.layer = layerName(url);
    }

    // ---- AddressSource ------------------------------------------------------------------------

    @Override
    public NadQueryResult queryTile(NadTileKey tileKey) {
        try {
            try {
                return NadQueryResult.success(fetch(tileKey.toBounds()), false);
            } catch (ArchiveChangedException e) {
                // Republished under the same URL (nad-current moving to a new release): start over once.
                Logging.info(e.getMessage());
                clearRecentTiles();
                return NadQueryResult.success(fetch(tileKey.toBounds()), false);
            }
        } catch (IOException e) {
            Logging.warn("PMTiles read failed for " + tileKey.toFilename() + ": " + e.getMessage());
            return NadQueryResult.error("PMTiles read failed: " + e.getMessage());
        }
    }

    @Override
    public String dataVersion() throws IOException {
        return archive(fileUrl).version;
    }

    // ---- fetching -----------------------------------------------------------------------------

    /**
     * The addresses inside the cell. The cell is half-open (west and south edges included, east
     * and north edges excluded), so a point on the line between two cells is read once.
     */
    List<NadAddress> fetch(Bounds cell) throws IOException {
        Archive archive = archive(fileUrl);
        // The maximum zoom is the complete one; lower zooms are thinned for display.
        int z = archive.header.maxZoom;
        List<int[]> tiles = tilesCovering(cell, z);

        List<TilePoints> results = new ArrayList<>(tiles.size());
        List<int[]> toFetch = new ArrayList<>();
        synchronized (recentTiles) {
            for (int[] t : tiles) {
                TilePoints cached = recentTiles.get(tileId(z, t[0], t[1]));
                if (cached != null) {
                    results.add(cached);
                } else {
                    toFetch.add(t);
                }
            }
        }

        if (!toFetch.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(THREADS, toFetch.size()),
                    Utils.newThreadFactory("tigerreview-pmtiles-%d", Thread.NORM_PRIORITY));
            List<Future<TilePoints>> futures = new ArrayList<>();
            try {
                for (int[] t : toFetch) {
                    futures.add(pool.submit(() -> readTile(archive, z, t[0], t[1])));
                }
                for (int i = 0; i < futures.size(); i++) {
                    TilePoints tp = futures.get(i).get();
                    results.add(tp);
                    int[] t = toFetch.get(i);
                    synchronized (recentTiles) {
                        recentTiles.put(tileId(z, t[0], t[1]), tp);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                }
                throw new IOException(String.valueOf(e.getCause()), e);
            } finally {
                // Drop tiles not started; let those in flight finish rather than interrupt their requests.
                for (Future<TilePoints> f : futures) {
                    f.cancel(false);
                }
                pool.shutdown();
            }
        }

        List<NadAddress> out = new ArrayList<>();
        List<SeamPoint> seam = new ArrayList<>();
        Set<String> edge = new HashSet<>();
        for (TilePoints tp : results) {
            for (NadAddress a : tp.points) {
                if (inCell(a.location(), cell)) {
                    out.add(a);
                }
            }
            seam.addAll(tp.seam);
            edge.addAll(tp.edgeKeys);
        }
        // A seam point counts unless its neighbour already gave the same point at 0 (or another
        // tile gave the same seam point: a corner).
        for (SeamPoint p : seam) {
            if (inCell(p.address.location(), cell) && edge.add(p.key)) {
                out.add(p.address);
            }
        }
        return out;
    }

    private static boolean inCell(LatLon ll, Bounds cell) {
        return ll.lon() >= cell.getMinLon() && ll.lon() < cell.getMaxLon()
                && ll.lat() >= cell.getMinLat() && ll.lat() < cell.getMaxLat();
    }

    private void clearRecentTiles() {
        synchronized (recentTiles) {
            recentTiles.clear();
        }
    }

    /** One tile's address points; {@link #NO_TILE} when the archive has no tile there. */
    private TilePoints readTile(Archive archive, int z, int x, int y) throws IOException {
        byte[] tile = archive.tile(z, x, y);
        if (tile == null) {
            return NO_TILE;
        }
        TilePoints out = new TilePoints();
        decode(tile, z, x, y, layer, out);
        return out;
    }

    /**
     * One tile's points. A point on the tile's east or south seam (coordinate exactly the extent)
     * may also be in the neighbouring tile at 0, from a buffer or from how the generator split the
     * seam, or only here; so seam points are held back until every tile is read, and checked
     * against the points read at 0.
     */
    static final class TilePoints {
        /** Points inside the tile, seam excluded. */
        final List<NadAddress> points = new ArrayList<>();
        /** Points at x or y exactly the extent. */
        final List<SeamPoint> seam = new ArrayList<>();
        /** Keys of the points at x or y exactly 0, where a neighbour's seam point would also be. */
        final Set<String> edgeKeys = new HashSet<>();
    }

    /** A point on a tile's far seam, with its identity: exact position and every property. */
    record SeamPoint(NadAddress address, String key) {
    }

    /** The {@code {x, y}} tiles at zoom {@code z} that cover the bounds. */
    static List<int[]> tilesCovering(Bounds b, int z) {
        int max = (1 << z) - 1;
        int x0 = clamp(tileX(b.getMinLon(), z), max);
        int x1 = clamp(tileX(b.getMaxLon(), z), max);
        int y0 = clamp(tileY(b.getMaxLat(), z), max);
        int y1 = clamp(tileY(b.getMinLat(), z), max);
        List<int[]> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                out.add(new int[] {x, y});
            }
        }
        return out;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    static int tileX(double lon, int z) {
        return (int) Math.floor((lon + 180) / 360 * (1 << z));
    }

    static int tileY(double lat, int z) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z));
    }

    // ---- URLs ---------------------------------------------------------------------------------

    /** The archive's URL, without the {@code #layer}. */
    static String fileUrl(String url) {
        int hash = url.indexOf('#');
        return (hash < 0 ? url : url.substring(0, hash)).trim();
    }

    /** The layer the URL names after {@code #}, or null for every layer. */
    static String layerName(String url) {
        int hash = url.indexOf('#');
        if (hash < 0 || hash == url.trim().length() - 1) {
            return null;
        }
        return URLDecoder.decode(url.substring(hash + 1).trim(), StandardCharsets.UTF_8);
    }

    // ---- the archive --------------------------------------------------------------------------

    /** The archive at the URL, opened once per session. */
    static Archive archive(String fileUrl) throws IOException {
        Archive a = ARCHIVES.get(fileUrl);
        if (a == null) {
            a = Archive.open(fileUrl);
            ARCHIVES.put(fileUrl, a);
        }
        return a;
    }

    /** Forget every opened archive (for tests). */
    static void forgetArchives() {
        ARCHIVES.clear();
    }

    /** The fixed-size header at the start of the archive. */
    static final class Header {
        long rootOffset;
        long rootLength;
        long metadataOffset;
        long metadataLength;
        long leafOffset;
        long tileDataOffset;
        int internalCompression;
        int tileCompression;
        int tileType;
        int minZoom;
        int maxZoom;

        static Header parse(byte[] b) throws IOException {
            if (b.length < HEADER_BYTES || !"PMTiles".equals(new String(b, 0, 7, StandardCharsets.US_ASCII))) {
                throw new IOException("not a PMTiles file");
            }
            if (b[7] != 3) {
                throw new IOException("PMTiles version " + b[7] + " is not supported; only version 3 can be read");
            }
            ByteBuffer bb = ByteBuffer.wrap(b, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            Header h = new Header();
            h.rootOffset = bb.getLong(8);
            h.rootLength = bb.getLong(16);
            h.metadataOffset = bb.getLong(24);
            h.metadataLength = bb.getLong(32);
            h.leafOffset = bb.getLong(40);
            h.tileDataOffset = bb.getLong(56);
            h.internalCompression = b[97] & 0xff;
            h.tileCompression = b[98] & 0xff;
            h.tileType = b[99] & 0xff;
            h.minZoom = b[100] & 0xff;
            h.maxZoom = b[101] & 0xff;
            if (h.tileType != TILE_TYPE_MVT) {
                throw new IOException("the PMTiles file holds images, not vector tiles");
            }
            checkCompression(h.internalCompression);
            checkCompression(h.tileCompression);
            if (h.maxZoom > 26 || h.maxZoom < h.minZoom) {
                throw new IOException("the PMTiles file has a broken header (zoom " + h.minZoom + " to " + h.maxZoom + ")");
            }
            return h;
        }

        private static void checkCompression(int c) throws IOException {
            if (c != COMPRESSION_NONE && c != COMPRESSION_GZIP) {
                throw new IOException("the PMTiles file uses " + (c == 3 ? "brotli" : c == 4 ? "zstd" : "unknown")
                        + " compression; only gzip or uncompressed can be read");
            }
        }
    }

    /** A decoded directory: entries sorted by tile id. A run length of 0 marks a leaf directory. */
    static final class Directory {
        final long[] tileIds;
        final long[] offsets;
        final long[] lengths;
        final long[] runLengths;

        Directory(int n) {
            tileIds = new long[n];
            offsets = new long[n];
            lengths = new long[n];
            runLengths = new long[n];
        }

        static Directory parse(byte[] b) throws IOException {
            Varints in = new Varints(b);
            long n = in.next();
            // Every entry takes at least four bytes, so a bigger count is a broken directory.
            if (n < 0 || n > b.length) {
                throw new IOException("the PMTiles file has a broken directory");
            }
            Directory d = new Directory((int) n);
            long id = 0;
            for (int i = 0; i < n; i++) {
                id += in.next();
                d.tileIds[i] = id;
            }
            for (int i = 0; i < n; i++) {
                d.runLengths[i] = in.next();
            }
            for (int i = 0; i < n; i++) {
                d.lengths[i] = in.next();
            }
            for (int i = 0; i < n; i++) {
                long v = in.next();
                // 0 means "right after the previous entry", saving the bytes of clustered archives.
                d.offsets[i] = v == 0 && i > 0 ? d.offsets[i - 1] + d.lengths[i - 1] : v - 1;
            }
            return d;
        }

        /** The entry holding the tile or the leaf directory that may, or -1. */
        int find(long tileId) {
            int lo = 0;
            int hi = tileIds.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (tileIds[mid] < tileId) {
                    lo = mid + 1;
                } else if (tileIds[mid] > tileId) {
                    hi = mid - 1;
                } else {
                    return mid;
                }
            }
            // The last entry before the tile: a leaf directory, or a run of identical tiles reaching it.
            if (hi >= 0 && (runLengths[hi] == 0 || tileId - tileIds[hi] < runLengths[hi])) {
                return hi;
            }
            return -1;
        }
    }

    /** Unsigned LEB128 varints, as directories are written. */
    private static final class Varints {
        private final byte[] b;
        private int pos;

        Varints(byte[] b) {
            this.b = b;
        }

        long next() throws IOException {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= b.length) {
                    throw new IOException("the PMTiles file has a broken directory");
                }
                byte x = b[pos++];
                v |= (long) (x & 0x7f) << shift;
                if (x >= 0) {
                    return v;
                }
            }
            throw new IOException("the PMTiles file has a broken directory");
        }
    }

    /**
     * The tile's id: the tiles of all lower zooms, then its place along the Hilbert curve that
     * orders this zoom's tiles.
     */
    static long tileId(int z, int x, int y) {
        if (z < 0 || z > 26 || x < 0 || y < 0 || x >= 1 << z || y >= 1 << z) {
            throw new IllegalArgumentException("No tile " + z + "/" + x + "/" + y);
        }
        long n = 1L << z;
        long id = (n * n - 1) / 3;
        long tx = x;
        long ty = y;
        for (long s = n / 2; s > 0; s /= 2) {
            int rx = (tx & s) > 0 ? 1 : 0;
            int ry = (ty & s) > 0 ? 1 : 0;
            id += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    tx = n - 1 - tx;
                    ty = n - 1 - ty;
                }
                long t = tx;
                tx = ty;
                ty = t;
            }
        }
        return id;
    }

    /** An opened archive: header and root directory, plus the leaf directories read so far. */
    static final class Archive {
        final String url;
        final Header header;
        final Directory root;
        /** The ETag of the first read, to notice the file being replaced mid-session; may be null. */
        final String etag;
        /** The ETag, or failing that Last-Modified, identifying this release; may be null. */
        final String version;
        private final Map<Long, Directory> leaves = new ConcurrentHashMap<>();

        private Archive(String url, Header header, Directory root, String etag, String version) {
            this.url = url;
            this.header = header;
            this.root = root;
            this.etag = etag;
            this.version = version;
        }

        static Archive open(String url) throws IOException {
            Range first = readRange(url, 0, FIRST_READ, null, true);
            Header h = Header.parse(first.bytes);
            byte[] rootBytes;
            if (h.rootOffset + h.rootLength <= first.bytes.length) {
                rootBytes = Arrays.copyOfRange(first.bytes, (int) h.rootOffset, (int) (h.rootOffset + h.rootLength));
            } else {
                rootBytes = readRange(url, h.rootOffset, length(h.rootLength), first.etag, false).bytes;
            }
            String version = first.etag != null ? first.etag : first.lastModified;
            return new Archive(url, h, Directory.parse(decompress(rootBytes, h.internalCompression)), first.etag, version);
        }

        /** The decoded tile bytes, or null when the archive has no tile there (nothing at that spot). */
        byte[] tile(int z, int x, int y) throws IOException {
            long id = tileId(z, x, y);
            Directory d = root;
            for (int depth = 0; depth < MAX_DEPTH; depth++) {
                int i = d.find(id);
                if (i < 0) {
                    return null;
                }
                if (d.runLengths[i] > 0) {
                    byte[] raw = read(header.tileDataOffset + d.offsets[i], length(d.lengths[i]));
                    return decompress(raw, header.tileCompression);
                }
                d = leaf(d.offsets[i], d.lengths[i]);
            }
            throw new IOException("the PMTiles file has directories nested too deep");
        }

        private Directory leaf(long offset, long length) throws IOException {
            Directory d = leaves.get(offset);
            if (d == null) {
                d = Directory.parse(decompress(read(header.leafOffset + offset, length(length)), header.internalCompression));
                if (leaves.size() >= MAX_LEAVES) {
                    leaves.clear();
                }
                leaves.put(offset, d);
            }
            return d;
        }

        private byte[] read(long offset, int length) throws IOException {
            return readRange(url, offset, length, etag, false).bytes;
        }
    }

    private static int length(long l) throws IOException {
        if (l < 0 || l > Integer.MAX_VALUE - 8) {
            throw new IOException("the PMTiles file has a broken directory");
        }
        return (int) l;
    }

    /** The file behind the URL is not the one whose header we read. */
    static final class ArchiveChangedException extends IOException {
        private static final long serialVersionUID = 1L;

        ArchiveChangedException(String url) {
            super("PMTiles file changed on the server: " + url);
        }
    }

    private record Range(byte[] bytes, String etag, String lastModified) {
    }

    /**
     * {@code length} bytes from {@code offset}. The server must honour the range: an answer with
     * the whole file is refused unread, since it can be gigabytes. {@code mayBeShort} allows
     * fewer bytes, for the opening read of a file smaller than 16 KiB.
     */
    private static Range readRange(String url, long offset, int length, String expectedEtag, boolean mayBeShort)
            throws IOException {
        if (length == 0) {
            return new Range(new byte[0], expectedEtag, null);
        }
        Logging.debug("PMTiles range " + offset + "+" + length + ": " + url);
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("not a valid web address: " + url, e);
        }
        HttpClient.Response resp = HttpClient.create(uri.toURL())
                .setHeader("Range", "bytes=" + offset + "-" + (offset + length - 1))
                // Ranges count bytes of the stored file; a compressed answer would shift them.
                .setHeader("Accept-Encoding", "identity")
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .connect();
        try {
            int code = resp.getResponseCode();
            if (code == 416 && !mayBeShort) {
                // Past the end of the file whose directory we hold: it was replaced by a smaller one.
                ARCHIVES.remove(url);
                throw new ArchiveChangedException(url);
            }
            boolean wholeSmallFile = code == 200 && offset == 0 && mayBeShort && resp.getContentLength() >= 0
                    && resp.getContentLength() <= length;
            if (code == 200 && !wholeSmallFile) {
                throw new IOException("the server does not support range requests, which reading a PMTiles file needs");
            }
            if (code != 206 && !wholeSmallFile) {
                throw new IOException(code == 404 || code == 403 ? "file not found (HTTP " + code + ")"
                        : "server error (HTTP " + code + ")");
            }
            String etag = resp.getHeaderField("ETag");
            if (expectedEtag != null && etag != null && !Objects.equals(expectedEtag, etag)) {
                ARCHIVES.remove(url);
                throw new ArchiveChangedException(url);
            }
            byte[] b;
            try (InputStream in = resp.getContent()) {
                b = in.readNBytes(length);
            }
            if (b.length < length && !mayBeShort) {
                throw new IOException("the server sent " + b.length + " of " + length + " bytes");
            }
            return new Range(b, etag, resp.getHeaderField("Last-Modified"));
        } finally {
            resp.disconnect();
        }
    }

    static byte[] decompress(byte[] b, int compression) throws IOException {
        if (compression == COMPRESSION_NONE) {
            return b;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            return in.readAllBytes();
        }
    }

    // ---- vector tiles -------------------------------------------------------------------------

    /**
     * Add the tile's address points to {@code out}. The tile owns the closed range 0 to extent;
     * points beyond it are in the buffer, belong to a neighbour, and are skipped. Points exactly
     * on the far seam go to {@link TilePoints#seam}. Features without a street are skipped.
     */
    static void decode(byte[] mvt, int z, int x, int y, String layerName, TilePoints out) throws IOException {
        double n = 1 << z;
        for (MvtParser.Layer layer : MvtParser.parse(mvt)) {
            if (layerName != null && !layerName.equals(layer.name)) {
                continue;
            }
            int streetKey = keyIndex(layer, STREET_KEYS);
            if (streetKey < 0) {
                continue;
            }
            int numberKey = keyIndex(layer, HOUSENUMBER_KEYS);
            int cityKey = keyIndex(layer, CITY_KEYS);
            int stateKey = keyIndex(layer, STATE_KEYS);
            int postcodeKey = keyIndex(layer, POSTCODE_KEYS);
            int extent = layer.extent;
            for (MvtParser.Feature f : layer.features) {
                if (f.geomType != GEOM_POINT) {
                    continue;
                }
                String street = value(f, layer, streetKey);
                if (street == null || street.isBlank()) {
                    continue;
                }
                for (int[] p : f.decodePoints()) {
                    int px = p[0];
                    int py = p[1];
                    if (px < 0 || py < 0 || px > extent || py > extent) {
                        continue;
                    }
                    double lon = (x + px / (double) extent) / n * 360 - 180;
                    double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + py / (double) extent) / n))));
                    NadAddress address = new NadAddress(street, value(f, layer, numberKey), value(f, layer, cityKey),
                            value(f, layer, stateKey), value(f, layer, postcodeKey), new LatLon(lat, lon));
                    if (px == extent || py == extent) {
                        out.seam.add(new SeamPoint(address, key(f, layer, lon, lat)));
                    } else {
                        out.points.add(address);
                        if (px == 0 || py == 0) {
                            out.edgeKeys.add(key(f, layer, lon, lat));
                        }
                    }
                }
            }
        }
    }

    /** The index in the layer's key table of the first key that matches one of the names, or -1. */
    private static int keyIndex(MvtParser.Layer layer, Set<String> names) {
        for (int i = 0; i < layer.keys.size(); i++) {
            if (names.contains(normalizeKey(layer.keys.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** A property name lowercased with punctuation removed: {@code addr:street} becomes {@code addrstreet}. */
    static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The feature's value for the key at {@code keyIndex}, as a string, or null. */
    private static String value(MvtParser.Feature f, MvtParser.Layer layer, int keyIndex) {
        if (keyIndex < 0) {
            return null;
        }
        for (int i = 0; i + 1 < f.tags.size(); i += 2) {
            if (f.tags.get(i) == keyIndex) {
                int v = f.tags.get(i + 1);
                Object value = v < layer.values.size() ? layer.values.get(v) : null;
                return value == null ? null : value.toString();
            }
        }
        return null;
    }

    /** A point's identity: exact position and every property. Seam copies compute the same doubles. */
    private static String key(MvtParser.Feature f, MvtParser.Layer layer, double lon, double lat) {
        Map<String, String> props = new TreeMap<>();
        for (int i = 0; i + 1 < f.tags.size(); i += 2) {
            int k = f.tags.get(i);
            int v = f.tags.get(i + 1);
            if (k < layer.keys.size() && v < layer.values.size()) {
                props.put(layer.keys.get(k), String.valueOf(layer.values.get(v)));
            }
        }
        return lon + "," + lat + props;
    }
}
