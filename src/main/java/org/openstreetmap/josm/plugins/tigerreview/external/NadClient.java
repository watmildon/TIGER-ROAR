// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.tools.Logging;

/**
 * Client for querying the National Address Database (NAD) via ESRI ArcGIS REST API.
 *
 * Endpoint: https://services6.arcgis.com/Do88DoK2xjTUCXd1/arcgis/rest/services/USA_NAD_Addresses/FeatureServer/0
 */
public class NadClient {

    private static final String NAD_BASE_URL =
            "https://services6.arcgis.com/Do88DoK2xjTUCXd1/arcgis/rest/services/USA_NAD_Addresses/FeatureServer/0/query";

    /** Maximum features per request (ESRI limit is 2000) */
    private static final int MAX_FEATURES_PER_REQUEST = 2000;

    /** Connection timeout */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Request timeout */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Maximum area in square degrees before skipping fetch */
    private static final double MAX_AREA_DEGREES = 0.25; // ~roughly 25km x 25km at mid-latitudes

    private final HttpClient httpClient;

    public NadClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

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
    private NadQueryResult queryPage(Bounds bounds, int offset) throws IOException, InterruptedException {
        String url = buildQueryUrl(bounds, offset);
        Logging.info("NAD query: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return NadQueryResult.error("NAD API returned HTTP " + response.statusCode());
        }

        String body = response.body();

        // Check for API error
        if (body.contains("\"error\"")) {
            String errorMsg = extractJsonString(body, "message");
            return NadQueryResult.error("NAD API error: " + (errorMsg != null ? errorMsg : "Unknown error"));
        }

        // Parse features
        List<NadAddress> addresses = parseFeatures(body);

        // Check if there are more results
        boolean hasMore = body.contains("\"exceededTransferLimit\":true") ||
                body.contains("\"exceededTransferLimit\": true");

        return NadQueryResult.success(addresses, hasMore);
    }

    /**
     * Build the query URL for the NAD endpoint.
     */
    private String buildQueryUrl(Bounds bounds, int offset) {
        // ESRI uses xmin,ymin,xmax,ymax format (lon,lat,lon,lat)
        String geometry = String.format("%f,%f,%f,%f",
                bounds.getMinLon(), bounds.getMinLat(),
                bounds.getMaxLon(), bounds.getMaxLat());

        StringBuilder sb = new StringBuilder(NAD_BASE_URL);
        sb.append("?where=1%3D1"); // where=1=1 (match all)
        sb.append("&geometry=").append(URLEncoder.encode(geometry, StandardCharsets.UTF_8));
        sb.append("&geometryType=esriGeometryEnvelope");
        sb.append("&inSR=4326"); // WGS84
        sb.append("&spatialRel=esriSpatialRelIntersects");
        sb.append("&outFields=").append(URLEncoder.encode("addr_street,addr_housenumber,addr_city,addr_state,addr_postcode", StandardCharsets.UTF_8));
        sb.append("&returnGeometry=true");
        sb.append("&outSR=4326");
        sb.append("&f=json");
        sb.append("&resultOffset=").append(offset);
        sb.append("&resultRecordCount=").append(MAX_FEATURES_PER_REQUEST);

        return sb.toString();
    }

    /**
     * Parse features from the JSON response using simple string parsing.
     * This avoids external JSON library dependencies.
     */
    private List<NadAddress> parseFeatures(String json) {
        List<NadAddress> addresses = new ArrayList<>();

        // Find the features array
        int featuresStart = json.indexOf("\"features\":");
        if (featuresStart == -1) {
            return addresses;
        }

        // Find each feature object
        Pattern featurePattern = Pattern.compile(
                "\\{\\s*\"attributes\"\\s*:\\s*\\{([^}]*)\\}\\s*,\\s*\"geometry\"\\s*:\\s*\\{([^}]*)\\}\\s*\\}");
        Matcher matcher = featurePattern.matcher(json);

        while (matcher.find()) {
            String attributes = matcher.group(1);
            String geometry = matcher.group(2);

            String street = extractJsonString(attributes, "addr_street");
            if (street == null || street.isEmpty()) {
                continue;
            }

            String houseNumber = extractJsonString(attributes, "addr_housenumber");
            String city = extractJsonString(attributes, "addr_city");
            String state = extractJsonString(attributes, "addr_state");
            String postcode = extractJsonString(attributes, "addr_postcode");

            Double x = extractJsonNumber(geometry, "x");
            Double y = extractJsonNumber(geometry, "y");

            if (x == null || y == null) {
                continue;
            }

            addresses.add(new NadAddress(
                    street,
                    houseNumber,
                    city,
                    state,
                    postcode,
                    new LatLon(y, x) // lat, lon
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

    /**
     * Extract a numeric value from JSON-like text.
     */
    private Double extractJsonNumber(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9.]+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
