// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.HttpClient.Response;
import org.openstreetmap.josm.tools.Logging;

/**
 * Client for querying the National Address Database (NAD) via ESRI ArcGIS REST API.
 *
 * Uses JOSM's HttpClient and the GeoJSON response format, matching the query style
 * used by MapWithAI and the Rapid editor against this ESRI Feature Server.
 *
 * Endpoint: https://services6.arcgis.com/Do88DoK2xjTUCXd1/arcgis/rest/services/USA_NAD_Addresses/FeatureServer/0
 */
public class NadClient {

    private static final String NAD_BASE_URL =
            "https://services6.arcgis.com/Do88DoK2xjTUCXd1/arcgis/rest/services/USA_NAD_Addresses/FeatureServer/0/query";

    /** Maximum features per request (ESRI limit is 2000) */
    private static final int MAX_FEATURES_PER_REQUEST = 2000;

    /** Connection timeout in milliseconds */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Read timeout in milliseconds */
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Maximum area in square degrees before skipping fetch */
    private static final double MAX_AREA_DEGREES = 0.25; // ~roughly 25km x 25km at mid-latitudes

    /**
     * Represents an address from the NAD.
     */
    public record NadAddress(
            String street,
            String houseNumber,
            String city,
            String state,
            String postcode,
            LatLon location
    ) {}

    /**
     * Result of a NAD query operation.
     */
    public record NadQueryResult(
            List<NadAddress> addresses,
            boolean hasMore,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null;
        }

        public static NadQueryResult success(List<NadAddress> addresses, boolean hasMore) {
            return new NadQueryResult(addresses, hasMore, null);
        }

        public static NadQueryResult error(String message) {
            return new NadQueryResult(List.of(), false, message);
        }
    }

    /**
     * Query NAD addresses within the given bounds.
     *
     * @param bounds The geographic bounds to query
     * @return Query result containing addresses or error
     */
    public NadQueryResult queryAddresses(Bounds bounds) {
        // Check if area is too large
        double area = bounds.getArea();
        if (area > MAX_AREA_DEGREES) {
            return NadQueryResult.error(
                    String.format("Area too large for NAD query (%.4f sq degrees > %.4f max). " +
                            "Download a smaller area.", area, MAX_AREA_DEGREES));
        }

        List<NadAddress> allAddresses = new ArrayList<>();
        int offset = 0;
        boolean hasMore = true;

        try {
            while (hasMore) {
                NadQueryResult pageResult = queryPage(bounds, offset);

                if (!pageResult.isSuccess()) {
                    return pageResult;
                }

                allAddresses.addAll(pageResult.addresses());
                hasMore = pageResult.hasMore();
                offset += MAX_FEATURES_PER_REQUEST;

                // Safety limit - don't fetch more than 10000 addresses
                if (allAddresses.size() >= 10000) {
                    Logging.warn("NAD query hit safety limit of 10000 addresses");
                    break;
                }
            }

            return NadQueryResult.success(allAddresses, false);

        } catch (Exception e) {
            Logging.error("NAD query failed: " + e.getMessage());
            return NadQueryResult.error("NAD query failed: " + e.getMessage());
        }
    }

    /**
     * Query a single page of results.
     */
    private NadQueryResult queryPage(Bounds bounds, int offset) throws IOException {
        String url = buildQueryUrl(bounds, offset);
        Logging.info("NAD query: " + url);

        Response response = HttpClient.create(URI.create(url).toURL())
                .setAccept("application/geo+json, application/json")
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .connect();

        try {
            int responseCode = response.getResponseCode();
            String body = response.fetchContent();

            if (responseCode != 200) {
                return NadQueryResult.error("NAD API returned HTTP " + responseCode);
            }

            // Check for API error (ESRI returns 200 with error in body)
            if (body.contains("\"error\"")) {
                String errorMsg = extractJsonString(body, "message");
                String details = extractJsonString(body, "details");
                String fullMsg = "NAD API error";
                if (errorMsg != null && !errorMsg.isEmpty()) {
                    fullMsg += ": " + errorMsg;
                }
                if (details != null && !details.isEmpty()) {
                    fullMsg += " (" + details + ")";
                }
                return NadQueryResult.error(fullMsg);
            }

            // Parse GeoJSON features
            List<NadAddress> addresses = parseGeoJsonFeatures(body);

            // Check if there are more results (GeoJSON wraps this in properties)
            boolean exceededLimit = body.contains("\"exceededTransferLimit\":true")
                    || body.contains("\"exceededTransferLimit\": true");

            return NadQueryResult.success(addresses, exceededLimit);
        } finally {
            response.disconnect();
        }
    }

    /**
     * Build the query URL for the NAD endpoint.
     *
     * Uses the minimal parameter set that works with this ESRI Feature Server,
     * matching the format used by the Rapid editor and MapWithAI plugin.
     */
    private String buildQueryUrl(Bounds bounds, int offset) {
        // ESRI envelope format: xmin,ymin,xmax,ymax (lon,lat,lon,lat)
        String geometry = String.format("%f,%f,%f,%f",
                bounds.getMinLon(), bounds.getMinLat(),
                bounds.getMaxLon(), bounds.getMaxLat());

        return NAD_BASE_URL
                + "?f=geojson"
                + "&geometry=" + geometry
                + "&geometryType=esriGeometryEnvelope"
                + "&outfields=addr_street,addr_housenumber,addr_city,addr_state,addr_postcode"
                + "&outSR=4326"
                + "&resultOffset=" + offset
                + "&resultRecordCount=" + MAX_FEATURES_PER_REQUEST;
    }

    /**
     * Parse features from a GeoJSON response.
     *
     * ESRI returns geometry before properties:
     * {"type":"Feature","geometry":{"type":"Point","coordinates":[-90.013,35.025]},
     *  "properties":{"addr_street":"Rodney Road","addr_housenumber":"4538",...}}
     *
     * We extract coordinates and properties separately from each feature to
     * handle either ordering.
     */
    private List<NadAddress> parseGeoJsonFeatures(String json) {
        List<NadAddress> addresses = new ArrayList<>();

        int featuresStart = json.indexOf("\"features\":");
        if (featuresStart == -1) {
            return addresses;
        }

        // Extract coordinates and properties from each feature independently
        Pattern coordPattern = Pattern.compile(
                "\"coordinates\"\\s*:\\s*\\[\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*\\]");
        Pattern propsPattern = Pattern.compile(
                "\"properties\"\\s*:\\s*\\{([^}]*)\\}");

        // Split on feature boundaries to process each feature separately
        String featuresJson = json.substring(featuresStart);
        int searchFrom = 0;
        while (true) {
            int featureStart = featuresJson.indexOf("\"type\"", searchFrom);
            if (featureStart == -1) break;
            int featureTypeEnd = featuresJson.indexOf("\"Feature\"", featureStart);
            if (featureTypeEnd == -1) break;

            // Find the end of this feature (next Feature or end of array)
            int nextFeature = featuresJson.indexOf("\"type\"", featureTypeEnd + 9);
            String featureBlock = nextFeature == -1
                    ? featuresJson.substring(featureStart)
                    : featuresJson.substring(featureStart, nextFeature);
            searchFrom = featureTypeEnd + 9;

            Matcher coordMatcher = coordPattern.matcher(featureBlock);
            Matcher propsMatcher = propsPattern.matcher(featureBlock);

            if (!coordMatcher.find() || !propsMatcher.find()) {
                continue;
            }

            String lonStr = coordMatcher.group(1);
            String latStr = coordMatcher.group(2);
            String properties = propsMatcher.group(1);

            String street = extractJsonString(properties, "addr_street");
            if (street == null || street.isEmpty()) {
                continue;
            }

            double lon;
            double lat;
            try {
                lon = Double.parseDouble(lonStr);
                lat = Double.parseDouble(latStr);
            } catch (NumberFormatException e) {
                continue;
            }

            String houseNumber = extractJsonString(properties, "addr_housenumber");
            String city = extractJsonString(properties, "addr_city");
            String state = extractJsonString(properties, "addr_state");
            String postcode = extractJsonString(properties, "addr_postcode");

            addresses.add(new NadAddress(
                    street,
                    houseNumber,
                    city,
                    state,
                    postcode,
                    new LatLon(lat, lon)
            ));
        }

        return addresses;
    }

    /**
     * Extract a string value from JSON-like text.
     */
    private String extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
