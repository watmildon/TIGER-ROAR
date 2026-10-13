// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpServer;

/**
 * Builds small PMTiles v3 archives of vector tiles for tests, and serves them by range the way
 * object storage does. Ported from the Better Address Conflation plugin's tests.
 */
final class PmtilesFixture {
    private PmtilesFixture() {
    }

    // ---- protobuf -----------------------------------------------------------------------------

    /** Just enough protobuf writing for vector tiles. */
    static final class Pb {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb varint(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
            return this;
        }

        Pb uint(int field, long v) {
            return varint(field << 3).varint(v);
        }

        Pb bytes(int field, byte[] b) {
            varint(field << 3 | 2).varint(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        Pb string(int field, String s) {
            return bytes(field, s.getBytes(StandardCharsets.UTF_8));
        }

        Pb packed(int field, long... values) {
            Pb p = new Pb();
            for (long v : values) {
                p.varint(v);
            }
            return bytes(field, p.toBytes());
        }

        byte[] toBytes() {
            return out.toByteArray();
        }
    }

    static long zigzag(long v) {
        return (v << 1) ^ (v >> 63);
    }

    /** One vector tile layer being built. */
    static final class MvtLayer {
        final String name;
        final int extent;
        final List<String> keys = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        final List<byte[]> features = new ArrayList<>();

        MvtLayer(String name, int extent) {
            this.name = name;
            this.extent = extent;
        }

        /** A point at tile coordinates (px, py) with string properties. */
        MvtLayer point(int px, int py, Map<String, String> tags) {
            features.add(new Pb().packed(2, tagIndexes(tags)).uint(3, 1)
                    .packed(4, 1 | 1 << 3, zigzag(px), zigzag(py)).toBytes());
            return this;
        }

        /** A square polygon. */
        MvtLayer square(int px, int py, int size, Map<String, String> tags) {
            features.add(new Pb().packed(2, tagIndexes(tags)).uint(3, 3)
                    .packed(4, 1 | 1 << 3, zigzag(px), zigzag(py), 2 | 3 << 3, zigzag(size), 0, 0, zigzag(size), zigzag(-size), 0, 7 | 1 << 3)
                    .toBytes());
            return this;
        }

        private long[] tagIndexes(Map<String, String> tags) {
            long[] t = new long[tags.size() * 2];
            int i = 0;
            for (Map.Entry<String, String> e : tags.entrySet()) {
                t[i++] = index(keys, e.getKey());
                t[i++] = index(values, e.getValue());
            }
            return t;
        }

        private static int index(List<String> list, String s) {
            int i = list.indexOf(s);
            if (i < 0) {
                list.add(s);
                i = list.size() - 1;
            }
            return i;
        }

        byte[] toBytes() {
            Pb p = new Pb().uint(15, 2).string(1, name);
            for (byte[] f : features) {
                p.bytes(2, f);
            }
            for (String k : keys) {
                p.string(3, k);
            }
            for (String v : values) {
                p.bytes(4, new Pb().string(1, v).toBytes());
            }
            return p.uint(5, extent).toBytes();
        }
    }

    /** A tile of the given layers. */
    static byte[] tile(MvtLayer... layers) {
        Pb p = new Pb();
        for (MvtLayer l : layers) {
            p.bytes(3, l.toBytes());
        }
        return p.toBytes();
    }

    static Map<String, String> tags(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static double lon(int z, double x) {
        return x / (1 << z) * 360 - 180;
    }

    static double lat(int z, double y) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / (1 << z)))));
    }

    // ---- archive ------------------------------------------------------------------------------

    /** An archive under construction. */
    static final class Archive {
        final Map<Long, byte[]> tiles = new TreeMap<>();
        int minZoom = 10;
        int maxZoom = 14;
        String metadata = "{}";
        /** Put the entries in a leaf directory below the root. */
        boolean leaf;
        int tileCompression = PmtilesClient.COMPRESSION_GZIP;
        int internalCompression = PmtilesClient.COMPRESSION_GZIP;
        int tileType = PmtilesClient.TILE_TYPE_MVT;
        double[] bounds = {-180, -85, 180, 85};

        Archive tile(int z, int x, int y, byte[] mvt) {
            tiles.put(PmtilesClient.tileId(z, x, y), mvt);
            return this;
        }

        byte[] build() throws IOException {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            List<long[]> entries = new ArrayList<>();
            for (Map.Entry<Long, byte[]> t : tiles.entrySet()) {
                byte[] b = compress(t.getValue(), tileCompression);
                entries.add(new long[] {t.getKey(), data.size(), b.length, 1});
                data.write(b);
            }
            byte[] leafDir = new byte[0];
            byte[] rootDir;
            if (leaf) {
                leafDir = compress(directory(entries), internalCompression);
                long first = entries.isEmpty() ? 0 : entries.get(0)[0];
                List<long[]> root = new ArrayList<>();
                root.add(new long[] {first, 0, leafDir.length, 0});
                rootDir = compress(directory(root), internalCompression);
            } else {
                rootDir = compress(directory(entries), internalCompression);
            }
            byte[] meta = compress(metadata.getBytes(StandardCharsets.UTF_8), internalCompression);
            long rootOffset = PmtilesClient.HEADER_BYTES;
            long metaOffset = rootOffset + rootDir.length;
            long leafOffset = metaOffset + meta.length;
            long dataOffset = leafOffset + leafDir.length;
            ByteBuffer h = ByteBuffer.allocate(PmtilesClient.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            h.put("PMTiles".getBytes(StandardCharsets.US_ASCII)).put((byte) 3);
            h.putLong(rootOffset).putLong(rootDir.length).putLong(metaOffset).putLong(meta.length)
                    .putLong(leafOffset).putLong(leafDir.length).putLong(dataOffset).putLong(data.size())
                    .putLong(tiles.size()).putLong(tiles.size()).putLong(tiles.size());
            h.put((byte) 1).put((byte) internalCompression).put((byte) tileCompression).put((byte) tileType)
                    .put((byte) minZoom).put((byte) maxZoom);
            h.putInt(e7(bounds[0])).putInt(e7(bounds[1])).putInt(e7(bounds[2])).putInt(e7(bounds[3]));
            h.put((byte) maxZoom).putInt(e7((bounds[0] + bounds[2]) / 2)).putInt(e7((bounds[1] + bounds[3]) / 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(h.array());
            out.write(rootDir);
            out.write(meta);
            out.write(leafDir);
            data.writeTo(out);
            return out.toByteArray();
        }
    }

    private static int e7(double deg) {
        return (int) Math.round(deg * 1e7);
    }

    /** Entries of {tile id, offset, length, run length}, offsets written the clustered way where they can be. */
    static byte[] directory(List<long[]> entries) {
        Pb p = new Pb().varint(entries.size());
        long last = 0;
        for (long[] e : entries) {
            p.varint(e[0] - last);
            last = e[0];
        }
        for (long[] e : entries) {
            p.varint(e[3]);
        }
        for (long[] e : entries) {
            p.varint(e[2]);
        }
        for (int i = 0; i < entries.size(); i++) {
            long[] e = entries.get(i);
            boolean follows = i > 0 && e[1] == entries.get(i - 1)[1] + entries.get(i - 1)[2];
            p.varint(follows ? 0 : e[1] + 1);
        }
        return p.toBytes();
    }

    static byte[] compress(byte[] b, int compression) throws IOException {
        if (compression != PmtilesClient.COMPRESSION_GZIP) {
            return b;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(b);
        }
        return out.toByteArray();
    }

    // ---- server -------------------------------------------------------------------------------

    /** Serves one file at {@code /x.pmtiles} by range, with an ETag. */
    static final class RangeServer implements AutoCloseable {
        private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");
        private final HttpServer server;
        volatile byte[] file;
        volatile String etag = "\"1\"";
        /** False to behave like a server that ignores Range and sends the whole file. */
        volatile boolean honourRange = true;
        final AtomicInteger requests = new AtomicInteger();

        RangeServer(byte[] file) throws IOException {
            this.file = file;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                requests.incrementAndGet();
                byte[] f = this.file;
                if (!"/x.pmtiles".equals(ex.getRequestURI().getPath())) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                Matcher m = RANGE.matcher(String.valueOf(ex.getRequestHeaders().getFirst("Range")));
                byte[] body = f;
                int status = 200;
                if (honourRange && m.matches()) {
                    int a = Integer.parseInt(m.group(1));
                    if (a >= f.length) {
                        // As object storage answers a range past the end.
                        ex.getResponseHeaders().add("Content-Range", "bytes */" + f.length);
                        ex.getResponseHeaders().add("ETag", etag);
                        ex.sendResponseHeaders(416, -1);
                        ex.close();
                        return;
                    }
                    int b = Math.min(Integer.parseInt(m.group(2)), f.length - 1);
                    body = java.util.Arrays.copyOfRange(f, a, b + 1);
                    status = 206;
                    ex.getResponseHeaders().add("Content-Range", "bytes " + a + "-" + b + "/" + f.length);
                }
                ex.getResponseHeaders().add("ETag", etag);
                ex.sendResponseHeaders(status, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                } catch (IOException e) {
                    // The client hung up, as it should on a whole-file answer.
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/x.pmtiles";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
