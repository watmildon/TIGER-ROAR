// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.HttpClient.Response;
import org.openstreetmap.josm.tools.Logging;

/**
 * Client for querying Mapillary traffic sign vector tiles for speed limit detections.
 *
 * <p>Uses the Mapillary vector tile endpoint which provides reliable access to
 * traffic sign detections. The Entity API (graph.mapillary.com/map_features)
 * is unreliable due to incomplete reprocessing, so we use vector tiles instead.</p>
 *
 * <p>Endpoint: {@code https://tiles.mapillary.com/maps/vtp/mly_map_feature_traffic_sign/2/{z}/{x}/{y}}</p>
 * <p>Tiles are Mapbox Vector Tiles (MVT) at zoom level 14 only.</p>
 */
public class MapillaryClient {

    private static final String TILE_BASE_URL =
            "https://tiles.mapillary.com/maps/vtp/mly_map_feature_traffic_sign/2";

    /** Zoom level for Mapillary traffic sign tiles (only z=14 is supported) */
    private static final int TILE_ZOOM = 14;

    /** Connection timeout in milliseconds */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Read timeout in milliseconds */
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Maximum number of retry attempts for timeout errors */
    private static final int MAX_RETRIES = 2;

    /** Base delay between retries in milliseconds (doubles each retry) */
    private static final long RETRY_BASE_DELAY_MS = 2_000;

    /** Maximum area in square degrees before skipping fetch */
    private static final double MAX_AREA_DEGREES = 0.25;

    /** Safety limit on total detections to avoid memory issues */
    private static final int MAX_TOTAL_DETECTIONS = 50_000;

    /** Tile base URL for point features (lane markings, crosswalks, etc.) */
    private static final String POINT_TILE_BASE_URL =
            "https://tiles.mapillary.com/maps/vtp/mly_map_feature_point/2";

    /** Pattern to extract speed value from object_value like regulatory--maximum-speed-limit-35--g3 */
    private static final Pattern SPEED_PATTERN = Pattern.compile(
            "regulatory--maximum-speed-limit-(\\d+)--(.+)");

    /** Prefix for all lane/road marking detection values */
    private static final String MARKING_PREFIX = "marking--";

    /**
     * Represents a speed limit sign detection from Mapillary.
     */
    public record SpeedLimitDetection(
            String id,
            int speedValue,
            LatLon location,
            String objectValue,
            String firstSeenAt,
            String lastSeenAt
    ) {}

    /**
     * Represents a road marking detection from Mapillary (lane lines, crosswalks, stop lines, etc.).
     * The presence of any road marking near a way is strong evidence the road is paved.
     */
    public record MarkingDetection(
            String id,
            LatLon location,
            String objectValue
    ) {}

    /**
     * Result of a Mapillary query operation.
     */
    public record MapillaryQueryResult(
            List<SpeedLimitDetection> detections,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null;
        }

        public static MapillaryQueryResult success(List<SpeedLimitDetection> detections) {
            return new MapillaryQueryResult(detections, null);
        }

        public static MapillaryQueryResult error(String message) {
            return new MapillaryQueryResult(List.of(), message);
        }
    }

    /**
     * Result of a Mapillary marking query operation.
     */
    public record MarkingQueryResult(
            List<MarkingDetection> markings,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null;
        }

        public static MarkingQueryResult success(List<MarkingDetection> markings) {
            return new MarkingQueryResult(markings, null);
        }

        public static MarkingQueryResult error(String message) {
            return new MarkingQueryResult(List.of(), message);
        }
    }

    /**
     * Query Mapillary for speed limit sign detections within the given bounds.
     *
     * <p>Computes the set of zoom-14 tiles covering the bounds and fetches
     * each MVT tile, extracting speed limit sign features.</p>
     *
     * @param bounds   The geographic bounds to query
     * @param apiToken The Mapillary OAuth token
     * @return Query result containing detections or error
     */
    public MapillaryQueryResult queryDetections(Bounds bounds, String apiToken) {
        if (apiToken == null || apiToken.isEmpty()) {
            return MapillaryQueryResult.error("Mapillary API token not configured");
        }

        double area = bounds.getArea();
        if (area > MAX_AREA_DEGREES) {
            return MapillaryQueryResult.error(
                    String.format("Area too large for Mapillary query (%.4f sq degrees > %.4f max). " +
                            "Download a smaller area.", area, MAX_AREA_DEGREES));
        }

        // Compute z14 tile range
        int minTileX = lonToTileX(bounds.getMinLon(), TILE_ZOOM);
        int maxTileX = lonToTileX(bounds.getMaxLon(), TILE_ZOOM);
        int minTileY = latToTileY(bounds.getMaxLat(), TILE_ZOOM); // note: Y is inverted
        int maxTileY = latToTileY(bounds.getMinLat(), TILE_ZOOM);

        int tileCount = (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1);
        Logging.info("Mapillary: fetching " + tileCount + " vector tile(s) for bounds " + bounds);

        List<SpeedLimitDetection> allDetections = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        try {
            for (int tx = minTileX; tx <= maxTileX; tx++) {
                for (int ty = minTileY; ty <= maxTileY; ty++) {
                    MapillaryQueryResult tileResult = fetchVectorTile(tx, ty, apiToken, bounds);
                    if (!tileResult.isSuccess()) {
                        return tileResult;
                    }
                    // Deduplicate by feature ID (features near tile edges may appear in multiple tiles)
                    for (SpeedLimitDetection det : tileResult.detections()) {
                        if (det.id() != null && seenIds.add(det.id())) {
                            allDetections.add(det);
                        }
                    }

                    if (allDetections.size() >= MAX_TOTAL_DETECTIONS) {
                        Logging.warn("Mapillary query hit safety limit of " + MAX_TOTAL_DETECTIONS + " detections");
                        return MapillaryQueryResult.success(allDetections);
                    }
                }
            }

            Logging.info("Mapillary: found " + allDetections.size() + " speed limit detections");
            return MapillaryQueryResult.success(allDetections);

        } catch (Exception e) {
            Logging.error("Mapillary query failed: " + e.getMessage());
            return MapillaryQueryResult.error("Mapillary query failed: " + e.getMessage());
        }
    }

    /**
     * Fetch and parse a single z14 MVT tile, retrying on timeout.
     *
     * @param tileX    tile X coordinate
     * @param tileY    tile Y coordinate
     * @param apiToken the API access token
     * @param bounds   the query bounds (used to filter features outside the requested area)
     * @return detections found in this tile
     */
    private MapillaryQueryResult fetchVectorTile(int tileX, int tileY, String apiToken,
                                                  Bounds bounds) throws IOException {
        String encodedToken = apiToken.replace("|", "%7C");
        String url = TILE_BASE_URL + "/" + TILE_ZOOM + "/" + tileX + "/" + tileY
                + "?access_token=" + encodedToken;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                Logging.info("Mapillary tile retry " + attempt + "/" + MAX_RETRIES + " after " + delay + "ms");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return MapillaryQueryResult.error("Mapillary query interrupted");
                }
            }

            Logging.info("Mapillary tile: z=" + TILE_ZOOM + " x=" + tileX + " y=" + tileY);

            Response response;
            try {
                response = HttpClient.create(URI.create(url).toURL())
                        .setAccept("application/x-protobuf")
                        .setConnectTimeout(CONNECT_TIMEOUT_MS)
                        .setReadTimeout(READ_TIMEOUT_MS)
                        .connect();
            } catch (SocketTimeoutException e) {
                if (attempt < MAX_RETRIES) {
                    Logging.warn("Mapillary tile fetch timed out, will retry: " + e.getMessage());
                    continue;
                }
                throw e;
            }

            try {
                int responseCode = response.getResponseCode();

                if (responseCode == 401 || responseCode == 403) {
                    return MapillaryQueryResult.error("Mapillary API authentication failed (HTTP " + responseCode
                            + "). Check your API token in preferences.");
                }

                if (responseCode == 204 || responseCode == 404) {
                    // No data for this tile
                    return MapillaryQueryResult.success(List.of());
                }

                if (responseCode != 200) {
                    return MapillaryQueryResult.error("Mapillary API returned HTTP " + responseCode);
                }

                byte[] tileData;
                try {
                    tileData = readAllBytes(response);
                } catch (SocketTimeoutException e) {
                    response.disconnect();
                    if (attempt < MAX_RETRIES) {
                        Logging.warn("Mapillary tile read timed out, will retry: " + e.getMessage());
                        continue;
                    }
                    throw e;
                }

                if (tileData.length == 0) {
                    return MapillaryQueryResult.success(List.of());
                }

                List<SpeedLimitDetection> detections = parseMvtTile(tileData, tileX, tileY, bounds);
                return MapillaryQueryResult.success(detections);

            } finally {
                response.disconnect();
            }
        }

        return MapillaryQueryResult.error("Mapillary tile fetch failed after " + (MAX_RETRIES + 1) + " attempts");
    }

    /**
     * Read all bytes from an HTTP response.
     */
    private byte[] readAllBytes(Response response) throws IOException {
        try (InputStream is = response.getContent()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    /**
     * Parse an MVT tile and extract speed limit sign detections.
     *
     * @param tileData raw MVT protobuf bytes
     * @param tileX    tile X coordinate (for coordinate conversion)
     * @param tileY    tile Y coordinate (for coordinate conversion)
     * @param bounds   query bounds for filtering
     * @return list of speed limit detections found in the tile
     */
    private List<SpeedLimitDetection> parseMvtTile(byte[] tileData, int tileX, int tileY,
                                                    Bounds bounds) {
        List<SpeedLimitDetection> detections = new ArrayList<>();

        List<MvtParser.Layer> layers;
        try {
            layers = MvtParser.parse(tileData);
        } catch (IOException e) {
            Logging.warn("Failed to parse Mapillary MVT tile: " + e.getMessage());
            return detections;
        }

        for (MvtParser.Layer layer : layers) {
            // We want the traffic_sign layer
            if (!"traffic_sign".equals(layer.name)) {
                continue;
            }

            for (MvtParser.Feature feature : layer.features) {
                // Only point features
                if (feature.geomType != 1) continue;

                // Get the sign classification value
                Object valueObj = feature.getProperty("value", layer);
                if (!(valueObj instanceof String objectValue)) continue;

                // Filter for speed limit signs
                Matcher speedMatcher = SPEED_PATTERN.matcher(objectValue);
                if (!speedMatcher.matches()) continue;

                int speedValue;
                try {
                    speedValue = Integer.parseInt(speedMatcher.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }

                // Decode point geometry to lat/lon
                int[] point = feature.decodePoint();
                if (point == null) continue;

                LatLon location = tilePointToLatLon(point[0], point[1], tileX, tileY, layer.extent);

                // Filter to requested bounds
                if (!bounds.contains(location)) continue;

                // Extract timestamps
                Object firstSeenObj = feature.getProperty("first_seen_at", layer);
                Object lastSeenObj = feature.getProperty("last_seen_at", layer);
                String firstSeenAt = firstSeenObj instanceof Long ts
                        ? String.valueOf(ts) : firstSeenObj instanceof String s ? s : null;
                String lastSeenAt = lastSeenObj instanceof Long ts
                        ? String.valueOf(ts) : lastSeenObj instanceof String s ? s : null;

                String id = String.valueOf(feature.id);

                detections.add(new SpeedLimitDetection(
                        id, speedValue, location, objectValue, firstSeenAt, lastSeenAt));
            }
        }

        return detections;
    }

    /**
     * Convert a tile-relative point to geographic coordinates.
     *
     * @param px     x coordinate within tile (0..extent)
     * @param py     y coordinate within tile (0..extent)
     * @param tileX  tile X index
     * @param tileY  tile Y index
     * @param extent tile extent (typically 4096)
     * @return the geographic location
     */
    private static LatLon tilePointToLatLon(int px, int py, int tileX, int tileY, int extent) {
        double x = (tileX + (double) px / extent);
        double y = (tileY + (double) py / extent);

        double n = Math.pow(2.0, TILE_ZOOM);
        double lon = x / n * 360.0 - 180.0;
        double latRad = Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n)));
        double lat = Math.toDegrees(latRad);

        return new LatLon(lat, lon);
    }

    /**
     * Query Mapillary for road marking detections within the given bounds.
     *
     * <p>Uses the point feature tile layer ({@code mly_map_feature_point})
     * and filters for features whose value starts with {@code marking--}.</p>
     *
     * @param bounds   The geographic bounds to query
     * @param apiToken The Mapillary OAuth token
     * @return Query result containing marking detections or error
     */
    public MarkingQueryResult queryMarkings(Bounds bounds, String apiToken) {
        if (apiToken == null || apiToken.isEmpty()) {
            return MarkingQueryResult.error("Mapillary API token not configured");
        }

        double area = bounds.getArea();
        if (area > MAX_AREA_DEGREES) {
            return MarkingQueryResult.error(
                    String.format("Area too large for Mapillary query (%.4f sq degrees > %.4f max). " +
                            "Download a smaller area.", area, MAX_AREA_DEGREES));
        }

        int minTileX = lonToTileX(bounds.getMinLon(), TILE_ZOOM);
        int maxTileX = lonToTileX(bounds.getMaxLon(), TILE_ZOOM);
        int minTileY = latToTileY(bounds.getMaxLat(), TILE_ZOOM);
        int maxTileY = latToTileY(bounds.getMinLat(), TILE_ZOOM);

        int tileCount = (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1);
        Logging.info("Mapillary markings: fetching " + tileCount + " vector tile(s) for bounds " + bounds);

        List<MarkingDetection> allMarkings = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        try {
            for (int tx = minTileX; tx <= maxTileX; tx++) {
                for (int ty = minTileY; ty <= maxTileY; ty++) {
                    MarkingQueryResult tileResult = fetchMarkingTile(tx, ty, apiToken, bounds);
                    if (!tileResult.isSuccess()) {
                        return tileResult;
                    }
                    for (MarkingDetection det : tileResult.markings()) {
                        if (det.id() != null && seenIds.add(det.id())) {
                            allMarkings.add(det);
                        }
                    }

                    if (allMarkings.size() >= MAX_TOTAL_DETECTIONS) {
                        Logging.warn("Mapillary markings query hit safety limit of " + MAX_TOTAL_DETECTIONS);
                        return MarkingQueryResult.success(allMarkings);
                    }
                }
            }

            Logging.info("Mapillary: found " + allMarkings.size() + " road marking detections");
            return MarkingQueryResult.success(allMarkings);

        } catch (Exception e) {
            Logging.error("Mapillary markings query failed: " + e.getMessage());
            return MarkingQueryResult.error("Mapillary markings query failed: " + e.getMessage());
        }
    }

    /**
     * Fetch and parse a single z14 point feature MVT tile for road markings.
     */
    private MarkingQueryResult fetchMarkingTile(int tileX, int tileY, String apiToken,
                                                  Bounds bounds) throws IOException {
        String encodedToken = apiToken.replace("|", "%7C");
        String url = POINT_TILE_BASE_URL + "/" + TILE_ZOOM + "/" + tileX + "/" + tileY
                + "?access_token=" + encodedToken;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                Logging.info("Mapillary marking tile retry " + attempt + "/" + MAX_RETRIES + " after " + delay + "ms");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return MarkingQueryResult.error("Mapillary query interrupted");
                }
            }

            Response response;
            try {
                response = HttpClient.create(URI.create(url).toURL())
                        .setAccept("application/x-protobuf")
                        .setConnectTimeout(CONNECT_TIMEOUT_MS)
                        .setReadTimeout(READ_TIMEOUT_MS)
                        .connect();
            } catch (SocketTimeoutException e) {
                if (attempt < MAX_RETRIES) {
                    Logging.warn("Mapillary marking tile fetch timed out, will retry: " + e.getMessage());
                    continue;
                }
                throw e;
            }

            try {
                int responseCode = response.getResponseCode();

                if (responseCode == 401 || responseCode == 403) {
                    return MarkingQueryResult.error("Mapillary API authentication failed (HTTP " + responseCode
                            + "). Check your API token in preferences.");
                }

                if (responseCode == 204 || responseCode == 404) {
                    return MarkingQueryResult.success(List.of());
                }

                if (responseCode != 200) {
                    return MarkingQueryResult.error("Mapillary API returned HTTP " + responseCode);
                }

                byte[] tileData;
                try {
                    tileData = readAllBytes(response);
                } catch (SocketTimeoutException e) {
                    response.disconnect();
                    if (attempt < MAX_RETRIES) {
                        Logging.warn("Mapillary marking tile read timed out, will retry: " + e.getMessage());
                        continue;
                    }
                    throw e;
                }

                if (tileData.length == 0) {
                    return MarkingQueryResult.success(List.of());
                }

                List<MarkingDetection> markings = parseMarkingMvtTile(tileData, tileX, tileY, bounds);
                return MarkingQueryResult.success(markings);

            } finally {
                response.disconnect();
            }
        }

        return MarkingQueryResult.error("Mapillary marking tile fetch failed after " + (MAX_RETRIES + 1) + " attempts");
    }

    /**
     * Parse an MVT tile and extract road marking detections.
     * Accepts any feature whose {@code value} starts with {@code marking--}.
     */
    private List<MarkingDetection> parseMarkingMvtTile(byte[] tileData, int tileX, int tileY,
                                                         Bounds bounds) {
        List<MarkingDetection> markings = new ArrayList<>();

        List<MvtParser.Layer> layers;
        try {
            layers = MvtParser.parse(tileData);
        } catch (IOException e) {
            Logging.warn("Failed to parse Mapillary marking MVT tile: " + e.getMessage());
            return markings;
        }

        for (MvtParser.Layer layer : layers) {
            // Point features layer name is "point"
            if (!"point".equals(layer.name)) {
                continue;
            }

            for (MvtParser.Feature feature : layer.features) {
                if (feature.geomType != 1) continue;

                Object valueObj = feature.getProperty("value", layer);
                if (!(valueObj instanceof String objectValue)) continue;

                if (!objectValue.startsWith(MARKING_PREFIX)) continue;

                int[] point = feature.decodePoint();
                if (point == null) continue;

                LatLon location = tilePointToLatLon(point[0], point[1], tileX, tileY, layer.extent);

                if (!bounds.contains(location)) continue;

                String id = String.valueOf(feature.id);

                markings.add(new MarkingDetection(id, location, objectValue));
            }
        }

        return markings;
    }

    /**
     * Convert longitude to tile X index at the given zoom.
     */
    private static int lonToTileX(double lon, int zoom) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
    }

    /**
     * Convert latitude to tile Y index at the given zoom.
     */
    private static int latToTileY(double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        return (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad))
                / Math.PI) / 2.0 * (1 << zoom));
    }
}
